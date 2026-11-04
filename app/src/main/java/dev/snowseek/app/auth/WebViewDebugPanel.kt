package dev.snowseek.app.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewDebugPanel(log: WebViewDebugLog, russian: Boolean, onDismiss: () -> Unit, onDomSnapshot: () -> Unit) {
    fun label(ru: String, en: String) = if (russian) ru else en
    val snapshot by log.state.collectAsState()
    var selected by remember { mutableIntStateOf(0) }
    val tabs = WebDebugTab.entries
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(8.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                snackbarHost = { SnackbarHost(snackbarHost) },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label("Отладка WebView", "WebView diagnostics"), Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, label("Закрыть панель", "Close panel")) }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { log.clear() }) { Text(label("Очистить всё", "Clear all")) }
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("SnowSeek WebView diagnostics", log.export()))
                            scope.launch { snackbarHost.showSnackbar(label("Все вкладки скопированы", "All tabs copied")) }
                        }) { Text(label("Копировать всё", "Copy all")) }
                    }
                    Text(
                        label("В памяти: ${snapshot.entries.size}/600 · удалено старых: ${snapshot.dropped}. Console может содержать текст сайта — проверьте перед отправкой.",
                            "In memory: ${snapshot.entries.size}/600 · dropped: ${snapshot.dropped}. Console may contain website text; review before sharing."),
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                    )
                    PrimaryTabRow(selectedTabIndex = selected) {
                        tabs.forEachIndexed { index, tab ->
                            Tab(selected = selected == index, onClick = { selected = index }, text = { Text(tab.name) })
                        }
                    }
                    val tab = tabs[selected]
                    if (tab == WebDebugTab.WebView) {
                        TextButton(onClick = onDomSnapshot) { Text(label("Снимок DOM сейчас", "Capture DOM now")) }
                    }
                    if (tab == WebDebugTab.Network) {
                        val network = snapshot.entries.filter { it.tab == WebDebugTab.Network }
                        Text("HTTP_ERROR: ${network.count { it.message.startsWith("HTTP_ERROR") }} · REQUEST_ERROR: ${network.count { it.message.startsWith("REQUEST_ERROR") }} · TLS_ERROR: ${network.count { it.message.startsWith("TLS_ERROR") }} · DOM RESOURCE_ERROR: ${network.count { it.message.startsWith("RESOURCE_ERROR") }}",
                            Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
                        Text(label("Только callbacks WebView. HTTP ? — статус неизвестен. Успешные ответы, тела, заголовки и полная цепочка редиректов недоступны.",
                            "WebView callbacks only. HTTP ? = unknown status. Successful responses, bodies, headers and complete redirect chains are unavailable."),
                            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                        if (tab == WebDebugTab.WebView) {
                            item(key = "environment") {
                                SelectionContainer {
                                    Text("${snapshot.environment}\nURL: ${snapshot.currentUrl}\nProgress: ${snapshot.progress}%",
                                        Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                                HorizontalDivider()
                            }
                        }
                        val entries = snapshot.entries.filter { it.tab == tab }
                        if (entries.isEmpty()) {
                            item(key = "empty") { Text(label("Событий пока нет. Закройте панель и воспроизведите ошибку; запись продолжается.",
                                "No events yet. Close the panel and reproduce the issue; recording continues."), Modifier.padding(vertical = 16.dp)) }
                        }
                        items(entries, key = { it.id }) { entry ->
                            SelectionContainer {
                                Text(entry.display(), Modifier.fillMaxWidth().padding(vertical = 8.dp), fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
