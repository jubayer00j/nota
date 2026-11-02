package app.nota.feature.editor

import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.8 (E1) — tap-anywhere caret placement: the row math and the
 * in-state growth, exercised against the real rich editor state the
 * same way the spike round-trip tests do.
 *
 * Dialect note (probe-verified against the shipped library build):
 * this editor joins paragraphs with a single SPACE in its raw text —
 * there are no '\n' separators — and serialises trailing empty
 * paragraphs as bare `<br>` markers in HTML. The fixtures below use
 * single words per paragraph, so splitting the raw text on the
 * separator recovers the paragraphs exactly.
 */
@OptIn(ExperimentalRichTextApi::class)
class TapBelowContentTest {

    private fun stateWith(html: String) = RichTextState().apply { setHtml(html) }

    private fun paragraphsOf(state: RichTextState): List<String> =
        state.annotatedString.text.split(' ')

    @Test
    fun rowMathBands() {
        // 28px line height, 8px gap above the tap area: the first
        // (28 - 8) px of the area are row 0 (end of text); each full
        // line below that is another row.
        assertEquals(0, tapRowsBelowContent(0f, 8f, 28f))
        assertEquals(0, tapRowsBelowContent(19f, 8f, 28f))
        assertEquals(1, tapRowsBelowContent(20f, 8f, 28f))
        assertEquals(2, tapRowsBelowContent(48f, 8f, 28f))
        assertEquals(3, tapRowsBelowContent(76f, 8f, 28f))
        // Degenerate inputs never grow.
        assertEquals(0, tapRowsBelowContent(-5f, 8f, 28f))
        assertEquals(0, tapRowsBelowContent(100f, 8f, 0f))
    }

    @Test
    fun rowZeroIsEndOfTextWithoutGrowth() {
        val state = stateWith("<p>Hello</p>")
        val before = state.annotatedString.text
        assertTrue(state.placeCaretBelowContent(0))
        assertEquals(before, state.annotatedString.text)
        assertEquals(before.length, state.selection.start)
        assertEquals(before.length, state.selection.end)
    }

    @Test
    fun growsParagraphsAndLandsCaretInTheLast() {
        val state = stateWith("<p>Hello</p>")
        assertTrue(state.placeCaretBelowContent(3))
        assertEquals(listOf("Hello", "", "", ""), paragraphsOf(state))
        // Caret: collapsed, at the very end — the start of the last
        // (new, empty) paragraph.
        assertEquals(state.annotatedString.text.length, state.selection.start)
        assertEquals(state.selection.start, state.selection.end)
        // The growth is real paragraphs in the HTML (the editor's
        // trailing-empty dialect: bare <br> markers), not a visual trick.
        assertEquals("<p>Hello</p><br><br>", state.toHtml())
    }

    @Test
    fun typingIntoTheGrownParagraphKeepsTheSpacing() {
        val state = stateWith("<p>Hello</p>")
        assertTrue(state.placeCaretBelowContent(3))
        state.addTextAfterSelection("Deep")
        assertEquals(listOf("Hello", "", "", "Deep"), paragraphsOf(state))
        val html = state.toHtml()
        assertEquals("<p>Hello</p><br><br><p>Deep</p>", html)
        // Save/load is a fixed point: the spacing above the typed
        // text is real document spacing and survives the round trip.
        val reloaded = stateWith(html)
        assertEquals(state.annotatedString.text, reloaded.annotatedString.text)
        assertEquals(html, reloaded.toHtml())
    }

    @Test
    fun blankDocumentGrowsFromItsSingleEmptyLine() {
        val state = RichTextState()
        assertTrue(state.placeCaretBelowContent(3))
        val paragraphs = paragraphsOf(state)
        assertEquals(4, paragraphs.size)
        assertTrue(paragraphs.all { it.isEmpty() })
        assertEquals(state.annotatedString.text.length, state.selection.start)
    }

    @Test
    fun growthIsCapped() {
        val state = stateWith("<p>Hello</p>")
        assertTrue(state.placeCaretBelowContent(MAX_TAP_GROW_PARAGRAPHS + 40))
        assertEquals(MAX_TAP_GROW_PARAGRAPHS, paragraphsOf(state).size - 1)
    }

    @Test
    fun listEndedDocumentRefusesWithoutMutating() {
        val state = stateWith("<ul><li>one</li><li>two</li></ul>")
        val textBefore = state.annotatedString.text
        val htmlBefore = state.toHtml()
        assertFalse(state.placeCaretBelowContent(2))
        // No phantom list items, no paragraphs: the caller grows a
        // separate padding segment for this shape instead.
        assertEquals(textBefore, state.annotatedString.text)
        assertEquals(htmlBefore, state.toHtml())
    }

    @Test
    fun headingEndedDocumentGrowsBelowTheHeading() {
        val state = stateWith("<h1>Title</h1>")
        assertTrue(state.placeCaretBelowContent(1))
        assertEquals(listOf("Title", ""), paragraphsOf(state))
        assertEquals(state.annotatedString.text.length, state.selection.start)
        assertEquals("<h1>Title</h1><br>", state.toHtml())
        // The grown paragraph is a normal paragraph (the library
        // exits the heading on Enter, like typing would).
        assertEquals(HeadingStyle.Normal, state.currentHeadingStyle)
    }

    @Test
    fun paddingSegmentShapeAndCap() {
        // One empty paragraph per requested row: the caret lands in
        // the segment's last paragraph, the same row the in-state
        // growth would have produced below the list.
        assertEquals("<p></p><p></p>", paddingSegmentHtml(2))
        assertEquals("<p></p>", paddingSegmentHtml(1))
        assertEquals("<p></p>", paddingSegmentHtml(0))
        val capped = paddingSegmentHtml(MAX_TAP_GROW_PARAGRAPHS + 40)
        assertEquals(
            MAX_TAP_GROW_PARAGRAPHS,
            Regex("<p></p>").findAll(capped).count(),
        )
    }
}
