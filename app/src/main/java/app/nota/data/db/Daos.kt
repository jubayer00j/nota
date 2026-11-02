package app.nota.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE trashedAt IS NULL AND archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeActive(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE trashedAt IS NULL AND archived = 1 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC")
    fun observeTrashed(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE folderId = :folderId AND trashedAt IS NULL AND archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeByFolder(folderId: String): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT n.* FROM notes n
        JOIN note_tags nt ON nt.noteId = n.id
        WHERE nt.tagId = :tagId AND n.trashedAt IS NULL AND n.archived = 0
        ORDER BY n.pinned DESC, n.updatedAt DESC
        """,
    )
    fun observeByTag(tagId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE favourite = 1 AND trashedAt IS NULL AND archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeFavourites(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE dailyDate = :date ORDER BY createdAt ASC")
    fun observeDaily(date: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE dailyDate = :date LIMIT 1")
    suspend fun getDaily(date: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE title = :title LIMIT 1")
    suspend fun getByTitle(title: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    /** Every note, for backlink re-resolution and the startup backfill. */
    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    /** Notes that can be the target of a [[backlink]] (not trashed/locked). */
    @Query("SELECT * FROM notes WHERE trashedAt IS NULL AND locked = 0 ORDER BY title COLLATE NOCASE ASC")
    fun observeLinkable(): Flow<List<NoteEntity>>

    @Query("DELETE FROM notes")
    suspend fun deleteAll()

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)

    // Phase 2b: locked notes DO appear in search — their FTS row carries
    // the title only (plainText is empty under the seal, see
    // NoteRepository.upsert), so a body word can never match them, while a
    // title word finds the locked placeholder, exactly like 1.x.
    @Query(
        """
        SELECT n.* FROM notes n
        JOIN notes_fts f ON f.noteId = n.id
        WHERE notes_fts MATCH :query AND n.trashedAt IS NULL AND n.archived = 0
        ORDER BY n.updatedAt DESC
        """,
    )
    suspend fun searchFts(query: String): List<NoteEntity>
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: String): FolderEntity?

    @Query("DELETE FROM folders")
    suspend fun deleteAll()

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Delete
    suspend fun delete(folder: FolderEntity)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Query("SELECT * FROM tags")
    suspend fun getAllTags(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: String): TagEntity?

    @Query("DELETE FROM tags")
    suspend fun deleteAllTags()

    @Query("DELETE FROM note_tags")
    suspend fun deleteAllCrossRefs()

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Delete
    suspend fun delete(tag: TagEntity)

    @Query("SELECT t.* FROM tags t JOIN note_tags nt ON nt.tagId = t.id WHERE nt.noteId = :noteId ORDER BY t.name ASC")
    fun observeForNote(noteId: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM note_tags")
    fun observeAllCrossRefs(): Flow<List<NoteTagCrossRef>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(crossRef: NoteTagCrossRef)

    @Query("DELETE FROM note_tags WHERE noteId = :noteId AND tagId = :tagId")
    suspend fun unlink(noteId: String, tagId: String)

    @Query("DELETE FROM note_tags WHERE noteId = :noteId")
    suspend fun unlinkAllForNote(noteId: String)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE noteId = :noteId ORDER BY createdAt ASC")
    fun observeForNote(noteId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE noteId = :noteId ORDER BY createdAt ASC")
    suspend fun getForNote(noteId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments")
    suspend fun getAll(): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun getById(id: String): AttachmentEntity?

    @Query("DELETE FROM attachments")
    suspend fun deleteAll()

    @Upsert
    suspend fun upsert(attachment: AttachmentEntity)

    @Delete
    suspend fun delete(attachment: AttachmentEntity)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE enabled = 1 ORDER BY triggerAt ASC")
    fun observeEnabled(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE noteId = :noteId")
    suspend fun getForNote(noteId: String): List<ReminderEntity>

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()

    @Upsert
    suspend fun upsert(reminder: ReminderEntity)

    @Delete
    suspend fun delete(reminder: ReminderEntity)
}

@Dao
interface NoteVersionDao {
    @Query("SELECT * FROM note_versions WHERE noteId = :noteId ORDER BY createdAt DESC")
    fun observeForNote(noteId: String): Flow<List<NoteVersionEntity>>

    /** Phase 3a: bulk read for the guest→account library copy. */
    @Query("SELECT * FROM note_versions")
    suspend fun getAll(): List<NoteVersionEntity>

    @Insert
    suspend fun insert(version: NoteVersionEntity)
}

/** Keeps [NoteFtsEntity] in sync with note writes; called by the repository in the
 *  same transaction as the note upsert/delete. */
@Dao
interface NoteFtsDao {
    @Insert
    suspend fun insert(entry: NoteFtsEntity)

    @Query("DELETE FROM notes_fts WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: String)

    @Query("DELETE FROM notes_fts")
    suspend fun deleteAll()
}

@Dao
interface SavedSearchDao {
    @Query("SELECT * FROM saved_searches ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SavedSearchEntity>>

    @Upsert
    suspend fun upsert(search: SavedSearchEntity)

    @Delete
    suspend fun delete(search: SavedSearchEntity)
}

/** Resolved [[backlink]] edges (Phase 1c); written only by NoteRepository. */
@Dao
interface NoteLinkDao {
    /** Notes that link TO [noteId] ("Linked from…"), newest first. */
    @Query(
        """
        SELECT n.* FROM notes n
        JOIN note_links l ON l.sourceId = n.id
        WHERE l.targetId = :noteId AND n.trashedAt IS NULL AND n.locked = 0
        ORDER BY n.updatedAt DESC
        """,
    )
    fun observeIncoming(noteId: String): Flow<List<NoteEntity>>

    @Query("SELECT COUNT(*) FROM note_links")
    suspend fun count(): Int

    @Query("DELETE FROM note_links WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    @Query("DELETE FROM note_links")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(links: List<NoteLinkEntity>)
}
