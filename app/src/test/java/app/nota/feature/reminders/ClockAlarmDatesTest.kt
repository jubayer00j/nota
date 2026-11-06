package app.nota.feature.reminders

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5.1 fix round (Codex P2): a Clock alarm can only carry an
 * hour and a minute, so it rings at the NEXT occurrence of the
 * picked time. Only the date of that next occurrence is
 * representable; every other date must be refused for the
 * phone-alarm style so the stored reminder never lies about its day.
 */
class ClockAlarmDatesTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // Fri 6 Nov 2026, 10:00 UTC.
    private val now = millis("2026-11-06T10:00:00Z")

    @Test
    fun nextOccurrenceLaterTodayIsToday() {
        assertEquals(
            millis("2026-11-06T14:30:00Z"),
            ClockAlarmDates.nextOccurrenceMillis(LocalTime.of(14, 30), now, utc),
        )
    }

    @Test
    fun todayAheadIsRepresentable() {
        assertTrue(
            ClockAlarmDates.isRepresentable(millis("2026-11-06T14:30:00Z"), now, utc),
        )
    }

    @Test
    fun timeAlreadyPastTodayRollsToTomorrow() {
        assertEquals(
            millis("2026-11-07T09:00:00Z"),
            ClockAlarmDates.nextOccurrenceMillis(LocalTime.of(9, 0), now, utc),
        )
    }

    @Test
    fun pickedTodayAtPastTimeIsNotRepresentable() {
        assertFalse(
            ClockAlarmDates.isRepresentable(millis("2026-11-06T09:00:00Z"), now, utc),
        )
    }

    @Test
    fun pickedTomorrowAtPastTimeIsRepresentable() {
        assertTrue(
            ClockAlarmDates.isRepresentable(millis("2026-11-07T09:00:00Z"), now, utc),
        )
    }

    @Test
    fun tomorrowWithLaterTimeIsNotRepresentable() {
        // The next 23:00 is TODAY's — picking tomorrow's 23:00 would
        // ring a day early.
        assertFalse(
            ClockAlarmDates.isRepresentable(millis("2026-11-07T23:00:00Z"), now, utc),
        )
    }

    @Test
    fun farFutureIsNotRepresentable() {
        assertFalse(
            ClockAlarmDates.isRepresentable(millis("2026-12-25T09:00:00Z"), now, utc),
        )
    }

    @Test
    fun midnightEdgeRollsToTomorrowsMidnight() {
        val lateEvening = millis("2026-11-06T23:59:00Z")
        assertEquals(
            millis("2026-11-07T00:00:00Z"),
            ClockAlarmDates.nextOccurrenceMillis(LocalTime.of(0, 0), lateEvening, utc),
        )
        assertTrue(
            ClockAlarmDates.isRepresentable(
                millis("2026-11-07T00:00:00Z"), lateEvening, utc,
            ),
        )
        assertFalse(
            ClockAlarmDates.isRepresentable(
                millis("2026-11-08T00:00:00Z"), lateEvening, utc,
            ),
        )
    }

    @Test
    fun exactlyNowRollsToTomorrow() {
        // The next occurrence is strictly after now: a picked time
        // equal to now is the past as far as the dialog is concerned,
        // and its next occurrence is tomorrow's.
        val atNine = millis("2026-11-06T09:00:00Z")
        assertEquals(
            millis("2026-11-07T09:00:00Z"),
            ClockAlarmDates.nextOccurrenceMillis(LocalTime.of(9, 0), atNine, utc),
        )
        assertFalse(ClockAlarmDates.isRepresentable(atNine, atNine, utc))
    }

    @Test
    fun worksInDhakaTimeToo() {
        val dhaka: ZoneId = ZoneId.of("Asia/Dhaka")
        // 10:00 in Dhaka on Fri 6 Nov 2026 = 04:00 UTC.
        val dhakaNow = millis("2026-11-06T04:00:00Z")
        // 14:30 Dhaka the same day = 08:30 UTC — still ahead.
        assertTrue(
            ClockAlarmDates.isRepresentable(millis("2026-11-06T08:30:00Z"), dhakaNow, dhaka),
        )
        // 14:30 Dhaka the NEXT day is not the next occurrence.
        assertFalse(
            ClockAlarmDates.isRepresentable(millis("2026-11-07T08:30:00Z"), dhakaNow, dhaka),
        )
    }
}
