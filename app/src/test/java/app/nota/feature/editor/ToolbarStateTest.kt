package app.nota.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import app.nota.data.html.NoteDocument
import app.nota.data.html.TextSegment
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.8 (E2) — the toolbar state mapping: every flag must follow
 * the editor state's own semantics as the caret/selection moves,
 * with no clicking involved. These tests pin the mapping against
 * the real rich editor state.
 */
@OptIn(ExperimentalRichTextApi::class)
class ToolbarStateTest {

    private fun stateWith(html: String) = RichTextState().apply { setHtml(html) }

    private fun RichTextState.caretAt(index: Int) {
        selection = TextRange(index)
    }

    private fun RichTextState.select(range: IntRange) {
        selection = TextRange(range.first, range.last + 1)
    }

    @Test
    fun caretInBoldLightsBoldAndMovingOutUnlightsIt() {
        val state = stateWith("<p>plain <b>bold</b> tail</p>")
        // text = "plain bold tail"
        state.caretAt(1)
        assertFalse(state.toolbarFormatState().bold)
        state.caretAt(8) // inside "bold"
        assertTrue(state.toolbarFormatState().bold)
        state.caretAt(14) // inside "tail"
        assertFalse(state.toolbarFormatState().bold)
    }

    @Test
    fun fullyBoldSelectionIsBoldButMixedSelectionIsNot() {
        val state = stateWith("<p><b>bold</b> plain</p>")
        state.select(0..3)
        assertTrue(state.toolbarFormatState().bold)
        state.select(0..9) // "bold plain" — bold does not cover it all
        assertFalse(state.toolbarFormatState().bold)
    }

    @Test
    fun italicUnderlineStrikethroughFollowTheCaret() {
        val state = stateWith("<p><i>it</i> <u>un</u> <s>st</s></p>")
        // text = "it un st"
        state.caretAt(1)
        var format = state.toolbarFormatState()
        assertTrue(format.italic)
        assertFalse(format.underline)
        assertFalse(format.strikethrough)
        state.caretAt(4)
        format = state.toolbarFormatState()
        assertTrue(format.underline)
        assertFalse(format.italic)
        state.caretAt(7)
        format = state.toolbarFormatState()
        assertTrue(format.strikethrough)
        assertFalse(format.underline)
    }

    @Test
    fun headingLevelFollowsTheParagraph() {
        val state = stateWith("<h2>Head</h2><p>body</p>")
        // text = "Head\nbody"
        state.caretAt(1)
        assertEquals(HeadingStyle.H2, state.toolbarFormatState().heading)
        state.caretAt(6)
        assertEquals(HeadingStyle.Normal, state.toolbarFormatState().heading)
    }

    @Test
    fun listFlagsFollowTheParagraph() {
        val bullets = stateWith("<ul><li>one</li><li>two</li></ul><p>after</p>")
        bullets.caretAt(1)
        var format = bullets.toolbarFormatState()
        assertTrue(format.unorderedList)
        assertFalse(format.orderedList)
        // Caret in the plain paragraph after the list: both off.
        bullets.caretAt(bullets.annotatedString.text.length)
        format = bullets.toolbarFormatState()
        assertFalse(format.unorderedList)
        assertFalse(format.orderedList)

        val numbered = stateWith("<ol><li>one</li></ol>")
        numbered.caretAt(1)
        format = numbered.toolbarFormatState()
        assertTrue(format.orderedList)
        assertFalse(format.unorderedList)
    }

    @Test
    fun textColorAndHighlightAreReadFromTheState() {
        val state = stateWith("<p>plain word</p>")
        state.select(6..9) // "word"
        state.toggleSpanStyle(SpanStyle(color = Color(0xFFC0392B)))
        state.toggleSpanStyle(SpanStyle(background = Color(0xFFFFEB3B)))
        state.caretAt(8) // inside "word"
        val format = state.toolbarFormatState()
        assertEquals(Color(0xFFC0392B), format.textColor)
        assertEquals(Color(0xFFFFEB3B), format.highlightColor)
        state.caretAt(1) // plain text: neither applies
        val plain = state.toolbarFormatState()
        assertNull(plain.textColor)
        assertNull(plain.highlightColor)
    }

