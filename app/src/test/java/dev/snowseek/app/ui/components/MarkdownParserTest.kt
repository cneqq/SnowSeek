package dev.snowseek.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class MarkdownParserTest {
    @Test fun fencedCodeKeepsMarkupAndBlankLinesLiteral() {
        val blocks = parseMarkdown("# Heading\n\n```kotlin\nval text = \"**literal**\"\n\nprintln(text)\n```\n\nAfter")
        assertEquals(MarkdownBlock.Heading("Heading", 1), blocks[0])
        assertEquals(MarkdownBlock.Code("kotlin", "val text = \"**literal**\"\n\nprintln(text)"), blocks[1])
        assertEquals(MarkdownBlock.Paragraph("After"), blocks[2])
    }

    @Test fun unfinishedStreamingFenceIsStillCode() {
        assertEquals(listOf(MarkdownBlock.Code("python", "print(\"hello\")")), parseMarkdown("```python\nprint(\"hello\")"))
    }

    @Test fun paragraphsListsAndQuotesRemainReadable() {
        assertEquals(listOf(
            MarkdownBlock.Paragraph("First line\nsecond line"),
            MarkdownBlock.Bullet("One"),
            MarkdownBlock.Bullet("Two"),
            MarkdownBlock.Quote("Quoted"),
        ), parseMarkdown("First line\r\nsecond line\r\n\r\n- One\r\n2. Two\r\n> Quoted"))
    }

    @Test fun emptyInputHasNoPhantomParagraph() { assertEquals(emptyList<MarkdownBlock>(), parseMarkdown("\n\n")) }
}
