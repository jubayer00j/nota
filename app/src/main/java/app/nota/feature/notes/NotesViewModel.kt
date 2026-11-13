package app.nota.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nota.data.db.AttachmentEntity
import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.FolderEntity
import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteTagCrossRef
import app.nota.data.db.TagEntity
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.LibraryRepository
import app.nota.data.repository.NoteRepository
import app.nota.domain.SmartFolderKey
import app.nota.domain.SmartFolders
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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

/** A note enriched with everything its card renders. Phase 6.1: the
 *  note itself is the lightweight [app.nota.data.db.NoteListRow]
 *  projection — no body, no lock columns; anything that needs the full
 *  entity (swipe ops, batch ops, the undo snapshot) fetches it by id
 *  at action time. */
data class NoteListItem(
    val note: app.nota.data.db.NoteListRow,
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
    private val attachmentStore: AttachmentStore,
    private val provider: DatabaseProvider,
    private val preferences: PreferencesRepository,
) : ViewModel() {

    private val filterFlow = MutableStateFlow<NotesFilter>(NotesFilter.All)
    private val sortFlow = MutableStateFlow(SortOrder.UpdatedDesc)
    private val selectionFlow = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Phase 3.5 (D1): the library chain is (re)built for every account
     * the provider switches to. `repository.observe*()` resolves the
     * provider's CURRENT database when the flow is CREATED, while every
     * write resolves it per call — so the pre-3.5 shape (chain built
     * once at construction) stranded the collector on the previous,
     * by-then-CLOSED database after an account switch, and a swiped
     * note — archived in the live database — never left the list: its
     * row stayed composed at the dismissed offset as a tappable ghost
     * (the Phase 3.5 recording). Rebinding per [DatabaseProvider.accountId]
     * keeps observation on the same instance the writes land in,
     * regardless of whether the shell above recreates this ViewModel.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<NotesUiState> = provider.accountId
        .flatMapLatest { libraryState() }
        .combine(selectionFlow) { state, selection ->
            // Performance chapter 2 (F5): selection is applied
            // OUTSIDE the library pipeline. It used to be a
            // combine input of libraryState(), so every toggle
            // re-ran the whole enrichment — a fresh NoteListItem
            // for every note in the library, plus grouping and a
            // full re-sort — to change a set the rows only read.
            // Now a toggle copies the state shell and intersects
            // against the SAME items list instance; no row item
            // is rebuilt.
            state.copy(
                selection = selection.intersect(state.items.map { it.note.id }.toSet()),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    /** Phase 3.5 (D3): the user's swipe actions for the main list. */
    val swipePreferences: StateFlow<SwipePreferences> = combine(
        preferences.swipeRightAction,
        preferences.swipeLeftAction,
    ) { right, left -> SwipePreferences(right = right, left = left) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SwipePreferences())

    /** PR #52: ask before a swipe archives or deletes (Settings →
     *  Notes list). Default off — swipes behave exactly as before
     *  until the user turns it on. Null until DataStore delivers the
     *  saved value: the gate treats "not loaded yet" as ON for the
     *  destructive kinds, so a swipe in the startup window can never
     *  slip past a confirmation the user asked for (Codex R1). */
    val swipeConfirmDestructive: StateFlow<Boolean?> = preferences.swipeConfirmDestructive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun libraryState(): Flow<NotesUiState> {
        // One item cache per library chain (i.e. per account):
        // enrichment results are shared across emissions for as
        // long as the chain lives — see NoteListItemCache (F5).
        val itemCache = NoteListItemCache()
        return combine(
            repository.observeActiveRows(),
            repository.observeArchivedRows(),
            repository.observeTrashedRows(),
            repository.observeFolders(),
            repository.observeTags(),
        ) { active, archived, trashed, folders, tags -> LibrarySnapshot(active, archived, trashed, folders, tags) }
            .combine(repository.observeAllCrossRefs()) { snap, crossRefs -> snap.copy(crossRefs = crossRefs) }
            .combine(repository.observeAttachments()) { snap, attachments -> snap.copy(attachments = attachments) }
            .combine(filterFlow) { snap, filter -> snap.copy(filter = filter) }
            .combine(sortFlow) { snap, sort ->
                snap.copy(sort = sort).toUiState(itemCache) { attachment ->
                    // Attachment localPaths are relative to filesDir
                    // (attachments/<noteId>/<file>); Coil cannot load a bare
                    // relative string, so resolve through the store (Codex
                    // fix G2). Paths already absolute pass through unchanged.
                    if (attachment.localPath.startsWith("/")) {
                        attachment.localPath
                    } else {
                        attachmentStore.fileFor(attachment).absolutePath
                    }
                }
            }
    }

    private data class LibrarySnapshot(
        val active: List<app.nota.data.db.NoteListRow>,
        val archived: List<app.nota.data.db.NoteListRow>,
        val trashed: List<app.nota.data.db.NoteListRow>,
        val folders: List<FolderEntity>,
        val tags: List<TagEntity>,
        val crossRefs: List<NoteTagCrossRef> = emptyList(),
        val attachments: List<AttachmentEntity> = emptyList(),
        val filter: NotesFilter = NotesFilter.All,
        val sort: SortOrder = SortOrder.UpdatedDesc,
    ) {
        fun toUiState(
            itemCache: NoteListItemCache,
            coverPath: (AttachmentEntity) -> String,
        ): NotesUiState {
            // Performance chapter 2 (F5, Codex R6): prune the
            // item cache to the notes that still exist. The
            // snapshot ALWAYS carries the full library — the
            // active, archived and trashed row queries partition
            // every note row between them (trashedAt null/not-null
            // × archived 0/1) — so their id union is the complete
            // live set, and an id missing from all three belongs
            // to a hard-deleted note whose cached item (row
            // excerpt included) must not stay reachable for the
            // chain's lifetime. The filter below only picks which
            // slice gets enriched; it never narrows this set, so
            // a pass composed under a folder/tag/trash view cannot
            // evict notes that merely sit outside that view.
            val liveIds = HashSet<String>(active.size + archived.size + trashed.size)
            active.forEach { liveIds += it.id }
            archived.forEach { liveIds += it.id }
            trashed.forEach { liveIds += it.id }
            itemCache.retainOnly(liveIds)
            val tagsById = tags.associateBy { it.id }
            val foldersById = folders.associateBy { it.id }
            val tagIdsByNote = crossRefs.groupBy({ it.noteId }, { it.tagId })
            val attachmentsByNote = attachments.groupBy { it.noteId }
            fun enrich(note: app.nota.data.db.NoteListRow): NoteListItem {
                val noteTagIds = tagIdsByNote[note.id].orEmpty().toSet()
                val noteAttachments = attachmentsByNote[note.id].orEmpty()
                // Phase 2b: a locked note never shows a cover — its
                // attachment files hold envelopes, not images.
                val coverImagePath = if (note.locked) {
                    null
                } else {
                    noteAttachments.firstOrNull { it.mimeType.startsWith("image/") }
                        ?.let(coverPath)
                }
                // F5: share the item instance across emissions
                // whenever every enrichment input is unchanged —
                // the row composable then sees the identical item
                // and skips work for notes nothing happened to.
                return itemCache.item(
                    note = note,
                    folderName = note.folderId?.let { foldersById[it]?.name },
                    folderColorArgb = note.folderId?.let { foldersById[it]?.colorArgb },
                    tagNames = noteTagIds.mapNotNull { tagsById[it]?.name }.sorted(),
                    tagIds = noteTagIds,
                    attachmentCount = noteAttachments.size,
                    coverImagePath = coverImagePath,
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
            // F5: under UpdatedDesc the ACTIVE-derived lists need
            // no re-sort at all — the DAO already emitted them in
            // exactly the comparator's order (pinned DESC,
            // updatedAt DESC) and filtering preserves order. The
            // archived query has no pinned term and the trash
            // query orders by trashedAt, so those two (and the
            // other sort orders everywhere) keep the in-memory
            // sort.
            val sorted = if (
                sort == SortOrder.UpdatedDesc &&
                filter != NotesFilter.Archived && filter != NotesFilter.Trash
            ) {
                source
            } else {
                when (sort) {
                    SortOrder.UpdatedDesc -> source.sortedWith(compareByDescending<NoteListItem> { it.note.pinned }.thenByDescending { it.note.updatedAt })
                    SortOrder.CreatedDesc -> source.sortedWith(compareByDescending<NoteListItem> { it.note.pinned }.thenByDescending { it.note.createdAt })
                    SortOrder.TitleAsc -> source.sortedWith(compareByDescending<NoteListItem> { it.note.pinned }.thenBy { it.note.title.lowercase() })
                }
            }
            return NotesUiState(
                items = sorted,
                folders = folders,
                tags = tags,
                filter = filter,
                sort = sort,
            )
        }
    }

    /** Performance chapter 2 (F5): structural sharing for row
     *  items. Enrichment (folder/tag/attachment lookups + the
     *  [NoteListItem] itself) is a pure function of the inputs
     *  below; when every input for a note is unchanged since the
     *  last emission, the SAME instance is handed back, so a
     *  one-note change rebuilds exactly one row's item and a
     *  selection toggle rebuilds none. Keyed by note id with the
     *  full input tuple as the validity check — note ids are
     *  UUIDs, so entries can never collide across accounts, and
     *  the cache lives as long as its library chain. Entries do
     *  NOT live that long by default, though: every pass prunes
     *  the cache to the ids the snapshot still holds (see
     *  [retainOnly]), so a hard-deleted note's item is dropped
     *  on the next emission instead of staying reachable — row
     *  excerpt and all — until the chain dies. */
    private class NoteListItemCache {
        private data class Inputs(
            val row: app.nota.data.db.NoteListRow,
            val folderName: String?,
            val folderColorArgb: Int?,
            val tagNames: List<String>,
            val tagIds: Set<String>,
            val attachmentCount: Int,
            val coverImagePath: String?,
        )

        private val cache = HashMap<String, Pair<Inputs, NoteListItem>>()

        /** Drops every cached entry whose note id is not in
         *  [liveIds]. Called once per toUiState pass with the
         *  FULL library's id set (the snapshot's active +
         *  archived + trashed union — never a filtered view), so
         *  surviving notes keep their entries, and with them
         *  their shared item identity, untouched. */
        fun retainOnly(liveIds: Set<String>) {
            cache.keys.retainAll(liveIds)
        }

        fun item(
            note: app.nota.data.db.NoteListRow,
            folderName: String?,
            folderColorArgb: Int?,
            tagNames: List<String>,
            tagIds: Set<String>,
            attachmentCount: Int,
            coverImagePath: String?,
        ): NoteListItem {
            val inputs = Inputs(
                note, folderName, folderColorArgb, tagNames, tagIds,
                attachmentCount, coverImagePath,
            )
            val hit = cache[note.id]
            if (hit != null && hit.first == inputs) return hit.second
            val item = NoteListItem(
                note = note,
                folderName = folderName,
                folderColorArgb = folderColorArgb,
                tagNames = tagNames,
                tagIds = tagIds,
                attachmentCount = attachmentCount,
                coverImagePath = coverImagePath,
            )
            cache[note.id] = inputs to item
            return item
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
    //
    // Phase 6.1: list items are lightweight rows now, so a swipe op
    // fetches the note's full entity by id at action time. Undoable ops
    // hand the PRE-OP entity to [onSnapshot] BEFORE the write lands —
    // fetch and write run in one coroutine, so the snapshot is always
    // the state the op replaced, exactly what the screen's undo batch
    // re-upserts (the fidelity SwipeUndoFidelityRobolectricTest pins
    // at the repository layer is preserved end to end).
    //
    // Fix round (Codex, PR #32): the fetch is asynchronous while the
    // screen's Undo snackbar runs in parallel, so Undo can resolve
    // BEFORE the fetch lands. [onSnapshot] therefore returns whether
    // the write may proceed: when the sink reports the batch was
    // already undone, the write is vetoed outright — running it
    // anyway archived/trashed the note after Undo had appeared to
    // succeed, with nothing queued to restore it.
    private fun swipeOp(
        row: app.nota.data.db.NoteListRow,
        onSnapshot: (NoteEntity) -> Boolean,
        op: suspend (NoteEntity) -> Unit,
    ) {
        viewModelScope.launch {
            val entity = repository.getById(row.id) ?: return@launch
            if (onSnapshot(entity)) op(entity)
        }
    }

    fun archive(row: app.nota.data.db.NoteListRow, onSnapshot: (NoteEntity) -> Boolean) =
        swipeOp(row, onSnapshot) { repository.setArchived(it, true) }
    /** Phase 3.5 (D3): the Pin swipe action (the batch bar has its own). */
    fun setPinned(row: app.nota.data.db.NoteListRow, pinned: Boolean, onSnapshot: (NoteEntity) -> Boolean) =
        swipeOp(row, onSnapshot) { repository.setPinned(it, pinned) }
    fun trash(row: app.nota.data.db.NoteListRow, onSnapshot: (NoteEntity) -> Boolean) =
        swipeOp(row, onSnapshot) { repository.trash(it) }
    fun restore(row: app.nota.data.db.NoteListRow, onSnapshot: (NoteEntity) -> Boolean) =
        swipeOp(row, onSnapshot) { repository.restore(it) }
    fun upsert(note: NoteEntity) { viewModelScope.launch { repository.upsert(note) } }
    fun deletePermanently(row: app.nota.data.db.NoteListRow) {
        viewModelScope.launch {
            repository.getById(row.id)?.let { repository.deletePermanently(it) }
        }
    }

    // Batch ops over the current selection (no undo — see the batch
    // bar). Phase 6.1: entities are fetched by id inside the op.
    private suspend fun selectedNotes(): List<NoteEntity> {
        val ids = selectionFlow.value
        return uiState.value.items.map { it.note.id }
            .filter { it in ids }
            .mapNotNull { repository.getById(it) }
    }
    fun batchPin() { viewModelScope.launch { selectedNotes().forEach { repository.setPinned(it, true) }; clearSelection() } }
    fun batchArchive() { viewModelScope.launch { selectedNotes().forEach { repository.setArchived(it, true) }; clearSelection() } }
    fun batchTrash() { viewModelScope.launch { selectedNotes().forEach { repository.trash(it) }; clearSelection() } }
    fun batchRestore() { viewModelScope.launch { selectedNotes().forEach { repository.restore(it) }; clearSelection() } }
    fun batchDelete() { viewModelScope.launch { selectedNotes().forEach { repository.deletePermanently(it) }; clearSelection() } }

    // Folder management (overflow dialog)
    fun createFolder(name: String, colorArgb: Int? = null) { if (name.isNotBlank()) viewModelScope.launch { library.createFolder(name, colorArgb) } }
    fun renameFolder(folder: FolderEntity, name: String) { if (name.isNotBlank()) viewModelScope.launch { library.renameFolder(folder, name) } }
    fun setFolderColor(folder: FolderEntity, colorArgb: Int?) { viewModelScope.launch { library.setFolderColor(folder, colorArgb) } }
    fun deleteFolder(folder: FolderEntity) { viewModelScope.launch { library.deleteFolder(folder) } }
}
