package app.nota.data.repository

import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.AttachmentDao
import app.nota.data.db.NoteEntity
import app.nota.data.html.NoteDocument
import app.nota.data.security.NoteLockBiometricStore
import app.nota.data.security.NoteLockCrypto
import app.nota.data.security.NoteLockMethod
import app.nota.data.security.NoteLockRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Outcome of one unlock attempt (1.x `UnlockResult`). */
sealed interface NoteUnlockResult {
    data class Ok(val nek: ByteArray) : NoteUnlockResult {
        override fun equals(other: Any?): Boolean =
            other is Ok && nek.contentEquals(other.nek)

        override fun hashCode(): Int = nek.contentHashCode()
    }

    data class Error(val message: String, val lockoutSeconds: Long? = null) : NoteUnlockResult
}

/** A locked note's decrypted contents, held in memory for a session only. */
data class DecryptedNoteData(
    /** Decrypted note body HTML. */
    val contentHtml: String,
    /** Decrypted attachment payload bytes by attachment id. */
    val attachmentBytes: Map<String, ByteArray>,
    /** Decrypted attachment data URLs by attachment id (the payload form). */
    val attachmentDataUrls: Map<String, String>,
)

/**
 * Per-note lock flows (Phase 2b) — a port of 1.x `note-lock.ts` onto
 * Nota's repository/attachment-store seams:
 *
 * - Body + attachment sealing is REAL encryption (AES-256-GCM under a
 *   per-note key, see NoteLockCrypto), not a UI barrier.
 * - Note writes go through [NoteRepository.upsert] so FTS (title-only
 *   while locked) and backlink edges (cleared while locked) stay
 *   consistent; attachment file writes go through [AttachmentStore]'s
 *   mutex-disciplined primitives.
 * - Attempt bookkeeping mirrors 1.x exactly: lockout is checked BEFORE
 *   the slot, failed attempts persist through the record JSON, and the
 *   lockout deadline is only overwritten when the new duration is > 0.
 * - Version history: nothing writes `note_versions` rows anywhere in
 *   Nota today (the DAO insert has no callers), so there is no plaintext
 *   history to purge on lock — 1.x's clearVersions has no counterpart
 *   yet; when versions land, lockNote must purge them.
 */
