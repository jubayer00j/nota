package app.nota.feature.reminders

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Phase 5.1 fix round (Jules P3): turning a picked (date, time)
 * into an instant, with DELIBERATE daylight-saving rules instead of
 * [LocalDateTime.atZone]'s silent defaults:
 *
 * - Normal: the single valid offset applies.
 * - GAP (spring forward — the picked local time never happens,
 *   e.g. 02:30 on a 02:00→03:00 jump): resolve to the first valid
 *   instant after the gap — the transition instant itself, when
 *   the clocks land (03:00 in the example), NOT atZone's 03:30.
 * - OVERLAP (fall back — the picked local time happens twice):
 *   take the EARLIER of the two offsets (the one in effect before
 *   the transition) — the same choice atZone made, now explicit.
 *
 * Pure and zone-injectable so the rules are unit-testable against
 * real DST zones.
 */
object ReminderTriggerTimes {

    fun resolveEpochMillis(date: LocalDate, time: LocalTime, zone: ZoneId): Long {
        val local = LocalDateTime.of(date, time)
        val rules = zone.rules
        val offsets = rules.getValidOffsets(local)
        if (offsets.isNotEmpty()) {
            // One offset: a normal local time. Two: an overlap, and
            // getValidOffsets lists the offset in effect BEFORE the
            // transition first — the earlier offset, taken on purpose.
            return local.toInstant(offsets.first()).toEpochMilli()
        }
        // No valid offset: the picked time sits in a spring-forward
        // gap and never happens. The first real instant at or after
        // it is the transition instant itself — the moment the
        // clocks land past the gap.
        val transition = rules.getTransition(local)
        if (transition != null && transition.isGap) {
            return transition.instant.toEpochMilli()
        }
        // Unreachable for real zone rules; keep atZone as a backstop.
        return local.atZone(zone).toInstant().toEpochMilli()
    }
}
