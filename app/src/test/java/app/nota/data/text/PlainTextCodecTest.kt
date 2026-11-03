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
