package app.nota.feature.widget

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5.3: the widget privacy policy, pinned as pure logic (the
 * WidgetStateMapper seam). The widget lives outside App Lock on the
 * home screen all day, so every masking rule gets a direct test:
 * App Lock hides EVERYTHING (even facts the caller supplied), a
 * locked note contributes only the generic label and never its id,
 * and a locked note's reminder never speaks its title.
 */
class WidgetStateMapperTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val locale: Locale = Locale.UK
    private val today: LocalDate = LocalDate.of(2026, 11, 6) // a Friday

    private fun map(
        appLockHidden: Boolean = false,
        todayNote: WidgetTodayFacts? = null,
        nextReminder: WidgetReminderFacts? = null,
    ): WidgetState = WidgetStateMapper.map(
        today = today,
        zone = zone,
        locale = locale,
        appLockHidden = appLockHidden,
        todayNote = todayNote,
        nextReminder = nextReminder,
    )

    private fun contentNote(
        title: String = "Friday plans",
        plainText: String = "Buy tickets\nand  pack\tthe bag",
        locked: Boolean = false,
    ) = WidgetTodayFacts(
        noteId = "note-1",
        title = title,
        plainText = plainText,
        locked = locked,
    )

    @Test
    fun dateLabelIsTheFullLocalDate() {
        assertEquals("Friday, 6 November", map().dateLabel)
    }

    @Test
    fun contentNoteShowsTitleCollapsedExcerptAndItsId() {
        val state = map(todayNote = contentNote())
        assertEquals(WidgetTodayKind.CONTENT, state.todayKind)
        assertEquals("Friday plans", state.todayTitle)
        assertEquals("Buy tickets and pack the bag", state.todayExcerpt)
        assertEquals("note-1", state.todayNoteId)
    }

    @Test
    fun blankTitleFallsBackToUntitledAndBlankBodyHasNoExcerpt() {
        val state = map(todayNote = contentNote(title = "  ", plainText = " \n "))
        assertEquals(WidgetStateMapper.UNTITLED, state.todayTitle)
        assertNull(state.todayExcerpt)
    }

    @Test
    fun longExcerptIsCappedWithAnEllipsis() {
        val long = "x".repeat(200)
        val excerpt = WidgetStateMapper.excerpt(long)
        assertEquals(WidgetStateMapper.MAX_EXCERPT_CHARS + 1, excerpt!!.length)
        assertTrue(excerpt.endsWith("…"))
    }

    @Test
    fun missingNoteIsTheFriendlyEmptyState() {
        val state = map(todayNote = null)
        assertEquals(WidgetTodayKind.EMPTY, state.todayKind)
        assertEquals(WidgetStateMapper.EMPTY_TITLE, state.todayTitle)
        assertEquals(WidgetStateMapper.EMPTY_EXCERPT, state.todayExcerpt)
        assertNull(state.todayNoteId)
    }

    @Test
    fun lockedNoteContributesOnlyTheGenericLabel() {
        val state = map(
            todayNote = contentNote(
                title = "Secret diary",
                plainText = "things nobody may read",
                locked = true,
            ),
        )
        assertEquals(WidgetTodayKind.LOCKED, state.todayKind)
        assertEquals(WidgetStateMapper.LOCKED_LABEL, state.todayTitle)
        assertNull(state.todayExcerpt)
        // The locked note's id never reaches the render: the Today
        // area's tap falls back to the door, never a note-open.
        assertNull(state.todayNoteId)
    }

    @Test
    fun appLockHidesEverythingEvenWhenFactsAreSupplied() {
        val state = map(
            appLockHidden = true,
            todayNote = contentNote(title = "Friday plans"),
            nextReminder = WidgetReminderFacts(
                triggerAt = 1_900_000_000_000L,
                noteTitle = "Dentist",
                noteLocked = false,
            ),
        )
        assertEquals(WidgetTodayKind.HIDDEN, state.todayKind)
        assertEquals(WidgetStateMapper.HIDDEN_TITLE, state.todayTitle)
        assertNull(state.todayNoteId)
        assertNull(state.reminderLine)
    }

    @Test
    fun reminderLineNamesAnUnlockedNoteAndItsTime() {
        val trigger = java.time.ZonedDateTime.of(
            2026, 11, 6, 14, 30, 0, 0, zone,
        ).toInstant().toEpochMilli()
        val state = map(
            nextReminder = WidgetReminderFacts(trigger, "  Dentist  ", noteLocked = false),
        )
        // Composition is what's under test (trimmed title + the
        // shared formatter's time); the formatter's own class pins
        // its exact rendering — CLDR AM/PM casing varies by JVM.
        val time = app.nota.data.reminders.ReminderNotificationText
            .formatReminderTime(trigger, zone, locale)
        assertEquals("Dentist · $time", state.reminderLine)
        assertTrue(state.reminderLine!!.contains("Fri, 6 Nov"))
    }

    @Test
    fun reminderLineForALockedNoteNeverSpeaksItsTitle() {
        val trigger = java.time.ZonedDateTime.of(
            2026, 11, 6, 14, 30, 0, 0, zone,
        ).toInstant().toEpochMilli()
        val state = map(
            nextReminder = WidgetReminderFacts(trigger, "Secret diary", noteLocked = true),
        )
        assertTrue(state.reminderLine!!.startsWith("You have a note reminder"))
        assertFalse(state.reminderLine!!.contains("Secret diary"))
    }

    @Test
    fun reminderLineForAnUnreadableNoteIsGeneric() {
        val state = map(
            nextReminder = WidgetReminderFacts(1_900_000_000_000L, null, noteLocked = false),
        )
        assertTrue(state.reminderLine!!.startsWith("You have a note reminder"))
    }

    @Test
    fun noReminderMeansNoLine() {
        assertNull(map().reminderLine)
    }
}
