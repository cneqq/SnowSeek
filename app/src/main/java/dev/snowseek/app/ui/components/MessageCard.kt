package dev.snowseek.app.ui.components

import android.net.Uri

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import dev.snowseek.app.ui.LocalWords
import dev.snowseek.core.ChatMessage
import dev.snowseek.core.MessageRole
import dev.snowseek.core.MessageStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
fun MessageCard(
    message: ChatMessage,
    onReasoningToggle: () -> Unit = {},
    onEdit: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    animateStreaming: Boolean = false,
    onReveal: () -> Unit = {},
    showInstruction: Boolean = true,
    attachmentPhotos: Map<String, Uri> = emptyMap(),
    wallpaperEnabled: Boolean = false,
) {
    val w = LocalWords.current
    val user = message.role == MessageRole.USER
    val background = if (wallpaperEnabled && !user) Modifier.clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surface.copy(alpha = .94f)).padding(12.dp) else Modifier
    Column(Modifier.fillMaxWidth().then(background), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        if (user) {
            if (showInstruction) message.instruction?.let { dev.snowseek.app.ui.AppliedInstruction(it) }
            Surface(shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp),
                color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.widthIn(max = 560.dp)
                    .then(if (onEdit != null) Modifier.combinedClickable(
                        onClick = {}, onLongClick = onEdit,
                        onLongClickLabel = w.text("Изменить сообщение", "Edit message"),
                    ) else Modifier)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    var expanded by rememberSaveable(message.id, message.text) { mutableStateOf(false) }
                    var overflows by remember(message.id, message.text) { mutableStateOf(false) }
                    if (message.text.isNotBlank()) Text(message.text, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = if (expanded) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
                    if (message.text.isBlank() && message.attachments.isEmpty()) {
                        Text(w.text("Вложение недоступно", "Attachment unavailable"), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (expanded || overflows) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) w.text("Свернуть", "Collapse") else w.text("Развернуть", "Expand"))
                        }
                    }
                    message.attachments.forEach { attachment ->
                        key(attachment.id) {
                            val photo = attachmentPhotos[attachment.id]
                            if (photo != null || attachment.isImage || attachment.previewUrl != null) {
                                PhotoAttachment(attachment.id, photo, attachment.name, Modifier.padding(top = 8.dp).size(width = 120.dp, height = 90.dp),
                                    previewUrl = attachment.previewUrl)
                            } else Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.AttachFile, null, Modifier.size(16.dp))
                                Text(attachment.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        } else {
            val generating = message.status == MessageStatus.GENERATING
            val reveal = animateStreaming && (generating || message.status == MessageStatus.COMPLETE)
            val text = revealStream(message.id, message.text, generating, reveal)
            val reasoning = revealStream(message.id, message.reasoning, generating, reveal)
            val revealing = text != message.text || reasoning != message.reasoning
            val revealCallback by rememberUpdatedState(onReveal)
            LaunchedEffect(text, reasoning) {
                if (reveal && (generating || revealing)) revealCallback()
            }
            if (reasoning.isNotEmpty()) {
                var expanded by rememberSaveable(message.id) {
                    mutableStateOf(message.status == MessageStatus.GENERATING)
                }
                Surface(shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f), modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth().clickable {
                            onReasoningToggle()
                            expanded = !expanded
                        }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Psychology, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(w.text("Рассуждение", "Reasoning"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, w.text("Развернуть / свернуть", "Expand / collapse"), Modifier.size(18.dp))
                        }
                        if (expanded) SelectionContainer {
                            Text(reasoning, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            CompositionLocalProvider(LocalSearchCitations provides message.citations) {
                MarkdownText(text)
            }
            if (generating || revealing) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
                    Text(w.text("DeepSeek отвечает…", "DeepSeek is responding…"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CopyButton(message.text, w.text("Копировать ответ", "Copy reply"), replyAction = true)
                        if (onRegenerate != null) ReplyActionButton(
                            onClick = onRegenerate,
                            icon = Icons.Rounded.Refresh,
                            description = w.text("Перегенерировать", "Regenerate"),
                        )
                    }
                    if (onContinue != null) TextButton(onClick = onContinue) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(w.text("Продолжить", "Continue"))
                    }
                    if (message.status == MessageStatus.STOPPED || message.status == MessageStatus.ERROR) {
                        Text(if (message.status == MessageStatus.STOPPED) {
                            w.text("Получение ответа остановлено", "Receiving stopped")
                        } else w.text("Ошибка получения ответа", "Could not receive reply"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun revealStream(id: String, target: String, generating: Boolean, enabled: Boolean): String {
    val latest by rememberUpdatedState(target)
    var shown by remember(id, enabled) { mutableStateOf(if (enabled && generating) "" else target) }
    LaunchedEffect(id, enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            snapshotFlow { latest != shown }.first { it }
            delay(24)
            val next = latest
            if (!next.startsWith(shown)) {
                shown = next
            } else if (next.length > shown.length) {
                val remaining = next.length - shown.length
                // Catch up faster after a burst and keep Unicode surrogate pairs together.
                var end = shown.length + minOf(remaining, maxOf(2, (remaining + 5) / 6))
                if (end < next.length && next[end].isLowSurrogate()) end++
                shown = next.substring(0, end)
            }
        }
    }
    return if (enabled && target.startsWith(shown)) shown else target
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
private fun CopyButton(
    text: String, description: String,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
    iconSize: androidx.compose.ui.unit.Dp = 17.dp,
    replyAction: Boolean = false,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    val onCopy = { clipboard.setText(AnnotatedString(text)); copied = true }
    if (replyAction) {
        val w = LocalWords.current
        ReplyActionButton(
            onClick = onCopy,
            icon = if (copied) Icons.Outlined.Check else Icons.Rounded.ContentCopy,
            description = if (copied) w.text("Скопировано", "Copied") else description,
            enabled = text.isNotEmpty(),
            confirmed = copied,
        )
    } else {
        IconButton(onClick = onCopy, enabled = text.isNotEmpty(), colors = colors) {
            Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, description, Modifier.size(iconSize))
        }
    }
}

@Composable
private fun ReplyActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    confirmed: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).padding(4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (confirmed) colors.primaryContainer else colors.surfaceVariant,
            contentColor = if (confirmed) colors.onPrimaryContainer else colors.onSurfaceVariant,
        ),
    ) {
        Icon(icon, description, Modifier.size(18.dp))
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
