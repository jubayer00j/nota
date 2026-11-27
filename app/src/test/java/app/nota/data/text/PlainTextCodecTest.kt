package app.nota.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainTextCodecTest {
    @Test
    fun roundTripParagraphsAndLineBreaks() {
        val text = "First line\nsecond line\n\nNew paragraph"
        assertEquals(text, PlainTextCodec.htmlToText(PlainTextCodec.textToHtml(text)))
    }

    @Test
    fun roundTripSingleParagraph() {
        val text = "Just one line"
        assertEquals("<p>Just one line</p>", PlainTextCodec.textToHtml(text))
        assertEquals(text, PlainTextCodec.htmlToText(PlainTextCodec.textToHtml(text)))
    }

    @Test
    fun escapingOfAngleBracketAndAmpersand() {
        val text = "a < b & c > d"
        val html = PlainTextCodec.textToHtml(text)
        assertTrue(html.contains("&lt;"))
        assertTrue(html.contains("&amp;"))
        assertTrue(html.contains("&gt;"))
        assertEquals(text, PlainTextCodec.htmlToText(html))
    }

    @Test
    fun blankHandling() {
        assertEquals("", PlainTextCodec.textToHtml(""))
        assertEquals("", PlainTextCodec.htmlToText(""))
    }

    @Test
    fun cellTextSeedPreservesBoundarySpaces() {
        // Phase 3.8 (Codex finding 3): the field seed must NOT trim —
        // the field text is the write-back source for the next edit,
        // so a trimming seed silently deletes boundary spaces then.
        assertEquals(" foo ", PlainTextCodec.htmlToCellText(" foo "))
        assertEquals(" foo ", PlainTextCodec.htmlToCellText("<p> foo </p>"))
        assertEquals(" x ", PlainTextCodec.htmlToCellText("<b> x </b>"))
        assertEquals("a & b ", PlainTextCodec.htmlToCellText("a &amp; b "))
        assertEquals(" ", PlainTextCodec.htmlToCellText(" "))
        assertEquals("", PlainTextCodec.htmlToCellText(""))
        assertEquals("CellA", PlainTextCodec.htmlToCellText("CellA"))
    }

    @Test
    fun blankLineHeavyTextConvertsToTinyNote() {
        // Codex R47-F1: millions of blank-line separators used to
        // materialise one list entry per paragraph before the filter
        // ran. The range-based conversion must produce the tiny note
        // without ever building that list.
        val text = "\n\n".repeat(300_000) + "hello"
        assertEquals("<p>hello</p>", PlainTextCodec.textToHtml(text))
    }

    @Test
    fun singleHugeParagraphWithManySingleNewlines() {
        // Same family, inner split: a paragraph of many single newlines
        // must not materialise a per-line list either.
        val text = "word\n".repeat(50_000) + "end"
        val html = PlainTextCodec.textToHtml(text)
        assertTrue(html.startsWith("<p>word<br>word<br>word"))
        assertTrue(html.endsWith("end</p>"))
        assertEquals(50_000, html.split("<br>").size - 1)
    }

    @Test
    fun paragraphSplittingMatchesExactOutput() {
        // Pins the exact output shape the range-based conversion must
        // preserve: <br> for single newlines, trimmed paragraphs,
        // CRLF / lone CR normalisation, separator whitespace, escaping.
        assertEquals(
            "<p>First line<br>second line</p><p>New paragraph</p>",
            PlainTextCodec.textToHtml("First line\nsecond line\n\nNew paragraph"),
        )
        assertEquals(
            "<p>a<br>  x</p><p>b</p>",
            PlainTextCodec.textToHtml("a\n  x\n\nb"),
        )
        assertEquals("<p>a</p><p>b</p>", PlainTextCodec.textToHtml("a\r\n\r\nb"))
        assertEquals("<p>a<br>b</p>", PlainTextCodec.textToHtml("a\rb"))
        assertEquals("<p>a</p><p>b</p>", PlainTextCodec.textToHtml("a\n \n b"))
        assertEquals("<p>a</p><p>b</p>", PlainTextCodec.textToHtml("a\n\n   \n\nb"))
        assertEquals("<p>padded</p><p>next</p>", PlainTextCodec.textToHtml("  padded  \n\n  next  "))
        assertEquals("<p>a</p>", PlainTextCodec.textToHtml("a\n\n"))
        assertEquals("<p>a</p>", PlainTextCodec.textToHtml("\n\na"))
        assertEquals("<p>a</p><p>b</p>", PlainTextCodec.textToHtml("a\n\n\nb"))
        assertEquals("<p>a</p><p>b</p>", PlainTextCodec.textToHtml("a\n\t\nb"))
        assertEquals(
            "<p>a &amp; b &lt; c &gt; d &quot;e&quot;</p>",
            PlainTextCodec.textToHtml("a & b < c > d \"e\""),
        )
    }

    @Test
    fun blockBoundariesBecomeLineBreaksNotFusedWords() {
        // Rich-editor shapes must not fuse adjacent blocks into single
        // words in the plain-text projection (snippets + FTS).
        val html = "<h1>Shopping</h1><ul><li>milk</li><li>eggs</li></ul>" +
            "<blockquote><p>note</p></blockquote>" +
            "<table><tbody><tr><td>Physics</td><td>133</td></tr></tbody></table>"
        val text = PlainTextCodec.htmlToText(html)
        assertTrue(text.contains("Shopping\n"))
        assertTrue(text.contains("milk\n"))
        assertTrue(text.contains("eggs"))
        assertTrue(text.contains("Physics 133"))
        assertTrue(!text.contains("Shoppingmilk"))
        assertTrue(!text.contains("milkeggs"))
    }
}
