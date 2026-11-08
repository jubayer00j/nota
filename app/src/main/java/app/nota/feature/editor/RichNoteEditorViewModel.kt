package app.nota.feature.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.FolderEntity
import app.nota.data.db.NoteEntity
import app.nota.data.db.ReminderEntity
import app.nota.data.html.DocSegment
import app.nota.data.html.FileSegment
import app.nota.data.html.ImageSegment
import app.nota.data.html.NoteDocument
import app.nota.data.html.TextSegment
import app.nota.data.reminders.ReminderRepository
import app.nota.data.reminders.ReminderScheduler
import app.nota.data.repository.NoteLockRepository
import app.nota.data.repository.NoteRepository
import app.nota.data.repository.NoteUnlockResult
import app.nota.data.security.AppLockMethod
import app.nota.data.security.NoteLockMethod
import app.nota.data.templates.TemplateRepository
import app.nota.feature.export.NoteExportArtifact
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Save indicator state for the rich editor's app bar. */
sealed interface SaveState {
    data object Editing : SaveState
    data object Saving : SaveState
    data class Saved(val atMillis: Long) : SaveState
}

/**
 * Backing VM for the Phase 1c rich editor. The screen owns the working
 * segment list + RichTextStates; this VM owns persistence: it serialises
 * through [NoteDocument] (HTML stays canonical in Room, spec §3), derives
 * plain text / FTS / backlinks via the repository write path, and no-ops
 * when nothing actually changed, so merely opening a note never rewrites
 * it (the loaded baseline is the guard-normalised form).
 *
 * Dictation seam (spec §5): [textInserter] is the single insert-at-caret
 * path. The screen registers its active editor's inserter here; Phase 5's
 * voice engine will call [insertDictatedText] — the exact code path the
 * keyboard uses — instead of touching the document itself.
 */
