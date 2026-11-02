package app.nota.data.auth

import androidx.room.withTransaction
import app.nota.data.backup.rebuildLibraryIndexes
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import kotlinx.coroutines.flow.first

/** What a guest→account copy moved (user-visible summary counts). */
data class TransferCounts(
    val notes: Int = 0,
    val folders: Int = 0,
    val tags: Int = 0,
    val attachments: Int = 0,
) {
    val isEmpty: Boolean get() = notes == 0 && folders == 0 && tags == 0 && attachments == 0
}

/**
 * The guest→account library copy (Phase 3a) — the move Jubayer's real
 * library depends on: his Phase 2 crossover restore lives in the
 * GUEST database, and on first sign-in Nota offers to bring it into
 * the account.
 *
 * Semantics, chosen for safety above cleverness:
 * - **Copy, never move.** The guest database is only ever READ here.
 *   After a copy, the guest library is byte-for-byte what it was —
 *   signing back out lands on the identical library.
 * - **Insert-if-absent per entity id.** Ids are preserved verbatim
 *   (folders, tags, notes, note↔tag links, attachments, reminders,
 *   saved searches, note versions), so a copy can be re-run — e.g.
 *   after an interrupted first attempt — without duplicating or
 *   overwriting anything the account already has. Existing account
 *   rows always win; nothing in the account is ever replaced by the
 *   guest copy.
 * - **Attachment binaries need no copying.** Files live in the shared
 *   filesDir/attachments/<noteId>/ root keyed by the same note ids
 *   the copy preserves — the account rows point at the very files the
 *   guest rows point at.
 * - **Indexes are rebuilt, not copied.** FTS rows and backlink edges
 *   are derived state; the shared [rebuildLibraryIndexes] re-derives
 *   them for the target library (locked notes keep their title-only
 *   FTS discipline there).
 * - Templates are NOT part of the copy: Nota keeps them as global
 *   device furniture (filesDir/templates.json), the same way backups
 *   treat them as a separate payload partition.
 */
class GuestLibraryTransfer(
    private val provider: DatabaseProvider,
) {
    /** Notes in the guest library (the offer's gate). Reads the guest
     *  database directly when an account is active. */
    suspend fun guestNoteCount(): Int {
        val guest = provider.openAdditional(NotaDatabase.GUEST_ACCOUNT)
        return try {
            guest.noteDao().getAll().size
        } finally {
            provider.closeAdditional(guest)
        }
    }

    /**
     * Copies the guest library into the CURRENTLY ACTIVE account
     * database. Callers must have switched the provider to the target
     * account first ([AuthManager] does this as part of sign-in).
     */
    suspend fun copyGuestIntoCurrentAccount(): TransferCounts {
        check(!provider.isGuest) { "Guest copy needs an active account database." }
        val source = provider.openAdditional(NotaDatabase.GUEST_ACCOUNT)
        val target = provider.current
        try {
            var notes = 0
            var folders = 0
            var tags = 0
            var attachments = 0
            target.withTransaction {
                val folderDao = target.folderDao()
                for (folder in source.folderDao().getAll()) {
                    if (folderDao.getById(folder.id) == null) {
                        folderDao.upsert(folder)
                        folders++
                    }
                }
                val tagDao = target.tagDao()
                for (tag in source.tagDao().getAllTags()) {
                    if (tagDao.getById(tag.id) == null) {
                        tagDao.upsert(tag)
                        tags++
                    }
                }
                val noteDao = target.noteDao()
                for (note in source.noteDao().getAll()) {
                    if (noteDao.getById(note.id) == null) {
                        noteDao.upsert(note)
                        notes++
                    }
                }
                // Links only land when both ends exist in the target
                // (a link to a note the account already had under a
                // different copy state must never dangle); the link
                // insert itself is IGNORE-on-conflict.
                val sourceRefs = source.tagDao().observeAllCrossRefs().first()
                for (ref in sourceRefs) {
                    if (noteDao.getById(ref.noteId) != null && tagDao.getById(ref.tagId) != null) {
                        tagDao.link(ref)
                    }
                }
                val attachmentDao = target.attachmentDao()
                for (attachment in source.attachmentDao().getAll()) {
                    if (attachmentDao.getById(attachment.id) == null) {
                        attachmentDao.upsert(attachment)
                        attachments++
                    }
                }
                val reminderDao = target.reminderDao()
                for (reminder in source.reminderDao().getAll()) {
                    if (reminderDao.getById(reminder.id) == null) {
                        reminderDao.upsert(reminder)
                    }
                }
                val versionDao = target.noteVersionDao()
                val existingVersionIds = versionDao.getAll().map { it.id }.toSet()
                for (version in source.noteVersionDao().getAll()) {
                    if (version.id !in existingVersionIds) versionDao.insert(version)
                }
                val searchDao = target.savedSearchDao()
                val existingSearchIds = searchDao.observeAll().first().map { it.id }.toSet()
                for (search in source.savedSearchDao().observeAll().first()) {
                    if (search.id !in existingSearchIds) searchDao.upsert(search)
                }
            }
            rebuildLibraryIndexes(target)
            return TransferCounts(
                notes = notes,
                folders = folders,
                tags = tags,
                attachments = attachments,
            )
        } finally {
            provider.closeAdditional(source)
        }
    }
}

/**
 * The pure offer rule (Phase 3a): offer the guest→account copy when —
 * and only when — the guest library actually has notes AND this
 * account has no recorded outcome yet. Kept side-effect-free so the
 * rule is unit-testable without Firebase or a database.
 */
object GuestMovePolicy {
    const val OUTCOME_COPIED = "copied"
    const val OUTCOME_DECLINED = "declined"

    fun shouldOffer(guestNoteCount: Int, recordedOutcome: String?): Boolean =
        guestNoteCount > 0 && recordedOutcome == null
}
