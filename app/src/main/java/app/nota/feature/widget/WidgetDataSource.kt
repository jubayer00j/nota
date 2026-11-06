package app.nota.feature.widget

import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.security.AppLockManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 5.3: reads the ACTIVE account's glance facts and maps them
 * through [WidgetStateMapper]. Each load runs against ONE account
 * snapshot — the account id and its database, captured together at
 * the start of the load — so the widget can only ever render one
 * account's library, whole: a switch landing mid-load can never mix
 * two accounts into one render, and a load whose account was
 * switched away from discards its result instead of publishing
 * stale content (the Application-level refresher re-renders for the
 * new account right after a switch anyway).
 */
class WidgetDataSource(
    private val provider: DatabaseProvider,
    private val appLockHidden: suspend () -> Boolean,
) {

    /**
     * Test seam (cf. [DatabaseProvider.forDatabase]): invoked at
     * the two points where an account switch can interleave with a
     * load — between the daily read and the reminder read, and
     * again after the reads, before the result is published — so
     * tests can drive a real switch into those windows
     * deterministically. Always null in production.
     */
    internal var midLoadHook: (suspend () -> Unit)? = null

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
            return hiddenState(today, zone, locale)
        }
        // The account snapshot: id FIRST, then the database (the
        // provider publishes a switch as current-then-id; reading
        // in this order means a switch interleaving the two reads
        // is always caught by the re-check before publishing).
        val accountId = provider.accountId.value
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
        midLoadHook?.invoke()
        val reminderFacts = nextReminderFacts(db, nowMillis)
        midLoadHook?.invoke()
        // A switch that landed anywhere during the load — even
        // after the last read — must not publish the old account's
        // content into the new account's widget. Discard to the
        // safe hidden state; the account-keyed refresher re-renders
        // for the new account immediately after anyway.
        if (provider.accountId.value != accountId) {
            return hiddenState(today, zone, locale)
        }
        return WidgetStateMapper.map(
            today = today,
            zone = zone,
            locale = locale,
            appLockHidden = false,
            todayNote = todayFacts,
            nextReminder = reminderFacts,
        )
    }

    /** The HIDDEN mapping — the only state a load may publish when
     *  it holds nothing it is allowed to show. */
    private fun hiddenState(
        today: LocalDate,
        zone: ZoneId,
        locale: Locale,
    ): WidgetState = WidgetStateMapper.map(
        today = today,
        zone = zone,
        locale = locale,
        appLockHidden = true,
        todayNote = null,
        nextReminder = null,
    )

    /** The earliest enabled, future reminder whose note is live —
     *  spent rows and reminders on trashed/gone notes never surface
     *  (the ReminderRepository.observeRows rule). Reads run against
     *  the load's captured snapshot [db] — never a fresh
     *  [DatabaseProvider.current] resolution (Codex P2, PR #30). */
    private suspend fun nextReminderFacts(
        db: NotaDatabase,
        nowMillis: Long,
    ): WidgetReminderFacts? {
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
