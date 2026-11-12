package app.nota.feature.reminders

import app.nota.data.reminders.ReminderTriggerTimes
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Phase 5.1 fix round (Codex P2): what a Clock-alarm hand-off can
 * and cannot promise. ACTION_SET_ALARM carries only an hour and a
 * minute, so the Clock app rings at the NEXT occurrence of that
 * time of day — later today if it is still ahead, otherwise
 * tomorrow. A picked reminder date is therefore representable as a
 * Clock alarm only when it IS that next-occurrence date; anything
 * further out would ring on the wrong day while Nota kept the
 * picked date, so the dialog says so plainly and refuses the
 * phone-alarm save for such dates.
 */
object ClockAlarmDates {

    /**
     * The instant of the next occurrence of [time] strictly after
     * [nowMillis] in [zone] (today's if still ahead, else
     * tomorrow's), resolved with the same DST rules as a picked
     * reminder trigger ([ReminderTriggerTimes]).
     */
    fun nextOccurrenceMillis(time: LocalTime, nowMillis: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val todayOccurrence = ReminderTriggerTimes.resolveEpochMillis(today, time, zone)
        return if (todayOccurrence > nowMillis) {
            todayOccurrence
        } else {
            ReminderTriggerTimes.resolveEpochMillis(today.plusDays(1), time, zone)
        }
    }

    /**
     * True iff [triggerAtMillis] falls on the date of the next
     * occurrence of its own time of day — the only dates the Clock
     * app can faithfully ring.
     */
    fun isRepresentable(triggerAtMillis: Long, nowMillis: Long, zone: ZoneId): Boolean {
        val picked = Instant.ofEpochMilli(triggerAtMillis).atZone(zone)
        val next = Instant.ofEpochMilli(
            nextOccurrenceMillis(picked.toLocalTime(), nowMillis, zone),
        ).atZone(zone)
        return picked.toLocalDate() == next.toLocalDate()
    }

    /**
     * PR #53: the first ring of a REPEATING Clock alarm — the next
     * occurrence of [time] strictly after [nowMillis] whose local
     * date falls on one of [daysIso] (ISO Mon=1..Sun=7). Scans at
     * most 8 days; a non-empty day set always matches within a week.
     */
    fun nextOccurrenceMillis(
        time: LocalTime,
        nowMillis: Long,
        zone: ZoneId,
        daysIso: Set<Int>,
    ): Long {
        val startDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        for (offset in 0..7) {
            val date = startDate.plusDays(offset.toLong())
            if (date.dayOfWeek.value in daysIso) {
                val occurrence = ReminderTriggerTimes.resolveEpochMillis(date, time, zone)
                if (occurrence > nowMillis) return occurrence
            }
        }
        // Unreachable for a non-empty day set; fall back to the
        // one-shot next occurrence rather than crashing a save.
        return nextOccurrenceMillis(time, nowMillis, zone)
    }

    /**
     * PR #53: representability for a repeating hand-off. Once
     * EXTRA_DAYS is set, the Clock app rings the series — the only
     * promise left to keep is that the FIRST ring lands on the
     * picked date, so the picked trigger must equal the series'
     * next occurrence. A null/empty [daysIso] defers to the
     * one-shot rule above.
     */
    fun isRepresentable(
        triggerAtMillis: Long,
        nowMillis: Long,
        zone: ZoneId,
        daysIso: Set<Int>?,
    ): Boolean {
        if (daysIso.isNullOrEmpty()) {
            return isRepresentable(triggerAtMillis, nowMillis, zone)
        }
        val picked = Instant.ofEpochMilli(triggerAtMillis).atZone(zone)
        return triggerAtMillis == nextOccurrenceMillis(
            picked.toLocalTime(), nowMillis, zone, daysIso,
        )
    }
}
