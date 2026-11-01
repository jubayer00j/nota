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
        WHERE notes_fts MATCH :query AND n.trashedAt IS NULL
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

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Query("SELECT t.* FROM tags t JOIN note_tags nt ON nt.tagId = t.id WHERE nt.noteId = :noteId ORDER BY t.name ASC")
    fun observeForNote(noteId: String): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(crossRef: NoteTagCrossRef)

    @Query("DELETE FROM note_tags WHERE noteId = :noteId AND tagId = :tagId")
    suspend fun unlink(noteId: String, tagId: String)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE noteId = :noteId ORDER BY createdAt ASC")
    fun observeForNote(noteId: String): Flow<List<AttachmentEntity>>

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
