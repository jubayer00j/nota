package app.nota.feature.reminders

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * Phase 6 extras (PR #45): the hand-off now carries the reminder's
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
    ): Intent = buildSetAlarmIntent(
        noteTitle, triggerAtMillis, zone, vibrate, ringtoneUri, repeatDaysIso = null,
    )

    /**
     * PR #53: the repeating form. [repeatDaysIso] is the rule's ISO
     * day set (Mon=1..Sun=7, via ReminderRepeat.daysIso) and maps to
     * AlarmClock.EXTRA_DAYS so the Clock app rings the series, not
     * just the first occurrence. null = one-shot (no extra, exactly
     * the intent the 5-arg form has always built).
     */
    fun buildSetAlarmIntent(
        noteTitle: String,
        triggerAtMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        vibrate: Boolean = true,
        ringtoneUri: String? = null,
        repeatDaysIso: Set<Int>?,
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
            // Clock app uses its own default. A picked CUSTOM tone is
            // a document URI the Clock app holds no grant for — it
            // could not open the file and would silently fall back to
            // its default — so the hand-off also carries a read grant
            // for exactly that URI (Codex PR #45 round 1).
            if (ringtoneUri != null) {
                putExtra(AlarmClock.EXTRA_RINGTONE, ringtoneUri)
                clipData = ClipData.newRawUri("Alarm tone", Uri.parse(ringtoneUri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // PR #53: a repeating reminder hands the Clock app its
            // day set, or the alarm would ring once and stop while
            // Nota's row keeps repeating.
            if (repeatDaysIso != null) {
                putIntegerArrayListExtra(
                    AlarmClock.EXTRA_DAYS,
                    ArrayList(calendarDaysFor(repeatDaysIso)),
                )
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
    }

    /**
     * ISO day numbers (Mon=1..Sun=7) → Calendar day constants
     * (Sun=1..Sat=7), Monday-first order preserved:
     * iso % 7 + 1 (Mon 1→2, Tue 2→3, …, Sat 6→7, Sun 7→1).
     */
    fun calendarDaysFor(daysIso: Set<Int>): List<Int> =
        daysIso.filter { it in 1..7 }.sorted().map { it % 7 + 1 }

    /** Whether any app can take the hand-off. Callers must check
     *  BEFORE saving a phone-alarm reminder: with no Clock app the
     *  reminder is kept as a notification reminder instead. */
    fun canHandle(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null
}
