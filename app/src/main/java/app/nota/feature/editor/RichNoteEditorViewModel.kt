package app.nota.feature.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.FolderEntity
import app.nota.data.db.NoteEntity
import app.nota.data.html.DocSegment
import app.nota.data.html.NoteDocument
import app.nota.data.repository.NoteRepository
import app.nota.data.templates.TemplateRepository
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    private val _note = MutableStateFlow<NoteEntity?>(null)
    val note: StateFlow<NoteEntity?> = _note

    val folders: StateFlow<List<FolderEntity>> = repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _tagNames = MutableStateFlow<List<String>>(emptyList())
    val tagNames: StateFlow<List<String>> = _tagNames

    private val _attachments = MutableStateFlow<List<AttachmentEntity>>(emptyList())
    val attachments: StateFlow<List<AttachmentEntity>> = _attachments

    private val _linkedFrom = MutableStateFlow<List<NoteEntity>>(emptyList())
    val linkedFrom: StateFlow<List<NoteEntity>> = _linkedFrom

    /** [[ ]] autocomplete candidates (all linkable notes). */
    val linkableNotes: StateFlow<List<NoteEntity>> = repository.observeLinkable()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Saved(0))
    val saveState: StateFlow<SaveState> = _saveState

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
            if (loaded != null) {
                baselineHtml = NoteDocument.serialize(NoteDocument.parse(loaded.contentHtml))
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
        val html = NoteDocument.serialize(segments)
        val tags = tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val baselineTags = baselineTagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val bodyChanged = html != baselineHtml || title != baselineTitle
        val folderChanged = folderId != baselineFolderId
        val tagsChanged = tags != baselineTags
        if (!bodyChanged && !folderChanged && !tagsChanged) {
            onPersisted?.invoke()
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
        viewModelScope.launch { attachmentStore.delete(attachment) }
    }

    fun attachmentFile(attachment: AttachmentEntity): File = attachmentStore.fileFor(attachment)

    // --- Templates -------------------------------------------------------------

    fun saveAsTemplate(name: String, title: String, segments: List<DocSegment>) {
        viewModelScope.launch {
            templateRepository.add(name, title, NoteDocument.serialize(segments))
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
