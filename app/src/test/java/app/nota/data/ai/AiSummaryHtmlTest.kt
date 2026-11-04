package app.nota.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Summary → HTML conversion (Phase 4.1): the model's fixed
 * plain-text format becomes paragraphs + a bullet list, and —
 * the security point — model output is escaped, never trusted
 * as markup.
 */
class AiSummaryHtmlTest {

    @Test
    fun paragraphsAndBullets() {
        val html = AiSummaryHtml.toHtml(
            "The note plans a trip to the hills.\n\n- Book the bus\n- Pack light",
        )
        assertEquals(
            "<p>The note plans a trip to the hills.</p><ul><li>Book the bus</li><li>Pack light</li></ul>",
            html,
        )
    }

    @Test
    fun modelMarkupIsEscapedNeverInjected() {
        val html = AiSummaryHtml.toHtml("Look: <script>alert(1)</script> & \"quotes\"")
        assertFalse(html.contains("<script>"))
        assertEquals(
            "<p>Look: &lt;script&gt;alert(1)&lt;/script&gt; &amp; \"quotes\"</p>",
            html,
        )
    }

    @Test
    fun blankInputProducesEmptyHtml() {
        assertEquals("", AiSummaryHtml.toHtml("  \n\n  "))
    }
}
