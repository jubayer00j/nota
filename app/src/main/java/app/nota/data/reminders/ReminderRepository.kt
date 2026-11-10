package app.nota.data.reminders

import androidx.room.withTransaction
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.ReminderEntity
import app.nota.data.sync.EntityHash
import app.nota.data.sync.SyncStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Phase 5.1: the reminder write/read seam over [ReminderEntity].
 *
 * The 5.1 contract is ONE pending reminder per note — the same shape
 * 1.x's setNoteReminder enforces (it keeps the first row as the
 * primary, updates it in place so the id — and therefore the alarm /
 * notification ids derived from it — stays stable, and deletes any
 * extras) and the same shape the backup crossover mirrors in
 * `BxNote.reminderAt` (the earliest ENABLED reminder's triggerAt).
 * Because every mutation goes through the Room tables the backup
 * layer reads, a backup taken after any UI-path set/remove is correct
 * with no extra wiring.
 *
 * Status model (matching the shipped crossover): `enabled = true` is
 * 1.x's "pending"; `enabled = false` is the spent state — a fired
 * one-shot, a completed reminder, a dismissed one — which the export
 * maps to BxReminder "dismissed" and which clears reminderAt.
 * repeatRule is always written null: 1.x backups carry no repeat, so
 * repeats stay dormant in 5.1.
 */
class ReminderRepository(private val provider: DatabaseProvider) {

    private val db get() = provider.current
    private fun dao() = db.reminderDao()

    /** One Reminders-screen row: the reminder plus its note's display
     *  facts. Locked notes show their title here (the list lives
     *  in-app, behind App Lock — 1.x's reminders view does the same);
     *  reminders whose note is trashed or gone are not listed. */
    data class ReminderRow(
        val reminder: ReminderEntity,
        val noteTitle: String,
        val noteLocked: Boolean,
    )

    fun observeRows(): Flow<List<ReminderRow>> = combine(
        dao().observeAll(),
        db.noteDao().observeAllNotes(),
    ) { reminders, notes ->
        val liveNotes = notes.filter { it.trashedAt == null }.associateBy { it.id }
        reminders.mapNotNull { reminder ->
            liveNotes[reminder.noteId]?.let { note ->
                ReminderRow(reminder, note.title, note.locked)
            }
        }
    }

    /** Upcoming pending reminders (enabled, triggerAt ascending) —
     *  both alert types; scheduling filters phone-alarms out itself. */
    fun observeUpcoming(): Flow<List<ReminderEntity>> = dao().observeEnabled()

    /** The note's pending reminder (the 5.1 contract: at most one),
     *  or null. Drives the editor's overflow label + dialog. */
    fun observePendingForNote(noteId: String): Flow<ReminderEntity?> =
        dao().observeForNote(noteId).map { rows -> rows.firstOrNull { it.enabled } }

    suspend fun pendingForNote(noteId: String): ReminderEntity? =
        dao().getForNote(noteId).firstOrNull { it.enabled }

    suspend fun getById(id: String): ReminderEntity? = dao().getById(id)

    /**
     * Sets (or replaces) the note's pending reminder, 1.x-style: the
     * existing primary row is updated in place (same id, back to
     * pending), extras are deleted with sync tombstones, and a fresh
     * row is created only when the note has none. Returns the saved
     * row. Arming is the scheduler's job — callers follow with
     * ReminderScheduler.schedule / the reconciler re-arms on the
     * table change either way.
     */
    suspend fun setReminder(
        noteId: String,
        triggerAt: Long,
        alertType: String,
        nowMillis: Long = System.currentTimeMillis(),
        tone: AlarmToneSettings = AlarmToneSettings(),
    ): ReminderEntity {
        require(alertType == ReminderAlertType.NOTIFICATION || alertType == ReminderAlertType.PHONE_ALARM) {
            "Unknown reminder alertType: $alertType"
        }
        // The tone fields are device-local extras (PR #45): they are
        // stored on the row like alertType but never cross into a
        // backup or sync payload (the BxReminder crossover has no
        // tone fields, so a restored reminder comes back with the
        // entity defaults — system tone, vibrate on).
        val storedTone = tone.normalised()
        return db.withTransaction {
            val existing = dao().getForNote(noteId)
            val primary = existing.firstOrNull()
            val syncStore = SyncStore(db)
            existing.drop(1).forEach { extra ->
                dao().delete(extra)
                syncStore.recordTombstone(EntityHash.ENTITY_REMINDER, extra.id, nowMillis)
            }
            val saved = if (primary != null) {
                primary.copy(
                    triggerAt = triggerAt,
                    repeatRule = null,
                    enabled = true,
                    alertType = alertType,
                    alarmToneMode = storedTone.mode,
                    alarmToneUri = storedTone.uri,
                    alarmToneName = storedTone.name,
                    alarmVibrate = storedTone.vibrate,
                )
            } else {
                ReminderEntity(
                    id = UUID.randomUUID().toString(),
                    noteId = noteId,
                    triggerAt = triggerAt,
                    repeatRule = null,
                    enabled = true,
                    createdAt = nowMillis,
                    alertType = alertType,
                    alarmToneMode = storedTone.mode,
                    alarmToneUri = storedTone.uri,
                    alarmToneName = storedTone.name,
                    alarmVibrate = storedTone.vibrate,
                )
            }
            dao().upsert(saved)
            saved
        }
    }

    /** Removes every reminder row for the note (1.x deleteNoteReminder
     *  parity), recording a sync tombstone per row. */
    suspend fun removeForNote(noteId: String, nowMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val syncStore = SyncStore(db)
            dao().getForNote(noteId).forEach { row ->
                dao().delete(row)
                syncStore.recordTombstone(EntityHash.ENTITY_REMINDER, row.id, nowMillis)
            }
        }
    }

    /** Deletes a single reminder row (Reminders-screen Delete). */
    suspend fun deleteReminder(reminder: ReminderEntity, nowMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            dao().delete(reminder)
            SyncStore(db).recordTombstone(EntityHash.ENTITY_REMINDER, reminder.id, nowMillis)
        }
    }

    /**
     * Complete / reopen (1.x updateReminderStatus): completing spends
     * the reminder (enabled = false, so reminderAt clears); reopening
     * makes it pending again. Arming stays the scheduler's call.
     */
    suspend fun setEnabled(reminder: ReminderEntity, enabled: Boolean) {
        if (reminder.enabled != enabled) {
            dao().upsert(reminder.copy(enabled = enabled))
        }
    }

    /** A fired one-shot leaves the pending state (the alarm receiver's
     *  state transition): enabled = false, so it stops showing as
     *  pending and the note's reminderAt clears. */
    suspend fun markFired(reminder: ReminderEntity) {
        if (reminder.enabled) {
            dao().upsert(reminder.copy(enabled = false))
        }
    }
}
