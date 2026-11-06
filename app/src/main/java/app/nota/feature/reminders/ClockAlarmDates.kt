package app.nota.feature.reminders

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
}
