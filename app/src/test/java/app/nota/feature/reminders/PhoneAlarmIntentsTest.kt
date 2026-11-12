package app.nota.feature.reminders

import android.content.Intent
import android.provider.AlarmClock
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 6 extras (PR #45): the Clock hand-off carries the reminder's
 * stored tone choices — EXTRA_VIBRATE from the stored flag (5.1
 * hardcoded it on) and EXTRA_RINGTONE only when a tone URI is
 * stored. The pre-existing extras (hour, minutes, message, skip-UI)
 * are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneAlarmIntentsTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    // 2030-01-01T10:30:00Z.
    private val triggerAt = 1_893_493_800_000L

    @Test
    fun vibrateFlagPropagates() {
        val off = PhoneAlarmIntents.buildSetAlarmIntent(
            "Dentist", triggerAt, utc, vibrate = false,
        )
        assertFalse(off.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, true))
        val on = PhoneAlarmIntents.buildSetAlarmIntent(
            "Dentist", triggerAt, utc, vibrate = true,
        )
        assertTrue(on.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, false))
    }

    @Test
    fun vibrateDefaultsToOn() {
        val intent = PhoneAlarmIntents.buildSetAlarmIntent("Dentist", triggerAt, utc)
        assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, false))
    }

    @Test
    fun ringtoneExtraPresentOnlyWithAStoredUri() {
        val withTone = PhoneAlarmIntents.buildSetAlarmIntent(
            "Dentist", triggerAt, utc,
            ringtoneUri = "content://media/internal/audio/42",
        )
        assertEquals(
            "content://media/internal/audio/42",
            withTone.getStringExtra(AlarmClock.EXTRA_RINGTONE),
        )
        val withoutTone = PhoneAlarmIntents.buildSetAlarmIntent("Dentist", triggerAt, utc)
        assertNull(withoutTone.getStringExtra(AlarmClock.EXTRA_RINGTONE))
    }

    @Test
    fun hourMinutesMessageAndSkipUiUnchanged() {
        val intent = PhoneAlarmIntents.buildSetAlarmIntent(
            "Dentist", triggerAt, utc, vibrate = false,
            ringtoneUri = "content://media/internal/audio/42",
        )
        assertEquals(10, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertEquals("Nota: Dentist", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertFalse(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
    }

    // --- PR #53: EXTRA_DAYS for repeats ----------------------------
    // ISO day numbers (Mon=1..Sun=7) map to Calendar constants
    // (Sun=1..Sat=7) via iso % 7 + 1.

    @Test
    fun repeatingIntentCarriesCalendarDays() {
        val daily = PhoneAlarmIntents.buildSetAlarmIntent(
            "Dentist", triggerAt, utc, repeatDaysIso = (1..7).toSet(),
        )
        // The full week in Monday-first ISO order, mapped: Mon..Sat
        // become 2..7 and Sunday wraps to 1 at the tail — the Clock
        // app reads EXTRA_DAYS as a set, so the order is cosmetic.
        assertEquals(
            arrayListOf(2, 3, 4, 5, 6, 7, 1),
            daily.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS),
        )
        // Tuesday ISO 2 -> Calendar 3.
        val tuesday = PhoneAlarmIntents.buildSetAlarmIntent(
            "Dentist", triggerAt, utc, repeatDaysIso = setOf(2),
        )
        assertEquals(
            arrayListOf(3),
            tuesday.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS),
        )
        // Mon/Wed/Fri -> 2/4/6.
        val custom = PhoneAlarmIntents.buildSetAlarmIntent(
            "Dentist", triggerAt, utc, repeatDaysIso = setOf(1, 3, 5),
        )
        assertEquals(
            arrayListOf(2, 4, 6),
            custom.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS),
        )
        // Sunday ISO 7 wraps to Calendar 1.
        val sunday = PhoneAlarmIntents.buildSetAlarmIntent(
            "Dentist", triggerAt, utc, repeatDaysIso = setOf(7),
        )
        assertEquals(
            arrayListOf(1),
            sunday.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS),
        )
    }

    @Test
    fun pickedTimeOverridesTheResolvedTriggerTime() {
        // Sun 8 Mar 2026, 03:00 EDT: the spring-forward transition
        // instant in New York. A picked 02:30 that morning never
        // happens and resolves to this moment (PR #53, round 6).
        val gapInstant = 1_772_953_200_000L
        val newYork = ZoneId.of("America/New_York")
        val picked = PhoneAlarmIntents.buildSetAlarmIntent(
            "Dentist", gapInstant, newYork,
            repeatDaysIso = (1..7).toSet(),
            pickedTime = LocalTime.of(2, 30),
        )
        // The Clock series is handed the PICKED time, not the
        // resolved instant's 03:00.
        assertEquals(2, picked.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, picked.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        // With no picked time, the derivation from the instant is
        // exactly as before: 03:00.
        val derived = PhoneAlarmIntents.buildSetAlarmIntent(
            "Dentist", gapInstant, newYork,
            repeatDaysIso = (1..7).toSet(),
        )
        assertEquals(3, derived.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(0, derived.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
    }

    @Test
    fun oneShotIntentHasNoDaysExtra() {
        val intent = PhoneAlarmIntents.buildSetAlarmIntent("Dentist", triggerAt, utc)
        assertNull(intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS))
    }

    @Test
    fun storedToneCarriesAReadGrantForTheClockApp() {
        // Codex PR #45 round 1: a picked custom tone is a document
        // URI the Clock app holds no grant for; the hand-off must
        // grant read access to exactly that URI or the Clock falls
        // back to its default tone.
        val withTone = PhoneAlarmIntents.buildSetAlarmIntent(
            "Dentist", triggerAt, utc,
            ringtoneUri = "content://com.android.externalstorage.documents/document/primary%3Aalarm.mp3",
        )
        assertTrue(
            withTone.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3Aalarm.mp3",
            withTone.clipData?.getItemAt(0)?.uri?.toString(),
        )
        val withoutTone = PhoneAlarmIntents.buildSetAlarmIntent("Dentist", triggerAt, utc)
        assertTrue(
            withoutTone.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0,
        )
        assertNull(withoutTone.clipData)
    }
}
