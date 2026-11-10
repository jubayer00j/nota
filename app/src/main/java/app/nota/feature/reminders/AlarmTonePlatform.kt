package app.nota.feature.reminders

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Phase 6 extras (PR #45): the platform half of 1.x's phone-alarm
 * tone handling (src/lib/noma/phone-alarm.ts) — accessibility
 * validation and display-name resolution for a stored tone URI.
 *
 * A stored URI can go stale: the file is deleted, the SD card is
 * removed, or the picked document's grant is revoked. 1.x checks
 * readability when the reminder dialog opens and again right
 * before the Clock hand-off, resetting to the system default (with
 * a notice) when the tone is gone; [ReminderDialog] does the same
 * with these helpers.
 */
object AlarmTonePlatform {

    /** True when [uriString] can still be opened for reading. */
    fun isToneAccessible(context: Context, uriString: String): Boolean =
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriString))
                ?.use { true } ?: false
        }.getOrDefault(false)

    /**
     * Best display name for a tone URI, 1.x's order: the content
     * provider's DISPLAY_NAME, then the ringtone's own title, then
     * the last path segment. Null when nothing resolves.
     */
    fun toneDisplayName(context: Context, uriString: String): String? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        val fromProvider = runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull()
        if (!fromProvider.isNullOrBlank()) return fromProvider
        val fromRingtone = runCatching {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)
        }.getOrNull()
        if (!fromRingtone.isNullOrBlank()) return fromRingtone
        return uri.lastPathSegment?.takeIf { it.isNotBlank() }
    }
}
