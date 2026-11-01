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

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)

    @Query(
        """
        SELECT n.* FROM notes n
        JOIN notes_fts f ON f.noteId = n.id
        WHERE notes_fts MATCH :query AND n.trashedAt IS NULL AND n.archived = 0 AND n.locked = 0
        ORDER BY n.updatedAt DESC
        """,
    )
    suspend fun searchFts(query: String): List<NoteEntity>
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<FolderEntity>>

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

    @Upsert
    suspend fun upsert(attachment: AttachmentEntity)

    @Delete
    suspend fun delete(attachment: AttachmentEntity)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE enabled = 1 ORDER BY triggerAt ASC")
    fun observeEnabled(): Flow<List<ReminderEntity>>

    @Upsert
    suspend fun upsert(reminder: ReminderEntity)

    @Delete
    suspend fun delete(reminder: ReminderEntity)
}

@Dao
interface NoteVersionDao {
    @Query("SELECT * FROM note_versions WHERE noteId = :noteId ORDER BY createdAt DESC")
    fun observeForNote(noteId: String): Flow<List<NoteVersionEntity>>

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
