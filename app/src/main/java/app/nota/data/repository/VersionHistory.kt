package app.nota.data.repository

import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteVersionEntity
import app.nota.data.db.NotaDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Per-note version history (Phase 6, PR #43) — a faithful port of
 * Noma 1.x `versions.ts`.
 *
 * A version is the title + content of a note state that was replaced
 * by a later edit. Snapshots hook the user-edit write paths
 * ([NoteRepository.updateRichBody] / updateBody / updateTitle — the
 * counterparts of 1.x's single `updateNote`) and follow a "settled
 * state" rule: the replaced state only becomes a version once it had
 * rested untouched for [SETTLE_MS], so autosave bursts (a write every
 * ~400 ms while typing) produce nothing, while the state a user
 * paused on is preserved before the next edit lands. History
 * therefore tracks editing sessions, not keystrokes.
 *
 * Two hard rules (1.x, unchanged):
 * - Locked notes have NO history. A snapshot stores the note's own
 *   storage form, so a plaintext version of a note that later gets
 *   locked would defeat the encryption. Nothing is ever snapshotted
 *   while a note carries a lock, and locking purges existing history
 *   (see NoteLockRepository.lockNote). In an unlocked session,
 *   content saves bypass the edit paths entirely (the lock session
 *   re-seals first), and a title-only save still sees the stored
 *   record's lock and no-ops here — ciphertext is never copied into
 *   a version row.
 * - History is local to this device. Versions are not part of the
 *   backup payload and never sync (the versions table is simply not
 *   enumerated there); cross-device safety comes from sync's
 *   note-level conflict copies instead. The one exception is the
 *   guest→account library copy, which moves the rows with the
 *   library on the same device.
 * - Versions store content verbatim — including attachment
 *   references. If an attachment is deleted after a version was
 *   taken, restoring that version brings the reference back with
 *   no file behind it; the renderers degrade gracefully (a file
 *   block shows its stored name, an image block its empty state)
 *   and the block stays deletable. This is 1.x's behaviour too;
 *   copying attachment files into versions was rejected (up to
 *   [MAX_VERSIONS] duplicated image sets per note).
 *
 * Restore lives on [NoteRepository.restoreVersion]: it force-
 * snapshots the current state through here, then writes the
 * version back through the repository's own derivation path.
 */
object VersionHistory {

    /** A replaced state must have rested at least this long to become a version. */
    const val SETTLE_MS = 60_000L

    /** Hard cap on kept versions per note; the oldest are pruned beyond it. */
    const val MAX_VERSIONS = 50

    /** Keeps only the newest [MAX_VERSIONS] rows for a note. */
    private suspend fun pruneVersions(db: NotaDatabase, noteId: String) {
        val dao = db.noteVersionDao()
        val rows = dao.getForNote(noteId)
        if (rows.size <= MAX_VERSIONS) return
        for (row in rows.drop(MAX_VERSIONS)) dao.delete(row)
    }

    private suspend fun insertVersion(db: NotaDatabase, note: NoteEntity) {
        db.noteVersionDao().insert(
            NoteVersionEntity(
                id = UUID.randomUUID().toString(),
                noteId = note.id,
                title = note.title,
                contentHtml = note.contentHtml,
                // The version is timestamped by the state it holds:
                // when that text was current, i.e. the replaced
                // state's own updatedAt.
                createdAt = note.updatedAt,
            ),
        )
        pruneVersions(db, note.id)
    }

    /**
     * Snapshots the note state about to be replaced, if it had
     * settled (see the object doc). No-ops for locked notes and for
     * states younger than [SETTLE_MS]. The caller (the edit paths on
     * [NoteRepository]) only calls this when title or content
     * actually changed.
     */
    suspend fun snapshotIfSettled(
        db: NotaDatabase,
        oldNote: NoteEntity,
        now: Long = System.currentTimeMillis(),
    ) {
        if (oldNote.locked) return
        if (now - oldNote.updatedAt < SETTLE_MS) return
        insertVersion(db, oldNote)
    }

    /**
     * Snapshots unconditionally (restore uses it so a restore is
     * itself undoable even when the current state is fresh). Same
     * lock guard.
     */
    suspend fun forceSnapshot(db: NotaDatabase, note: NoteEntity) {
        if (note.locked) return
        insertVersion(db, note)
    }

    /** All versions of a note, newest first. */
    suspend fun listVersions(db: NotaDatabase, noteId: String): List<NoteVersionEntity> =
        db.noteVersionDao().getForNote(noteId)

    /** All versions of a note, newest first, as they change. */
    fun observeVersions(db: NotaDatabase, noteId: String): Flow<List<NoteVersionEntity>> =
        db.noteVersionDao().observeForNote(noteId)

    /** Deletes every version of one note (permanent note deletion, locking). */
    suspend fun clearVersions(db: NotaDatabase, noteId: String) {
        db.noteVersionDao().deleteForNote(noteId)
    }
}
