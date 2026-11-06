package app.nota.feature.widget

import app.nota.data.reminders.ReminderNotificationText
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phase 5.3: the Glance widget's render model — a pure seam in the
 * 1.x `buildWidgetSnapshot` shape (widget-snapshot.ts): ALL of the
 * widget's privacy policy lives here, in one place, and it is plain
 * JVM logic so the policy is unit-testable without a launcher.
 *
 * The widget sits on the home screen, OUTSIDE App Lock, all day. The
 * rules, mirroring 1.x's snapshot policy and Nota's own reminder
 * notification contract:
 *
 *  - App Lock enabled (or its settings unreadable — fail closed):
 *    the HIDDEN state. No note title, no excerpt, no reminder text;
 *    the widget shows only brand, date, a generic line and the
 *    capture buttons. 1.x stages a "generic" snapshot in exactly
 *    this case, so even its file on disk holds no titles.
 *  - A per-note-locked daily note contributes NOTHING — not its
 *    title, not a placeholder excerpt. The Today area shows the
 *    generic locked label and its tap falls back to the today door
 *    (never a note-open carrying the locked note's id), exactly as
 *    1.x nulls a locked daily note out of its Today row.
 *  - A reminder whose note is locked (or unreadable) renders the
 *    generic reminder text, never the note's title — the
 *    [ReminderNotificationText] rule, one surface over.
 *  - Only then does content flow: the daily note's title + a short
 *    plain-text excerpt (a locked note's plainText is empty under
 *    the seal anyway; the mapper masks regardless — defence in
 *    depth, so no caller can leak by passing raw fields).
 */

/** The daily note's display facts, as read from the active account. */
data class WidgetTodayFacts(
    val noteId: String,
    val title: String,
    val plainText: String,
    val locked: Boolean,
)

/** The next upcoming reminder's display facts. */
data class WidgetReminderFacts(
    val triggerAt: Long,
    /** Null when the reminder's note could not be read. */
    val noteTitle: String?,
    val noteLocked: Boolean,
)

enum class WidgetTodayKind {
    /** A readable daily note exists: title + excerpt are safe to show. */
    CONTENT,

    /** No daily note for today yet: the invitation state. */
    EMPTY,

    /** A daily note exists but is per-note locked: generic label only. */
    LOCKED,

    /** App Lock posture hides everything: generic brand state. */
    HIDDEN,
}

data class WidgetState(
    /** "Friday, 6 November" — the glance's date line. */
    val dateLabel: String,
    val todayKind: WidgetTodayKind,
    /** Title line for the Today area; null only in a state with no line. */
    val todayTitle: String?,
    /** Excerpt line for the Today area; null when there is nothing safe. */
    val todayExcerpt: String?,
    /** The note the Today area opens — set ONLY in the CONTENT state. */
    val todayNoteId: String?,
    /** One-line next-reminder summary; null when none may be shown. */
    val reminderLine: String?,
)

object WidgetStateMapper {

    const val LOCKED_LABEL = "Locked note"
    const val EMPTY_TITLE = "No note for today yet"
    const val EMPTY_EXCERPT = "Tap to start today's note"
    const val HIDDEN_TITLE = "Today's note is private"
    const val HIDDEN_EXCERPT = "Unlock Nota to see it here"
    const val UNTITLED = "Untitled note"
    const val MAX_EXCERPT_CHARS = 120

    /** Whitespace-collapsed, length-capped excerpt; null when blank. */
    fun excerpt(plainText: String): String? {
        val collapsed = plainText.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return null
        if (collapsed.length <= MAX_EXCERPT_CHARS) return collapsed
        return collapsed.substring(0, MAX_EXCERPT_CHARS).trimEnd() + "…"
    }

    fun map(
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        appLockHidden: Boolean,
        todayNote: WidgetTodayFacts?,
        nextReminder: WidgetReminderFacts?,
    ): WidgetState {
        val dateLabel = DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)
            .format(today)
        if (appLockHidden) {
            return WidgetState(
                dateLabel = dateLabel,
                todayKind = WidgetTodayKind.HIDDEN,
                todayTitle = HIDDEN_TITLE,
                todayExcerpt = HIDDEN_EXCERPT,
                todayNoteId = null,
                reminderLine = null,
            )
        }
        val reminderLine = nextReminder?.let { reminder ->
            val time = ReminderNotificationText.formatReminderTime(
                reminder.triggerAt, zone, locale,
            )
            if (reminder.noteLocked || reminder.noteTitle == null) {
                "${ReminderNotificationText.GENERIC_TEXT} · $time"
            } else {
                val title = reminder.noteTitle.trim().ifBlank { UNTITLED }
                "$title · $time"
            }
        }
        return when {
            todayNote == null -> WidgetState(
                dateLabel = dateLabel,
                todayKind = WidgetTodayKind.EMPTY,
                todayTitle = EMPTY_TITLE,
                todayExcerpt = EMPTY_EXCERPT,
                todayNoteId = null,
                reminderLine = reminderLine,
            )
            todayNote.locked -> WidgetState(
                dateLabel = dateLabel,
                todayKind = WidgetTodayKind.LOCKED,
                todayTitle = LOCKED_LABEL,
                todayExcerpt = null,
                todayNoteId = null,
                reminderLine = reminderLine,
            )
            else -> WidgetState(
                dateLabel = dateLabel,
                todayKind = WidgetTodayKind.CONTENT,
                todayTitle = todayNote.title.trim().ifBlank { UNTITLED },
                todayExcerpt = excerpt(todayNote.plainText),
                todayNoteId = todayNote.noteId,
                reminderLine = reminderLine,
            )
        }
    }
}
