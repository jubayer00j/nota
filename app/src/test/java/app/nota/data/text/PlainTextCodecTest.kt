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
}
