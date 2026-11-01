package app.nota.data.repository

import androidx.room.withTransaction
import app.nota.data.db.AttachmentDao
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.FolderDao
import app.nota.data.db.FolderEntity
import app.nota.data.db.NoteDao
import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteFtsDao
import app.nota.data.db.NoteFtsEntity
import app.nota.data.db.NoteLinkDao
import app.nota.data.db.NoteLinkEntity
import app.nota.data.db.NoteTagCrossRef
import app.nota.data.db.NotaDatabase
import app.nota.data.db.SavedSearchDao
import app.nota.data.db.SavedSearchEntity
import app.nota.data.db.TagDao
import app.nota.data.db.TagEntity
import app.nota.data.html.NoteDocument
import app.nota.data.text.PlainTextCodec
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Notes repository — the single write path for notes.
 * Every upsert/delete keeps the FTS4 index in sync inside the same transaction.
 * Phase 1b grows it into the notes core: create/update helpers, state ops,
 * tag assignment, daily notes and saved searches.
 */
class NoteRepository(
    private val db: NotaDatabase,
    private val noteDao: NoteDao,
    private val ftsDao: NoteFtsDao,
    private val folderDao: FolderDao,
    private val tagDao: TagDao,
    private val attachmentDao: AttachmentDao,
    private val savedSearchDao: SavedSearchDao,
    private val noteLinkDao: NoteLinkDao,
) {
    // --- Observation -------------------------------------------------------
    fun observeActive(): Flow<List<NoteEntity>> = noteDao.observeActive()
    fun observeArchived(): Flow<List<NoteEntity>> = noteDao.observeArchived()
    fun observeTrashed(): Flow<List<NoteEntity>> = noteDao.observeTrashed()
    fun observeByFolder(folderId: String): Flow<List<NoteEntity>> = noteDao.observeByFolder(folderId)
    fun observeByTag(tagId: String): Flow<List<NoteEntity>> = noteDao.observeByTag(tagId)
    fun observeFavourites(): Flow<List<NoteEntity>> = noteDao.observeFavourites()
    fun observeDaily(date: String): Flow<List<NoteEntity>> = noteDao.observeDaily(date)
    fun observeById(id: String): Flow<NoteEntity?> = noteDao.observeById(id)
    fun observeFolders(): Flow<List<FolderEntity>> = folderDao.observeAll()
    fun observeTags(): Flow<List<TagEntity>> = tagDao.observeAll()
    fun observeTagsForNote(noteId: String): Flow<List<TagEntity>> = tagDao.observeForNote(noteId)
    fun observeAllCrossRefs(): Flow<List<NoteTagCrossRef>> = tagDao.observeAllCrossRefs()
    fun observeAttachments(): Flow<List<AttachmentEntity>> = attachmentDao.observeAll()
    fun observeAttachmentsForNote(noteId: String): Flow<List<AttachmentEntity>> =
        attachmentDao.observeForNote(noteId)
    fun observeSavedSearches(): Flow<List<SavedSearchEntity>> = savedSearchDao.observeAll()

    suspend fun getById(id: String): NoteEntity? = noteDao.getById(id)

    // --- Core write path (FTS-synced) --------------------------------------
    suspend fun upsert(note: NoteEntity) {
        db.withTransaction {
            noteDao.upsert(note)
            ftsDao.deleteForNote(note.id)
            if (note.trashedAt == null && !note.locked) {
                ftsDao.insert(
                    NoteFtsEntity(noteId = note.id, title = note.title, plainText = note.plainText),
                )
            }
            relink(note)
        }
    }

    /**
     * Recomputes [note]'s outgoing [[backlink]] edges from its plain text.
     * Runs inside the caller's transaction (the upsert/delete write path),
     * so edges can never diverge from the stored body. Locked or trashed
     * notes hold no resolvable body text here, so their edges are cleared.
     */
    private suspend fun relink(note: NoteEntity) {
        noteLinkDao.deleteForSource(note.id)
        if (note.locked || note.trashedAt != null) return
        val titles = NoteDocument.backlinkTitles(note.plainText)
        if (titles.isEmpty()) return
        val links = titles.mapNotNull { title ->
            val target = noteDao.getByTitle(title)
            if (target != null && target.id != note.id && !target.locked) {
                NoteLinkEntity(sourceId = note.id, targetId = target.id)
            } else {
                null
            }
        }
        if (links.isNotEmpty()) noteLinkDao.insertAll(links)
    }

    suspend fun delete(note: NoteEntity) {
        db.withTransaction {
            noteDao.delete(note)
            ftsDao.deleteForNote(note.id)
        }
    }

    suspend fun deletePermanently(note: NoteEntity) = delete(note)

    // --- Create / edit -------------------------------------------------------
    suspend fun createNote(
        title: String = "",
        plainBody: String = "",
        dailyDate: String? = null,
        folderId: String? = null,
    ): NoteEntity {
        val now = System.currentTimeMillis()
        val note = NoteEntity(
            id = UUID.randomUUID().toString(),
            folderId = folderId,
            title = title,
            contentHtml = PlainTextCodec.textToHtml(plainBody),
            plainText = plainBody,
            createdAt = now,
            updatedAt = now,
            dailyDate = dailyDate,
        )
        upsert(note)
        return note
    }

    suspend fun updateBody(note: NoteEntity, title: String, plainBody: String): NoteEntity {
        val updated = note.copy(
            title = title,
            contentHtml = PlainTextCodec.textToHtml(plainBody),
            plainText = plainBody,
            updatedAt = System.currentTimeMillis(),
        )
        upsert(updated)
        return updated
    }

    /**
     * Title-only update that leaves the body untouched. The plain editor
     * uses this for rich-bodied notes (see [app.nota.data.text.RichBody]):
     * their HTML must not be rewritten by the plain codec.
     */
    suspend fun updateTitle(note: NoteEntity, title: String): NoteEntity {
        val updated = note.copy(title = title, updatedAt = System.currentTimeMillis())
        upsert(updated)
        return updated
    }

    // --- Rich create / edit (Phase 1c) --------------------------------------
    /** Creates a note from canonical HTML (rich editor, templates, daily notes). */
    suspend fun createRichNote(
        title: String = "",
        contentHtml: String = "",
        folderId: String? = null,
        dailyDate: String? = null,
    ): NoteEntity {
        val now = System.currentTimeMillis()
        val note = NoteEntity(
            id = UUID.randomUUID().toString(),
            folderId = folderId,
            title = title,
            contentHtml = contentHtml,
            plainText = NoteDocument.plainText(NoteDocument.parse(contentHtml)),
            createdAt = now,
            updatedAt = now,
            dailyDate = dailyDate,
        )
        upsert(note)
        return note
    }

    /** Replaces a note's title + HTML body, re-deriving plain text, FTS and links. */
    suspend fun updateRichBody(note: NoteEntity, title: String, contentHtml: String): NoteEntity {
        val updated = note.copy(
            title = title,
            contentHtml = contentHtml,
            plainText = NoteDocument.plainText(NoteDocument.parse(contentHtml)),
            updatedAt = System.currentTimeMillis(),
        )
        upsert(updated)
        return updated
    }

    // --- Backlinks (Phase 1c) --------------------------------------------------
    /** Notes whose bodies reference [noteId] via [[title]] — "Linked from…". */
    fun observeLinkedFrom(noteId: String): Flow<List<NoteEntity>> = noteLinkDao.observeIncoming(noteId)

    /** Notes eligible as [[ ]] autocomplete targets. */
    fun observeLinkable(): Flow<List<NoteEntity>> = noteDao.observeLinkable()

    // --- State ops (copies through the FTS-synced upsert) --------------------
    suspend fun setPinned(note: NoteEntity, pinned: Boolean) =
        upsert(note.copy(pinned = pinned, updatedAt = System.currentTimeMillis()))

    suspend fun setFavourite(note: NoteEntity, favourite: Boolean) =
        upsert(note.copy(favourite = favourite, updatedAt = System.currentTimeMillis()))

    suspend fun setArchived(note: NoteEntity, archived: Boolean) =
        upsert(note.copy(archived = archived, updatedAt = System.currentTimeMillis()))

    suspend fun trash(note: NoteEntity) =
        upsert(note.copy(trashedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))

    suspend fun restore(note: NoteEntity) =
        upsert(note.copy(trashedAt = null, archived = false, updatedAt = System.currentTimeMillis()))

    suspend fun moveToFolder(note: NoteEntity, folderId: String?) =
        upsert(note.copy(folderId = folderId, updatedAt = System.currentTimeMillis()))

    // --- Tags ------------------------------------------------------------------
    /**
     * Replaces a note's tags with [tagNames]: names are trimmed, blanks dropped
     * and duplicates removed case-insensitively (first typed form wins and is
     * stored as typed). Missing tags are created; links are replaced in one
     * transaction.
     */
    suspend fun setNoteTags(noteId: String, tagNames: List<String>) {
        val cleaned = LinkedHashMap<String, String>()
        for (raw in tagNames) {
            val name = raw.trim()
            if (name.isNotEmpty()) cleaned.putIfAbsent(name.lowercase(), name)
        }
        db.withTransaction {
            tagDao.unlinkAllForNote(noteId)
            val now = System.currentTimeMillis()
            for (name in cleaned.values) {
                val tag = tagDao.getByName(name)
                    ?: TagEntity(id = UUID.randomUUID().toString(), name = name, createdAt = now)
                        .also { tagDao.upsert(it) }
                tagDao.link(NoteTagCrossRef(noteId = noteId, tagId = tag.id))
            }
        }
    }

    // --- Daily notes -------------------------------------------------------------
    /** Returns the daily note for [date] ("yyyy-MM-dd"), creating it if needed. */
    suspend fun ensureDailyNote(date: String): NoteEntity {
        noteDao.getDaily(date)?.let { return it }
        return createNote(title = date, plainBody = "", dailyDate = date)
    }

    // --- Saved searches ------------------------------------------------------------
    suspend fun saveSearch(name: String, query: String): SavedSearchEntity {
        val search = SavedSearchEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            query = query,
            createdAt = System.currentTimeMillis(),
        )
        savedSearchDao.upsert(search)
        return search
    }

    suspend fun upsertSavedSearch(search: SavedSearchEntity) = savedSearchDao.upsert(search)

    suspend fun deleteSavedSearch(search: SavedSearchEntity) = savedSearchDao.delete(search)

    // --- Search ---------------------------------------------------------------------
    suspend fun searchNotes(raw: String): List<NoteEntity> {
        val match = FtsQueryBuilder.build(raw) ?: return emptyList()
        return noteDao.searchFts(match)
    }

    /** Phase 0 name, kept as a delegate. */
    suspend fun search(query: String): List<NoteEntity> = searchNotes(query)
}
