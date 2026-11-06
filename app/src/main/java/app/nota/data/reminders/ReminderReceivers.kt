package app.nota.data.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Phase 5.1: the alarm delivery receiver. Thin by design — all logic
 * lives in [ReminderFireHandler]. Not exported: only Nota's own
 * PendingIntents (and the system delivering them) can reach it.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID)
            ?: intent.data?.lastPathSegment
            ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { GlobalContext.get().get<ReminderFireHandler>() }
                    .getOrNull()
                    ?.onAlarmFired(reminderId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "app.nota.action.REMINDER_FIRE"
        const val EXTRA_REMINDER_ID = "app.nota.extra.REMINDER_ID"
    }
}

/**
 * Phase 5.1: boot / clock-change rescheduling. AlarmManager alarms do
 * not survive a reboot, and wall-clock changes move every trigger —
 * both funnel into [ReminderScheduler.rescheduleAll], which rebuilds
 * the armed set from the active account's database.
 */
class ReminderRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Unit
            else -> return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { GlobalContext.get().get<ReminderScheduler>() }
                    .getOrNull()
                    ?.rescheduleAll()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
