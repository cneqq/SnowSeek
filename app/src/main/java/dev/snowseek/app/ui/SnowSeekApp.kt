@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.snowseek.app.ui

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.snowseek.app.BuildConfig
import dev.snowseek.app.auth.OfficialWebActivity
import dev.snowseek.app.settings.AppSettings
import dev.snowseek.app.settings.FontMode
import dev.snowseek.app.settings.GlassSettings
import dev.snowseek.app.settings.PhotoCacheLimit
import dev.snowseek.app.settings.WallpaperKind
import dev.snowseek.app.settings.ThemeMode
import dev.snowseek.app.ui.components.MessageCard
import dev.snowseek.app.ui.components.PhotoAttachment
import dev.snowseek.app.ui.components.LocalPhotoCache
import dev.snowseek.app.ui.components.ChatWallpaper
import dev.snowseek.app.ui.components.WallpaperEditor
import dev.snowseek.app.ui.components.wallpaperName
import dev.snowseek.app.ui.components.ChatComposerLayout
import dev.snowseek.app.ui.components.ComposerSurface
import dev.snowseek.app.ui.components.AppearanceChoice
import dev.snowseek.app.ui.components.AppearanceLink
import dev.snowseek.app.ui.components.AppearancePage
import dev.snowseek.app.ui.components.AppearanceSlider
import dev.snowseek.app.ui.components.GlassEditor
import dev.snowseek.app.ui.theme.SnowSeekTheme
import dev.snowseek.app.ui.theme.fontFamily
import dev.snowseek.core.ChatSession
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SnowSeekApp(model: SnowSeekViewModel) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val ui by model.ui.collectAsStateWithLifecycle()
    val settingsReady by model.settingsReady.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var scalePreview by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(settings.textScale, ui.error) {
        if (scalePreview == settings.textScale || ui.error) scalePreview = null
    }
    val themedSettings = scalePreview?.let { settings.copy(textScale = it) } ?: settings
    val context = LocalContext.current
    val words = remember(settings.language) { Words(settings.language) }
    val openWeb = {
        context.startActivity(Intent(context, OfficialWebActivity::class.java).putExtra("language", settings.language))
    }
    SnowSeekTheme(themedSettings) {
        CompositionLocalProvider(
            LocalWords provides words,
            LocalPhotoCache provides model.photoCache.takeIf { ui.authenticated && !ui.sessionChanging },
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (!settingsReady) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        themedSettings, ui.authenticated, model,
                        onScaleChange = { scalePreview = it },
                        onScaleFinished = { scalePreview?.let(model::textScale) },
                        onBack = { showSettings = false },
                    )
                } else if (!ui.authenticated && (ui.loading || ui.sessionChanging)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (!ui.authenticated) {
                    SignInGate(onWeb = openWeb, onSettings = { showSettings = true })
                } else {
                    ChatScreen(ui, settings, model, { showSettings = true })
                }
            }
            if (ui.error) {
                AlertDialog(
                    onDismissRequest = model::dismissError,
                    icon = { Icon(Icons.Outlined.ErrorOutline, null) },
                    title = { Text(words.text("Не удалось выполнить действие", "Something went wrong")) },
                    text = { Text(if (ui.deliveryUncertain) words.text(
                        "Ответ пока не удалось получить. Обновите чат, чтобы проверить историю.",
                        "The reply is unavailable. Refresh the chat to check its history.",
                    ) else ui.errorMessage ?: words.text(
                        "Проверьте соединение и повторите попытку.",
                        "Check your connection and try again.",
                    )) },
                    confirmButton = { TextButton(onClick = {
                        model.dismissError()
                        if (!ui.initialized) model.initialize() else model.refreshHistory()
                    }) { Text(if (ui.deliveryUncertain) words.text("Обновить чат", "Refresh chat") else words.text("Повторить", "Try again")) } },
                    dismissButton = { TextButton(onClick = model::dismissError) { Text(words.text("Закрыть", "Close")) } },
                )
            }
            if (ui.signedOut) {
                AlertDialog(onDismissRequest = model::dismissError,
                    title = { Text(words.text("Веб-сессия очищена", "Website session cleared")) },
                    text = { Text(words.text("Данные входа удалены с этого устройства.", "Sign-in data has been removed from this device.")) },
                    confirmButton = { TextButton(onClick = model::dismissError) { Text("OK") } })
            }
            if (ui.signingOut) {
                AlertDialog(onDismissRequest = {},
                    title = { Text(words.text("Очистка веб-сессии…", "Clearing website session…")) },
                    text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
            }
        }
    }
}

