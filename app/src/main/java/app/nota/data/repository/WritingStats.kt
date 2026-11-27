package app.nota.data.repository

import app.nota.data.backup.BackupRepository
import app.nota.data.db.NoteStatsRow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Reading stats (v0.37.0, spec §12): the quiet "Your writing"
 * numbers, computed entirely on-device from the notes table the
 * app already holds. No activity log exists in the data model
 * and this feature adds none: a note contributes the day it was
 * created and the day it was last edited, and days it was edited
 * before its last edit are unknowable — the stats honestly
 * reflect exactly that.
 */
data class WritingStats(
    /** Every untrashed note, locked and archived included. */
    val totalNotes: Int,
    /** Whitespace-split words over unlocked notes' plain text.
     *  Locked notes never contribute (see [lockedNotes]). */
    val totalWords: Int,
    /** Distinct local days with any writing activity, all-time. */
    val writingDays: Int,
    /** Consecutive writing days ending today or yesterday. */
    val currentStreak: Int,
    /** The longest run of consecutive writing days, all-time. */
    val longestStreak: Int,
    /** Notes created or edited per week for the last
     *  [WritingStatsCalculator.ACTIVITY_WEEKS] weeks, oldest
     *  first; a note counts once per week however it qualifies. */
    val weeklyActivity: List<Int>,
    val startedThisWeek: Int,
    val editedThisWeek: Int,
    val startedThisMonth: Int,
    val editedThisMonth: Int,
    /** The local day the oldest note was created; null when the
     *  library is empty. */
    val writingSince: LocalDate?,
    /** Untrashed locked notes — counted in note totals and day
     *  activity, never in word totals. */
    val lockedNotes: Int,
) {
    val isEmpty: Boolean get() = totalNotes == 0
}

/**
 * The day/streak/period math over [NoteStatsRow]s. Pure
 * `java.time`: the caller injects "today" and the zone, so the
 * whole surface is unit-testable without Android. All day
 * boundaries are the device's local days in [zone]; weeks start
 * on Monday.
 */
object WritingStatsCalculator {

    const val ACTIVITY_WEEKS = 12

    fun compute(
        rows: List<NoteStatsRow>,
        today: LocalDate,
        zone: ZoneId,
    ): WritingStats {
        fun dayOf(epochMillis: Long): LocalDate =
            Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

        val createdDays = rows.map { dayOf(it.createdAt) }
        val updatedDays = rows.map { dayOf(it.updatedAt) }
        val writingDays = HashSet<LocalDate>().apply {
            addAll(createdDays)
            addAll(updatedDays)
        }

        // The activity window: the last ACTIVITY_WEEKS Monday-
        // started weeks, the current week last.
        val thisWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val windowStart = thisWeekStart.minusWeeks((ACTIVITY_WEEKS - 1).toLong())
        val weekly = IntArray(ACTIVITY_WEEKS)
        rows.indices.forEach { i ->
            val touched = HashSet<Int>()
            for (day in listOf(createdDays[i], updatedDays[i])) {
                val weekStart = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val index = ChronoUnit.WEEKS.between(windowStart, weekStart).toInt()
                if (index in 0 until ACTIVITY_WEEKS) touched += index
            }
            touched.forEach { weekly[it]++ }
        }

        val weekEnd = thisWeekStart.plusDays(6)
        val monthStart = today.withDayOfMonth(1)
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        var startedWeek = 0
        var editedWeek = 0
        var startedMonth = 0
        var editedMonth = 0
        rows.indices.forEach { i ->
            val created = createdDays[i]
            val updated = updatedDays[i]
            if (created in thisWeekStart..weekEnd) startedWeek++
            if (updated in thisWeekStart..weekEnd) editedWeek++
            if (created in monthStart..monthEnd) startedMonth++
            if (updated in monthStart..monthEnd) editedMonth++
        }

        return WritingStats(
            totalNotes = rows.size,
            // The app's one word-count definition (the backup
            // writer's), so a note's words mean the same thing
            // everywhere. Locked rows are skipped by flag —
            // their stored text is empty under the seal anyway.
            totalWords = rows.filter { !it.locked }
                .sumOf { BackupRepository.wordCount(it.plainText) },
            writingDays = writingDays.size,
            currentStreak = currentStreak(writingDays, today),
            longestStreak = longestStreak(writingDays),
            weeklyActivity = weekly.toList(),
            startedThisWeek = startedWeek,
            editedThisWeek = editedWeek,
            startedThisMonth = startedMonth,
            editedThisMonth = editedMonth,
            writingSince = createdDays.minOrNull(),
            lockedNotes = rows.count { it.locked },
        )
    }

    /**
     * The forgiving streak: alive when the last writing day is
     * today OR yesterday; its length counts back from that day.
     * A library whose last writing day is older has no current
     * streak (0) — never a negative or a carried-over run.
     */
    fun currentStreak(days: Set<LocalDate>, today: LocalDate): Int {
        var anchor = when {
            days.contains(today) -> today
            days.contains(today.minusDays(1)) -> today.minusDays(1)
            else -> return 0
        }
        var streak = 0
        while (days.contains(anchor)) {
            streak++
            anchor = anchor.minusDays(1)
        }
        return streak
    }

    /** The longest run of consecutive days in [days]. */
    fun longestStreak(days: Set<LocalDate>): Int {
        if (days.isEmpty()) return 0
        val sorted = days.sorted()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            if (sorted[i] == sorted[i - 1].plusDays(1)) {
                run++
                if (run > best) best = run
            } else {
                run = 1
            }
        }
        return best
    }
}

/**
 * The Settings row's live summary, in the root list's stamp
 * style: "128 notes · 6-day streak". An empty library and a
 * streak-less one fall back to the calm descriptor / the bare
 * count — the row never shows a fake "0-day streak".
 */
fun writingStatsSummary(stats: WritingStats): String = when {
    stats.totalNotes == 0 -> "Notes, words and streaks"
    stats.currentStreak > 0 ->
        "${stats.totalNotes} ${if (stats.totalNotes == 1) "note" else "notes"}" +
            " · ${stats.currentStreak}-day streak"
    else -> "${stats.totalNotes} ${if (stats.totalNotes == 1) "note" else "notes"}"
}
