package app.nota.data.reminders

import app.nota.data.db.ReminderEntity
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5.1: the pure reconciliation rule behind rescheduleAll —
 * 1.x's rehydrate semantics: arm enabled, notification-type, future
 * reminders on live notes; never arm phone-alarms (the Clock app owns
 * them), spent rows, overdue rows (they sit in Overdue instead), or
 * reminders on trashed/missing notes; disarm anything armed that is
 * no longer desired (e.g. a previous account's leftovers).
 */
class ReminderSchedulerTest {

    private val now = 1_800_000_000_000L

    private fun reminder(
        id: String,
        triggerAt: Long = now + 60_000,
        enabled: Boolean = true,
        alertType: String = ReminderAlertType.NOTIFICATION,
        noteId: String = "note-$id",
    ) = ReminderEntity(
        id = id,
        noteId = noteId,
        triggerAt = triggerAt,
        repeatRule = null,
        enabled = enabled,
        createdAt = now - 1_000,
        alertType = alertType,
    )

    private fun candidate(
        reminder: ReminderEntity,
        noteMissingOrTrashed: Boolean = false,
    ) = ReminderArmCandidate(reminder, noteMissingOrTrashed)

    @Test
    fun armsFutureEnabledNotificationReminderOnLiveNote() {
        val plan = ReminderReconcile.plan(
            listOf(candidate(reminder("r1"))),
            armedIds = emptySet(),
            nowMillis = now,
        )
        assertEquals(listOf("r1"), plan.arm.map { it.id })
        assertEquals(setOf("r1"), plan.desiredIds)
        assertTrue(plan.disarm.isEmpty())
    }

    @Test
    fun neverArmsPhoneAlarmSpentOverdueOrTrashedNoteReminders() {
        val plan = ReminderReconcile.plan(
            listOf(
                candidate(reminder("phone", alertType = ReminderAlertType.PHONE_ALARM)),
                candidate(reminder("spent", enabled = false)),
                candidate(reminder("overdue", triggerAt = now - 1)),
                candidate(reminder("trashed-note"), noteMissingOrTrashed = true),
                candidate(reminder("ok")),
            ),
            armedIds = emptySet(),
            nowMillis = now,
        )
        assertEquals(listOf("ok"), plan.arm.map { it.id })
        assertEquals(setOf("ok"), plan.desiredIds)
    }

    @Test
    fun reminderExactlyAtNowIsNotArmed() {
        val plan = ReminderReconcile.plan(
            listOf(candidate(reminder("edge", triggerAt = now))),
            armedIds = emptySet(),
            nowMillis = now,
        )
        assertTrue(plan.arm.isEmpty())
    }

    @Test
    fun disarmsArmedIdsThatAreNoLongerDesired() {
        val plan = ReminderReconcile.plan(
            listOf(candidate(reminder("kept"))),
            armedIds = setOf("kept", "other-account-leftover", "deleted-reminder"),
            nowMillis = now,
        )
        assertEquals(setOf("other-account-leftover", "deleted-reminder"), plan.disarm)
        assertEquals(setOf("kept"), plan.desiredIds)
    }

    @Test
    fun emptyWorldDisarmsEverything() {
        val plan = ReminderReconcile.plan(
            candidates = emptyList(),
            armedIds = setOf("a", "b"),
            nowMillis = now,
        )
        assertTrue(plan.arm.isEmpty())
        assertEquals(setOf("a", "b"), plan.disarm)
        assertTrue(plan.desiredIds.isEmpty())
    }

    @Test
    fun editedReminderIsReArmedWithItsNewTime() {
        val edited = reminder("r1", triggerAt = now + 3_600_000)
        val plan = ReminderReconcile.plan(
            listOf(candidate(edited)),
            armedIds = setOf("r1"),
            nowMillis = now,
        )
        // Same id stays desired (no disarm) but is re-armed so the
        // AlarmManager call replaces the old trigger time.
        assertEquals(listOf(edited), plan.arm)
        assertTrue(plan.disarm.isEmpty())
    }

    // --- PR #53: ReminderRepeat -----------------------------------
    // The pure repeat math: encoding, occurrence resolution (the
    // anchor carries the series' local time of day; results are
    // strictly after the ask), and the compact labels.

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun repeatParseEncodeRoundTrips() {
        assertEquals(ReminderRepeat.Rule.Daily, ReminderRepeat.parse("DAILY"))
        assertEquals("DAILY", ReminderRepeat.encode(ReminderRepeat.Rule.Daily))
        assertEquals(ReminderRepeat.Rule.Weekly, ReminderRepeat.parse(" weekly "))
        assertEquals("WEEKLY", ReminderRepeat.encode(ReminderRepeat.Rule.Weekly))
        // Stored custom days are canonicalised: sorted, de-duplicated.
        assertEquals(
            ReminderRepeat.Rule.CustomDays(setOf(1, 3, 5)),
            ReminderRepeat.parse("DAYS:5,1,3,5"),
        )
        assertEquals(
            "DAYS:1,3,5",
            ReminderRepeat.encode(ReminderRepeat.Rule.CustomDays(setOf(5, 1, 3))),
        )
        assertNull(ReminderRepeat.encode(null))
        assertNull(ReminderRepeat.encode(ReminderRepeat.Rule.CustomDays(emptySet())))
    }

    @Test
    fun repeatParseEncodeRoundTripsWithSeriesTime() {
        val time = LocalTime.of(2, 30)
        // The suffixed encodings for all three rule kinds
        // (zero-padded 24-hour).
        assertEquals(
            "DAILY@02:30",
            ReminderRepeat.encode(ReminderRepeat.Rule.Daily, time),
        )
        assertEquals(
            "WEEKLY@02:30",
            ReminderRepeat.encode(ReminderRepeat.Rule.Weekly, time),
        )
        assertEquals(
            "DAYS:1,3,5@02:30",
            ReminderRepeat.encode(
                ReminderRepeat.Rule.CustomDays(setOf(5, 1, 3)), time,
            ),
        )
        assertEquals(
            "DAILY@09:05",
            ReminderRepeat.encode(ReminderRepeat.Rule.Daily, LocalTime.of(9, 5)),
        )
        // …and back: rule + carried time both survive the trip.
        assertEquals(
            ReminderRepeat.Series(ReminderRepeat.Rule.Daily, time),
            ReminderRepeat.parseSeries("DAILY@02:30"),
        )
        assertEquals(
            ReminderRepeat.Series(ReminderRepeat.Rule.Weekly, time),
            ReminderRepeat.parseSeries("WEEKLY@02:30"),
        )
        assertEquals(
            ReminderRepeat.Series(
                ReminderRepeat.Rule.CustomDays(setOf(1, 3, 5)), time,
            ),
            ReminderRepeat.parseSeries("DAYS:1,3,5@02:30"),
        )
        // parse() still answers the rule alone for a suffixed form.
        assertEquals(
            ReminderRepeat.Rule.Daily,
            ReminderRepeat.parse("DAILY@02:30"),
        )
        // No suffix = the legacy form: a rule with no carried time.
        assertEquals(
            ReminderRepeat.Series(ReminderRepeat.Rule.Daily, null),
            ReminderRepeat.parseSeries("DAILY"),
        )
        // A malformed suffix is ignored, not fatal: the rule
        // survives as its legacy form…
        assertEquals(
            ReminderRepeat.Series(ReminderRepeat.Rule.Daily, null),
            ReminderRepeat.parseSeries("DAILY@25:99"),
        )
        assertEquals(
            ReminderRepeat.Series(ReminderRepeat.Rule.Daily, null),
            ReminderRepeat.parseSeries("DAILY@"),
        )
        // …but an unparseable body stays unparseable, suffix or not.
        assertNull(ReminderRepeat.parseSeries("MONTHLY@02:30"))
        assertNull(ReminderRepeat.parseSeries("@02:30"))
    }

    @Test
    fun repeatParseDegradesUnknownValuesToOneShot() {
        assertNull(ReminderRepeat.parse(null))
        assertNull(ReminderRepeat.parse(""))
        assertNull(ReminderRepeat.parse("MONTHLY"))
        assertNull(ReminderRepeat.parse("DAYS:"))
        assertNull(ReminderRepeat.parse("DAYS:0,9,x"))
    }

    @Test
    fun dailyNextOccurrenceIsStrictlyAfter() {
        // Anchor: Mon 2 Nov 2026, 09:00 UTC.
        val anchor = millis("2026-11-02T09:00:00Z")
        val daily = ReminderRepeat.Rule.Daily
        // The anchor itself while it is still ahead.
        assertEquals(
            anchor,
            ReminderRepeat.nextOccurrence(anchor, daily, anchor - 3_600_000, utc),
        )
        // At the anchor exactly, the series moves on a day.
        assertEquals(
            millis("2026-11-03T09:00:00Z"),
            ReminderRepeat.nextOccurrence(anchor, daily, anchor, utc),
        )
        // An hour late still lands on the next day, same local time.
        assertEquals(
            millis("2026-11-03T09:00:00Z"),
            ReminderRepeat.nextOccurrence(anchor, daily, anchor + 3_600_000, utc),
        )
    }

    @Test
    fun weeklyNextOccurrenceIsSevenDaysOut() {
        val anchor = millis("2026-11-02T09:00:00Z") // a Monday
        val weekly = ReminderRepeat.Rule.Weekly
        assertEquals(
            millis("2026-11-09T09:00:00Z"),
            ReminderRepeat.nextOccurrence(anchor, weekly, anchor, utc),
        )
        // A midweek ask lands on the coming Monday — the series is
        // anchored on the trigger, not on the ask.
        assertEquals(
            millis("2026-11-09T09:00:00Z"),
            ReminderRepeat.nextOccurrence(anchor, weekly, millis("2026-11-04T12:00:00Z"), utc),
        )
    }

    @Test
    fun customDaysNextOccurrenceWrapsTheWeek() {
        val anchor = millis("2026-11-02T09:00:00Z") // a Monday
        val rule = ReminderRepeat.Rule.CustomDays(setOf(1, 3, 5))
        // Mon at the anchor -> Wed.
        assertEquals(
            millis("2026-11-04T09:00:00Z"),
            ReminderRepeat.nextOccurrence(anchor, rule, anchor, utc),
        )
        // Fri after the time -> next Mon.
        assertEquals(
            millis("2026-11-09T09:00:00Z"),
            ReminderRepeat.nextOccurrence(anchor, rule, millis("2026-11-06T10:00:00Z"), utc),
        )
        // Sun -> Mon.
        assertEquals(
            millis("2026-11-09T09:00:00Z"),
            ReminderRepeat.nextOccurrence(anchor, rule, millis("2026-11-08T08:00:00Z"), utc),
        )
    }

    @Test
    fun nextOccurrenceKeepsLocalTimeAcrossDst() {
        val newYork = ZoneId.of("America/New_York")
        // Sat 7 Mar 2026, 02:30 EST (07:30Z). Clocks spring forward
        // Sun 8 Mar at 02:00, so Sunday's 02:30 never happens — the
        // occurrence is the transition instant (07:00Z), exactly the
        // ReminderTriggerTimes gap rule a picked trigger gets.
        val anchor = millis("2026-03-07T07:30:00Z")
        assertEquals(
            millis("2026-03-08T07:00:00Z"),
            ReminderRepeat.nextOccurrence(anchor, ReminderRepeat.Rule.Daily, anchor, newYork),
        )
    }

    @Test
    fun suffixedDailySeriesReturnsToItsTimeAfterADstGap() {
        val newYork = ZoneId.of("America/New_York")
        // Sat 7 Mar 2026, 02:30 EST (07:30Z). Sunday's 02:30 falls
        // in the spring-forward gap and resolves to 03:00 EDT —
        // the gap rule, same as a picked trigger.
        val anchor = millis("2026-03-07T07:30:00Z")
        val gapOccurrence = ReminderRepeat.nextOccurrence(
            anchor, "DAILY@02:30", anchor, newYork,
        )
        assertEquals(1_772_953_200_000L, gapOccurrence)
        // The gap-resolved instant is what the fire path persists
        // as the new anchor. The carried time must stop it from
        // re-basing the series: Monday rings at 02:30 EDT again,
        // NOT at the resolved 03:00.
        assertEquals(
            1_773_037_800_000L,
            ReminderRepeat.nextOccurrence(
                gapOccurrence!!, "DAILY@02:30", gapOccurrence, newYork,
            ),
        )
    }

    @Test
    fun legacyDailySeriesDriftsAcrossADstGap() {
        val newYork = ZoneId.of("America/New_York")
        // The legacy suffix-less form derives each occurrence's
        // time of day from the stored anchor, so the same chain
        // drifts: once the gap resolution (03:00 EDT) is persisted
        // as the anchor, Monday rings at 03:00. Pinned so the
        // legacy behaviour is explicit, not accidental — it is
        // what encodings without a series time have always done.
        val anchor = millis("2026-03-07T07:30:00Z")
        val gapOccurrence = ReminderRepeat.nextOccurrence(
            anchor, "DAILY", anchor, newYork,
        )
        assertEquals(1_772_953_200_000L, gapOccurrence)
        assertEquals(
            1_773_039_600_000L,
            ReminderRepeat.nextOccurrence(
                gapOccurrence!!, "DAILY", gapOccurrence, newYork,
            ),
        )
    }

    @Test
    fun nextOccurrenceOfNoRuleIsNull() {
        val anchor = millis("2026-11-02T09:00:00Z")
        assertNull(ReminderRepeat.nextOccurrence(anchor, null as String?, anchor - 1, utc))
        assertNull(ReminderRepeat.nextOccurrence(anchor, "GARBAGE", anchor - 1, utc))
    }

    @Test
    fun repeatLabelsAreCompact() {
        val monday = millis("2026-11-02T09:00:00Z")
        val tuesday = millis("2026-11-03T09:00:00Z")
        assertEquals("Daily", ReminderRepeat.repeatLabel("DAILY", monday, utc))
        assertEquals("Weekly · Tue", ReminderRepeat.repeatLabel("WEEKLY", tuesday, utc))
        assertEquals(
            "Mon · Wed · Fri",
            ReminderRepeat.repeatLabel("DAYS:5,1,3", monday, utc),
        )
        assertNull(ReminderRepeat.repeatLabel(null, monday, utc))
        assertNull(ReminderRepeat.repeatLabel("GARBAGE", monday, utc))
    }

    @Test
    fun repeatDaysIsoResolvesAgainstTheAnchor() {
        val tuesday = millis("2026-11-03T09:00:00Z")
        assertEquals((1..7).toSet(), ReminderRepeat.daysIso("DAILY", tuesday, utc))
        assertEquals(setOf(2), ReminderRepeat.daysIso("WEEKLY", tuesday, utc))
        assertEquals(setOf(1, 3, 5), ReminderRepeat.daysIso("DAYS:1,3,5", tuesday, utc))
        assertNull(ReminderRepeat.daysIso(null as String?, tuesday, utc))
    }
}
