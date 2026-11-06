package app.nota.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 5.2: pinned-shortcut labels. The privacy rule is the point:
 * a LOCKED note's title must not reach the launcher in either the
 * short or the long label slot.
 */
class NotePinLabelsTest {

    @Test
    fun lockedNote_genericInBothSlots_evenWithARealTitle() {
        val (short, long) = NotePinShortcut.labels("Bank passwords", locked = true)
        assertEquals(NotePinShortcut.GENERIC_LABEL, short)
        assertEquals(NotePinShortcut.GENERIC_LABEL, long)
    }

    @Test
    fun unlockedNote_titleInBothSlots() {
        val (short, long) = NotePinShortcut.labels("Shopping list", locked = false)
        assertEquals("Shopping list", short)
        assertEquals("Shopping list", long)
    }

    @Test
    fun unlockedNote_titleIsTrimmed() {
        val (short, _) = NotePinShortcut.labels("  Ideas  ", locked = false)
        assertEquals("Ideas", short)
    }

    @Test
    fun blankTitle_fallsBackToUntitledNote() {
        val (short, long) = NotePinShortcut.labels("   ", locked = false)
        assertEquals(NotePinShortcut.UNTITLED_LABEL, short)
        assertEquals(NotePinShortcut.UNTITLED_LABEL, long)
    }
}
