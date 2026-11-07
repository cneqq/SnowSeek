package dev.snowseek.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.darriousliu.katex.core.MTMathView
import io.github.darriousliu.katex.core.MathItem
import io.github.darriousliu.katex.mathdisplay.parse.MTLineStyle
import io.github.darriousliu.katex.mathdisplay.parse.MTMathListBuilder
import io.github.darriousliu.katex.mathdisplay.parse.MTParseError
import io.github.darriousliu.katex.mathdisplay.parse.MTParseErrors
import io.github.darriousliu.katex.mathdisplay.render.MTFontManager
import io.github.darriousliu.katex.mathdisplay.render.MTTypesetter

@Composable
internal fun MarkdownInline(
    source: String,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    textAlign: TextAlign = TextAlign.Start,
) {
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface
    val size = with(density) { style.fontSize.toPx() }
    val width = with(density) { maxWidth.toPx() }
    val (text, inlineContent) = remember(source, size, width, density, color) {
        val formulas = mutableMapOf<String, InlineTextContent>()
        val text = buildAnnotatedString {
            appendMarkdown(source) { latex, original ->
                val math = typeset(latex, size, MTLineStyle.KMTLineStyleText)
                if (math == null || math.width > width) {
                    append(original)
                } else {
                    val id = "math-${formulas.size}"
                    val placeholder = with(density) {
                        Placeholder(math.width.toSp(), math.height.toSp(), PlaceholderVerticalAlign.TextCenter)
                    }
                    formulas[id] = InlineTextContent(placeholder) { MTMathView(math, textColor = color) }
                    appendInlineContent(id, original)
                }
            }
        }
        text to formulas
    }
    Text(text, modifier, style = style, textAlign = textAlign, inlineContent = inlineContent)
}

private fun AnnotatedString.Builder.appendMarkdown(
    source: String,
    appendMath: AnnotatedString.Builder.(latex: String, original: String) -> Unit,
) {
    var end = 0
    for (match in inlinePattern.findAll(source)) {
        append(source.substring(end, match.range.first))
        val start = length
        val groups = match.groups
        when {
            groups["bold"] != null -> {
                appendMarkdown(groups["bold"]!!.value, appendMath)
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
            }
            groups["code"] != null -> {
                append(groups["code"]!!.value)
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x224163EB)), start, length)
            }
            groups["label"] != null -> append("${groups["label"]!!.value} (${groups["url"]!!.value})")
            else -> appendMath((groups["paren"] ?: groups["dollar"])!!.value, match.value)
        }
        end = match.range.last + 1
    }
    append(source.substring(end))
}

@Composable
internal fun MarkdownMath(block: MarkdownBlock.Math) {
    val fontSize = with(LocalDensity.current) { 18.sp.toPx() }
    val math = remember(block.latex, fontSize) {
        typeset(block.latex, fontSize, MTLineStyle.KMTLineStyleDisplay)
    }
    if (math == null) {
        SelectionContainer { Text(block.source, style = MaterialTheme.typography.bodyLarge) }
    } else {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            MTMathView(
                math,
                modifier = Modifier.semantics { contentDescription = block.latex },
                textColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun typeset(latex: String, fontSize: Float, style: MTLineStyle): MathItem? {
    if (latex.isBlank() || latex.length > 2_048) return null
    return try {
        val error = MTParseError()
        val list = MTMathListBuilder.buildFromString(latex, error) ?: return null
        if (error.errorCode != MTParseErrors.ErrorNone) return null
        val font = MTFontManager.defaultFont().copyFontWithSize(fontSize)
        val display = MTTypesetter.createLineForMathList(list, font, style)
        MathItem(display, display.width + 1, display.ascent + display.descent + 1, latex)
    } catch (_: Exception) {
        // Incomplete or unsupported formulas are still readable and copyable.
        null
    } catch (_: StackOverflowError) {
        // Deep nesting can exceed the parser's recursion limit.
        null
    }
}

@Composable
internal fun MarkdownTable(block: MarkdownBlock.Table) {
    val cellWidth = 144.dp
    val padding = 10.dp
    val colors = MaterialTheme.colorScheme
    SelectionContainer {
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            (listOf(block.header) + block.rows).forEachIndexed { index, cells ->
                val header = index == 0
                val rowModifier = if (header) Modifier.background(colors.surfaceVariant) else Modifier
                Row(rowModifier) {
                    cells.forEachIndexed { column, cell ->
                        MarkdownInline(
                            cell,
                            maxWidth = cellWidth - padding * 2,
                            modifier = Modifier.width(cellWidth).padding(padding),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            textAlign = when (block.alignments[column]) {
                                CellAlignment.START -> TextAlign.Start
                                CellAlignment.CENTER -> TextAlign.Center
                                CellAlignment.END -> TextAlign.End
                            },
                        )
                    }
                }
                HorizontalDivider(Modifier.width(cellWidth * block.header.size), color = colors.outlineVariant)
            }
        }
    }
}
