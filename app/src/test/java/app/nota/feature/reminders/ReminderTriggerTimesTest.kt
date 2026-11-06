package app.nota.feature.reminders

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 5.1 fix round (Jules P3): the DST rules for turning a
 * picked (date, time) into an instant are deliberate, not
 * atZone's silent defaults — a gap time resolves to the moment the
 * clocks land, an overlap takes the earlier offset.
 */
class ReminderTriggerTimesTest {

    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private fun resolve(isoDate: String, hour: Int, minute: Int, zone: ZoneId): Long =
        ReminderTriggerTimes.resolveEpochMillis(
            LocalDate.parse(isoDate), LocalTime.of(hour, minute), zone,
        )

    private fun instant(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun springForwardGapResolvesToTransitionInstant() {
        // Sun 8 Mar 2026: clocks jump 02:00 EST → 03:00 EDT, so
        // 02:30 never happens. It resolves to the transition
        // instant itself — 03:00 EDT = 07:00 UTC — NOT atZone's
        // shifted 03:30 (07:30 UTC).
        assertEquals(
            instant("2026-03-08T07:00:00Z"),
            resolve("2026-03-08", 2, 30, newYork),
        )
    }

    @Test
    fun firstValidTimeAfterGapIsTheSameInstant() {
        assertEquals(
            instant("2026-03-08T07:00:00Z"),
            resolve("2026-03-08", 3, 0, newYork),
        )
    }

    @Test
    fun fallBackOverlapTakesEarlierOffset() {
        // Sun 1 Nov 2026: clocks fall back 02:00 EDT → 01:00 EST,
        // so 01:30 happens twice. The earlier offset (EDT, UTC-4)
        // wins: 01:30 EDT = 05:30 UTC, not 06:30 UTC.
        assertEquals(
            instant("2026-11-01T05:30:00Z"),
            resolve("2026-11-01", 1, 30, newYork),
        )
    }

    @Test
    fun normalSummerTimeUnaffected() {
        // 09:00 EDT = 13:00 UTC.
        assertEquals(
            instant("2026-06-15T13:00:00Z"),
            resolve("2026-06-15", 9, 0, newYork),
        )
    }

    @Test
    fun normalWinterTimeUnaffected() {
        // 09:00 EST = 14:00 UTC.
        assertEquals(
            instant("2026-01-15T14:00:00Z"),
            resolve("2026-01-15", 9, 0, newYork),
        )
    }

    @Test
    fun dhakaHasNoDstAndIsUnaffected() {
        // Asia/Dhaka is UTC+6 year-round: 02:30 local = 20:30 UTC
        // the day before, even on New York's gap date.
        val dhaka: ZoneId = ZoneId.of("Asia/Dhaka")
        assertEquals(
            instant("2026-03-07T20:30:00Z"),
            resolve("2026-03-08", 2, 30, dhaka),
        )
    }
}
