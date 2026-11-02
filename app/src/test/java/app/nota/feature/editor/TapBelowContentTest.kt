package app.nota.feature.editor

import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.8 (E1) — tap-anywhere caret placement: the row math and the
 * in-state growth, exercised against the real rich editor state the
 * same way the spike round-trip tests do.
 */
@OptIn(ExperimentalRichTextApi::class)
class TapBelowContentTest {

    private fun stateWith(html: String) = RichTextState().apply { setHtml(html) }

    private fun linesOf(state: RichTextState): List<String> =
        state.annotatedString.text.split('\n')

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
        val lines = linesOf(state)
        assertEquals(4, lines.size)
        assertEquals("Hello", lines[0])
        assertTrue(lines.drop(1).all { it.isEmpty() })
        // Caret: collapsed, at the start of the last (new) paragraph.
        assertEquals(state.annotatedString.text.length, state.selection.start)
        assertEquals(state.selection.start, state.selection.end)
        // The growth is real paragraphs in the HTML, not a visual trick.
        assertEquals(3, Regex("<p></p>").findAll(state.toHtml()).count())
    }

    @Test
    fun blankDocumentGrowsFromItsSingleEmptyLine() {
        val state = RichTextState()
        assertTrue(state.placeCaretBelowContent(3))
        val lines = linesOf(state)
        assertEquals(4, lines.size)
        assertTrue(lines.all { it.isEmpty() })
        assertEquals(state.annotatedString.text.length, state.selection.start)
    }

    @Test
    fun growthIsCapped() {
        val state = stateWith("<p>Hello</p>")
        assertTrue(state.placeCaretBelowContent(MAX_TAP_GROW_PARAGRAPHS + 40))
        assertEquals(MAX_TAP_GROW_PARAGRAPHS, linesOf(state).size - 1)
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
        assertEquals(2, linesOf(state).size)
        assertEquals(state.annotatedString.text.length, state.selection.start)
        assertTrue(state.toHtml().contains("<h1"))
    }

    @Test
    fun paddingSegmentHtmlShapeAndCap() {
        assertEquals("<p></p><p></p>", paddingSegmentHtml(2))
        assertEquals(
            MAX_TAP_GROW_PARAGRAPHS,
            Regex("<p></p>").findAll(paddingSegmentHtml(MAX_TAP_GROW_PARAGRAPHS + 10)).count(),
        )
    }
}
