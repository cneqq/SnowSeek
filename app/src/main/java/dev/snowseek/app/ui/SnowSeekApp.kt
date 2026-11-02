@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.snowseek.app.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.snowseek.app.BuildConfig
import dev.snowseek.app.auth.OfficialWebActivity
import dev.snowseek.app.settings.AppSettings
import dev.snowseek.app.settings.ThemeMode
import dev.snowseek.app.ui.components.MessageCard
import dev.snowseek.app.ui.theme.SnowSeekTheme
import dev.snowseek.core.ChatSession
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SnowSeekApp(model: SnowSeekViewModel) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val ui by model.ui.collectAsStateWithLifecycle()
    val settingsReady by model.settingsReady.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val words = remember(settings.language) { Words(settings.language) }
    val openWeb = {
        context.startActivity(Intent(context, OfficialWebActivity::class.java).putExtra("language", settings.language))
    }
    SnowSeekTheme(settings) {
        CompositionLocalProvider(LocalWords provides words, LocalDemoMode provides ui.isDemo) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (!settingsReady) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (!settings.welcomeComplete) {
                    WelcomeScreen(onContinue = model::welcomeComplete, onWeb = openWeb, language = model::language)
                } else if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(settings, model, { showSettings = false }, openWeb)
                } else {
                    ChatScreen(ui, settings, model, { showSettings = true }, openWeb)
                }
            }
            if (ui.error) {
                AlertDialog(
                    onDismissRequest = model::dismissError,
                    icon = { Icon(Icons.Outlined.ErrorOutline, null) },
                    title = { Text(words.text("Не удалось выполнить действие", "Something went wrong")) },
                    text = { Text(ui.errorMessage ?: words.text(
                        "Проверьте свободное место и повторите попытку. Если локальная история повреждена, она сохранена без перезаписи. Веб-соединение проверяется отдельно на экране сайта.",
                        "Check available storage and try again. A damaged local history is preserved without overwriting. Website connectivity is handled separately on the website screen.",
                    )) },
                    confirmButton = { TextButton(onClick = {
                        model.dismissError()
                        if (!ui.initialized) model.initialize() else ui.selectedId?.let(model::select)
                    }) { Text(words.text("Повторить", "Try again")) } },
                    dismissButton = { TextButton(onClick = model::dismissError) { Text(words.text("Закрыть", "Close")) } },
                )
            }
            if (ui.signedOut) {
                AlertDialog(onDismissRequest = model::dismissError,
                    title = { Text(words.text("Веб-сессия очищена", "Website session cleared")) },
                    text = { Text(words.text("Cookies и данные входа сайта удалены с этого устройства. Локальные демонстрационные чаты сохранены.", "Website cookies and sign-in data have been removed from this device. Local demo chats are kept.")) },
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
private fun WelcomeScreen(onContinue: () -> Unit, onWeb: () -> Unit, language: (String) -> Unit) {
    val w = LocalWords.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp).verticalScrollCompat(),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { language(if (w.language == "en") "ru" else "en") }) {
                Icon(Icons.Outlined.Language, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (w.language == "en") "Русский" else "English")
            }
        }
        Spacer(Modifier.height(64.dp))
        BrandMark(96)
        Spacer(Modifier.height(28.dp))
        Text("SnowSeek", fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
        Spacer(Modifier.height(12.dp))
        Text(w.text("Пространство для ваших мыслей", "A little space for big ideas"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(36.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                WelcomeFeature(Icons.Outlined.Forum, w.text("Нативный чат", "Native conversation"), w.text("Потоковые ответы и история на устройстве", "Streaming replies and on-device history"))
                WelcomeFeature(Icons.Outlined.Shield, w.text("Ваше пространство", "Your space"), w.text("Без рекламы, трекеров и посредников", "No ads, trackers or intermediary servers"))
                WelcomeFeature(Icons.Outlined.Science, w.text("Бесплатный аккаунт", "Free web account"), w.text("Вход на официальном сайте, общение в SnowSeek", "Sign in on the official website, chat in SnowSeek"))
            }
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onWeb, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
            Text(w.text("Войти в DeepSeek", "Sign in to DeepSeek")); Spacer(Modifier.width(12.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
        }
        TextButton(onClick = onContinue, modifier = Modifier.padding(top = 8.dp)) { Text(w.text("Попробовать демочат", "Try the demo")) }
        Spacer(Modifier.height(24.dp))
        Text(w.text("Неофициальный открытый клиент. После входа нажмите «Подключить SnowSeek». Веб-интеграция экспериментальная.", "Unofficial open-source client. After signing in, tap Connect SnowSeek. Web integration is experimental."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun WelcomeFeature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
private fun ChatScreen(ui: ChatUiState, settings: AppSettings, model: SnowSeekViewModel, onSettings: () -> Unit, onWeb: () -> Unit) {
    val w = LocalWords.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val current = ui.chats.firstOrNull { it.id == ui.selectedId }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(modifier = Modifier.widthIn(max = 320.dp), drawerContainerColor = MaterialTheme.colorScheme.background) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandMark(); Text("SnowSeek", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Button(onClick = { model.newChat(); scope.launch { drawer.close() } },
                enabled = !ui.loading && !ui.generating && ui.initialized,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(w.text("Новый чат", "New chat"))
            }
            Text(if (ui.isDemo) w.text("НА УСТРОЙСТВЕ", "ON THIS DEVICE") else "DEEPSEEK", Modifier.padding(start = 24.dp, top = 28.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.5.sp)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(ui.chats, key = { it.id }) { chat ->
                    NavigationDrawerItem(
                        label = { Text(chatTitle(chat, w), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        selected = chat.id == ui.selectedId,
                        onClick = { model.select(chat.id); scope.launch { drawer.close() } },
                        icon = { Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(20.dp)) },
                        badge = { IconButton(onClick = { deleteId = chat.id }, enabled = !ui.generating && !ui.loading) {
                            Icon(Icons.Outlined.DeleteOutline, w.text("Удалить чат", "Delete chat"), Modifier.size(19.dp))
                        } }, modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                if (ui.chats.isEmpty()) item { Text(w.text("Здесь появятся ваши диалоги", "Your conversations will appear here"), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = { scope.launch { drawer.close() }; onWeb() }, Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Spacer(Modifier.width(12.dp)); Text(w.text("Официальный сайт", "Official website"))
            }
            TextButton(onClick = { scope.launch { drawer.close() }; onSettings() }, Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Icon(Icons.Outlined.Settings, null); Spacer(Modifier.width(12.dp)); Text(w.text("Настройки", "Settings"))
            }
            Text("SnowSeek ${BuildConfig.VERSION_NAME} · GPLv3", Modifier.padding(24.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }) {
        BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(current?.let { chatTitle(it, w) } ?: "SnowSeek", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (ui.isDemo) w.text("Локальная демонстрация", "Local demo") else "DeepSeek · Web", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    } },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, w.text("История чатов", "Chat history")) } },
                    actions = { IconButton(onClick = model::newChat, enabled = !ui.generating && !ui.loading && ui.initialized) { Icon(Icons.Outlined.Edit, w.text("Новый чат", "New chat")) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!ui.initialized && !ui.loading) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                        Text(if (ui.isDemo) w.text("Не удалось прочитать локальную историю", "Could not read local history") else w.text("Не удалось подключиться к DeepSeek. Проверьте вход в настройках.", "Could not connect to DeepSeek. Check sign-in in settings."), Modifier.padding(vertical = 16.dp))
                        Button(onClick = model::initialize) { Text(w.text("Повторить", "Try again")) }
                    }
                } else if (current?.messages.isNullOrEmpty()) {
                    EmptyChat(Modifier.weight(1f), model::input)
                } else {
                    MessageList(current, settings.animations, Modifier.weight(1f))
                }
                Composer(ui, model)
            }
        }
    }
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null },
        title = { Text(w.text("Удалить диалог?", "Delete this conversation?")) },
        text = { Text(if (ui.isDemo) w.text("История этого демонстрационного чата будет удалена с устройства.", "This demo conversation will be removed from this device.") else w.text("Диалог будет удалён из аккаунта DeepSeek. Отменить это действие нельзя.", "This conversation will be deleted from your DeepSeek account. This cannot be undone.")) },
        confirmButton = { TextButton(onClick = { deleteId?.let(model::deleteChat); deleteId = null }) { Text(w.text("Удалить", "Delete")) } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text(w.text("Отмена", "Cancel")) } })
}

private fun chatTitle(chat: ChatSession, w: Words): String =
    if (chat.messages.isEmpty()) w.text("Новый чат", "New chat") else chat.title

@Composable
private fun EmptyChat(modifier: Modifier, onSuggestion: (String) -> Unit) {
    val w = LocalWords.current
    Column(modifier.fillMaxWidth().verticalScrollCompat().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BrandMark(68)
        Spacer(Modifier.height(24.dp))
        Text(w.text("С чего начнём?", "What’s on your mind?"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(w.text("Место для идей, вопросов и маленьких открытий.", "A space for ideas, questions and little discoveries."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(30.dp))
        listOf(
            Triple(Icons.Outlined.Code, w.text("Покажи пример кода на Kotlin", "Show me a Kotlin code example"), w.text("Немного кода", "A little code")),
            Triple(Icons.Outlined.Lightbulb, w.text("Помоги придумать идею для проекта", "Help me find an idea for a project"), w.text("Новая идея", "A new idea")),
            Triple(Icons.Outlined.AutoAwesome, w.text("Что умеет SnowSeek?", "What can SnowSeek do?"), w.text("Знакомство", "Meet SnowSeek")),
        ).forEach { (icon, prompt, label) ->
            OutlinedCard(onClick = { onSuggestion(prompt) }, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MessageList(chat: ChatSession, animations: Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    val last = chat.messages.lastOrNull()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var followLatest by remember(chat.id) { mutableStateOf(true) }
    val atBottom by remember { derivedStateOf {
        listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key == "conversation-bottom"
    } }
    LaunchedEffect(dragged) {
        if (dragged) followLatest = false
        else if (atBottom) followLatest = true
    }
    LaunchedEffect(atBottom) { if (atBottom && !dragged) followLatest = true }
    LaunchedEffect(chat.id) {
        if (chat.messages.isNotEmpty()) listState.scrollToItem(chat.messages.size)
    }
    LaunchedEffect(last?.id, last?.text?.length, last?.reasoning?.length, followLatest, dragged) {
        if (followLatest && !dragged && chat.messages.isNotEmpty()) {
            // Scroll to a separate trailing item, so even a reply taller than the viewport ends visibly.
            val index = chat.messages.size
            if (animations) listState.animateScrollToItem(index) else listState.scrollToItem(index)
        }
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            items(chat.messages, key = { it.id }) { message ->
                var visible by remember(message.id) { mutableStateOf(!animations) }
                LaunchedEffect(message.id) { visible = true }
                AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) { MessageCard(message) }
            }
            item(key = "conversation-bottom") { Spacer(Modifier.height(1.dp)) }
        }
        if (!atBottom) {
            val scope = rememberCoroutineScope()
            val w = LocalWords.current
            SmallFloatingActionButton(onClick = { followLatest = true; scope.launch { listState.scrollToItem(chat.messages.size) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp), containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Outlined.ArrowDownward, w.text("К последнему сообщению", "Jump to latest message"))
            }
        }
    }
}

@Composable
private fun Composer(ui: ChatUiState, model: SnowSeekViewModel) {
    val w = LocalWords.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 3.dp) {
            Column(Modifier.padding(8.dp)) {
                TextField(value = ui.input, onValueChange = model::input,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 180.dp),
                    placeholder = { Text(w.text("Спросите что-нибудь…", "Ask anything…"), style = MaterialTheme.typography.bodyLarge) },
                    enabled = ui.initialized && !ui.loading, maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(onSend = { keyboard?.hide(); model.send() }),
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface, disabledContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                )
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = ui.deepThink, onClick = model::deepThink, enabled = !ui.generating,
                        label = { Text("DeepThink", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Outlined.Psychology, null, Modifier.size(16.dp)) })
                    FilterChip(selected = ui.search, onClick = model::search, enabled = !ui.generating,
                        label = { Text(w.text("Поиск", "Search"), fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Outlined.Language, null, Modifier.size(16.dp)) })
                    }
                    FilledIconButton(onClick = { if (ui.generating) model.stop() else { keyboard?.hide(); model.send() } },
                        enabled = ui.generating || (ui.input.isNotBlank() && ui.initialized && !ui.loading),
                        modifier = Modifier.size(46.dp), shape = CircleShape) {
                        Icon(if (ui.generating) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send,
                            w.text(if (ui.generating) "Остановить ответ" else "Отправить сообщение", if (ui.generating) "Stop generating" else "Send message"), Modifier.size(21.dp))
                    }
                }
            }
        }
        Text(if (ui.isDemo) w.text("Демо · ответы, DeepThink и поиск моделируются на устройстве", "Demo · replies, DeepThink and search are simulated on-device") else w.text("DeepSeek · ответы могут содержать ошибки", "DeepSeek · answers may contain mistakes"),
            Modifier.padding(horizontal = 8.dp, vertical = 10.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsScreen(settings: AppSettings, model: SnowSeekViewModel, onBack: () -> Unit, onWeb: () -> Unit) {
    val w = LocalWords.current
    var logoutConfirmation by rememberSaveable { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text(w.text("Настройки", "Settings")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, w.text("Назад", "Back")) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScrollCompat().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SettingGroup(w.text("ОФОРМЛЕНИЕ", "APPEARANCE")) {
                Text(w.text("Тема", "Theme"), style = MaterialTheme.typography.titleSmall)
                ThemeMode.entries.forEach { theme ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = settings.theme == theme, onClick = { model.theme(theme) })
                        TextButton(onClick = { model.theme(theme) }, modifier = Modifier.weight(1f)) { Text(when(theme) {
                            ThemeMode.SYSTEM -> w.text("Как в системе", "System default")
                            ThemeMode.LIGHT -> w.text("Светлая", "Light")
                            ThemeMode.DARK -> w.text("Тёмная", "Dark")
                        }, modifier = Modifier.fillMaxWidth()) }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(w.text("Анимации", "Animations"), Modifier.weight(1f)); Switch(checked = settings.animations, onCheckedChange = model::animations)
                }
                Text(w.text("Размер текста", "Text size") + " · ${(settings.textScale * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                var textSize by remember(settings.textScale) { mutableFloatStateOf(settings.textScale) }
                Slider(value = textSize, onValueChange = { textSize = it }, onValueChangeFinished = { model.textScale(textSize) }, valueRange = .85f..1.3f, steps = 8)
            }
            SettingGroup(w.text("ЯЗЫК", "LANGUAGE")) {
                listOf("ru" to "Русский", "en" to "English").forEach { (code, name) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = settings.language == code, onClick = { model.language(code) })
                        TextButton(onClick = { model.language(code) }) { Text(name) }
                    }
                }
            }
            SettingGroup(w.text("ПОДКЛЮЧЕНИЕ", "CONNECTION")) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Science, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(if (LocalDemoMode.current) w.text("Демонстрационный провайдер", "Demo provider") else "DeepSeek · Web", style = MaterialTheme.typography.titleSmall)
                        Text(w.text("Для реального чата войдите на сайте и нажмите «Подключить SnowSeek». Приватный веб-протокол может измениться.", "For real chat, sign in on the website and tap Connect SnowSeek. The private web protocol may change."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = onWeb, modifier = Modifier.fillMaxWidth()) { Text(w.text("Войти на официальном сайте", "Sign in on the official website")) }
                TextButton(onClick = { logoutConfirmation = true }, modifier = Modifier.fillMaxWidth()) { Text(w.text("Выйти из веб-аккаунта", "Sign out of website account"), color = MaterialTheme.colorScheme.error) }
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
                        .onFailure { android.widget.Toast.makeText(context, w.text("Не удалось открыть браузер", "Could not open a browser"), android.widget.Toast.LENGTH_SHORT).show() }
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
                listOf("THIRD_PARTY_NOTICES.md", "Apache-2.0.txt", "GPL-3.0.txt").joinToString("\n\n") { file ->
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

@Composable
private fun SettingGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.verticalScroll(rememberScrollState())
