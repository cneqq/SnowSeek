package dev.snowseek.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.snowseek.app.ui.BrandMark
import dev.snowseek.app.ui.LocalWords
import dev.snowseek.app.ui.LocalDemoMode
import dev.snowseek.core.ChatMessage
import dev.snowseek.core.MessageRole
import dev.snowseek.core.MessageStatus
import kotlinx.coroutines.delay

@Composable
fun MessageCard(message: ChatMessage) {
    val w = LocalWords.current
    val demo = LocalDemoMode.current
    val user = message.role == MessageRole.USER
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        if (user) {
            message.instruction?.let { dev.snowseek.app.ui.AppliedInstruction(it) }
            Surface(shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp),
                color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.widthIn(max = 560.dp)) {
                SelectionContainer { Text(message.text, Modifier.padding(horizontal = 18.dp, vertical = 14.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandMark(28)
                Text(if (demo) "SnowSeek" else "DeepSeek", style = MaterialTheme.typography.labelLarge)
                if (demo) Text(w.text("ДЕМО", "DEMO"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            if (message.reasoning.isNotEmpty()) {
                var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
                Surface(onClick = { expanded = !expanded }, shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Psychology, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(if (demo) w.text("Ход размышлений · демо", "Thinking summary · demo") else w.text("Размышления", "Thinking"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, w.text("Развернуть / свернуть", "Expand / collapse"), Modifier.size(18.dp))
                        }
                        if (expanded) SelectionContainer { Text(message.reasoning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            MarkdownText(message.text)
            if (message.status == MessageStatus.GENERATING) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
                    Text(if (demo) w.text("Создаётся демоответ…", "Generating a demo reply…") else w.text("DeepSeek отвечает…", "DeepSeek is responding…"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CopyButton(message.text, w.text("Копировать ответ", "Copy reply"))
                    if (message.status == MessageStatus.STOPPED || message.status == MessageStatus.ERROR) {
                        Text(if (message.status == MessageStatus.STOPPED) {
                            if (demo) w.text("Генерация остановлена", "Generation stopped") else w.text("Получение ответа остановлено", "Receiving stopped")
                        } else w.text("Ошибка получения ответа", "Could not receive reply"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownText(source: String) {
    val blocks = remember(source) { parseMarkdown(source) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val textWidth = maxWidth
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Code -> CodeBlock(block)
                    is MarkdownBlock.Heading -> SelectionContainer {
                        MarkdownInline(block.text, textWidth, style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        }.copy(fontWeight = FontWeight.SemiBold))
                    }
                    is MarkdownBlock.Paragraph -> SelectionContainer {
                        MarkdownInline(block.text, textWidth, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp))
                    }
                    is MarkdownBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("•", Modifier.width(12.dp), color = MaterialTheme.colorScheme.primary)
                        SelectionContainer {
                            MarkdownInline(block.text, (textWidth - 22.dp).coerceAtLeast(0.dp),
                                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp))
                        }
                    }
                    is MarkdownBlock.Quote -> Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                        SelectionContainer {
                            MarkdownInline(block.text, (textWidth - 24.dp).coerceAtLeast(0.dp),
                                Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    is MarkdownBlock.Table -> MarkdownTable(block)
                    is MarkdownBlock.Math -> MarkdownMath(block)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MarkdownBlock.Code) {
    val w = LocalWords.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(block.language.ifBlank { w.text("Код", "Code") }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CopyButton(block.text, w.text("Копировать код", "Copy code"))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SelectionContainer {
                Text(highlightCode(block.text, block.language, MaterialTheme.colorScheme.surfaceVariant.luminance() < .3f), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(16.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp, softWrap = false)
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun CopyButton(text: String, description: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    IconButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }, enabled = text.isNotEmpty()) {
        Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, description, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val codeTokens = Regex("//[^\\n]*|#[^\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\b(?:val|var|fun|class|object|return|if|else|when|for|while|import|package|suspend|data|in|is|def|print|from|as|async|await|const|let|function|export|public|private|static|void|new|true|false|null|None|True|False)\\b|\\b[0-9]+(?:\\.[0-9]+)?\\b")

private fun highlightCode(source: String, language: String, dark: Boolean): AnnotatedString = buildAnnotatedString {
    append(source)
    if (language.lowercase() !in setOf("kotlin", "kt", "java", "python", "py", "javascript", "js", "typescript", "ts", "json", "")) return@buildAnnotatedString
    for (token in codeTokens.findAll(source)) {
        val value = token.value
        val color = when {
            value.startsWith("//") || value.startsWith("#") -> if (dark) Color(0xFFA6AFC4) else Color(0xFF646C80)
            value.startsWith('"') || value.startsWith('\'') -> if (dark) Color(0xFF8ACAA3) else Color(0xFF267149)
            value.first().isDigit() -> if (dark) Color(0xFFEAC085) else Color(0xFF9B5A0C)
            else -> if (dark) Color(0xFFB9A1FF) else Color(0xFF6943B4)
        }
        addStyle(SpanStyle(color = color), token.range.first, token.range.last + 1)
    }
}
