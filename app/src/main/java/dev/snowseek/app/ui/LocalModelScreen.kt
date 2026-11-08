@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.snowseek.app.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.snowseek.app.local.DeviceModelFitEvaluator
import dev.snowseek.app.local.InstalledModel
import dev.snowseek.app.local.ModelCatalogEntry

@Composable
internal fun LocalModelScreen(onBack: () -> Unit) {
    val model: LocalModelViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val w = LocalWords.current
    var deleteModelId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::import)
    }
    BackHandler(enabled = state.chatModelId != null) { model.backToLibrary() }
    DisposableEffect(model) { onDispose { model.onScreenDisposed() } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (state.chatModelId != null) state.installed.firstOrNull { it.id == state.chatModelId }?.displayName
                ?: w.text("Локальный чат", "Local chat") else w.text("Локальные модели", "Local models")) },
            navigationIcon = {
                IconButton(onClick = { if (state.chatModelId != null) model.backToLibrary() else onBack() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, w.text("Назад", "Back"))
                }
            },
        )
    }) { padding ->
        if (state.chatModelId != null) {
            LocalModelChat(state.prompt, state.exchanges, state.loading, state.generating, state.error,
                model::prompt, model::send, model::cancelGeneration, Modifier.fillMaxSize().padding(padding).imePadding())
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(w.text("Ответы создаются на телефоне. Интернет нужен только для скачивания моделей.",
                        "Replies are generated on your phone. Internet is only needed to download models."),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { Text(w.text("Установлены", "Installed"), style = MaterialTheme.typography.titleMedium) }
                if (state.installed.isEmpty()) item {
                    Text(w.text("Пока нет установленных моделей. Скачайте модель из каталога или выберите свой файл.",
                        "No installed models yet. Download one from the catalogue or import a file."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.installed, key = { it.id }) { installed ->
                    InstalledModelCard(installed, selected = installed.id == state.selectedId,
                        onOpen = { model.openChat(installed.id) }, onDelete = { deleteModelId = installed.id })
                }
                item { Text(w.text("Каталог", "Catalogue"), style = MaterialTheme.typography.titleMedium) }
                items(state.catalog, key = { it.id }) { entry ->
                    val isInstalled = state.installed.any { it.id == entry.id }
                    CatalogueModelCard(entry, isInstalled, state.selectedId == entry.id,
                        state.downloadingId == entry.id, state.verifying && state.downloadingId == entry.id,
                        state.downloadedBytes, context, onDownload = { model.download(entry) }, onOpen = { model.openChat(entry.id) },
                        onCancel = model::cancelTransfer)
                }
                item {
                    Button(onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                        enabled = state.downloadingId == null, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FolderOpen, null)
                        Text("  ${w.text("Импортировать .gguf или .litertlm", "Import .gguf or .litertlm")}")
                    }
                    Text(w.text("Файл будет скопирован в закрытое хранилище SnowSeek. Совместимость зависит от шаблона и возможностей модели.",
                        "The file is copied to SnowSeek's private storage. Compatibility depends on its template and capabilities."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp))
                }
                if (state.downloadingId == "import") item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(if (state.verifying) w.text("Проверка файла и совместимости…", "Checking file and compatibility…")
                            else "${w.text("Импорт файла", "Importing file")} · ${Formatter.formatFileSize(context, state.downloadedBytes)}")
                        TextButton(onClick = model::cancelTransfer) { Text(w.text("Отмена", "Cancel")) }
                    }
                }
                state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
        }
    }

    val deleting = state.installed.firstOrNull { it.id == deleteModelId }
    if (deleting != null) AlertDialog(
        onDismissRequest = { deleteModelId = null },
        title = { Text(w.text("Удалить модель?", "Delete model?")) },
        text = { Text("${deleting.displayName} · ${Formatter.formatFileSize(context, deleting.fileSizeBytes)}") },
        confirmButton = { TextButton(onClick = { deleteModelId = null; model.delete(deleting.id) }) {
            Text(w.text("Удалить", "Delete"), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { deleteModelId = null }) { Text(w.text("Отмена", "Cancel")) } },
    )
}