class NoteLockRepository(
    private val noteRepository: NoteRepository,
    private val attachmentDao: AttachmentDao,
    private val attachmentStore: AttachmentStore,
    private val biometricStore: NoteLockBiometricStore,
) {
    /** Serialises unlock attempts so the persisted failed-attempt
     *  counter can never lose an increment to a concurrent submission. */
    private val attemptMutex = Mutex()

    fun recordFor(note: NoteEntity): NoteLockRecord? = NoteLockRecord.parse(note.lockRecordJson)

    private suspend fun persistRecord(note: NoteEntity, record: NoteLockRecord): NoteEntity {
        val updated = note.copy(lockRecordJson = record.toJson())
        noteRepository.upsert(updated)
        return updated
    }

    // ------------------------------------------------------------ lock

    /**
     * Locks a note (1.x `lockNote`): generates its NEK, wraps it in the
     * chosen credential's slot, seals the body and every attachment.
     * Fingerprint can be enabled alongside — never instead of — the
     * credential; the record's biometric flag is whether the device copy
     * actually stored.
     */
    suspend fun lockNote(
        noteId: String,
        method: NoteLockMethod,
        canonicalSecret: String,
        enableBiometric: Boolean = false,
    ): NoteEntity {
        val note = noteRepository.getById(noteId) ?: throw IllegalStateException("Note not found.")
        check(!note.locked && note.lockRecordJson == null) { "This note is already locked." }
        require(NoteLockCrypto.isValidCanonicalSecret(method, canonicalSecret)) {
            when (method) {
                NoteLockMethod.PASSWORD -> "Password must be at least 8 characters."
                NoteLockMethod.PIN -> "PIN must be 4-6 digits."
                NoteLockMethod.PATTERN -> "Pattern must connect at least 4 dots."
            }
        }
        val nek = NoteLockCrypto.generateNoteKey()
        try {
            val slot = withContext(Dispatchers.Default) {
                NoteLockCrypto.wrapNoteKey(nek, method, canonicalSecret)
            }
            val encryptedContent = NoteLockCrypto.encryptText(nek, note.contentHtml)
            sealAttachments(noteId, nek, onlyPlaintext = true)

            var record = NoteLockRecord(
                v = 1,
                slots = mapOf(method to slot),
                biometric = false,
                failedAttempts = 0,
                lockoutUntil = null,
                lockedAt = System.currentTimeMillis(),
            )
            if (enableBiometric) {
                record = record.copy(biometric = biometricStore.storeCopy(noteId, nek))
            }
            val locked = note.copy(
                contentHtml = encryptedContent,
                plainText = "",
                locked = true,
                lockRecordJson = record.toJson(),
                updatedAt = System.currentTimeMillis(),
            )
            noteRepository.upsert(locked)
            return noteRepository.getById(noteId) ?: locked
        } finally {
            nek.fill(0)
        }
    }

    // ------------------------------------------------------------ unlock

    /**
     * Verifies one credential against the note's slots, enforcing
     * lockout (1.x `verifyNoteCredential`, same check order and error
     * strings). [canonicalSecret] is the canonical 1.x form.
     */
    suspend fun verifyCredential(
        note: NoteEntity,
        method: NoteLockMethod,
        canonicalSecret: String,
    ): NoteUnlockResult = attemptMutex.withLock {
        val fresh = noteRepository.getById(note.id) ?: return@withLock NoteUnlockResult.Error("Note not found.")
        val lock = recordFor(fresh) ?: return@withLock NoteUnlockResult.Error("This note is not locked.")

        val lockout = lock.lockoutSeconds()
        if (lockout > 0) {
            return@withLock NoteUnlockResult.Error(
                "Too many attempts. Try again in ${lockout}s.",
                lockoutSeconds = lockout,
            )
        }
        val slot = lock.slots[method] ?: return@withLock NoteUnlockResult.Error(
            "${method.label} isn't set up for this note.",
        )
        val nek = withContext(Dispatchers.Default) {
            NoteLockCrypto.unwrapNoteKey(slot, method, canonicalSecret)
        }
        if (nek != null) {
            persistRecord(fresh, lock.withResetAttempts())
            return@withLock NoteUnlockResult.Ok(nek)
        }
        val (next, lockoutSeconds) = lock.withFailedAttempt()
        persistRecord(fresh, next)
        if (lockoutSeconds > 0) {
            NoteUnlockResult.Error(
                "Incorrect ${method.label}. Locked out for ${lockoutSeconds}s.",
                lockoutSeconds = lockoutSeconds,
            )
        } else {
            val left = 5 - next.failedAttempts
            NoteUnlockResult.Error(
                "Incorrect ${method.label}. $left attempt${if (left == 1) "" else "s"} left before lockout.",
            )
        }
    }

    /**
     * Releases the note key from the on-device fingerprint copy (1.x
     * `unlockNoteWithBiometricCopy`). Callers must have completed a
     * successful native biometric prompt first — this performs no
     * authentication itself.
     */
    suspend fun unlockWithBiometric(note: NoteEntity): NoteUnlockResult = attemptMutex.withLock {
        val fresh = noteRepository.getById(note.id) ?: return@withLock NoteUnlockResult.Error("Note not found.")
        val lock = recordFor(fresh)
        if (lock?.biometric != true) {
            return@withLock NoteUnlockResult.Error("Fingerprint unlock isn't enabled for this note.")
        }
        val lockout = lock.lockoutSeconds()
        if (lockout > 0) {
            return@withLock NoteUnlockResult.Error(
                "Too many attempts. Try again in ${lockout}s.",
                lockoutSeconds = lockout,
            )
        }
        val nek = biometricStore.readCopy(note.id)
            ?: return@withLock NoteUnlockResult.Error(
                "Fingerprint unlock isn't available on this device for this note. Use your backup credential.",
            )
        persistRecord(fresh, lock.withResetAttempts())
        NoteUnlockResult.Ok(nek)
    }

    // ------------------------------------------------------------ session data

    /** Decrypts a locked note's body and attachments into memory (1.x `decryptNoteData`). */
    suspend fun decryptNoteData(note: NoteEntity, nek: ByteArray): DecryptedNoteData {
        val content = if (NoteLockCrypto.isEncryptedContent(note.contentHtml)) {
            NoteLockCrypto.decryptText(nek, note.contentHtml)
        } else {
            note.contentHtml
        }
        val bytes = LinkedHashMap<String, ByteArray>()
        val dataUrls = LinkedHashMap<String, String>()
        for (attachment in attachmentDao.getForNote(note.id)) {
            if (!attachment.encrypted) continue
            val envelopeText = String(attachmentStore.readBytes(attachment), Charsets.UTF_8)
            val dataUrl = NoteLockCrypto.decryptText(nek, envelopeText)
            dataUrls[attachment.id] = dataUrl
            NoteLockCrypto.parseDataUrl(dataUrl)?.let { bytes[attachment.id] = it.second }
        }
        return DecryptedNoteData(content, bytes, dataUrls)
    }

    /** Seals any still-plaintext attachments of a locked note; returns how many. */
    suspend fun sealPlaintextAttachments(noteId: String, nek: ByteArray): Int =
        sealAttachments(noteId, nek, onlyPlaintext = true)

    private suspend fun sealAttachments(noteId: String, nek: ByteArray, onlyPlaintext: Boolean): Int {
        var sealed = 0
        for (attachment in attachmentDao.getForNote(noteId)) {
            if (onlyPlaintext && attachment.encrypted) continue
            val raw = attachmentStore.readBytes(attachment)
            val dataUrl = NoteLockCrypto.buildDataUrl(attachment.mimeType, raw)
            val envelope = NoteLockCrypto.encryptText(nek, dataUrl)
            attachmentStore.rewriteBytes(attachment, envelope.toByteArray(Charsets.UTF_8), encrypted = true)
            sealed++
        }
        return sealed
    }

    /**
     * Persists edits made during an unlocked session, re-sealing body +
     * any attachments added during the session (1.x
     * `saveLockedNoteContent`). Plaintext is never written to Room or to
     * attachment files for a locked note: the body lands as an envelope
     * with an empty plainText projection.
     */
    suspend fun saveLockedContent(note: NoteEntity, nek: ByteArray, newHtml: String) {
        val fresh = noteRepository.getById(note.id) ?: return
        check(fresh.locked) { "Note is not locked." }
        val updated = fresh.copy(
            contentHtml = NoteLockCrypto.encryptText(nek, newHtml),
            plainText = "",
            updatedAt = System.currentTimeMillis(),
        )
        noteRepository.upsert(updated)
        sealAttachments(note.id, nek, onlyPlaintext = true)
    }

    // ------------------------------------------------------------ manage

    /** Permanently removes a note's lock, decrypting body and attachments (1.x `removeNoteLock`). */
    suspend fun removeNoteLock(noteId: String, nek: ByteArray) {
        val note = noteRepository.getById(noteId) ?: return
        if (!note.locked && note.lockRecordJson == null) return
        val data = decryptNoteData(note, nek)
        for (attachment in attachmentDao.getForNote(noteId)) {
            if (!attachment.encrypted) continue
            val payload = data.attachmentBytes[attachment.id] ?: continue
            attachmentStore.rewriteBytes(attachment, payload, encrypted = false)
        }
        biometricStore.removeCopy(noteId)
        val unlocked = note.copy(
            contentHtml = data.contentHtml,
            plainText = NoteDocument.plainText(NoteDocument.parse(data.contentHtml)),
            locked = false,
            lockRecordJson = null,
            updatedAt = System.currentTimeMillis(),
        )
        noteRepository.upsert(unlocked)
    }

    /** Adds or replaces one credential slot (1.x `setNoteLockSlot`). */
    suspend fun setSlot(note: NoteEntity, nek: ByteArray, method: NoteLockMethod, canonicalSecret: String) {
        val fresh = noteRepository.getById(note.id) ?: throw IllegalStateException("Note not found.")
        val lock = recordFor(fresh) ?: throw IllegalStateException("This note is not locked.")
        require(NoteLockCrypto.isValidCanonicalSecret(method, canonicalSecret)) {
            when (method) {
                NoteLockMethod.PASSWORD -> "Password must be at least 8 characters."
                NoteLockMethod.PIN -> "PIN must be 4-6 digits."
                NoteLockMethod.PATTERN -> "Pattern must connect at least 4 dots."
            }
        }
        val slot = withContext(Dispatchers.Default) {
            NoteLockCrypto.wrapNoteKey(nek, method, canonicalSecret)
        }
        val slots = LinkedHashMap(lock.slots)
        slots[method] = slot
        persistRecord(fresh, lock.copy(slots = slots))
    }

    /** Removes a credential slot; refuses the last one (1.x `removeNoteLockSlot`). */
    suspend fun removeSlot(note: NoteEntity, method: NoteLockMethod): Boolean {
        val fresh = noteRepository.getById(note.id) ?: return false
        val lock = recordFor(fresh) ?: return false
        if (!lock.slots.containsKey(method)) return false
        if (lock.slots.size <= 1) return false
        val slots = LinkedHashMap(lock.slots)
        slots.remove(method)
        persistRecord(fresh, lock.copy(slots = slots))
        return true
    }

    /** Enables/disables fingerprint unlock for a locked note (1.x `setNoteLockBiometric`). */
    suspend fun setBiometric(note: NoteEntity, nek: ByteArray, enabled: Boolean): Boolean {
        val fresh = noteRepository.getById(note.id) ?: return false
        val lock = recordFor(fresh) ?: return false
        return if (enabled) {
            if (!biometricStore.storeCopy(note.id, nek)) {
                false
            } else {
                persistRecord(fresh, lock.copy(biometric = true))
                true
            }
        } else {
            biometricStore.removeCopy(note.id)
            persistRecord(fresh, lock.copy(biometric = false))
            true
        }
    }
}
