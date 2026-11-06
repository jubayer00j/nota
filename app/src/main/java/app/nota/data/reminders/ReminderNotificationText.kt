package app.nota.data.reminders

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phase 5.1: the notification text rules, ported from 1.x's
 * `reminderNotificationText` / `notificationSnapshotForNote`
 * (src/lib/noma/notifications.ts).
 *
 * Privacy contract: an UNLOCKED note's snapshot is its (trimmed) title
 * plus `Reminder · <time>`; a LOCKED note — or one that can't be read —
 * gets the generic pair only, so the note's title never appears in the
 * notification title, body, or anywhere else the system surfaces it.
 */
object ReminderNotificationText {

    const val GENERIC_TITLE = "Reminder"
    const val GENERIC_TEXT = "You have a note reminder"
    const val UNTITLED = "Untitled note"

    /** 1.x formatReminderTime: short weekday, day, short month,
     *  12-hour clock — e.g. "Fri, 6 Nov, 2:30 PM". */
    fun formatReminderTime(
        triggerAtMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = DateTimeFormatter.ofPattern("EEE, d MMM, h:mm a", locale)
        .format(Instant.ofEpochMilli(triggerAtMillis).atZone(zone))

    /** Reminders-list row date: 1.x formats rows with date-fns
     *  `PPP 'at' p` — "6 November 2026 at 2:30 PM". Java's pattern
     *  letters have no ordinal suffix, so the day renders bare. */
    fun formatListDateTime(
        triggerAtMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = DateTimeFormatter.ofPattern("d MMMM yyyy 'at' h:mm a", locale)
        .format(Instant.ofEpochMilli(triggerAtMillis).atZone(zone))

    /**
     * The (title, text) pair a fired reminder posts. [noteTitle] null
     * (note unreadable) or [noteLocked] true → the generic pair.
     */
    fun snapshot(
        noteTitle: String?,
        noteLocked: Boolean,
        triggerAtMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): Pair<String, String> {
        if (noteLocked || noteTitle == null) return GENERIC_TITLE to GENERIC_TEXT
        val title = noteTitle.trim().ifBlank { UNTITLED }
        return title to "Reminder · ${formatReminderTime(triggerAtMillis, zone, locale)}"
    }
}