@Composable
private fun InstalledModelCard(model: InstalledModel, selected: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val w = LocalWords.current
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${model.format.extension.uppercase()} · ${Formatter.formatFileSize(context, model.fileSizeBytes)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${model.source} · ${model.validation}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, w.text("Удалить", "Delete")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (selected) w.text("Выбрана", "Selected") else model.memoryEstimate ?: w.text("Оценка примерная", "Rough estimate"),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onOpen) { Text(if (selected) w.text("Открыть чат", "Open chat") else w.text("Выбрать", "Select")) }
            }
        }
    }
}

@Composable
private fun CatalogueModelCard(
    entry: ModelCatalogEntry,
    installed: Boolean,
    selected: Boolean,
    downloading: Boolean,
    verifying: Boolean,
    downloadedBytes: Long,
    context: android.content.Context,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    val w = LocalWords.current
    val uriHandler = LocalUriHandler.current
    val bytes = Formatter.formatFileSize(context, entry.sizeBytes)
    val fit = DeviceModelFitEvaluator.evaluate(context, entry)
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    Text("${entry.format.extension.uppercase()} · $bytes", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${entry.license}", style = MaterialTheme.typography.labelMedium)
            }
            Text("${w.text("Нагрузка", "Workload")}: ${fit.title} · ${fit.explanation}", style = MaterialTheme.typography.bodySmall)
            Text("${w.text(entry.tasks, "Short answers, simple questions, drafts")} · ${w.text(entry.languages, "Multilingual")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${entry.publisher} · ${entry.baseModel} · ${w.text("Ревизия", "Revision")} ${entry.revision.take(8)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { uriHandler.openUri(entry.downloadUrl.substringBefore("/resolve/")) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                    Text(w.text("Источник", "Source"), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { uriHandler.openUri(entry.licenseUrl) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                    Text(w.text("Лицензия", "License"), style = MaterialTheme.typography.labelMedium)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when {
                installed -> Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selected) w.text("Открыть чат", "Open chat") else w.text("Выбрать модель", "Select model"))
                }
                verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  ${w.text("Проверяем файл модели…", "Verifying model…")}", Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text(w.text("Отменить", "Cancel")) }
                }
                downloading -> {
                    val progress = (downloadedBytes.toFloat() / entry.sizeBytes).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${(progress * 100).toInt()}% · ${Formatter.formatFileSize(context, downloadedBytes)} / $bytes",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onCancel) { Text(w.text("Отменить", "Cancel")) }
                    }
                }
                else -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Download, null)
                    Text("  ${w.text("Скачать", "Download")} · $bytes")
                }
            }
        }
    }
}

@Composable
private fun LocalModelChat(
    prompt: String,
    exchanges: List<LocalModelExchange>,
    loading: Boolean,
    generating: Boolean,
    error: String?,
    onPrompt: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val w = LocalWords.current
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(w.text("Ответы создаются на устройстве. Поиск в интернете отключён.",
                    "Replies are generated on-device. Web search is off."), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(exchanges) { exchange ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.align(Alignment.End)) { Text(exchange.question, Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(exchange.answer.ifBlank { if (generating) w.text("Создаёт ответ…", "Generating…") else "" },
                            Modifier.fillMaxWidth().padding(14.dp))
                    }
                }
            }
            if (loading) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(w.text("Загрузка модели в память…", "Loading model into memory…"))
            } }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Spacer(Modifier.size(1.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = prompt, onValueChange = onPrompt, modifier = Modifier.weight(1f),
                placeholder = { Text(w.text("Напишите вопрос…", "Ask a question…")) }, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (generating) onCancel() else onSend() }),
                shape = RoundedCornerShape(24.dp))
            IconButton(onClick = { if (generating) onCancel() else onSend() }, enabled = generating || (!loading && prompt.isNotBlank())) {
                Icon(if (generating) Icons.Outlined.StopCircle else Icons.AutoMirrored.Outlined.Send,
                    if (generating) w.text("Остановить ответ", "Stop response") else w.text("Отправить", "Send"))
            }
        }
    }
}
