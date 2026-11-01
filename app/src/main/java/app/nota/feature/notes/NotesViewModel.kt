package app.nota.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.FolderEntity
import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteTagCrossRef
import app.nota.data.db.TagEntity
import app.nota.data.repository.LibraryRepository
import app.nota.data.repository.NoteRepository
import app.nota.domain.SmartFolderKey
import app.nota.domain.SmartFolders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which slice of the library the Notes list shows. */
sealed interface NotesFilter {
    data object All : NotesFilter
    data class Folder(val id: String) : NotesFilter
    data class Tag(val id: String) : NotesFilter
    data class Smart(val key: SmartFolderKey) : NotesFilter
    data object Favourites : NotesFilter
    data object Archived : NotesFilter
    data object Trash : NotesFilter
}

enum class SortOrder(val label: String) {
    UpdatedDesc("Recently updated"),
    CreatedDesc("Recently created"),
    TitleAsc("Title A-Z"),
}

/** A note enriched with everything its card renders. */
data class NoteListItem(
    val note: NoteEntity,
    val folderName: String? = null,
    val folderColorArgb: Int? = null,
    val tagNames: List<String> = emptyList(),
    val tagIds: Set<String> = emptySet(),
    val attachmentCount: Int = 0,
    val coverImagePath: String? = null,
)

data class NotesUiState(
    val items: List<NoteListItem> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val filter: NotesFilter = NotesFilter.All,
    val sort: SortOrder = SortOrder.UpdatedDesc,
    val selection: Set<String> = emptySet(),
)

