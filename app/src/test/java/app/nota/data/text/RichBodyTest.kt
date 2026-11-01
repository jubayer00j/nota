package app.nota.data.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichBodyTest {
    @Test
    fun plainParagraphHtmlIsNotRich() {
        assertFalse(RichBody.isRich("<p>hello world</p>"))
        assertFalse(RichBody.isRich("<p>one</p><p>two<br>lines</p>"))
        assertFalse(RichBody.isRich(""))
    }

    @Test
    fun blockStructuresAreRich() {
        assertTrue(RichBody.isRich("<ul><li>milk</li></ul>"))
        assertTrue(RichBody.isRich("<h2>Head</h2><p>x</p>"))
        assertTrue(RichBody.isRich("<table><tbody><tr><td>1</td></tr></tbody></table>"))
        assertTrue(RichBody.isRich("<p><img src=\"a.png\" alt=\"a\"></p>"))
        assertTrue(RichBody.isRich("<pre><code>x</code></pre>"))
        assertTrue(RichBody.isRich("<ul data-type=\"taskList\"><li data-checked=\"true\">x</li></ul>"))
    }

    @Test
    fun inlineMarkupIsRich() {
        // Inline-only bodies: the plain codec strips these tags (and, for
        // links, the href), so they get the same title-only protection.
        assertTrue(RichBody.isRich("<p>hello <strong>world</strong></p>"))
        assertTrue(RichBody.isRich("<p><em>italic</em> and <u>underlined</u></p>"))
        assertTrue(RichBody.isRich("<p>See <a href=\"https://example.com\">the reference</a></p>"))
        assertTrue(RichBody.isRich("<p><mark>highlighted</mark> <code>code</code></p>"))
        assertTrue(RichBody.isRich("<p><span style=\"color: #c0392b\">red words</span></p>"))
        // Bare inline markup with no wrapping block tag at all.
        assertTrue(RichBody.isRich("hello <strong>world</strong>"))
        assertTrue(RichBody.isRich("see <a href=\"https://example.com\">this</a>"))
    }
}
