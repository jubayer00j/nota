package app.nota.feature.reminders

import android.content.Intent
import android.provider.AlarmClock
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
