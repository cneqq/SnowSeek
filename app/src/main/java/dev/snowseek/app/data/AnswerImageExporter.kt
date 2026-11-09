package dev.snowseek.app.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.snowseek.core.SearchCitation
import java.io.File
import java.util.UUID

internal object AnswerImageExporter {
    private const val WIDTH = 1080
    private const val SIDE = 68f
    private const val MAX_PAGE_HEIGHT = 7_600f
    private const val BACKGROUND = 0xFF101113.toInt()
    private const val CARD = 0xFF292A2E.toInt()
    private const val CODE = 0xFF202125.toInt()
    private const val FOREGROUND = 0xFFF1F1F3.toInt()
    private const val MUTED = 0xFFA6A7AD.toInt()
    private const val ACCENT = 0xFFB9C6FF.toInt()

    private enum class Kind { HEADING, PARAGRAPH, BULLET, QUOTE, CODE }
    private data class Block(val text: String, val kind: Kind)
    private data class Page(val blocks: MutableList<Block> = mutableListOf(), var usedHeight: Float = 0f)

    suspend fun share(context: Context, question: String, answer: String, citations: List<SearchCitation>) {
        val app = context.applicationContext
        val files = withContext(Dispatchers.IO) { render(app, question, answer, citations) }
        val uris = files.map { FileProvider.getUriForFile(app, "${app.packageName}.files", it) }
        val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/png"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newUri(app.contentResolver, "SnowSeek", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }
        context.startActivity(Intent.createChooser(send, "SnowSeek"))
    }

    private fun render(context: Context, question: String, answer: String, citations: List<SearchCitation>): List<File> {
        val directory = File(context.cacheDir, "shared-answer-images").apply { mkdirs() }
        val staleBefore = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        directory.listFiles()?.filter { it.lastModified() < staleBefore }?.forEach { it.delete() }
        val sourceBlocks = parse(answer).toMutableList()
        if (citations.isNotEmpty()) {
            sourceBlocks += Block("Источники", Kind.HEADING)
            citations.forEach { source -> sourceBlocks += Block("${source.title} — ${source.url}", Kind.BULLET) }
        }
        val body = sourceBlocks.flatMap(::splitLargeBlock)
        val width = (WIDTH - SIDE * 2).toInt()
        val pages = mutableListOf(Page())
        val page = { pages.last() }
        if (question.isNotBlank()) {
            val questionLayout = layout(cleanInline(question), paint(34f, Typeface.NORMAL), width)
            page().usedHeight += questionLayout.height + 72f
        }
        body.forEach { block ->
            val measure = blockLayout(block, width)
            val gap = if (page().blocks.isEmpty()) 0f else 30f
            if (page().usedHeight + gap + measure.height > MAX_PAGE_HEIGHT && page().blocks.isNotEmpty()) {
                pages += Page()
            }
            page().usedHeight += (if (page().blocks.isEmpty()) 0f else 30f) + measure.height + 18f
            page().blocks += block
        }
        if (pages.size == 1 && pages.single().blocks.isEmpty() && question.isBlank()) {
            pages.single().blocks += Block("", Kind.PARAGRAPH)
        }
        return pages.mapIndexed { index, content ->
            val firstQuestionLayout = if (index == 0 && question.isNotBlank())
                layout(cleanInline(question), paint(34f, Typeface.NORMAL), width) else null
            val headerHeight = 190f
            val height = (headerHeight + content.usedHeight + 150f).coerceAtLeast(880f).toInt()
            val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(BACKGROUND)
            drawHeader(canvas)
            var y = headerHeight
            if (firstQuestionLayout != null) {
                val rect = RectF(SIDE, y, WIDTH - SIDE, y + firstQuestionLayout.height + 40f)
                canvas.drawRoundRect(rect, 28f, 28f, fill(CARD))
                canvas.save(); canvas.translate(SIDE + 22f, y + 20f); firstQuestionLayout.draw(canvas); canvas.restore()
                y += firstQuestionLayout.height + 72f
            }
            content.blocks.forEachIndexed { blockIndex, block ->
                val blockLayout = blockLayout(block, width)
                if (blockIndex > 0) y += 30f
                when (block.kind) {
                    Kind.CODE -> {
                        val rect = RectF(SIDE, y - 15f, WIDTH - SIDE, y + blockLayout.height + 15f)
                        canvas.drawRoundRect(rect, 18f, 18f, fill(CODE))
                        canvas.save(); canvas.translate(SIDE + 20f, y); blockLayout.draw(canvas); canvas.restore()
                    }
                    Kind.QUOTE -> {
                        val rect = RectF(SIDE, y - 6f, SIDE + 5f, y + blockLayout.height + 6f)
                        canvas.drawRoundRect(rect, 3f, 3f, fill(ACCENT))
                        canvas.save(); canvas.translate(SIDE + 24f, y); blockLayout.draw(canvas); canvas.restore()
                    }
                    Kind.BULLET -> {
                        canvas.drawCircle(SIDE + 7f, y + 19f, 5f, fill(ACCENT))
                        canvas.save(); canvas.translate(SIDE + 28f, y); blockLayout.draw(canvas); canvas.restore()
                    }
                    else -> {
                        canvas.save(); canvas.translate(SIDE, y); blockLayout.draw(canvas); canvas.restore()
                    }
                }
                y += blockLayout.height + 18f
            }
            val footerPaint = paint(22f, Typeface.NORMAL).apply { color = MUTED; textAlign = Paint.Align.CENTER }
            val footer = if (pages.size > 1) "SnowSeek  ·  ${index + 1}/${pages.size}" else "SnowSeek"
            canvas.drawText(footer, WIDTH / 2f, height - 58f, footerPaint)
            val file = File(directory, "answer-${UUID.randomUUID()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            file
        }
    }

    private fun drawHeader(canvas: Canvas) {
        val markPaint = paint(44f, Typeface.BOLD).apply { color = ACCENT }
        val titlePaint = paint(36f, Typeface.BOLD).apply { color = FOREGROUND }
        val subtitlePaint = paint(22f, Typeface.NORMAL).apply { color = MUTED }
        canvas.drawText("❄", SIDE, 93f, markPaint)
        canvas.drawText("SnowSeek", SIDE + 58f, 89f, titlePaint)
        canvas.drawText("Ответ DeepSeek", SIDE, 150f, subtitlePaint)
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF34353A.toInt(); strokeWidth = 2f }
        canvas.drawLine(SIDE, 184f, WIDTH - SIDE, 184f, divider)
    }

