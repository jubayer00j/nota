package app.nota.data.repository

import app.nota.data.db.NoteStatsRow
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading stats (v0.37.0): the pure day/streak/period math.
 * Every date is pinned — Wednesday 25 November 2026 is the
 * reference "today" (week of Monday 23rd) unless a test says
 * otherwise — and all timestamps are noon UTC so no zone or
 * DST edge can smear a day boundary.
 */
class WritingStatsCalculatorTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 11, 25)

    private fun millis(day: LocalDate): Long =
        day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    // Fix round 2: rows carry the stored word count, not the
    // body — the stats query no longer selects plain text.
    private fun row(
        created: LocalDate,
        contentEdited: LocalDate? = created,
        locked: Boolean = false,
        words: Int = 0,
    ) = NoteStatsRow(millis(created), contentEdited?.let { millis(it) }, locked, words)

    private fun compute(rows: List<NoteStatsRow>, on: LocalDate = today) =
        WritingStatsCalculator.compute(rows, on, zone)

    // ---- Streak liveness ----------------------------------------

    @Test
    fun currentStreak_aliveWhenLastWritingDayIsToday() {
        val stats = compute(
            listOf(
                row(LocalDate.of(2026, 11, 23)),
                row(LocalDate.of(2026, 11, 24)),
                row(LocalDate.of(2026, 11, 25)),
            ),
        )
        assertEquals(3, stats.currentStreak)
        assertEquals(3, stats.longestStreak)
    }

    @Test
    fun currentStreak_aliveWhenLastWritingDayIsYesterday() {
        // Nothing today yet — the forgiving rule keeps the
        // streak alive through yesterday.
        val stats = compute(
            listOf(
                row(LocalDate.of(2026, 11, 22)),
                row(LocalDate.of(2026, 11, 23)),
                row(LocalDate.of(2026, 11, 24)),
            ),
        )
        assertEquals(3, stats.currentStreak)
    }

    @Test
    fun currentStreak_deadWhenLastWritingDayIsOlder() {
        val stats = compute(
            listOf(
                row(LocalDate.of(2026, 11, 21)),
                row(LocalDate.of(2026, 11, 22)),
                row(LocalDate.of(2026, 11, 23)),
            ),
        )
        assertEquals(0, stats.currentStreak)
        assertEquals(3, stats.longestStreak)
    }

    @Test
    fun longestStreak_picksTheLongestRunAcrossGaps() {
        val stats = compute(
            listOf(
                // A four-day run last week…
                row(LocalDate.of(2026, 11, 16)),
                row(LocalDate.of(2026, 11, 17)),
                row(LocalDate.of(2026, 11, 18)),
                row(LocalDate.of(2026, 11, 19)),
                // …and a two-day run ending today.
                row(LocalDate.of(2026, 11, 24)),
                row(LocalDate.of(2026, 11, 25)),
            ),
        )
        assertEquals(2, stats.currentStreak)
        assertEquals(4, stats.longestStreak)
    }

    @Test
    fun streaks_spanTheYearBoundary() {
        val newYear = LocalDate.of(2027, 1, 1)
        val stats = compute(
            listOf(
                row(LocalDate.of(2026, 12, 30)),
                row(LocalDate.of(2026, 12, 31)),
                row(LocalDate.of(2027, 1, 1)),
            ),
            on = newYear,
        )
        assertEquals(3, stats.currentStreak)
        assertEquals(3, stats.longestStreak)
        // January's month counts only the New Year note.
        assertEquals(1, stats.startedThisMonth)
    }

    // ---- Writing days -------------------------------------------

    @Test
    fun writingDays_unionOfCreatedAndContentEditedDays() {
        val stats = compute(
            listOf(
                // Created Monday, content last edited Wednesday: two
                // writing days from one note.
                row(
                    created = LocalDate.of(2026, 11, 23),
                    contentEdited = LocalDate.of(2026, 11, 25),
                ),
                // A second note on the same two days adds none.
                row(
                    created = LocalDate.of(2026, 11, 23),
                    contentEdited = LocalDate.of(2026, 11, 25),
                ),
            ),
        )
        assertEquals(2, stats.totalNotes)
        assertEquals(2, stats.writingDays)
        assertEquals(LocalDate.of(2026, 11, 23), stats.writingSince)
    }

    // Fix round 1 (R1-F1): a row with no recorded content edit
    // contributes its creation day only — never a metadata bump.
    // There is no updatedAt in the row at all anymore; the
    // calculator cannot fall back to it.
    @Test
    fun unrecordedContentEdit_contributesNoEditedDay() {
        val stats = compute(
            listOf(
                row(
                    created = LocalDate.of(2026, 11, 23),
                    contentEdited = null,
                ),
            ),
        )
        assertEquals(1, stats.totalNotes)
        assertEquals(1, stats.writingDays)
        assertEquals(0, stats.editedThisWeek)
        assertEquals(0, stats.editedThisMonth)
        assertEquals(1, stats.startedThisWeek)
    }

    @Test
    fun emptyRows_zeroStatsAndNoSince() {
        val stats = compute(emptyList())
        assertEquals(0, stats.totalNotes)
        assertEquals(0, stats.totalWords)
        assertEquals(0, stats.writingDays)
        assertEquals(0, stats.currentStreak)
        assertEquals(0, stats.longestStreak)
        assertEquals(List(WritingStatsCalculator.ACTIVITY_WEEKS) { 0 }, stats.weeklyActivity)
        assertNull(stats.writingSince)
    }

    // ---- Words ---------------------------------------------------

    @Test
    fun wordCount_whitespaceSplitOverUnlockedRowsOnly() {
        val stats = compute(
            listOf(
                row(today, words = 4),
                row(today, words = 1),
                // A locked row's stored count never counts — the
                // flag rules even if a count were present.
                row(today, locked = true, words = 3),
                row(today),
            ),
        )
        assertEquals(5, stats.totalWords)
        assertEquals(4, stats.totalNotes)
        assertEquals(1, stats.lockedNotes)
    }

    // ---- Weeks ---------------------------------------------------

    @Test
    fun weekCounts_mondayStartAndBoundaryDays() {
        // Today is Wednesday 25 Nov; this week is Mon 23 – Sun 29.
        val stats = compute(
            listOf(
                row(LocalDate.of(2026, 11, 23)), // Monday: in
                row(LocalDate.of(2026, 11, 22)), // Sunday before: out
                row(LocalDate.of(2026, 11, 25)), // Today: in
            ),
        )
        assertEquals(2, stats.startedThisWeek)
        assertEquals(2, stats.editedThisWeek)
    }

    @Test
    fun weekCounts_whenTodayIsMonday_sundayBelongsToLastWeek() {
        val monday = LocalDate.of(2026, 11, 23)
        val stats = compute(
            listOf(
                row(LocalDate.of(2026, 11, 22)), // Sunday: last week
                row(LocalDate.of(2026, 11, 23)), // Monday: this week
            ),
            on = monday,
        )
        assertEquals(1, stats.startedThisWeek)
    }

    @Test
    fun weeklyActivity_twelveWeeksEndingThisWeek_notesCountedOncePerWeek() {
        // Window: Monday 7 Sep 2026 (index 0) … Monday 23 Nov
        // 2026 (index 11).
        val stats = compute(
            listOf(
                // Before the window (Sunday 6 Sep): excluded.
                row(LocalDate.of(2026, 9, 6)),
                // First window day: index 0.
                row(LocalDate.of(2026, 9, 7)),
                // Created AND edited inside this week: one count.
                row(
                    created = LocalDate.of(2026, 11, 24),
                    contentEdited = LocalDate.of(2026, 11, 25),
                ),
                // Created in week 0, edited in week 11: both.
                row(
                    created = LocalDate.of(2026, 9, 8),
                    contentEdited = LocalDate.of(2026, 11, 25),
                ),
            ),
        )
        assertEquals(WritingStatsCalculator.ACTIVITY_WEEKS, stats.weeklyActivity.size)
        assertEquals(2, stats.weeklyActivity[0])
        assertEquals(2, stats.weeklyActivity[11])
        assertEquals(0, stats.weeklyActivity[5])
        assertEquals(4, stats.weeklyActivity.sum())
    }

    // ---- Months --------------------------------------------------

    @Test
    fun monthCounts_calendarMonthBoundaries() {
        val stats = compute(
            listOf(
                row(LocalDate.of(2026, 10, 31)), // Last month: out
                row(LocalDate.of(2026, 11, 1)), // First of month: in
                row(LocalDate.of(2026, 11, 25)), // Today: in
            ),
        )
        assertEquals(2, stats.startedThisMonth)
        assertEquals(2, stats.editedThisMonth)
    }

    @Test
    fun periodCounts_startedAndEditedAreSeparateQuestions() {
        // Written last month, edited this week: an edit, not a
        // start, for both periods.
        val stats = compute(
            listOf(
                row(
                    created = LocalDate.of(2026, 10, 15),
                    contentEdited = LocalDate.of(2026, 11, 24),
                ),
            ),
        )
        assertEquals(0, stats.startedThisWeek)
        assertEquals(1, stats.editedThisWeek)
        assertEquals(0, stats.startedThisMonth)
        assertEquals(1, stats.editedThisMonth)
    }

    // ---- The row summary -----------------------------------------

    @Test
    fun summary_stamps() {
        val empty = compute(emptyList())
        assertEquals("Notes, words and streaks", writingStatsSummary(empty))

        val one = compute(listOf(row(today, words = 1)))
        assertEquals("1 note · 1-day streak", writingStatsSummary(one))

        val many = compute(
            (0..5).map { row(today.minusDays(it.toLong()), words = 1) } +
                (0..121).map { row(LocalDate.of(2026, 1, 5), words = 1) },
        )
        assertEquals(128, many.totalNotes)
        assertEquals(6, many.currentStreak)
        assertEquals("128 notes · 6-day streak", writingStatsSummary(many))

        val streakless = compute(listOf(row(LocalDate.of(2026, 11, 10), words = 1)))
        assertEquals("1 note", writingStatsSummary(streakless))
    }
}
