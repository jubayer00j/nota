package app.nota.feature.editor

import androidx.compose.ui.text.TextRange
import app.nota.data.text.PlainTextCodec
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3.5 (D4): the caret split behind insert-at-caret. The halves
 * come from the library's own range serialisation, so marks and
 * list structure must survive on their respective sides, and a
 * selection is deleted before the split (insert-over-selection).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaretSplitRobolectricTest {

    private fun stateWith(html: String, caret: Int): RichTextState {
        val state = RichTextState()
        state.setHtml(html)
        state.selection = TextRange(caret)
        return state
    }

    private fun text(html: String): String = PlainTextCodec.htmlToText(html).trim()

    @Test
    fun caretMidParagraph_splitsText() {
        val state = stateWith("<p>Alpha bravo</p>", caret = 3)
        val split = splitHtmlAtCaret(state)
        assertEquals("Alp", text(split.leftHtml))
        assertEquals("ha bravo", text(split.rightHtml))
    }

    @Test
    fun caretAtParagraphBoundary_keepsParagraphsWhole() {
        val state = RichTextState()
        state.setHtml("<p>First</p><p>Second</p>")
        // annotatedString joins paragraphs with a newline: the caret
        // at the start of "Second" is index 6.
        state.selection = TextRange(6)
        val split = splitHtmlAtCaret(state)
        assertEquals("First", text(split.leftHtml))
        assertEquals("Second", text(split.rightHtml))
    }

    @Test
    fun selection_isDeletedBeforeTheSplit() {
        val state = RichTextState()
        state.setHtml("<p>Alpha bravo charlie</p>")
        state.selection = TextRange(6, 11) // "bravo"
        val split = splitHtmlAtCaret(state)
        assertFalse(text(split.leftHtml).contains("bravo"))
        assertFalse(text(split.rightHtml).contains("bravo"))
        assertTrue(text(split.leftHtml).contains("Alpha"))
        assertTrue(text(split.rightHtml).contains("charlie"))
    }

    @Test
    fun inlineFormatting_staysOnItsSide() {
        val state = RichTextState()
        state.setHtml("<p>Alpha <strong>bravo</strong> charlie</p>")
        state.selection = TextRange(11) // right after "bravo"
        val split = splitHtmlAtCaret(state)
        assertEquals("Alpha bravo", text(split.leftHtml))
        assertEquals("charlie", text(split.rightHtml))
        assertTrue(
            "bold markup lost from the left half: ${split.leftHtml}",
            split.leftHtml.contains("<strong>") || split.leftHtml.contains("<b>"),
        )
    }

    @Test
    fun list_splitsIntoTwoLists() {
        val state = RichTextState()
        state.setHtml("<ul><li>One</li><li>Two</li></ul>")
        // "One\nTwo": the caret at the start of "Two" is index 4.
        state.selection = TextRange(4)
        val split = splitHtmlAtCaret(state)
        assertTrue("left half lost its list: ${split.leftHtml}", split.leftHtml.contains("<li>"))
        assertTrue("right half lost its list: ${split.rightHtml}", split.rightHtml.contains("<li>"))
        assertEquals("One", text(split.leftHtml))
        assertEquals("Two", text(split.rightHtml))
    }

    @Test
    fun caretAtStart_everythingGoesRight() {
        val state = stateWith("<p>Alpha</p>", caret = 0)
        val split = splitHtmlAtCaret(state)
        assertEquals("", text(split.leftHtml))
        assertEquals("Alpha", text(split.rightHtml))
    }

    @Test
    fun caretAtEnd_everythingGoesLeft() {
        val state = stateWith("<p>Alpha</p>", caret = 5)
        val split = splitHtmlAtCaret(state)
        assertEquals("Alpha", text(split.leftHtml))
        assertEquals("", text(split.rightHtml))
    }
}