class NotesViewModel(
    private val repository: NoteRepository,
    private val library: LibraryRepository,
) : ViewModel() {

    private val filterFlow = MutableStateFlow<NotesFilter>(NotesFilter.All)
    private val sortFlow = MutableStateFlow(SortOrder.UpdatedDesc)
    private val selectionFlow = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<NotesUiState> = combine(
        repository.observeActive(),
        repository.observeArchived(),
        repository.observeTrashed(),
        repository.observeFolders(),
        repository.observeTags(),
    ) { active, archived, trashed, folders, tags -> LibrarySnapshot(active, archived, trashed, folders, tags) }
        .combine(repository.observeAllCrossRefs()) { snap, crossRefs -> snap.copy(crossRefs = crossRefs) }
        .combine(repository.observeAttachments()) { snap, attachments -> snap.copy(attachments = attachments) }
        .combine(filterFlow) { snap, filter -> snap.copy(filter = filter) }
        .combine(sortFlow) { snap, sort -> snap.copy(sort = sort) }
        .combine(selectionFlow) { snap, selection -> snap.toUiState(selection) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    private data class LibrarySnapshot(
        val active: List<NoteEntity>,
        val archived: List<NoteEntity>,
        val trashed: List<NoteEntity>,
        val folders: List<FolderEntity>,
        val tags: List<TagEntity>,
        val crossRefs: List<NoteTagCrossRef> = emptyList(),
        val attachments: List<AttachmentEntity> = emptyList(),
        val filter: NotesFilter = NotesFilter.All,
        val sort: SortOrder = SortOrder.UpdatedDesc,
    ) {
        fun toUiState(selection: Set<String>): NotesUiState {
            val tagsById = tags.associateBy { it.id }
            val foldersById = folders.associateBy { it.id }
            val tagIdsByNote = crossRefs.groupBy({ it.noteId }, { it.tagId })
            val attachmentsByNote = attachments.groupBy { it.noteId }
            fun enrich(note: NoteEntity): NoteListItem {
                val noteTagIds = tagIdsByNote[note.id].orEmpty().toSet()
                val noteAttachments = attachmentsByNote[note.id].orEmpty()
                return NoteListItem(
                    note = note,
                    folderName = note.folderId?.let { foldersById[it]?.name },
                    folderColorArgb = note.folderId?.let { foldersById[it]?.colorArgb },
                    tagNames = noteTagIds.mapNotNull { tagsById[it]?.name }.sorted(),
                    tagIds = noteTagIds,
                    attachmentCount = noteAttachments.size,
                    coverImagePath = noteAttachments.firstOrNull { it.mimeType.startsWith("image/") }?.localPath,
                )
            }
            val now = System.currentTimeMillis()
            val source: List<NoteListItem> = when (val f = filter) {
                NotesFilter.All -> active.map(::enrich)
                is NotesFilter.Folder -> active.filter { it.folderId == f.id }.map(::enrich)
                is NotesFilter.Tag -> active.map(::enrich).filter { f.id in it.tagIds }
                is NotesFilter.Smart -> active.map(::enrich)
                    .filter { SmartFolders.matches(f.key, it.note, it.tagIds, it.attachmentCount, now) }
                NotesFilter.Favourites -> active.filter { it.favourite }.map(::enrich)
                NotesFilter.Archived -> archived.map(::enrich)
                NotesFilter.Trash -> trashed.map(::enrich)
            }
            val sorted = when (sort) {
                SortOrder.UpdatedDesc -> source.sortedWith(compareByDescending<NoteListItem> { it.note.pinned }.thenByDescending { it.note.updatedAt })
                SortOrder.CreatedDesc -> source.sortedWith(compareByDescending<NoteListItem> { it.note.pinned }.thenByDescending { it.note.createdAt })
                SortOrder.TitleAsc -> source.sortedWith(compareByDescending<NoteListItem> { it.note.pinned }.thenBy { it.note.title.lowercase() })
            }
            return NotesUiState(
                items = sorted,
                folders = folders,
                tags = tags,
                filter = filter,
                sort = sort,
                selection = selection.intersect(sorted.map { it.note.id }.toSet()),
            )
        }
    }

    fun setFilter(filter: NotesFilter) { filterFlow.value = filter; clearSelection() }
    fun setSort(sort: SortOrder) { sortFlow.value = sort }

    fun toggleSelect(id: String) {
        selectionFlow.value = if (id in selectionFlow.value) selectionFlow.value - id else selectionFlow.value + id
    }
    fun clearSelection() { selectionFlow.value = emptySet() }

    fun createNote(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val note = repository.createNote()
            onCreated(note.id)
        }
    }

    // Single ops (swipe + editor shortcuts)
    fun archive(note: NoteEntity) { viewModelScope.launch { repository.setArchived(note, true) } }
    fun trash(note: NoteEntity) { viewModelScope.launch { repository.trash(note) } }
    fun restore(note: NoteEntity) { viewModelScope.launch { repository.restore(note) } }
    fun upsert(note: NoteEntity) { viewModelScope.launch { repository.upsert(note) } }
    fun deletePermanently(note: NoteEntity) { viewModelScope.launch { repository.deletePermanently(note) } }

    // Batch ops over the current selection
    private fun selectedNotes(): List<NoteEntity> {
        val ids = selectionFlow.value
        return uiState.value.items.map { it.note }.filter { it.id in ids }
    }
    fun batchPin() { val n = selectedNotes(); viewModelScope.launch { n.forEach { repository.setPinned(it, true) }; clearSelection() } }
    fun batchArchive() { val n = selectedNotes(); viewModelScope.launch { n.forEach { repository.setArchived(it, true) }; clearSelection() } }
    fun batchTrash() { val n = selectedNotes(); viewModelScope.launch { n.forEach { repository.trash(it) }; clearSelection() } }
    fun batchRestore() { val n = selectedNotes(); viewModelScope.launch { n.forEach { repository.restore(it) }; clearSelection() } }
    fun batchDelete() { val n = selectedNotes(); viewModelScope.launch { n.forEach { repository.deletePermanently(it) }; clearSelection() } }

    // Folder management (overflow dialog)
    fun createFolder(name: String, colorArgb: Int? = null) { if (name.isNotBlank()) viewModelScope.launch { library.createFolder(name, colorArgb) } }
    fun renameFolder(folder: FolderEntity, name: String) { if (name.isNotBlank()) viewModelScope.launch { library.renameFolder(folder, name) } }
    fun setFolderColor(folder: FolderEntity, colorArgb: Int?) { viewModelScope.launch { library.setFolderColor(folder, colorArgb) } }
    fun deleteFolder(folder: FolderEntity) { viewModelScope.launch { library.deleteFolder(folder) } }
}
