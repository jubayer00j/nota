package app.nota.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
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
}
