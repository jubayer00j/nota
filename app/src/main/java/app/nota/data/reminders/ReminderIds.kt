package app.nota.data.reminders

/**
 * Phase 5.1: stable integer ids derived from a reminder's string id —
 * used as the AlarmManager PendingIntent request code and as the
 * notification id, so arming, cancelling and notifying always address
 * the same system slots across process restarts.
 *
 * The hash is 1.x's `reminderNotificationId` ported exactly (djb2-xor
 * over the id, unsigned, mod 2^31-1, never 0): the same reminder id
 * yields the same integer on both platforms.
 */
object ReminderIds {

    fun notificationId(reminderId: String): Int {
        var hash = 5381
        for (ch in reminderId) {
            // Int overflow in Kotlin wraps to 32 bits, matching the
            // int32 coercion JS applies inside its hash loop.
            hash = (hash * 33) xor ch.code
        }
        val unsigned = hash.toLong() and 0xFFFF_FFFFL
        val result = (unsigned % 2_147_483_647L).toInt()
        return if (result == 0) 1 else result
    }

    /** PendingIntent request code for a reminder's alarm — the same
     *  derivation as the notification id (1.x uses one hash for both). */
    fun requestCode(reminderId: String): Int = notificationId(reminderId)
}