    @Test
    fun codeAndLinkFollowTheRichSpan() {
        val state = stateWith("<p>run this now</p>")
        state.select(4..7) // "this"
        state.toggleCodeSpan()
        state.caretAt(5)
        assertTrue(state.toolbarFormatState().code)
        state.caretAt(10)
        assertFalse(state.toolbarFormatState().code)

        val linked = stateWith("<p>see the docs here</p>")
        linked.select(8..11) // "docs"
        linked.addLinkToSelection("https://example.com")
        linked.caretAt(9)
        assertTrue(linked.toolbarFormatState().link)
        linked.caretAt(1)
        assertFalse(linked.toolbarFormatState().link)
    }

    @Test
    fun oneXHexColourSpansParseToThePaletteColours() {
        // 1.x writes text colour as a hex span style (Tiptap
        // textStyle). Every palette hex, written the 1.x way, must
        // read back as exactly that colour — the shared format in
        // the 1.x -> Nota direction.
        for ((_, argb) in TEXT_COLOR_SWATCHES) {
            val hex = "#%06x".format(argb and 0xFFFFFF)
            val state = stateWith("<p><span style=\"color: $hex\">word</span> tail</p>")
            state.caretAt(2)
            assertEquals("hex $hex", Color(argb), state.toolbarFormatState().textColor)
        }
        for ((_, argb) in HIGHLIGHT_COLOR_SWATCHES) {
            val hex = "#%06x".format(argb and 0xFFFFFF)
            val state = stateWith("<p><span style=\"background: $hex\">word</span> tail</p>")
            state.caretAt(2)
            assertEquals("hex $hex", Color(argb), state.toolbarFormatState().highlightColor)
        }
    }

    @Test
    fun everyPaletteColourAppliesAndClears() {
        // The toolbar's gestures, pinned: toggle a palette colour
        // onto a selection -> the caret reports it, the exported
        // HTML carries it, and a NoteDocument round trip keeps it;
        // toggling the SAME colour again (the menus' clear path)
        // removes it.
        for ((_, argb) in TEXT_COLOR_SWATCHES) {
            val color = Color(argb)
            val state = stateWith("<p>plain word</p>")
            state.select(6..9) // "word"
            state.toggleSpanStyle(SpanStyle(color = color))
            state.caretAt(8)
            assertEquals(color, state.toolbarFormatState().textColor)
            val html = state.toHtml()
            val reparsed = NoteDocument.parse(html).single() as TextSegment
            val roundTripped = stateWith(NoteDocument.serialize(listOf(reparsed)))
            roundTripped.caretAt(8)
            assertEquals(color, roundTripped.toolbarFormatState().textColor)
            state.select(6..9)
            state.toggleSpanStyle(SpanStyle(color = color))
            state.caretAt(8)
            assertNull(state.toolbarFormatState().textColor)
        }
        for ((_, argb) in HIGHLIGHT_COLOR_SWATCHES) {
            val color = Color(argb)
            val state = stateWith("<p>plain word</p>")
            state.select(6..9)
            state.toggleSpanStyle(SpanStyle(background = color))
            state.caretAt(8)
            assertEquals(color, state.toolbarFormatState().highlightColor)
            val html = state.toHtml()
            val reparsed = NoteDocument.parse(html).single() as TextSegment
            val roundTripped = stateWith(NoteDocument.serialize(listOf(reparsed)))
            roundTripped.caretAt(8)
            assertEquals(color, roundTripped.toolbarFormatState().highlightColor)
            state.select(6..9)
            state.toggleSpanStyle(SpanStyle(background = color))
            state.caretAt(8)
            assertNull(state.toolbarFormatState().highlightColor)
        }
    }

