package app.nota.data.reminders

/**
 * Phase 5.1: the two alert styles a reminder can use, mirroring 1.x's
 * `alertType` on its Reminder model. Stored on ReminderEntity.alertType.
 *
 * - [NOTIFICATION]: Nota arms an exact AlarmManager alarm and posts a
 *   notification when it fires.
 * - [PHONE_ALARM]: at set time the reminder is handed to the device
 *   Clock app (ACTION_SET_ALARM) for the user to confirm there; Nota
 *   never arms an alarm or posts a notification for it (1.x excludes
 *   phone-alarm reminders from rehydrate/scheduling the same way).
 */
object ReminderAlertType {
    const val NOTIFICATION = "notification"
    const val PHONE_ALARM = "phone-alarm"
}
