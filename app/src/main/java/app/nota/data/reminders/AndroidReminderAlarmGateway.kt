package app.nota.data.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File

/**
 * Phase 5.1: the AlarmManager-backed [ReminderAlarmGateway].
 *
 * Exact alarms (`setExactAndAllowWhileIdle`) are used whenever the
 * platform permits; on API 31+ without the exact-alarm permission the
 * gateway falls back to `setAndAllowWhileIdle` — the reminder UI says
 * so plainly (never a silent downgrade), and below API 31 exact
 * scheduling needs no permission at all (the J7 on API 27 is
 * friction-free).
 *
 * The armed-id set persists to a small file in filesDir: it is the
 * only record of WHICH reminders hold system alarms, and alarms
 * outlive both the process and account switches.
 */
class AndroidReminderAlarmGateway(context: Context) : ReminderAlarmGateway {

    private val appContext = context.applicationContext
    private val alarmManager: AlarmManager =
        appContext.getSystemService(AlarmManager::class.java)
    private val armedIdsFile = File(appContext.filesDir, ARMED_IDS_FILE)

    override fun arm(reminderId: String, triggerAtMillis: Long) {
        val pendingIntent = alarmPendingIntent(reminderId)
        if (canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent,
            )
        }
    }

    override fun disarm(reminderId: String) {
        val pendingIntent = alarmPendingIntent(reminderId)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    override fun armedReminderIds(): Set<String> = runCatching {
        if (!armedIdsFile.exists()) {
            emptySet()
        } else {
            armedIdsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
    }.getOrDefault(emptySet())

    override fun noteArmedReminderIds(ids: Set<String>) {
        runCatching {
            armedIdsFile.writeText(ids.joinToString(separator = "\n"))
        }
    }

    override fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

    private fun alarmPendingIntent(reminderId: String): PendingIntent {
        val intent = Intent(appContext, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_FIRE
            // The data URI makes each reminder's intent distinct even
            // before extras are considered (PendingIntent identity
            // ignores extras).
            data = Uri.parse("nota://reminder/$reminderId")
            putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID, reminderId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            ReminderIds.requestCode(reminderId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val ARMED_IDS_FILE = "reminder-armed-ids.txt"
    }
}