    private fun parse(source: String): List<Block> {
        val result = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        var inCode = false
        val code = mutableListOf<String>()
        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                result += Block(cleanInline(paragraph.joinToString(" ")), Kind.PARAGRAPH)
                paragraph.clear()
            }
        }
        source.lines().forEach { raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    result += Block(code.joinToString("\n").ifBlank { " " }, Kind.CODE)
                    code.clear()
                }
                flushParagraph(); inCode = !inCode
            } else if (inCode) code += line
            else when {
                line.isBlank() -> flushParagraph()
                line.trimStart().startsWith("|") && line.trimEnd().endsWith("|") -> {
                    flushParagraph()
                    if (!line.contains(Regex("^\\|[\\s|:-]+\\|$"))) {
                        val cells = line.trim().trim('|').split('|').map { cleanInline(it.trim()) }
                        result += Block(cells.joinToString("     ·     "), Kind.PARAGRAPH)
                    }
                }
                line.trimStart().startsWith(">") -> { flushParagraph(); result += Block(cleanInline(line.trimStart().removePrefix(">").trim()), Kind.QUOTE) }
                line.trimStart().matches(Regex("^#{1,6}\\s+.*")) -> { flushParagraph(); result += Block(cleanInline(line.trimStart().replace(Regex("^#{1,6}\\s+"), "")), Kind.HEADING) }
                line.trimStart().matches(Regex("^(?:[-*+]\\s+|\\d+[.)]\\s+).+")) -> {
                    flushParagraph(); result += Block(cleanInline(line.trimStart().replace(Regex("^(?:[-*+]\\s+|\\d+[.)]\\s+)"), "")), Kind.BULLET)
                }
                else -> paragraph += line.trim()
            }
        }
        if (inCode) result += Block(code.joinToString("\n").ifBlank { " " }, Kind.CODE)
        flushParagraph()
        return result
    }

    private fun cleanInline(text: String): String = text
        .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "\$1")
        .replace(Regex("\\[([^]]+)]\\((https?://[^)]+)\\)"), "\$1")
        .replace(Regex("\\[(?:citation|cite):\\d+]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^\\s*[-*_]{3,}\\s*$"), "")
        .replace(Regex("[*_]{1,3}([^*_]+)[*_]{1,3}"), "\$1")
        .replace(Regex("`([^`]+)`"), "\$1")
        .replace(Regex("\\s+"), " ").trim()

    private fun splitLargeBlock(block: Block): List<Block> {
        if (block.kind == Kind.CODE) {
            val lines = block.text.lines().flatMap { line -> line.chunked(50) }
            val chunks = mutableListOf<Block>()
            var current = mutableListOf<String>()
            lines.forEach { line ->
                if (current.size >= 56) { chunks += Block(current.joinToString("\n"), Kind.CODE); current = mutableListOf() }
                current += line
            }
            if (current.isNotEmpty()) chunks += Block(current.joinToString("\n"), Kind.CODE)
            return chunks.ifEmpty { listOf(block) }
        }
        val chunks = mutableListOf<Block>()
        var current = StringBuilder()
        val words = block.text.split(Regex("\\s+")).flatMap { it.chunked(1_000) }
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (candidate.length > 3_000 && current.isNotEmpty()) {
                chunks += block.copy(text = current.toString())
                current = StringBuilder(word)
            } else current = StringBuilder(candidate)
        }
        if (current.isNotEmpty()) chunks += block.copy(text = current.toString())
        return chunks.ifEmpty { listOf(block) }
    }

    private fun blockLayout(block: Block, width: Int): StaticLayout {
        val (size, style, color, family) = when (block.kind) {
            Kind.HEADING -> Quadruple(42f, Typeface.BOLD, FOREGROUND, Typeface.DEFAULT)
            Kind.CODE -> Quadruple(29f, Typeface.NORMAL, 0xFFE0E0E4.toInt(), Typeface.MONOSPACE)
            Kind.QUOTE -> Quadruple(32f, Typeface.NORMAL, 0xFFDDDEE3.toInt(), Typeface.DEFAULT)
            else -> Quadruple(34f, Typeface.NORMAL, FOREGROUND, Typeface.DEFAULT)
        }
        val textPaint = paint(size, style, family).apply { this.color = color }
        val layoutWidth = if (block.kind == Kind.BULLET || block.kind == Kind.QUOTE) width - 28 else width
        return layout(block.text, textPaint, layoutWidth)
    }

    private data class Quadruple(val first: Float, val second: Int, val third: Int, val fourth: Typeface)

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout = StaticLayout.Builder
        .obtain(text.ifEmpty { " " }, 0, text.ifEmpty { " " }.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setLineSpacing(7f, 1.06f)
        .build()

    private fun paint(size: Float, style: Int, family: Typeface = Typeface.DEFAULT): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size
            typeface = Typeface.create(family, style)
            color = FOREGROUND
        }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
}
