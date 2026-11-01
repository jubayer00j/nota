package app.nota.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nota.data.db.FolderEntity
import app.nota.data.db.NoteEntity
import app.nota.data.repository.NoteRepository
import app.nota.data.text.PlainTextCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backing VM for the plain editor (Phase 1b). The route and screen signature
 * stay stable: PR-C swaps the body field for the rich editor behind them.
 */
class PlainNoteEditorViewModel(
    private val repository: NoteRepository,
) : ViewModel() {

    private val noteIdFlow = MutableStateFlow<String?>(null)
    private val _note = MutableStateFlow<NoteEntity?>(null)
    val currentNote: StateFlow<NoteEntity?> = _note

    val folders: StateFlow<List<FolderEntity>> = repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _tagNames = MutableStateFlow<List<String>>(emptyList())
    val tagNames: StateFlow<List<String>> = _tagNames

    fun load(noteId: String) {
        if (noteIdFlow.value == noteId) return
        noteIdFlow.value = noteId
        viewModelScope.launch {
            _note.value = repository.getById(noteId)
            repository.observeTagsForNote(noteId).collect { tags ->
                _tagNames.value = tags.map { it.name }
            }
        }
    }

    fun bodyText(note: NoteEntity): String = PlainTextCodec.htmlToText(note.contentHtml)

    fun save(
        title: String,
        plainBody: String,
        folderId: String?,
        tagsCsv: String,
        onSaved: () -> Unit,
    ) {
        val current = _note.value ?: return
        viewModelScope.launch {
            var updated = repository.updateBody(current, title, plainBody)
            if (updated.folderId != folderId) {
                repository.moveToFolder(updated, folderId)
                updated = updated.copy(folderId = folderId)
            }
            repository.setNoteTags(
                updated.id,
                tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            )
            _note.value = repository.getById(updated.id) ?: updated
            onSaved()
        }
    }

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
