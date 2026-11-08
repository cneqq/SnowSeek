package dev.snowseek.app.ui.components

sealed interface MarkdownBlock {
    data class Heading(val text: String, val level: Int) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Code(val language: String, val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Math(val latex: String, val source: String) : MarkdownBlock
    data class Table(val header: List<String>, val rows: List<List<String>>, val alignments: List<CellAlignment>) : MarkdownBlock
}

enum class CellAlignment { START, CENTER, END }

private val fenceStart = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
private val heading = Regex("^ {0,3}(#{1,6})\\s+(.+?)(?:\\s+#+)?\\s*$")
private val bullet = Regex("^\\s*(?:[-+*]|[0-9]+[.)])\\s+(.*)$")
private val quote = Regex("^\\s*>\\s?(.*)$")
private val tableDivider = Regex(":?-+:?")
private val tablePipe = Regex("""\\\||\|""")

internal val inlinePattern = Regex(
    """\*\*(?<bold>.+?)\*\*|`(?<code>[^`]+)`|\[(?<label>[^]]+)]\((?<url>https?://[^)]+)\)|""" +
        """\[citation:(?<citation>\d+)]|(?<!\\)\\\((?<paren>[^\n]+?)\\\)|""" +
        """(?<![\\$])\$(?![\s$])(?<dollar>(?:\\.|[^\\$`\n])+?)(?<!\s)\$(?![\d$])""",
)

private fun tableCells(line: String): List<String>? {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    val trimmed = line.trim()
    var end = 0
    for (pipe in tablePipe.findAll(trimmed)) {
        cell.append(trimmed.substring(end, pipe.range.first))
        if (pipe.value == "|") {
            cells += cell.toString().trim()
            cell.clear()
        } else {
            cell.append('|')
        }
        end = pipe.range.last + 1
    }
    if (cells.isEmpty()) return null
    cells += cell.append(trimmed.substring(end)).toString().trim()
    if (trimmed.startsWith('|')) cells.removeAt(0)
    if (end == trimmed.length && trimmed.endsWith('|') && cells.lastOrNull() == "") cells.removeAt(cells.lastIndex)
    return cells.takeIf { it.isNotEmpty() }
}

private fun tableAlignments(line: String): List<CellAlignment>? {
    val cells = tableCells(line) ?: listOf(line.trim())
    if (!cells.all(tableDivider::matches)) return null
    return cells.map {
        when {
            it.startsWith(':') && it.endsWith(':') -> CellAlignment.CENTER
            it.endsWith(':') -> CellAlignment.END
            else -> CellAlignment.START
        }
    }
}

private fun displayMath(lines: List<String>, start: Int): Pair<MarkdownBlock.Math, Int>? {
    val first = lines[start].trimStart()
    val close = when {
        first.startsWith("$$") -> "$$"
        first.startsWith("\\[") -> "\\]"
        else -> return null
    }
    for (index in start until lines.size) {
        if (index > start && fenceStart.matches(lines[index])) return null
        val last = if (index == start) first.drop(2).trimEnd() else lines[index].trimEnd()
        if (!last.endsWith(close)) continue
        val source = lines.subList(start, index + 1).joinToString("\n")
        val latex = source.trim().drop(2).dropLast(2).trim()
        return if (latex.isNotEmpty()) MarkdownBlock.Math(latex, source) to index else null
    }
    return null
}

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
            val math = displayMath(lines, index)
            if (math != null) {
                flush()
                result += math.first
                index = math.second + 1
                continue
            }
            val title = heading.matchEntire(line)
            val listItem = bullet.matchEntire(line)
            val quotation = quote.matchEntire(line)
            if (title == null && listItem == null && quotation == null && index + 1 < lines.size) {
                val header = tableCells(line)
                val alignments = header?.let { tableAlignments(lines[index + 1]) }
                if (header != null && alignments?.size == header.size) {
                    flush()
                    val rows = mutableListOf<List<String>>()
                    index += 2
                    while (index < lines.size) {
                        val row = lines[index]
                        if (fenceStart.matches(row) || heading.matches(row) || bullet.matches(row) || quote.matches(row)) break
                        val cells = tableCells(row) ?: break
                        rows += List(header.size) { cells.getOrElse(it) { "" } }
                        index++
                    }
                    result += MarkdownBlock.Table(header, rows, alignments)
                    continue
                }
            }
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
