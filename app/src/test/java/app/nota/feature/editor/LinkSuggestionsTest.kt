package app.nota.feature.editor

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 6 (Editor Refinement #3): the [[ trigger derivation
 * (unchanged semantics from the chip-strip era, generalised to
 * any edited rich state) and the caret-popup positioning math.
 */
class LinkSuggestionsTest {

    // ------------------------------------------------ trigger

    @Test
    fun triggerAt_openTriggerWithQuery() {
        val trigger = LinkSuggestions.triggerAt("see [[Tar", 9)
        assertEquals(4, trigger?.start)
        assertEquals("Tar", trigger?.query)
    }

    @Test
    fun triggerAt_emptyQueryRightAfterBrackets() {
        val trigger = LinkSuggestions.triggerAt("[[", 2)
        assertEquals(0, trigger?.start)
        assertEquals("", trigger?.query)
    }

    @Test
    fun triggerAt_closedTriggerIsNotActive() {
        assertNull(LinkSuggestions.triggerAt("[[Done]] tail", 13))
        // A second, still-open trigger after a closed one IS the
        // active one.
        val trigger = LinkSuggestions.triggerAt("[[Done]] and [[", 15)
        assertEquals(13, trigger?.start)
        assertEquals("", trigger?.query)
    }

    @Test
    fun triggerAt_bracketOrNewlineInQueryKillsIt() {
        assertNull(LinkSuggestions.triggerAt("[[a]b", 5))
        assertNull(LinkSuggestions.triggerAt("[[a\nb", 5))
        assertNull(LinkSuggestions.triggerAt("plain text", 5))
        assertNull(LinkSuggestions.triggerAt("[[x", 0))
    }

    @Test
    fun triggerAt_usesTextBeforeCaretOnly() {
        // The caret sits mid-trigger: the query is what precedes it.
        val trigger = LinkSuggestions.triggerAt("[[Target", 5)
        assertEquals(0, trigger?.start)
        assertEquals("Tar", trigger?.query)
    }

    // ------------------------------------------------ positioning

    private val window = IntSize(1080, 2400)

    @Test
    fun popupOffset_belowCaretWhenItFits() {
        val anchor = IntRect(100, 500, 102, 540)
        val offset = linkPopupOffset(
            anchor, window, IntSize(600, 300), marginPx = 48, gapPx = 12,
        )
        assertEquals(IntOffset(100, 552), offset)
    }

    @Test
    fun popupOffset_flipsAboveWhenBelowDoesNotFit() {
        val anchor = IntRect(100, 2200, 102, 2240)
        val offset = linkPopupOffset(
            anchor, window, IntSize(600, 300), marginPx = 48, gapPx = 12,
        )
        // Above: caret top (2200) - gap (12) - popup height (300).
        assertEquals(IntOffset(100, 1888), offset)
    }

    @Test
    fun popupOffset_xClampedToMargins() {
        val nearRight = IntRect(1000, 500, 1002, 540)
        val offset = linkPopupOffset(
            nearRight, window, IntSize(600, 300), marginPx = 48, gapPx = 12,
        )
        // 1080 - 48 - 600 = 432.
        assertEquals(432, offset.x)
        val nearLeft = IntRect(4, 500, 6, 540)
        val leftOffset = linkPopupOffset(
            nearLeft, window, IntSize(600, 300), marginPx = 48, gapPx = 12,
        )
        assertEquals(48, leftOffset.x)
    }

    @Test
    fun popupOffset_flipClampsToTopMargin() {
        // Short window: neither below nor above truly fits — the
        // flip still clamps to the top margin rather than going
        // negative.
        val shortWindow = IntSize(1080, 400)
        val anchor = IntRect(100, 200, 102, 240)
        val offset = linkPopupOffset(
            anchor, shortWindow, IntSize(600, 300), marginPx = 48, gapPx = 12,
        )
        assertEquals(48, offset.y)
    }
}
