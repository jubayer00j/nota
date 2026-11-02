package app.nota.data.auth

import android.content.Context
import androidx.room.withTransaction
import app.nota.data.backup.rebuildLibraryIndexes
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import java.io.File
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
 * - **Attachment binaries are copied to account-local paths**
 *   (Phase 3a, Codex P1). Files live under filesDir/attachments/, but
 *   they are NOT immutable: per-note lock/unlock rewrites them in
 *   place (raw bytes ↔ AES-GCM envelope), and attachment deletion
 *   removes them. If the account row pointed at the guest's file,
 *   locking a note in one library would silently corrupt the other's
 *   copy of that attachment — the guest library must stay intact in
 *   substance, not just in rows. So each copied attachment's bytes
 *   are duplicated under `attachments/<noteId>~<account>/…` and the
 *   account row points there; the two libraries' files then evolve
 *   independently. (The cross-account reconcile sweep already keeps
 *   every path any account's rows reference, so the copies are safe
 *   from housekeeping.)
 * - **Indexes are rebuilt, not copied.** FTS rows and backlink edges
 *   are derived state; the shared [rebuildLibraryIndexes] re-derives
 *   them for the target library (locked notes keep their title-only
 *   FTS discipline there).
 * - Templates are NOT part of the copy: Nota keeps them as global
 *   device furniture (filesDir/templates.json), the same way backups
 *   treat them as a separate payload partition.
 */
class GuestLibraryTransfer(
    context: Context,
    private val provider: DatabaseProvider,
    private val prefs: app.nota.data.preferences.PreferencesRepository,
) {
    private val filesDir: File = context.filesDir
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
            // Phase 1: read the whole guest library OUTSIDE any
            // transaction. (Phase 3a CI finding: reading a second
            // database from inside the target's withTransaction is
            // unreliable — the attachment read came back empty under
            // Robolectric while the identical read outside the
            // transaction saw the row. Read-then-write is also the
            // better shape: the write transaction stays short and
            // single-database.)
            val sourceFolders = source.folderDao().getAll()
            val sourceTags = source.tagDao().getAllTags()
            val sourceNotes = source.noteDao().getAll()
            val sourceRefs = source.tagDao().observeAllCrossRefs().first()
            val sourceAttachments = source.attachmentDao().getAll()
            val sourceReminders = source.reminderDao().getAll()
            val sourceVersions = source.noteVersionDao().getAll()
            val sourceSearches = source.savedSearchDao().observeAll().first()

            // Phase 1.5: duplicate the attachment BINARIES that will
            // actually be inserted, under account-local paths (see
            // the class doc, Codex P1) — file IO stays outside the
            // write transaction; the in-transaction insert-if-absent
            // check remains the authority.
            val existingAttachmentIds = target.attachmentDao().getAll().map { it.id }.toSet()
            val copiedAttachments = sourceAttachments
                .filter { it.id !in existingAttachmentIds }
                .map { accountLocalCopy(it, provider.accountId.value) }

            // Phase 2: insert-if-absent into the target, one
            // transaction, target DAOs only.
            var notes = 0
            var folders = 0
            var tags = 0
            var attachments = 0
            val insertedLockedNoteIds = mutableListOf<String>()
            target.withTransaction {
                val folderDao = target.folderDao()
                for (folder in sourceFolders) {
                    if (folderDao.getById(folder.id) == null) {
                        folderDao.upsert(folder)
                        folders++
                    }
                }
                val tagDao = target.tagDao()
                for (tag in sourceTags) {
                    if (tagDao.getById(tag.id) == null) {
                        tagDao.upsert(tag)
                        tags++
                    }
                }
                val noteDao = target.noteDao()
                for (note in sourceNotes) {
                    if (noteDao.getById(note.id) == null) {
                        noteDao.upsert(note)
                        notes++
                        if (note.lockRecordJson != null) insertedLockedNoteIds += note.id
                    }
                }
                // Links only land when both ends exist in the target
                // (a link to a note the account already had under a
                // different copy state must never dangle); the link
                // insert itself is IGNORE-on-conflict.
                for (ref in sourceRefs) {
                    if (noteDao.getById(ref.noteId) != null && tagDao.getById(ref.tagId) != null) {
                        tagDao.link(ref)
                    }
                }
                val attachmentDao = target.attachmentDao()
                for (attachment in copiedAttachments) {
                    if (attachmentDao.getById(attachment.id) == null) {
                        attachmentDao.upsert(attachment)
                        attachments++
                    }
                }
                val reminderDao = target.reminderDao()
                for (reminder in sourceReminders) {
                    if (reminderDao.getById(reminder.id) == null) {
                        reminderDao.upsert(reminder)
                    }
                }
                val versionDao = target.noteVersionDao()
                val existingVersionIds = versionDao.getAll().map { it.id }.toSet()
                for (version in sourceVersions) {
                    if (version.id !in existingVersionIds) versionDao.insert(version)
                }
                val searchDao = target.savedSearchDao()
                val existingSearchIds = searchDao.observeAll().first().map { it.id }.toSet()
                for (search in sourceSearches) {
                    if (search.id !in existingSearchIds) searchDao.upsert(search)
                }
            }
            // Phase 2.5: per-note biometric copies follow their notes
            // (Codex re-review). A copied locked note's lock record
            // still says "biometric enabled", and since Phase 3a the
            // sealed NEK copies are account-scoped preferences — the
            // Room copy alone would leave the account's unlock screen
            // offering a fingerprint that can never succeed. The
            // sealed blob stays valid verbatim (same NEK, device-bound
            // Keystore seal), so it is copied into the target scope;
            // the guest's own entry is left in place for the guest
            // library's copy of the note.
            val targetAccountId = provider.accountId.value
            for (noteId in insertedLockedNoteIds) {
                val packed = prefs.getNoteLockBiometricCopy(
                    app.nota.data.preferences.PreferencesRepository.GUEST_ACCOUNT_ID,
                    noteId,
                )
                if (packed != null) {
                    prefs.setNoteLockBiometricCopy(targetAccountId, noteId, packed)
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

    /**
     * Duplicates one attachment's binary under an account-local path
     * and returns the row pointing at the copy. A missing source file
     * (a row whose binary is already gone) copies as-is — there is
     * nothing on disk for the two libraries to fight over. A failed
     * duplication ABORTS the transfer (Codex re-review): it runs in
     * phase 1.5, before a single row is written, so aborting leaves
     * the target untouched and the copy safely retryable — whereas
     * falling back to the guest's path would reintroduce exactly the
     * shared-file corruption the account-local copy exists to prevent.
     */
    private fun accountLocalCopy(
        source: AttachmentEntity,
        targetAccountId: String,
    ): AttachmentEntity {
        val sourceFile = File(filesDir, source.localPath)
        if (!sourceFile.isFile) return source
        val storedName = source.localPath.substringAfterLast('/')
        val newPath = "attachments/${source.noteId}~${accountPathSuffix(targetAccountId)}/$storedName"
        val dest = File(filesDir, newPath)
        dest.parentFile?.mkdirs()
        sourceFile.inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return source.copy(localPath = newPath)
    }

    private fun accountPathSuffix(accountId: String): String =
        accountId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(48)
            .ifEmpty { "account" }
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
