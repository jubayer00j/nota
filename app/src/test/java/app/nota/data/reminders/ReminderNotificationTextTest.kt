package app.nota.data.reminders

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Phase 5.1: the notification privacy rules ported from 1.x — an
 * unlocked note shows its title + "Reminder · <time>"; a locked note
 * (or an unreadable one) yields the generic pair ONLY, with the
 * note's title appearing nowhere.
 */
class ReminderNotificationTextTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val locale: Locale = Locale.US

    // Fri 6 Nov 2026, 14:30 UTC.
    private val triggerAt = 1793975400000L

    @Test
    fun unlockedNoteShowsTitleAndFormattedTime() {
        val (title, text) = ReminderNotificationText.snapshot(
            noteTitle = "Dentist appointment",
            noteLocked = false,
            triggerAtMillis = triggerAt,
            zone = zone,
            locale = locale,
        )
        assertEquals("Dentist appointment", title)
        assertEquals("Reminder · Fri, 6 Nov, 2:30 PM", text)
    }

    @Test
    fun lockedNoteGetsGenericPairWithNoTitleAnywhere() {
        val secretTitle = "Therapy session notes"
        val (title, text) = ReminderNotificationText.snapshot(
            noteTitle = secretTitle,
            noteLocked = true,
            triggerAtMillis = triggerAt,
            zone = zone,
            locale = locale,
        )
        assertEquals(ReminderNotificationText.GENERIC_TITLE, title)
        assertEquals(ReminderNotificationText.GENERIC_TEXT, text)
        assertFalse(title.contains(secretTitle))
        assertFalse(text.contains(secretTitle))
        assertFalse(text.contains("Therapy"))
    }

    @Test
    fun missingNoteGetsGenericPair() {
        val (title, text) = ReminderNotificationText.snapshot(
            noteTitle = null,
            noteLocked = false,
            triggerAtMillis = triggerAt,
            zone = zone,
            locale = locale,
        )
        assertEquals("Reminder", title)
        assertEquals("You have a note reminder", text)
    }

    @Test
    fun blankTitleFallsBackToUntitled() {
        val (title, _) = ReminderNotificationText.snapshot(
            noteTitle = "   ",
            noteLocked = false,
            triggerAtMillis = triggerAt,
            zone = zone,
            locale = locale,
        )
        assertEquals("Untitled note", title)
    }

    @Test
    fun titleIsTrimmedLikeOneX() {
        val (title, _) = ReminderNotificationText.snapshot(
            noteTitle = "  Groceries  ",
            noteLocked = false,
            triggerAtMillis = triggerAt,
            zone = zone,
            locale = locale,
        )
        assertEquals("Groceries", title)
    }

    @Test
    fun listDateTimeFormatMatchesOneXShape() {
        assertEquals(
            "6 November 2026 at 2:30 PM",
            ReminderNotificationText.formatListDateTime(triggerAt, zone, locale),
        )
    }
}
