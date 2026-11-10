package app.nota.feature.reminders

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import app.nota.data.reminders.ReminderNotificationText
import java.time.Instant
import java.time.ZoneId

/**
 * Phase 5.1: the "Clock alarm" alert style — 1.x's phone-alarm
 * hand-off (src/lib/noma/phone-alarm.ts). The reminder is given to
 * the device Clock app via ACTION_SET_ALARM at set time, with the
 * skip-UI flag OFF so the user confirms the alarm there ("Your Clock
 * app will open so you can confirm"). Ringing, snooze and dismiss
 * belong to the Clock app; Nota arms nothing and posts nothing for
 * these reminders.
 *
 * Phase 6 extras (PR #44): the hand-off now carries the reminder's
 * stored tone choices — EXTRA_VIBRATE from the stored flag (5.1
 * hardcoded it on, which stays the default) and EXTRA_RINGTONE only
 * when a tone URI is stored, exactly as 1.x forwards them.
 */
object PhoneAlarmIntents {

    fun buildSetAlarmIntent(
        noteTitle: String,
        triggerAtMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        vibrate: Boolean = true,
        ringtoneUri: String? = null,
    ): Intent {
        val time = Instant.ofEpochMilli(triggerAtMillis).atZone(zone)
        return Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            // 1.x labels the alarm "Noma: <note title>".
            putExtra(
                AlarmClock.EXTRA_MESSAGE,
                "Nota: ${noteTitle.trim().ifBlank { ReminderNotificationText.UNTITLED }}",
            )
            putExtra(AlarmClock.EXTRA_VIBRATE, vibrate)
            // Only when the user picked a tone: with no extra the
            // Clock app uses its own default.
            if (ringtoneUri != null) {
                putExtra(AlarmClock.EXTRA_RINGTONE, ringtoneUri)
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
    }

    /** Whether any app can take the hand-off. Callers must check
     *  BEFORE saving a phone-alarm reminder: with no Clock app the
     *  reminder is kept as a notification reminder instead. */
    fun canHandle(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null
}
