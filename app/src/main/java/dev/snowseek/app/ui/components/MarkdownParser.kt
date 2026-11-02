package dev.snowseek.app.ui.components

sealed interface MarkdownBlock {
    data class Heading(val text: String, val level: Int) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Code(val language: String, val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
}

private val fenceStart = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
private val heading = Regex("^ {0,3}(#{1,6})\\s+(.+?)(?:\\s+#+)?\\s*$")
private val bullet = Regex("^\\s*(?:[-+*]|[0-9]+[.)])\\s+(.*)$")
private val quote = Regex("^\\s*>\\s?(.*)$")

/** A streaming-friendly subset of Markdown; code is kept literal, including unfinished fences. */
fun parseMarkdown(source: String): List<MarkdownBlock> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val result = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    fun flush() {
        if (paragraph.isNotEmpty()) {
            result += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
            paragraph.clear()
        }
    }
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val fence = fenceStart.matchEntire(line)
        if (fence != null) {
            flush()
            val marker = fence.groupValues[1]
            val language = fence.groupValues[2].trim().takeWhile { !it.isWhitespace() }.take(64)
            val close = Regex("^ {0,3}${Regex.escape(marker.first().toString())}{${marker.length},}\\s*$")
            val body = mutableListOf<String>()
            index++
            while (index < lines.size && !close.matches(lines[index])) {
                body += lines[index]
                index++
            }
            result += MarkdownBlock.Code(language, body.joinToString("\n"))
        } else {
            val title = heading.matchEntire(line)
            val listItem = bullet.matchEntire(line)
            val quotation = quote.matchEntire(line)
            when {
                line.isBlank() -> flush()
                title != null -> { flush(); result += MarkdownBlock.Heading(title.groupValues[2], title.groupValues[1].length) }
                listItem != null -> { flush(); result += MarkdownBlock.Bullet(listItem.groupValues[1]) }
                quotation != null -> { flush(); result += MarkdownBlock.Quote(quotation.groupValues[1]) }
                else -> paragraph += line
            }
        }
        index++
    }
    flush()
    return result
}
