package app.nota.feature.reminders

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Phase 5.1: the honest capability checks behind the reminder UI,
 * mirroring 1.x's checkNotificationCapability. The reminder always
 * saves; what changes is what the UI promises:
 *
 * - Notifications denied (API 33+ runtime permission) → the dialog
 *   says plainly the reminder will save but alerts won't fire.
 * - Exact alarms not permitted (API 31+) → scheduling falls back to
 *   inexact and the dialog says so, with a route to the system
 *   setting. Below those API levels neither gate exists (the J7 on
 *   API 27 is friction-free).
 */
object ReminderPermissions {

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun exactAlarmsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return alarmManager.canScheduleExactAlarms()
    }

    /** The system screen where the user can allow exact alarms for
     *  Nota (API 31+). Callers guard with runCatching. */
    fun exactAlarmSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}"),
        )
}