    @Test
    fun linkUrlIsExposedForPrefillAndRemoveLinkUnwraps() {
        // Editor Refinement #1 (A10): the dialog's three moves
        // against the real state — linkUrl feeds the prefill,
        // updateLink rewrites in place, removeLink unwraps and
        // keeps the text.
        val state = stateWith("<p>see the docs here</p>")
        state.caretAt(1)
        assertNull(state.toolbarFormatState().linkUrl)
        state.select(8..11) // "docs"
        state.addLinkToSelection("https://example.com/docs")
        state.caretAt(9)
        assertTrue(state.toolbarFormatState().link)
        assertEquals("https://example.com/docs", state.toolbarFormatState().linkUrl)
        state.updateLink("https://example.com/new-docs")
        assertEquals("https://example.com/new-docs", state.toolbarFormatState().linkUrl)
        state.removeLink()
        assertFalse(state.toolbarFormatState().link)
        assertNull(state.toolbarFormatState().linkUrl)
        assertEquals("see the docs here", state.annotatedString.text)
        assertFalse(state.toHtml().contains("<a"))
    }

    @Test
    fun stagedStyleAtCollapsedCaretIsReflected() {
        // The toolbar's own gesture: tap Bold with a collapsed caret —
        // the staged style must light the button before any typing,
        // because that IS the state's current span style.
        val state = stateWith("<p>plain</p>")
        state.caretAt(2)
        assertFalse(state.toolbarFormatState().bold)
        state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        assertTrue(state.toolbarFormatState().bold)
    }

    @Test
    fun uniformColourSelectionIsNotMixed() {
        val state = stateWith("<p><span style=\"color: #b3261e\">all red here</span></p>")
        state.select(0..11)
        val format = state.toolbarFormatState()
        assertEquals(Color(0xFFB3261E), format.textColor)
        assertFalse(format.textColorMixed)
    }

    @Test
    fun mixedColourSelectionReadsAsMixedAndClearsAcrossTheWholeSelection() {
        // Codex PR #34 P2: two differently-coloured words under
        // one selection. The common style reports no colour, but
        // colour IS present — the state must say "mixed" (so the
        // menu withholds its misleading "Default" check), and the
        // clear must strip BOTH colours; the old common-colour
        // toggle path no-oped on exactly this selection.
        val state = stateWith(
            "<p><span style=\"color: #b3261e\">red</span> and " +
                "<span style=\"color: #1d4ed8\">blue</span></p>",
        )
        // text = "red and blue"
        state.select(0..11)
        var format = state.toolbarFormatState()
        assertNull(format.textColor)
        assertTrue(format.textColorMixed)
        state.clearTextColorInSelection()
        state.select(0..11)
        format = state.toolbarFormatState()
        assertNull(format.textColor)
        assertFalse(format.textColorMixed)
        assertFalse(state.toHtml().contains("color:"))
    }

    @Test
    fun mixedColourClearPreservesOtherFormatting() {
        val state = stateWith(
            "<p><span style=\"color: #b3261e\"><b>red</b></span> " +
                "<span style=\"color: #1d4ed8\">blue</span></p>",
        )
        // text = "red blue"
        state.select(0..7)
        state.clearTextColorInSelection()
        state.caretAt(1) // inside "red"
        val format = state.toolbarFormatState()
        assertNull(format.textColor)
        assertTrue(format.bold)
    }

    @Test
    fun mixedHighlightSelectionReadsAsMixedAndClearsAcrossTheWholeSelection() {
        val state = stateWith(
            "<p><span style=\"background: #fbe0e0\">rose</span> " +
                "<span style=\"background: #dde9fb\">sky</span></p>",
        )
        // text = "rose sky"
        state.select(0..7)
        var format = state.toolbarFormatState()
        assertNull(format.highlightColor)
        assertTrue(format.highlightMixed)
        state.clearHighlightInSelection()
        state.select(0..7)
        format = state.toolbarFormatState()
        assertNull(format.highlightColor)
        assertFalse(format.highlightMixed)
        assertFalse(state.toHtml().contains("background"))
    }
}
