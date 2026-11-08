@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.snowseek.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
internal fun LocalModelScreen(onBack: () -> Unit) {
    val model: LocalModelViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val w = LocalWords.current
    val bytes = Formatter.formatFileSize(context, 347_251_840L)
    var deleteConfirmation by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(model) { onDispose { model.unload() } }

    Scaffold(topBar = { TopAppBar(title = { Text(w.text("Локальная модель", "Local model")) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, w.text("Назад", "Back")) }
    }) }) { padding ->
        if (state.ready) {
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(w.text("Пробный чат · Qwen3 0.6B", "Test chat · Qwen3 0.6B"), style = MaterialTheme.typography.titleMedium)
                        Text(w.text("Ответы создаются на телефоне. Интернет для них не нужен.", "Replies are generated on your phone. No internet connection is needed."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(w.text("Модель может ошибаться. Поиск в интернете отключён.", "The model may make mistakes. Web search is unavailable."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { deleteConfirmation = true }) {
                            Text(w.text("Удалить модель · $bytes", "Delete model · $bytes"), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    items(state.exchanges) { exchange ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.align(Alignment.End)) {
                                Text(exchange.question, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                            }
                            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()) {
                                Text(exchange.answer.ifBlank { if (state.generating) w.text("Думает…", "Thinking…") else "" },
                                    Modifier.fillMaxWidth().padding(14.dp))
                            }
                        }
                    }
                    if (state.loading) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(w.text("Загрузка модели в память…", "Loading model into memory…"))
                    } }
                    state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.prompt,
                        onValueChange = model::prompt,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(w.text("Напишите вопрос…", "Ask a question…")) },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { model.send() }),
                        shape = RoundedCornerShape(24.dp),
                    )
                    IconButton(onClick = model::send, enabled = !state.generating && !state.loading && state.prompt.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Outlined.Send, w.text("Отправить", "Send"))
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Qwen3 0.6B · INT4", style = MaterialTheme.typography.titleLarge)
                Text(w.text("Компактная языковая модель для ответов без отправки сообщений в интернет.",
                    "A compact language model that answers without sending messages to the internet."))
                Text(w.text("Размер загрузки · $bytes", "Download size · $bytes"))
                Text(w.text("Модель Apache-2.0. SnowSeek сохранит её в памяти приложения и проверит файл после загрузки.",
                    "Model license: Apache-2.0. SnowSeek stores it in app storage and verifies the file after download."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.downloading || state.verifying) {
                    if (state.downloading) {
                        val progress = (state.downloadedBytes.toFloat() / 347_251_840f).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()}% · ${Formatter.formatFileSize(context, state.downloadedBytes)} / $bytes")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(w.text("Проверяем файл модели…", "Verifying model file…"))
                    }
                    TextButton(onClick = model::cancelDownload) { Text(w.text("Отменить", "Cancel")) }
                } else {
                    Button(onClick = model::download, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Download, null)
                        Text("  ${if (state.downloadedBytes > 0) w.text("Продолжить загрузку", "Resume download") else w.text("Скачать модель · $bytes", "Download model · $bytes")}")
                    }
                    if (state.downloadedBytes > 0) Text(w.text("Уже загружено · ${Formatter.formatFileSize(context, state.downloadedBytes)}",
                        "Already downloaded · ${Formatter.formatFileSize(context, state.downloadedBytes)}"))
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (deleteConfirmation) AlertDialog(
        onDismissRequest = { deleteConfirmation = false },
        title = { Text(w.text("Удалить модель?", "Delete model?")) },
        text = { Text(w.text("Файл Qwen3 размером около $bytes будет удалён с устройства.", "The Qwen3 file, about $bytes, will be removed from this device.")) },
        confirmButton = { TextButton(onClick = { deleteConfirmation = false; model.deleteModel() }) {
            Text(w.text("Удалить", "Delete"), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { deleteConfirmation = false }) { Text(w.text("Отмена", "Cancel")) } },
    )
}
