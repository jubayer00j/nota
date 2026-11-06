package app.nota.feature.widget

import app.nota.data.db.DatabaseProvider
import app.nota.data.security.AppLockManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 5.3: reads the ACTIVE account's glance facts and maps them
 * through [WidgetStateMapper]. All reads resolve through
 * [DatabaseProvider.current] at call time, so the widget can only
 * ever see the active account's library — the same scoping rule the
 * repositories follow, and a switch swaps what the next render
 * shows (the Application-level refresher re-renders on the switch).
 */
class WidgetDataSource(
    private val provider: DatabaseProvider,
    private val appLockHidden: suspend () -> Boolean,
) {

    suspend fun load(
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): WidgetState {
        val hidden = appLockHidden()
        // Privacy first: under the hidden posture the database is
        // never even read — there is nothing the render may show
        // from it, so no note data crosses into the widget at all.
        if (hidden) {
            return WidgetStateMapper.map(
                today = today,
                zone = zone,
                locale = locale,
                appLockHidden = true,
                todayNote = null,
                nextReminder = null,
            )
        }
        val db = provider.current
        val daily = db.noteDao().observeDaily(today.toString()).first()
            .firstOrNull { it.trashedAt == null }
        val todayFacts = daily?.let {
            WidgetTodayFacts(
                noteId = it.id,
                title = it.title,
                plainText = it.plainText,
                locked = it.locked,
            )
        }
        val reminderFacts = nextReminderFacts(nowMillis)
        return WidgetStateMapper.map(
            today = today,
            zone = zone,
            locale = locale,
            appLockHidden = false,
            todayNote = todayFacts,
            nextReminder = reminderFacts,
        )
    }

    /** The earliest enabled, future reminder whose note is live —
     *  spent rows and reminders on trashed/gone notes never surface
     *  (the ReminderRepository.observeRows rule). */
    private suspend fun nextReminderFacts(nowMillis: Long): WidgetReminderFacts? {
        val db = provider.current
        val upcoming = db.reminderDao().getAll()
            .filter { it.enabled && it.triggerAt >= nowMillis }
            .sortedBy { it.triggerAt }
        for (reminder in upcoming) {
            val note = db.noteDao().getById(reminder.noteId) ?: continue
            if (note.trashedAt != null) continue
            return WidgetReminderFacts(
                triggerAt = reminder.triggerAt,
                noteTitle = note.title,
                noteLocked = note.locked,
            )
        }
        return null
    }
}

/**
 * The widget's App Lock posture, from [AppLockManager]: content is
 * hidden whenever App Lock is ENABLED (1.x's generic-snapshot rule —
 * the gate's promise is that content waits behind it, unlocked
 * session or not) and whenever the persisted settings cannot be
 * read. A cold widget process may render before the manager's first
 * settings emission lands; it waits briefly for readiness and fails
 * CLOSED on a timeout — the widget must never guess "lock off".
 */
object WidgetLockPosture {

    private const val READY_TIMEOUT_MS = 2_500L

    suspend fun hidden(manager: AppLockManager): Boolean {
        val ready = withTimeoutOrNull(READY_TIMEOUT_MS) {
            manager.ready.first { it }
        }
        if (ready != true) return true
        if (manager.settingsUnreadable.value) return true
        return manager.settings.value.isEnabled
    }
}