class RichNoteEditorViewModel(
    private val repository: NoteRepository,
    private val attachmentStore: AttachmentStore,
    private val templateRepository: TemplateRepository,
    private val noteLockRepository: NoteLockRepository,
    private val reminderRepository: ReminderRepository,
    private val reminderScheduler: ReminderScheduler,
) : ViewModel() {

    private val _note = MutableStateFlow<NoteEntity?>(null)
    val note: StateFlow<NoteEntity?> = _note

    val folders: StateFlow<List<FolderEntity>> = repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _tagNames = MutableStateFlow<List<String>>(emptyList())
    val tagNames: StateFlow<List<String>> = _tagNames

    /** Every tag in the library (Phase 4.1: the Suggest-tags prompt
     *  prefers existing library tags, mirroring 1.x). */
    val libraryTagNames: StateFlow<List<String>> = repository.observeTags()
        .map { tags -> tags.map { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _attachments = MutableStateFlow<List<AttachmentEntity>>(emptyList())
    val attachments: StateFlow<List<AttachmentEntity>> = _attachments

    private val _linkedFrom = MutableStateFlow<List<NoteEntity>>(emptyList())
    val linkedFrom: StateFlow<List<NoteEntity>> = _linkedFrom

    /** Phase 5.1: the note's pending reminder (at most one — the 5.1
     *  contract), or null. Drives the overflow item's label and the
     *  reminder dialog's initial state. Title/time only: collecting it
     *  never touches the note's content, so it is safe while the
     *  per-note lock gate is up. */
    private val _reminder = MutableStateFlow<ReminderEntity?>(null)
    val reminder: StateFlow<ReminderEntity?> = _reminder

    /** Notes this note links TO via [[title]] (Phase 4.4: the "Links" footer list). */
    private val _links = MutableStateFlow<List<NoteEntity>>(emptyList())
    val links: StateFlow<List<NoteEntity>> = _links

    /** [[ ]] autocomplete candidates (all linkable notes). */
    val linkableNotes: StateFlow<List<NoteEntity>> = repository.observeLinkable()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Saved(0))
    val saveState: StateFlow<SaveState> = _saveState

    // --- Per-note lock session (Phase 2b) ----------------------------------
    // While a locked note is open, the NEK and the decrypted content live
    // ONLY in this VM's memory (never in Room, never in files except the
    // cacheDir session temps for attachment bytes). The session ends when
    // the VM is cleared: the NEK is zeroed and the temps are deleted.
    // (Invalidation when App Lock engages is out of scope for 2b.)

    private var sessionNek: ByteArray? = null

    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive

    /** Decrypted body HTML for the active session; null while gated. */
    private val _sessionHtml = MutableStateFlow<String?>(null)
    val sessionHtml: StateFlow<String?> = _sessionHtml

    private val _lockError = MutableStateFlow<String?>(null)
    val lockError: StateFlow<String?> = _lockError

    /**
     * Phase 2.5 (F3): the active per-note lockout deadline (epoch
     * millis) as last reported by the repository, or null when no
     * backoff is in force. Previously the VM kept only the error STRING
     * from a failed attempt and dropped `NoteUnlockResult.Error`'s
     * `lockoutSeconds`, while the gate derived its countdown solely from
     * the load-time note record (never refreshed after an attempt) — so
     * a lockout imposed mid-session was invisible: the form stayed live
     * and every correct password entered during the backoff was refused
     * by the repository's lockout-first check with no visible wait. The
     * gate now receives this state (and the refreshed record, below) so
     * the remaining wait can be shown. F5 owns the visuals; this VM owns
     * the state.
     */
    private val _lockoutUntilMillis = MutableStateFlow<Long?>(null)
    val lockoutUntilMillis: StateFlow<Long?> = _lockoutUntilMillis

    /**
     * Phase 2.5 (F3): true while one unlock attempt is being verified.
     * Submissions arriving while an attempt is in flight are dropped:
     * one user action must produce at most one recorded attempt (a
     * multi-second PBKDF2 verify on a slow device otherwise invites
     * repeat submissions that stack up as separate failed attempts and
     * can push the note into lockout), and two overlapping successes
     * must never race in [startSession] (the second would zero the key
     * the first session is decrypting with).
     */
    private val _unlockInProgress = MutableStateFlow(false)
    val unlockInProgress: StateFlow<Boolean> = _unlockInProgress

    val hasSession: Boolean get() = sessionNek != null

    /** Registered by the screen: inserts text at the active editor's caret. */
    @Volatile
    var textInserter: ((String) -> Unit)? = null

    private var loadedNoteId: String? = null
    private var baselineHtml: String = ""
    private var baselineTitle: String = ""
    private var baselineFolderId: String? = null
    private var baselineTagsCsv: String = ""

    fun load(noteId: String) {
        if (loadedNoteId == noteId) return
        loadedNoteId = noteId
        viewModelScope.launch {
            val loaded = repository.getById(noteId)
            _note.value = loaded
            if (loaded != null && !loaded.locked) {
                // Storage form (Phase 1.5 fix 2): the editor's ephemeral
                // trailing caret segment is stripped here too, so merely
                // opening a block-ending note never counts as a change.
                // A LOCKED note's contentHtml is an envelope, not HTML:
                // no baseline is taken from it — the session start sets
                // the baseline from the decrypted body instead.
                baselineHtml = NoteDocument.serialize(
                    NoteDocument.stripTrailingEmptyTextSegments(
                        NoteDocument.parse(loaded.contentHtml),
                    ),
                )
                baselineTitle = loaded.title
                baselineFolderId = loaded.folderId
            }
        }
        viewModelScope.launch {
            repository.observeTagsForNote(noteId).collect { tags ->
                _tagNames.value = tags.map { it.name }
                if (baselineTagsCsv.isEmpty()) {
                    baselineTagsCsv = tags.joinToString(", ") { it.name }
                }
            }
        }
        viewModelScope.launch {
            repository.observeAttachmentsForNote(noteId).collect { _attachments.value = it }
        }
        viewModelScope.launch {
            repository.observeLinkedFrom(noteId).collect { _linkedFrom.value = it }
        }
        viewModelScope.launch {
            repository.observeOutgoing(noteId).collect { _links.value = it }
        }
        viewModelScope.launch {
            reminderRepository.observePendingForNote(noteId).collect { _reminder.value = it }
        }
    }

    // --- Reminders (Phase 5.1) ----------------------------------------------

    /** Sets/replaces the note's pending reminder and arms it (the
     *  scheduler no-ops phone-alarm reminders — the screen hands those
     *  to the Clock app after this call). */
    fun setReminder(triggerAt: Long, alertType: String) {
        val noteId = _note.value?.id ?: return
        viewModelScope.launch {
            val saved = reminderRepository.setReminder(noteId, triggerAt, alertType)
            reminderScheduler.schedule(saved)
        }
    }

    /** Removes the note's reminder entirely (row + alarm + any
     *  posted notification). */
    fun removeReminder() {
        val noteId = _note.value?.id ?: return
        viewModelScope.launch {
            reminderRepository.pendingForNote(noteId)?.let { pending ->
                reminderScheduler.cancel(pending.id)
            }
            reminderRepository.removeForNote(noteId)
        }
    }

    // --- Lock gate / session (Phase 2b) --------------------------------------

    /** One credential attempt from the gate. Canonical secret, as reported
     *  by PR-A's shared credential components. */
    fun unlockWithCredential(method: AppLockMethod, canonicalSecret: String) {
        // Phase 2.5 (F3): one attempt in flight at a time — a repeat
        // submission while a verify is running is dropped here, never
        // queued as a second recorded attempt.
        if (_unlockInProgress.value) return
        val current = _note.value ?: return
        viewModelScope.launch {
            _unlockInProgress.value = true
            _lockError.value = null
            try {
                when (val result = noteLockRepository.verifyCredential(
                    current,
                    NoteLockMethod.fromAppLockMethod(method),
                    canonicalSecret,
                )) {
                    is NoteUnlockResult.Ok -> {
                        _lockoutUntilMillis.value = null
                        // Publish the persisted record (attempts reset)
                        // before the session starts, so any state the
                        // gate still shows is the truth.
                        refreshNote(current.id)
                        startSession(result.nek)
                    }
                    is NoteUnlockResult.Error -> {
                        _lockError.value = result.message
                        _lockoutUntilMillis.value = result.lockoutSeconds?.let {
                            System.currentTimeMillis() + it * 1_000L
                        }
                        // Phase 2.5 (F3): publish the persisted record —
                        // its failedAttempts / lockoutUntil are what the
                        // gate's countdown is driven from. Without this
                        // refresh the gate kept rendering the load-time
                        // record and a fresh lockout never engaged it.
                        refreshNote(current.id)
                    }
                }
            } finally {
                _unlockInProgress.value = false
            }
        }
    }

    /** Cipher for the gate's crypto prompt (Phase 2.5, F4).
     *  Phase 2.6: typed — the gate surfaces the failure reason. */
    suspend fun biometricUnlockCipher(): app.nota.data.security.StepResult<javax.crypto.Cipher> {
        val current = _note.value
            ?: return app.nota.data.security.StepResult.Failed(
                app.nota.data.security.BiometricStep.TOKEN_READ,
                "note isn't loaded",
            )
        return noteLockRepository.biometricUnlockCipher(current)
    }

    /**
     * Crypto-bound biometric path (Phase 2.5, F4): [authorisedCipher]
     * is the cipher the gate's prompt authorised via its CryptoObject;
     * the repository releases the NEK only when it decrypts the sealed
     * device copy. The F3 outcome treatment applies unchanged: refresh
     * the record after the attempt, surface any lockout deadline, and
     * drop overlapping submissions.
     */
    fun unlockWithBiometric(authorisedCipher: javax.crypto.Cipher) {
        if (_unlockInProgress.value) return
        val current = _note.value ?: return
        viewModelScope.launch {
            _unlockInProgress.value = true
            _lockError.value = null
            try {
                when (val result = noteLockRepository.unlockWithBiometric(current, authorisedCipher)) {
                    is NoteUnlockResult.Ok -> {
                        _lockoutUntilMillis.value = null
                        refreshNote(current.id)
                        startSession(result.nek)
                    }
                    is NoteUnlockResult.Error -> {
                        _lockError.value = result.message
                        _lockoutUntilMillis.value = result.lockoutSeconds?.let {
                            System.currentTimeMillis() + it * 1_000L
                        }
                        refreshNote(current.id)
                    }
                }
            } finally {
                _unlockInProgress.value = false
            }
        }
    }

    /** Re-reads the note so [_note] (and everything the gate derives
     *  from it) reflects what the lock repository just persisted. */
    private suspend fun refreshNote(noteId: String) {
        repository.getById(noteId)?.let { _note.value = it }
    }

    private suspend fun startSession(nek: ByteArray) {
        val current = _note.value ?: run {
            nek.fill(0)
            return
        }
        // A re-unlock replaces any previous session key — zero it first.
        sessionNek?.fill(0)
        sessionNek = nek
        _lockError.value = null
        val data = noteLockRepository.decryptNoteData(current, nek)
        _sessionHtml.value = data.contentHtml
        baselineHtml = NoteDocument.serialize(
            NoteDocument.stripTrailingEmptyTextSegments(NoteDocument.parse(data.contentHtml)),
        )
        baselineTitle = current.title
        baselineFolderId = current.folderId
        writeSessionTemps(current.id, data.attachmentBytes)
        _sessionActive.value = true
    }

    /** (Re)writes the cacheDir session temps from decrypted bytes, so
     *  image blocks render session-decrypted content through the same
     *  file seam as stored attachments. The attachment list is fetched
     *  fresh — the collected flow may not have emitted yet at unlock
     *  time, and flags change as saves seal plaintext attachments. */
    private suspend fun writeSessionTemps(
        noteId: String,
        decrypted: Map<String, ByteArray>,
    ) {
        val attachments = repository.observeAttachmentsForNote(noteId).first()
        for (attachment in attachments) {
            val bytes = decrypted[attachment.id] ?: continue
            runCatching { attachmentStore.writeSessionBytes(noteId, attachment, bytes) }
        }
    }

    private suspend fun refreshSessionTemps() {
        val nek = sessionNek ?: return
        val current = _note.value ?: return
        if (!current.locked) return
        val data = noteLockRepository.decryptNoteData(current, nek)
        writeSessionTemps(current.id, data.attachmentBytes)
    }

    /** Ends the session: NEK zeroed, decrypted state dropped, temps deleted. */
    private fun endSession() {
        sessionNek?.fill(0)
        sessionNek = null
        _sessionActive.value = false
        _sessionHtml.value = null
        loadedNoteId?.let { attachmentStore.clearSession(it) }
    }

    override fun onCleared() {
        endSession()
        super.onCleared()
    }

    // --- Lock management (Phase 2b) -------------------------------------------

    /** Locks the currently open (unlocked) note, then ends editing: the
     *  caller navigates back — no session is started implicitly. */
    fun lockCurrentNote(
        method: AppLockMethod,
        canonicalSecret: String,
        enableBiometric: Boolean,
        biometricCipher: javax.crypto.Cipher? = null,
        onDone: (String?) -> Unit,
    ) {
        val current = _note.value ?: return onDone("Note not loaded.")
        viewModelScope.launch {
            try {
                val locked = noteLockRepository.lockNote(
                    current.id,
                    NoteLockMethod.fromAppLockMethod(method),
                    canonicalSecret,
                    enableBiometric,
                    biometricCipher,
                )
                _note.value = locked
                onDone(null)
            } catch (e: Exception) {
                onDone(e.message ?: "Could not lock this note.")
            }
        }
    }

    fun setLockSlot(method: AppLockMethod, canonicalSecret: String, onDone: (String?) -> Unit) {
        val current = _note.value ?: return onDone("Note not loaded.")
        val nek = sessionNek ?: return onDone("Unlock the note first.")
        viewModelScope.launch {
            try {
                noteLockRepository.setSlot(
                    current, nek, NoteLockMethod.fromAppLockMethod(method), canonicalSecret,
                )
                _note.value = repository.getById(current.id)
                onDone(null)
            } catch (e: Exception) {
                onDone(e.message ?: "Could not update the credential.")
            }
        }
    }

    fun removeLockSlot(method: AppLockMethod, onDone: (Boolean) -> Unit) {
        val current = _note.value ?: return onDone(false)
        viewModelScope.launch {
            val removed = noteLockRepository.removeSlot(
                current, NoteLockMethod.fromAppLockMethod(method),
            )
            if (removed) _note.value = repository.getById(current.id)
            onDone(removed)
        }
    }

    fun setLockBiometric(
        enabled: Boolean,
        cipher: javax.crypto.Cipher? = null,
        onDone: (Boolean) -> Unit,
    ) {
        val current = _note.value ?: return onDone(false)
        val nek = sessionNek ?: return onDone(false)
        viewModelScope.launch {
            val ok = noteLockRepository.setBiometric(current, nek, enabled, cipher)
            if (ok) _note.value = repository.getById(current.id)
            onDone(ok)
        }
    }

    /** Removes the lock entirely (session required); the editor session
     *  ends and the caller navigates back to the now-plaintext note. */
    fun removeCurrentLock(onDone: () -> Unit) {
        val current = _note.value ?: return
        val nek = sessionNek ?: return
        viewModelScope.launch {
            noteLockRepository.removeNoteLock(current.id, nek)
            endSession()
            _note.value = repository.getById(current.id)
            onDone()
        }
    }

    /** The Phase 5 dictation entry point — inserts at the caret, keyboard path. */
    fun insertDictatedText(text: String) {
        textInserter?.invoke(text)
    }

    /**
     * Persists the editor's current state if anything differs from the
     * baseline. Called by the screen's autosave tick, on back, and on the
     * explicit save action.
     */
    fun persist(
        title: String,
        segments: List<DocSegment>,
        folderId: String?,
        tagsCsv: String,
        onPersisted: (() -> Unit)? = null,
    ) {
        val current = _note.value ?: return
        // Storage form (Phase 1.5 fix 2): never persist the editor's
        // trailing caret segment; repeated saves cannot accumulate
        // empty paragraphs at the end of a note.
        val html = NoteDocument.serialize(
            NoteDocument.stripTrailingEmptyTextSegments(segments),
        )
        val tags = tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val baselineTags = baselineTagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val bodyChanged = html != baselineHtml || title != baselineTitle
        val folderChanged = folderId != baselineFolderId
        val tagsChanged = tags != baselineTags
        if (!bodyChanged && !folderChanged && !tagsChanged) {
            // Phase 2b: even a no-change persist seals attachments that
            // were imported during a locked session (they land as
            // plaintext files and must not stay that way past any save
            // tick, including this autosave).
            val nek = sessionNek
            if (current.locked && nek != null) {
                viewModelScope.launch {
                    noteLockRepository.sealPlaintextAttachments(current.id, nek)
                    refreshSessionTemps()
                    onPersisted?.invoke()
                }
            } else {
                onPersisted?.invoke()
            }
            return
        }
        if (current.locked) {
            // Phase 2b: a locked note persists ONLY through the seal —
            // never through the plaintext write path (which now also
            // refuses locked notes as defence-in-depth).
            persistLocked(current, title, html, folderId, tags, bodyChanged, folderChanged, tagsChanged, onPersisted)
            return
        }
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            var updated = current
            if (bodyChanged) {
                updated = repository.updateRichBody(updated, title, html)
                baselineHtml = html
                baselineTitle = title
            }
            if (folderChanged) {
                repository.moveToFolder(updated, folderId)
                updated = updated.copy(folderId = folderId)
                baselineFolderId = folderId
            }
            if (tagsChanged) {
                repository.setNoteTags(updated.id, tags)
                baselineTagsCsv = tags.joinToString(", ")
            }
            _note.value = repository.getById(updated.id) ?: updated
            _saveState.value = SaveState.Saved(System.currentTimeMillis())
            onPersisted?.invoke()
        }
    }

    /**
     * The locked-session persist: title (plaintext metadata by design)
     * goes through the title-only update; the body is re-sealed via
     * [NoteLockRepository.saveLockedContent], which also seals any
     * attachments imported during the session. Without a session NEK
     * nothing is written at all.
     */
    private fun persistLocked(
        current: NoteEntity,
        title: String,
        html: String,
        folderId: String?,
        tags: List<String>,
        bodyChanged: Boolean,
        folderChanged: Boolean,
        tagsChanged: Boolean,
        onPersisted: (() -> Unit)?,
    ) {
        val nek = sessionNek
        if (nek == null) {
            onPersisted?.invoke()
            return
        }
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            var updated = repository.getById(current.id) ?: current
            if (title != baselineTitle) {
                updated = repository.updateTitle(updated, title)
                baselineTitle = title
            }
            if (bodyChanged) {
                noteLockRepository.saveLockedContent(updated, nek, html)
                baselineHtml = html
                updated = repository.getById(current.id) ?: updated
                // Attachments sealed by the save now hold envelopes on
                // disk; refresh the session temps so image blocks keep
                // rendering the decrypted bytes.
                refreshSessionTemps()
            }
            if (folderChanged) {
                repository.moveToFolder(updated, folderId)
                updated = updated.copy(folderId = folderId)
                baselineFolderId = folderId
            }
            if (tagsChanged) {
                repository.setNoteTags(updated.id, tags)
                baselineTagsCsv = tags.joinToString(", ")
            }
            if (!bodyChanged) {
                // Codex review PR #8: a metadata-only save (folder/tags/
                // title) must ALSO seal attachments imported during the
                // session — saveLockedContent's internal seal only runs
                // on the body-changed path, and this save may be the
                // last one before the editor closes.
                noteLockRepository.sealPlaintextAttachments(updated.id, nek)
                refreshSessionTemps()
            }
            _note.value = repository.getById(updated.id) ?: updated
            _saveState.value = SaveState.Saved(System.currentTimeMillis())
            onPersisted?.invoke()
        }
    }

    fun markEditing() {
        if (_saveState.value is SaveState.Saved) _saveState.value = SaveState.Editing
    }

    // --- Attachments ---------------------------------------------------------

    fun importImage(uri: Uri, onDone: (AttachmentEntity?) -> Unit) {
        val noteId = loadedNoteId ?: return onDone(null)
        viewModelScope.launch {
            onDone(runCatching { attachmentStore.importImage(noteId, uri) }.getOrNull())
        }
    }

    fun importFile(uri: Uri, onDone: (AttachmentEntity?) -> Unit) {
        val noteId = loadedNoteId ?: return onDone(null)
        viewModelScope.launch {
            onDone(runCatching { attachmentStore.importFile(noteId, uri) }.getOrNull())
        }
    }

    fun deleteAttachment(attachment: AttachmentEntity) {
        viewModelScope.launch {
            attachmentStore.delete(attachment)
            // Phase 2b: in a locked session the stored body is an
            // envelope — the screen has already dropped the block from
            // its working segments, and the next persist re-seals the
            // cleaned document. The plaintext strip below must NOT run
            // (it would fail the locked-write guard, by design).
            if (_note.value?.locked == true) return@launch
            // Codex F7: an embedded ImageSegment pointing at this
            // attachment would otherwise be left referencing a deleted
            // file/row. Strip it from the owning note's stored document
            // through the NoteDocument guard layer and persist if the
            // document actually changed. (The screen also drops the
            // block from its working segments, so the next autosave
            // cannot resurrect it.)
            val owner = repository.getById(attachment.noteId) ?: return@launch
            val segments = NoteDocument.parse(owner.contentHtml)
            val targetSrc = NoteDocument.ATTACHMENT_SRC_PREFIX + attachment.id
            // Phase 3.5 (D4): file blocks reference the attachment by
            // id directly — strip them on the same rule as images.
            val cleaned = segments.filterNot {
                (it is ImageSegment && it.src == targetSrc) ||
                    (it is FileSegment && it.attachmentId == attachment.id)
            }
            if (cleaned.size != segments.size) {
                val kept = cleaned.ifEmpty { listOf(TextSegment("")) }
                val html = NoteDocument.serialize(kept)
                val updated = repository.updateRichBody(owner, owner.title, html)
                if (loadedNoteId == owner.id) {
                    baselineHtml = html
                    _note.value = updated
                }
            }
        }
    }

    fun attachmentFile(attachment: AttachmentEntity): File =
        // Phase 2b: during a locked session, an encrypted attachment's
        // stored file holds an envelope — render the session temp with
        // the decrypted bytes instead. Plaintext attachments imported
        // mid-session keep the normal file until a save seals them.
        if (_sessionActive.value && attachment.encrypted) {
            attachmentStore.sessionFile(attachment.noteId, attachment)
        } else {
            attachmentStore.fileFor(attachment)
        }

    // --- Per-note export (Phase 6, Export #1) --------------------------------

    /** The built export file waiting on the system file picker.
     *  Held here, not in composition state: App Lock's immediate
     *  relock on picker return destroys the editor composition
     *  (the same hazard the backup staging store exists for), and
     *  the artifact must still be writable when the picker result
     *  is redelivered after unlock. In-memory only — a process
     *  death mid-picker simply drops the pending save. */
    var pendingExportSave: NoteExportArtifact? = null

    // --- Templates -------------------------------------------------------------

    fun saveAsTemplate(name: String, title: String, segments: List<DocSegment>) {
        // Phase 2b: templates are a plaintext store (templates.json) — a
        // locked note's body must never be copied into one, not even from
        // an unlocked session. The screen also hides the action.
        if (_note.value?.locked == true) return
        viewModelScope.launch {
            // Codex re-review N4: an embedded attachment image references
            // this note's attachment id (nota-attachment://<id>), but no
            // attachment file/metadata travels with a template — applying
            // the template would create a note whose image can never
            // resolve. Strip attachment ImageSegments from the stored
            // template HTML so a template never contains an unresolvable
            // reference. (External-URL images are untouched; copying the
            // underlying files into each applied note is a future
            // enhancement, not done here.)
            // Phase 3.5 (D4): a file block references this note's
            // attachment id exactly the same way — strip those too.
            val cleaned = segments.filterNot {
                (it is ImageSegment &&
                    it.src.startsWith(NoteDocument.ATTACHMENT_SRC_PREFIX)) ||
                    it is FileSegment
            }
            val kept = cleaned.ifEmpty { listOf(TextSegment("")) }
            templateRepository.add(
                name,
                title,
                NoteDocument.serialize(
                    NoteDocument.stripTrailingEmptyTextSegments(kept),
                ),
            )
        }
    }

    // --- State ops (mirror the plain editor's menu) ----------------------------

    fun togglePinned() {
        val current = _note.value ?: return
        viewModelScope.launch {
            repository.setPinned(current, !current.pinned)
            _note.value = repository.getById(current.id)
        }
    }

    fun toggleFavourite() {
        val current = _note.value ?: return
        viewModelScope.launch {
            repository.setFavourite(current, !current.favourite)
            _note.value = repository.getById(current.id)
        }
    }

    fun archive(onDone: () -> Unit) {
        val current = _note.value ?: return
        viewModelScope.launch { repository.setArchived(current, true); onDone() }
    }

    fun delete(onDone: () -> Unit) {
        val current = _note.value ?: return
        viewModelScope.launch { repository.trash(current); onDone() }
    }
}