@Composable
private fun SignInGate(onWeb: () -> Unit, onSettings: () -> Unit) {
    val w = LocalWords.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScrollCompat().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("SnowSeek", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        Text(w.text("Войдите в аккаунт для использования DeepSeek", "Sign in to use DeepSeek"),
            style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onWeb, shape = CircleShape, modifier = Modifier.fillMaxWidth()) {
            Text(w.text("Войти в DeepSeek", "Sign in to DeepSeek"))
        }
        TextButton(onClick = onSettings, shape = CircleShape) { Text(w.text("Настройки", "Settings")) }
    }
}

@Composable
fun BrandMark(size: Int = 40) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 3).dp))
        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = .72f)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.AcUnit, null, Modifier.size((size * .58f).dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun ChatScreen(ui: ChatUiState, settings: AppSettings, model: SnowSeekViewModel, onSettings: () -> Unit) {
    val w = LocalWords.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var chatQuery by rememberSaveable { mutableStateOf("") }
    val query = chatQuery.trim()
    val visibleChats = remember(ui.chats, query, w) {
        if (query.isEmpty()) ui.chats else ui.chats.filter { chatTitle(it, w).contains(query, ignoreCase = true) }
    }
    val today by produceState(java.time.LocalDate.now()) {
        while (true) {
            value = java.time.LocalDate.now()
            kotlinx.coroutines.delay(60_000)
        }
    }
    val groups = remember(visibleChats, today) { groupChats(visibleChats, today, java.time.ZoneId.systemDefault()) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameTitle by rememberSaveable { mutableStateOf("") }
    val current = ui.chats.firstOrNull { it.id == ui.selectedId }
    val haze = rememberHazeState()
    val backdrop = haze.takeIf { settings.glass.enabled }
    val requests = remember(current?.messages) { current?.messages.orEmpty().filter { it.role == dev.snowseek.core.MessageRole.USER } }
    var showRequests by rememberSaveable(current?.id) { mutableStateOf(false) }
    var jumpToRequest by remember(current?.id) { mutableStateOf<String?>(null) }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(modifier = Modifier.widthIn(max = 320.dp).imePadding(), drawerContainerColor = MaterialTheme.colorScheme.surface) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandMark(32); Text("SnowSeek", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Button(onClick = { keyboard?.hide(); model.newChat(); scope.launch { drawer.close() } },
                enabled = !ui.loading && !ui.generating && ui.initialized,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(w.text("Новый чат", "New chat"))
            }
            OutlinedTextField(
                value = chatQuery,
                onValueChange = { chatQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                placeholder = { Text(w.text("Поиск чатов", "Search chats")) },
                shape = RoundedCornerShape(18.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = if (chatQuery.isEmpty()) null else ({
                    IconButton(onClick = { chatQuery = "" }) {
                        Icon(Icons.Outlined.Close, w.text("Очистить поиск", "Clear search"))
                    }
                }),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
                ChatAge.entries.forEach { age ->
                    val chats = groups[age].orEmpty()
                    if (chats.isNotEmpty()) {
                        item(key = "section-$age") {
                            Text(w.ageName(age), Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(chats, key = { it.id }) { chat ->
                            ChatHistoryRow(
                                title = chatTitle(chat, w), selected = chat.id == ui.selectedId, pinned = chat.pinned,
                                enabled = !ui.generating && !ui.loading && !ui.uploading,
                                onSelect = { keyboard?.hide(); model.select(chat.id); scope.launch { drawer.close() } },
                                onPin = { model.pinChat(chat.id, !chat.pinned) },
                                onRename = { renameTitle = chat.title; renameId = chat.id },
                                onDelete = { deleteId = chat.id },
                            )
                        }
                    }
                }
                if (visibleChats.isEmpty()) item {
                    when {
                        !ui.initialized && ui.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp))
                        !ui.initialized -> TextButton(onClick = model::initialize) {
                            Text(w.text("Повторить загрузку чатов", "Retry loading chats"))
                        }
                        else -> Text(
                            if (query.isEmpty()) w.text("В аккаунте пока нет чатов", "Your account has no chats yet")
                            else w.text("Чаты с таким названием не найдены", "No matching chat titles"),
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!keyboardOpen) {
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(onClick = { scope.launch { drawer.close() }; onSettings() }, Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Icon(Icons.Outlined.Settings, null); Spacer(Modifier.width(12.dp)); Text(w.text("Настройки", "Settings"))
                }
                Text("SnowSeek ${BuildConfig.VERSION_NAME} · GPLv3", Modifier.padding(24.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }) {
        BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(current?.let { chatTitle(it, w) } ?: "SnowSeek",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, w.text("История чатов", "Chat history")) } },
                    actions = {
                        if (requests.isNotEmpty()) {
                            FilledTonalIconButton(onClick = { keyboard?.hide(); showRequests = true }) {
                                Icon(Icons.Outlined.History, w.text("История запросов", "Request history"))
                            }
                        }
                        IconButton(onClick = model::newChat,
                            modifier = Modifier.padding(end = 8.dp),
                            enabled = !ui.generating && !ui.loading && !ui.uploading && ui.initialized) {
                            RoundNewChatIcon(w.text("Новый чат", "New chat"))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                ChatComposerLayout(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    overlay = backdrop != null,
                    composer = { Composer(ui, model, settings.glass, backdrop, settings.hideInstructions) },
                ) { composerInset ->
                    val source = if (backdrop == null) Modifier else Modifier.hazeSource(backdrop)
                    Box(Modifier.fillMaxSize().then(source)) {
                        ChatWallpaper(settings.wallpaper, Modifier.matchParentSize())
                        if (!ui.initialized && !ui.loading) {
                            Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                                Text(w.text("Не удалось подключиться. Проверьте соединение и повторите попытку.", "Could not connect. Check your connection and try again."), Modifier.padding(vertical = 16.dp))
                                Button(onClick = model::initialize) { Text(w.text("Повторить", "Try again")) }
                            }
                        } else if (current?.messages.isNullOrEmpty()) {
                            EmptyChat(current?.id ?: "empty-start", Modifier.fillMaxSize())
                        } else {
                            key(current.id) { MessageList(current, settings.animations, Modifier.fillMaxSize(),
                                hideInstructions = settings.hideInstructions,
                                wallpaperEnabled = settings.wallpaper.kind != WallpaperKind.NONE,
                                attachmentPhotos = ui.attachmentPhotos,
                                composerInset = composerInset,
                                canAct = ui.authenticated && !ui.generating && !ui.loading && !ui.uploading,
                                onEdit = model::editMessage, onContinue = model::continueMessage,
                                onRegenerate = model::regenerateMessage, onVersion = model::switchVersion,
                                canSwitchVersion = ui.editingMessageId == null,
                                jumpToRequest = jumpToRequest, onJumpHandled = { jumpToRequest = null }) }
                        }
                    }
                }
            }
        }
    }
    if (showRequests && requests.isNotEmpty()) ModalBottomSheet(
        onDismissRequest = { showRequests = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(w.text("История запросов", "Request history"),
                    Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { showRequests = false }) {
                    Icon(Icons.Outlined.Close, w.text("Закрыть", "Close"))
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                contentPadding = PaddingValues(bottom = 12.dp)) {
                itemsIndexed(requests, key = { _, message -> message.id }) { index, message ->
                    val preview = message.text.trim().take(240).replace(Regex("\\s+"), " ")
                        .ifBlank { message.attachments.firstOrNull()?.name.orEmpty() }
                    ListItem(
                        headlineContent = { Text(preview.ifBlank { w.text("Вложение", "Attachment") }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Text("${index + 1}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable {
                            jumpToRequest = message.id
                            showRequests = false
                        },
                    )
                }
            }
        }
    }
    val renamingChat = ui.chats.firstOrNull { it.id == renameId }
    if (renamingChat != null) AlertDialog(
        onDismissRequest = { if (!ui.loading) renameId = null },
        title = { Text(w.text("Переименовать чат", "Rename chat")) },
        text = {
            OutlinedTextField(
                value = renameTitle,
                onValueChange = { renameTitle = it.replace('\n', ' ').replace('\r', ' ') },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(w.text("Название", "Title")) },
                singleLine = true,
                enabled = !ui.loading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (!ui.loading && renameTitle.isNotBlank() && renameTitle.trim() != renamingChat.title) {
                        model.renameChat(renamingChat.id, renameTitle) {
                            if (renameId == renamingChat.id) renameId = null
                        }
                    }
                }),
            )
        },
        confirmButton = {
            TextButton(
                enabled = !ui.loading && renameTitle.isNotBlank() && renameTitle.trim() != renamingChat.title,
                onClick = {
                    model.renameChat(renamingChat.id, renameTitle) {
                        if (renameId == renamingChat.id) renameId = null
                    }
                },
            ) { Text(w.text("Сохранить", "Save")) }
        },
        dismissButton = {
            TextButton(onClick = { renameId = null }, enabled = !ui.loading) { Text(w.text("Отмена", "Cancel")) }
        },
    )
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null },
        title = { Text(w.text("Удалить диалог?", "Delete this conversation?")) },
        text = { Text(w.text("Диалог будет удалён из аккаунта. Отменить это действие нельзя.", "This conversation will be deleted from your account. This cannot be undone.")) },
        confirmButton = { TextButton(onClick = { deleteId?.let(model::deleteChat); deleteId = null }) { Text(w.text("Удалить", "Delete")) } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text(w.text("Отмена", "Cancel")) } })
}

@Composable
private fun RoundNewChatIcon(description: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(25.dp).semantics { contentDescription = description }) {
        val unit = size.minDimension / 24f
        val bubble = Path().apply {
            moveTo(12f * unit, 3f * unit)
            cubicTo(17f * unit, 3f * unit, 21f * unit, 7f * unit, 21f * unit, 12f * unit)
            cubicTo(21f * unit, 17f * unit, 17f * unit, 21f * unit, 12f * unit, 21f * unit)
            cubicTo(10f * unit, 21f * unit, 8f * unit, 20.4f * unit, 6.5f * unit, 19f * unit)
            lineTo(3f * unit, 21f * unit); lineTo(4f * unit, 16.5f * unit)
            cubicTo(3.3f * unit, 15f * unit, 3f * unit, 13.5f * unit, 3f * unit, 12f * unit)
            cubicTo(3f * unit, 7f * unit, 7f * unit, 3f * unit, 12f * unit, 3f * unit)
            close()
        }
        drawPath(bubble, color, style = Stroke(1.7f * unit, cap = StrokeCap.Round))
        drawLine(color, Offset(8f * unit, 12f * unit), Offset(16f * unit, 12f * unit), 1.7f * unit, StrokeCap.Round)
        drawLine(color, Offset(12f * unit, 8f * unit), Offset(12f * unit, 16f * unit), 1.7f * unit, StrokeCap.Round)
    }
}

private fun chatTitle(chat: ChatSession, w: Words): String =
    chat.title.ifBlank { w.text("Новый чат", "New chat") }

@Composable
private fun EmptyChat(chatId: String, modifier: Modifier) {
    val w = LocalWords.current
    val greeting by rememberSaveable(chatId) { mutableIntStateOf(kotlin.random.Random.nextInt(4)) }
    val greetings = listOf(
        w.text("Привет! О чём хотите поговорить сегодня?", "Hi! What would you like to talk about today?"),
        w.text("Какая мысль занимает вас сегодня?", "What’s on your mind today?"),
        w.text("Давайте разберёмся вместе. С чего начнём?", "Let’s think it through together. Where shall we start?"),
        w.text("Чем могу помочь сегодня?", "How can I help today?"),
    )
    Box(modifier.fillMaxWidth().verticalScrollCompat().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(greetings[greeting], style = MaterialTheme.typography.headlineSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MessageList(
    chat: ChatSession, animations: Boolean, modifier: Modifier, canAct: Boolean,
    onEdit: (String) -> Unit, onContinue: (String) -> Unit, onRegenerate: (String) -> Unit,
    onVersion: (String, String) -> Unit, canSwitchVersion: Boolean,
    jumpToRequest: String?, onJumpHandled: () -> Unit,
    composerInset: Dp, hideInstructions: Boolean, attachmentPhotos: Map<String, Uri>, wallpaperEnabled: Boolean,
) {
    val words = LocalWords.current
    // Index 0 is always the end of the conversation, independent of measured reply heights.
    val listState = rememberLazyListState()
    val newestFirst = remember(chat.messages) { chat.messages.asReversed() }
    val last = chat.messages.lastOrNull()
    val lastAssistant = chat.messages.lastOrNull { it.role == dev.snowseek.core.MessageRole.ASSISTANT }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var followLatest by remember(chat.id) { mutableStateOf(true) }
    val atBottom by remember { derivedStateOf {
        !listState.canScrollBackward
    } }
    LaunchedEffect(dragged) {
        if (dragged) followLatest = false
        else if (atBottom) followLatest = true
    }
    LaunchedEffect(atBottom) { if (atBottom && !dragged) followLatest = true }
    LaunchedEffect(chat.id) {
        followLatest = true
        listState.scrollToItem(0)
    }
    LaunchedEffect(jumpToRequest) {
        if (jumpToRequest == null) return@LaunchedEffect
        val index = chat.messages.indexOfFirst { it.id == jumpToRequest }
        if (index >= 0) {
            followLatest = false
            listState.scrollToItem(chat.messages.size - index)
        }
        onJumpHandled()
    }
    LaunchedEffect(last?.uiKey, last?.text?.length, last?.reasoning?.length, last?.status, followLatest, dragged) {
        if (followLatest && !dragged && chat.messages.isNotEmpty()) {
            // A token must not restart a slow animation and fight the next token's scroll.
            listState.scrollToItem(0)
        }
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 16.dp + composerInset),
            verticalArrangement = Arrangement.spacedBy(22.dp)) {
            item(key = "conversation-bottom") { Spacer(Modifier.height(1.dp)) }
            items(newestFirst, key = { it.uiKey }) { message ->
                Column {
                    MessageCard(message, onReasoningToggle = { followLatest = false },
                        wallpaperEnabled = wallpaperEnabled,
                        showInstruction = !hideInstructions,
                        attachmentPhotos = attachmentPhotos,
                        animateStreaming = animations,
                        onReveal = {
                            if (message.id == last?.id && followLatest && !dragged) {
                                listState.requestScrollToItem(0)
                            }
                        },
                        onEdit = if (message.role == dev.snowseek.core.MessageRole.USER) ({ onEdit(message.id) }) else null,
                        onContinue = if (canAct && message.id == lastAssistant?.id && message.continuable &&
                            message.id.toLongOrNull() != null) ({ onContinue(message.id) }) else null,
                        onRegenerate = if (canAct && message.role == dev.snowseek.core.MessageRole.ASSISTANT &&
                            message.regenerable && message.id.toLongOrNull() != null) ({ onRegenerate(message.id) }) else null)
                    chat.versions[message.id]?.let { version ->
                        Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            fun changeVersion(index: Int) {
                                val target = version.ids.getOrNull(index) ?: return
                                val position = chat.messages.indexOfFirst { it.id == message.id }
                                followLatest = false
                                onVersion(message.id, target)
                                if (position >= 0) listState.requestScrollToItem(chat.messages.size - position)
                            }
                            IconButton(enabled = canAct && canSwitchVersion && version.index > 0,
                                onClick = { changeVersion(version.index - 1) }) {
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, words.text("Предыдущая версия", "Previous version"))
                            }
                            Text("${version.index + 1}/${version.ids.size}", style = MaterialTheme.typography.labelMedium)
                            IconButton(enabled = canAct && canSwitchVersion && version.index < version.ids.lastIndex,
                                onClick = { changeVersion(version.index + 1) }) {
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, words.text("Следующая версия", "Next version"))
                            }
                        }
                    }
                }
            }
        }
        if (!atBottom) {
            val scope = rememberCoroutineScope()
            val w = LocalWords.current
            SmallFloatingActionButton(onClick = {
                scope.launch {
                    followLatest = false
                    listState.animateScrollToItem(0)
                    followLatest = true
                }
            },
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = composerInset).padding(12.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Outlined.ArrowDownward, w.text("В конец чата", "Jump to bottom"))
            }
        }
    }
}

