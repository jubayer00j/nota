package app.nota.data.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichBodyTest {
    @Test
    fun plainParagraphHtmlIsNotRich() {
        assertFalse(RichBody.isRich("<p>hello <strong>world</strong></p>"))
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
}
