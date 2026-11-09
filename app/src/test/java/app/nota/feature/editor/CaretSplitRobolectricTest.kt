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

    /** Visible text of an HTML half. The library serialises a
     *  range-leading space as the numeric entity `&#32;` (to preserve
     *  it), which PlainTextCodec does not decode — normalise it here
     *  so assertions judge the split, not the entity dialect. */
    private fun text(html: String): String =
        PlainTextCodec.htmlToText(html).replace("&#32;", " ").trim()

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
        // List items carry placeholder characters in the annotated
        // string (markers/spans), so locate the caret from the text
        // itself instead of assuming an index.
        val caret = state.annotatedString.text.indexOf("Two")
        assertTrue("fixture text has no second item", caret > 0)
        state.selection = TextRange(caret)
        val split = splitHtmlAtCaret(state)
        assertTrue("left half lost its list: ${split.leftHtml}", split.leftHtml.contains("<li>"))
        assertTrue("right half lost its list: ${split.rightHtml}", split.rightHtml.contains("<li>"))
        assertEquals("One", text(split.leftHtml).trim('•').trim())
        assertEquals("Two", text(split.rightHtml).trim('•').trim())
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

    // ---------------- Editor Refinement #2: the caret's paragraph ----

    @Test
    fun caretParagraph_middleParagraphOfThree() {
        val state = RichTextState()
        state.setHtml("<p>First</p><p>Second</p><p>Third</p>")
        // Paragraphs are separated by a single character in the raw
        // text: "Second" starts at 6, so 8 is inside it.
        state.selection = TextRange(8)
        val paragraph = caretParagraph(state)
        assertEquals("<p>Second</p>", paragraph?.html)
        val parts = paragraphPartsAtCaret(state)
        assertEquals("<p>First</p>", parts?.beforeHtml)
        assertEquals("<p>Second</p>", parts?.paragraphHtml)
        assertEquals("<p>Third</p>", parts?.afterHtml)
    }

    @Test
    fun caretParagraph_atParagraphEnd_staysInThatParagraph() {
        val state = RichTextState()
        state.setHtml("<p>First</p><p>Second</p>")
        state.selection = TextRange(5) // the very end of "First"
        assertEquals("<p>First</p>", caretParagraph(state)?.html)
        // One further — the start of "Second" — belongs to Second.
        state.selection = TextRange(6)
        assertEquals("<p>Second</p>", caretParagraph(state)?.html)
    }

    @Test
    fun caretParagraph_firstAndLast() {
        val state = RichTextState()
        state.setHtml("<p>First</p><p>Second</p>")
        state.selection = TextRange(0)
        var parts = paragraphPartsAtCaret(state)
        assertEquals("", parts?.beforeHtml)
        assertEquals("<p>First</p>", parts?.paragraphHtml)
        assertEquals("<p>Second</p>", parts?.afterHtml)
        state.selection = TextRange(state.annotatedString.text.length)
        parts = paragraphPartsAtCaret(state)
        assertEquals("<p>First</p>", parts?.beforeHtml)
        assertEquals("<p>Second</p>", parts?.paragraphHtml)
        assertEquals("", parts?.afterHtml)
    }

    @Test
    fun caretParagraph_headingAndListItem_areNotPlainParagraphs() {
        val state = RichTextState()
        state.setHtml("<h1>Title</h1><ul><li>One</li><li>Two</li></ul><p>Body</p>")
        state.selection = TextRange(2)
        val heading = caretParagraph(state)?.html ?: ""
        assertTrue(
            "heading paragraph serialised unexpectedly: $heading",
            heading.startsWith("<h1"),
        )
        assertFalse(app.nota.data.html.NoteDocument.isPlainParagraphHtml(heading))
        // Inside the second list item (located from the text, like
        // the list split test — items carry marker characters).
        val caret = state.annotatedString.text.indexOf("Two")
        assertTrue(caret > 0)
        state.selection = TextRange(caret + 1)
        val item = caretParagraph(state)?.html ?: ""
        assertTrue(
            "list item paragraph serialised unexpectedly: $item",
            item.contains("<li"),
        )
        assertFalse(app.nota.data.html.NoteDocument.isPlainParagraphHtml(item))
        // And the plain trailing paragraph still qualifies.
        val bodyCaret = state.annotatedString.text.indexOf("Body")
        state.selection = TextRange(bodyCaret + 1)
        val body = caretParagraph(state)?.html ?: ""
        assertEquals("<p>Body</p>", body)
        assertTrue(app.nota.data.html.NoteDocument.isPlainParagraphHtml(body))
    }

    @Test
    fun caretParagraph_emptyState_hasNone() {
        val state = RichTextState()
        assertEquals(null, caretParagraph(state))
        assertEquals(null, paragraphPartsAtCaret(state))
    }
}