@Composable
private fun Composer(ui: ChatUiState, model: SnowSeekViewModel, glass: GlassSettings, backdrop: HazeState?, hideInstructions: Boolean) {
    val w = LocalWords.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.attachFiles(uris)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) model.attachFiles(uris, fromPhotoPicker = true)
    }
    var showAttachmentOptions by rememberSaveable { mutableStateOf(false) }
    val canSend = ui.initialized && !ui.loading && !ui.uploading &&
        (ui.input.isNotBlank() || ui.attachments.isNotEmpty())
    LaunchedEffect(ui.editingMessageId) {
        if (ui.editingMessageId != null) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 20.dp)) {
        ComposerSurface(glass, backdrop) {
            Column(Modifier.padding(8.dp)) {
                if (!hideInstructions) InstructionSelector(ui, model)
                if (ui.editingMessageId != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(w.text("Изменение сообщения", "Editing message"), Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = model::cancelEdit, enabled = !ui.generating && !ui.uploading) {
                                Text(w.text("Отмена", "Cancel"))
                            }
                        }
                        Text(w.text("После сохранения ответ будет создан заново с этого места.",
                            "Saving generates a new reply from this point."), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextField(value = ui.input, onValueChange = model::input,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 180.dp).focusRequester(focusRequester),
                    placeholder = { Text(w.text("Спросите что-нибудь…", "Ask anything…"), style = MaterialTheme.typography.bodyLarge) },
                    enabled = ui.initialized && !ui.loading && !(ui.generating && ui.editingMessageId != null), maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(onSend = { if (canSend && !ui.generating) { keyboard?.hide(); model.send() } }),
                    colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                )
                if (ui.attachments.isNotEmpty()) {
                    FlowRow(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ui.attachments.forEach { attachment ->
                            key(attachment.id) {
                                val photo = ui.attachmentPhotos[attachment.id]
                                if (photo != null) {
                                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        PhotoAttachment(attachment.id, photo, attachment.name, Modifier.size(64.dp))
                                        IconButton(onClick = { model.removeAttachment(attachment.id) },
                                            enabled = !ui.generating && !ui.uploading) {
                                            Icon(Icons.Outlined.Close, w.text("Удалить фото", "Remove photo"), Modifier.size(18.dp))
                                        }
                                    }
                                } else {
                                    InputChip(selected = false, onClick = {}, modifier = Modifier.widthIn(max = 260.dp),
                                        label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = { Icon(Icons.Outlined.AttachFile, null, Modifier.size(16.dp)) },
                                        trailingIcon = {
                                            IconButton(onClick = { model.removeAttachment(attachment.id) },
                                                enabled = !ui.generating && !ui.uploading, modifier = Modifier.size(24.dp)) {
                                                Icon(Icons.Outlined.Close, w.text("Удалить вложение", "Remove attachment"), Modifier.size(16.dp))
                                            }
                                        })
                                }
                            }
                        }
                    }
                }
                if (ui.uploading) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(w.text("Загрузка вложений…", "Uploading attachments…"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    val options: @Composable (Modifier) -> Unit = { modifier ->
                        FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = ui.deepThink, onClick = model::deepThink,
                                enabled = !ui.generating && !ui.uploading, shape = CircleShape,
                                label = { Text(w.text("Рассуждение", "Reasoning"), fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Outlined.Psychology, null, Modifier.size(16.dp)) })
                            FilterChip(selected = ui.search, onClick = model::search,
                                enabled = !ui.generating && !ui.uploading, shape = CircleShape,
                                label = { Text(w.text("Поиск", "Search"), fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Outlined.Language, null, Modifier.size(16.dp)) })
                        }
                    }
                    val actions: @Composable () -> Unit = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box {
                                IconButton(onClick = { showAttachmentOptions = true },
                                    enabled = ui.initialized && !ui.generating && !ui.loading && !ui.uploading) {
                                    Icon(Icons.Outlined.AttachFile, w.text("Добавить вложения", "Add attachments"), Modifier.size(22.dp))
                                }
                                DropdownMenu(expanded = showAttachmentOptions, onDismissRequest = { showAttachmentOptions = false }) {
                                    DropdownMenuItem(
                                        text = { Text(w.text("Фото", "Photos")) },
                                        leadingIcon = { Icon(Icons.Outlined.Image, null) },
                                        onClick = {
                                            showAttachmentOptions = false
                                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(w.text("Файл", "File")) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null) },
                                        onClick = { showAttachmentOptions = false; attachmentPicker.launch(arrayOf("*/*")) },
                                    )
                                }
                            }
                            FilledIconButton(onClick = {
                                if (ui.generating) model.stop() else { keyboard?.hide(); model.send() }
                            }, enabled = ui.generating || canSend, modifier = Modifier.size(46.dp), shape = CircleShape) {
                                Icon(if (ui.generating) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                                    if (ui.generating) w.text("Остановить ответ", "Stop generating")
                                    else if (ui.editingMessageId != null) w.text("Сохранить изменение", "Save edit")
                                    else w.text("Отправить сообщение", "Send message"), Modifier.size(21.dp))
                            }
                        }
                    }
                    if (maxWidth < 300.dp) {
                        Column(Modifier.fillMaxWidth()) {
                            options(Modifier.fillMaxWidth())
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { actions() }
                        }
                    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        options(Modifier.weight(1f))
                        actions()
                    }
                }
            }
        }

    }
}

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    authenticated: Boolean,
    model: SnowSeekViewModel,
    onScaleChange: (Float) -> Unit,
    onScaleFinished: () -> Unit,
    onBack: () -> Unit,
) {
    val w = LocalWords.current
    var page by rememberSaveable { mutableStateOf(AppearanceSection.OVERVIEW) }
    val overviewScroll = rememberScrollState()
    val closePage = { page = AppearanceSection.OVERVIEW }
    when (page) {
        AppearanceSection.THEME -> {
            AppearancePage(w.text("Тема", "Theme"), closePage) {
                Column(Modifier.selectableGroup()) {
                    ThemeMode.entries.forEach { mode ->
                        AppearanceChoice(w.themeName(mode), settings.theme == mode, { model.theme(mode) })
                    }
                }
            }
            return
        }
        AppearanceSection.FONT -> {
            AppearancePage(w.text("Шрифт", "Font"), closePage) {
                Column(Modifier.selectableGroup()) {
                    FontMode.entries.forEach { mode ->
                        AppearanceChoice(w.fontName(mode), settings.font == mode, { model.font(mode) }) {
                            Text(w.text("Пример текста · 123", "Sample text · 123"),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = mode.fontFamily()),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            return
        }
        AppearanceSection.GLASS -> {
            GlassEditor(settings.glass, closePage, model::glass)
            return
        }
        AppearanceSection.CACHE -> {
            PhotoCacheSettings(settings.photoCacheLimit, model, closePage)
            return
        }
        AppearanceSection.LOCAL_MODEL -> {
            LocalModelScreen(closePage)
            return
        }
        AppearanceSection.WALLPAPER -> {
            val ui by model.ui.collectAsStateWithLifecycle()
            WallpaperEditor(settings.wallpaper, model, ui.importingWallpaper, closePage)
            return
        }
        AppearanceSection.OVERVIEW -> Unit
    }
    var logoutConfirmation by rememberSaveable { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHost) }, topBar = {
        TopAppBar(title = { Text(w.text("Настройки", "Settings")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, w.text("Назад", "Back")) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(overviewScroll).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingGroup(w.text("ОФОРМЛЕНИЕ", "APPEARANCE")) {
                AppearanceLink(w.text("Тема", "Theme"), w.themeName(settings.theme)) { page = AppearanceSection.THEME }
                AppearanceLink(w.text("Шрифт", "Font"), w.fontName(settings.font)) { page = AppearanceSection.FONT }
                AppearanceLink(w.text("Обои чата", "Chat wallpaper"), w.wallpaperName(settings.wallpaper.kind)) {
                    page = AppearanceSection.WALLPAPER
                }
                val glassStatus = if (settings.glass.enabled) w.text("Включено", "On") else w.text("Выключено", "Off")
                AppearanceLink(w.text("Стекло панели ввода", "Composer glass"), glassStatus) {
                    page = AppearanceSection.GLASS
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(w.text("Анимации", "Animations"), Modifier.weight(1f)); Switch(checked = settings.animations, onCheckedChange = model::animations)
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(w.text("Скрывать инструкции", "Hide instructions"), Modifier.weight(1f))
                    Switch(checked = settings.hideInstructions, onCheckedChange = model::hideInstructions)
                }
                AppearanceSlider(
                    w.text("Масштаб интерфейса", "Interface scale"), "${(settings.textScale * 100).roundToInt()}%",
                    settings.textScale, .7f..1.5f, onChange = onScaleChange, steps = 15, onFinished = onScaleFinished,
                )
            }
            SettingGroup(w.text("ЯЗЫК", "LANGUAGE")) {
                listOf("ru" to "Русский", "en" to "English").forEach { (code, name) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = settings.language == code, onClick = { model.language(code) })
                        TextButton(onClick = { model.language(code) }) { Text(name) }
                    }
                }
            }
            SettingGroup(w.text("ХРАНИЛИЩЕ", "STORAGE")) {
                AppearanceLink(w.text("Кеш фотографий", "Photo cache"), w.cacheLimitName(settings.photoCacheLimit)) {
                    page = AppearanceSection.CACHE
                }
            }
            SettingGroup(w.text("ЛОКАЛЬНЫЙ ИИ", "ON-DEVICE AI")) {
                AppearanceLink(w.text("Локальные модели", "Local models"), "LiteRT-LM · GGUF") {
                    page = AppearanceSection.LOCAL_MODEL
                }
            }
            SettingGroup(w.text("АККАУНТ", "ACCOUNT")) {
                if (authenticated) {
                    TextButton(onClick = { logoutConfirmation = true }, shape = CircleShape,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(w.text("Выйти из аккаунта", "Sign out"), color = MaterialTheme.colorScheme.error)
                    }
                } else Text(w.text("Аккаунт не подключён", "No account connected"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingGroup(w.text("О ПРИЛОЖЕНИИ", "ABOUT")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    BrandMark(46)
                    Column { Text("SnowSeek", style = MaterialTheme.typography.titleMedium); Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(w.text("Неофициальный клиент, не связан с DeepSeek. Открытый исходный код под GNU GPLv3. Без рекламы и аналитики.", "Unofficial client, not affiliated with DeepSeek. Open source under GNU GPLv3. No ads or analytics."), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { showLicenses = true }) { Text(w.text("Лицензии открытого ПО", "Open-source licenses")) }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/cneqq/SnowSeek".toUri())) }
                        .onFailure { scope.launch { snackbarHost.showSnackbar(w.text("Не удалось открыть браузер", "Could not open a browser")) } }
                }) {
                    Icon(Icons.Outlined.Code, null); Spacer(Modifier.width(8.dp)); Text("GitHub · cneqq/SnowSeek")
                }
            }
        }
    }
    if (logoutConfirmation) AlertDialog(onDismissRequest = { logoutConfirmation = false },
        title = { Text(w.text("Выйти из аккаунта?", "Sign out?")) },
        text = { Text(w.text("Будут удалены cookies, веб-хранилище и данные входа сайта на этом устройстве.", "This will remove website cookies, web storage and sign-in data from this device.")) },
        confirmButton = { TextButton(onClick = { logoutConfirmation = false; model.logout() }) { Text(w.text("Выйти", "Sign out")) } },
        dismissButton = { TextButton(onClick = { logoutConfirmation = false }) { Text(w.text("Отмена", "Cancel")) } })
    if (showLicenses) {
        val licenses by produceState<String?>(initialValue = null) {
            value = withContext(Dispatchers.IO) {
                listOf("THIRD_PARTY_NOTICES.md", "Apache-2.0.txt", "GPL-3.0.txt", "KotlinTeX-MIT.txt",
                    "NotoSans-OFL.txt", "Inter-OFL.txt", "SourceSerif4-OFL.txt").joinToString("\n\n") { file ->
                    context.assets.open("licenses/$file").bufferedReader().use { it.readText() }
                }
            }
        }
        AlertDialog(onDismissRequest = { showLicenses = false },
            title = { Text(w.text("Лицензии", "Licenses")) },
            text = {
                if (licenses == null) CircularProgressIndicator()
                else androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(licenses.orEmpty(), Modifier.heightIn(max = 440.dp).verticalScrollCompat(), style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = { showLicenses = false }) { Text(w.text("Закрыть", "Close")) } })
    }
}

private enum class AppearanceSection { OVERVIEW, THEME, FONT, GLASS, CACHE, WALLPAPER, LOCAL_MODEL }

@Composable
private fun PhotoCacheSettings(limit: PhotoCacheLimit, model: SnowSeekViewModel, onBack: () -> Unit) {
    val w = LocalWords.current
    val context = LocalContext.current
    val size by model.photoCache.size.collectAsStateWithLifecycle()
    var help by rememberSaveable { mutableStateOf(false) }
    AppearancePage(w.text("Кеш фотографий", "Photo cache"), onBack) {
        Text(w.text("Занято", "Used") + " · " + Formatter.formatFileSize(context, size),
            Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(w.text("Лимит кеша", "Cache limit"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { help = true }) {
                Icon(Icons.Outlined.HelpOutline, w.text("Что такое лимит кеша", "About the cache limit"))
            }
        }
        Column(Modifier.selectableGroup()) {
            PhotoCacheLimit.entries.forEach { option ->
                AppearanceChoice(w.cacheLimitName(option), option == limit, { model.photoCacheLimit(option) })
            }
        }
        TextButton(onClick = model::clearPhotoCache, enabled = size > 0) {
            Text(w.text("Очистить кеш фото", "Clear photo cache"))
        }
    }
    if (help) AlertDialog(
        onDismissRequest = { help = false },
        title = { Text(w.text("Лимит кеша", "Cache limit")) },
        text = {
            Text(w.text(
                "Лимит задаёт, сколько места фотографии могут занимать на телефоне. Когда кеш заполняется, первыми удаляются копии фото, которые дольше всего не открывали. «Без ограничений» позволяет кешу расти, пока есть свободное место. Фото в чатах DeepSeek сохраняются.",
                "The limit sets how much space cached photos can use on this device. When the cache fills up, the least recently opened copies are removed first. Unlimited lets the cache grow while space is available. Photos in DeepSeek chats remain available.",
            ))
        },
        confirmButton = { TextButton(onClick = { help = false }) { Text(w.text("Понятно", "Got it")) } },
    )
}

private fun Words.cacheLimitName(limit: PhotoCacheLimit): String = when (limit) {
    PhotoCacheLimit.MB_100 -> text("100 МБ", "100 MB")
    PhotoCacheLimit.MB_500 -> text("500 МБ", "500 MB")
    PhotoCacheLimit.GB_1 -> text("1 ГБ", "1 GB")
    PhotoCacheLimit.GB_2 -> text("2 ГБ", "2 GB")
    PhotoCacheLimit.UNLIMITED -> text("Без ограничений", "Unlimited")
}

private fun Words.themeName(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> text("Как в системе", "System default")
    ThemeMode.LIGHT -> text("Светлая", "Light")
    ThemeMode.DARK -> text("Тёмно-синяя", "Dark blue")
    ThemeMode.CHARCOAL -> text("Тёмная", "Dark")
    ThemeMode.BLACK -> text("Чёрная", "Black")
}

private fun Words.fontName(mode: FontMode): String = when (mode) {
    FontMode.SYSTEM -> text("Системный", "System")
    FontMode.STANDARD -> text("Обычный", "Standard")
    FontMode.APPLE_LIKE -> text("В стиле Apple", "Apple-inspired")
    FontMode.ANTHROPIC_LIKE -> text("В стиле Anthropic", "Anthropic-inspired")
}

@Composable
private fun SettingGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.verticalScroll(rememberScrollState())
