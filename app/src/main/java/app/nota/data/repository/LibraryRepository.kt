package app.nota.data.repository

import app.nota.data.db.DatabaseProvider
import app.nota.data.db.FolderEntity
import app.nota.data.db.TagEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Folder/tag library management (Phase 1b). Deleting a folder relies on the
 * notes.folderId foreign key's SET_NULL action, so its notes survive,
 * unfiled. Deleting a tag cascades its note_tags links only.
 *
 * Phase 3a: DAOs resolve through [DatabaseProvider] at call time (see
 * NoteRepository's note) — never captured.
 */
class LibraryRepository(
    private val provider: DatabaseProvider,
) {
    private val db get() = provider.current
    private val folderDao get() = db.folderDao()
    private val tagDao get() = db.tagDao()
    fun observeFolders(): Flow<List<FolderEntity>> = folderDao.observeAll()
    fun observeTags(): Flow<List<TagEntity>> = tagDao.observeAll()

    suspend fun createFolder(name: String, colorArgb: Int? = null): FolderEntity {
        val now = System.currentTimeMillis()
        val folder = FolderEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            colorArgb = colorArgb,
            createdAt = now,
            updatedAt = now,
        )
        folderDao.upsert(folder)
        return folder
    }

    suspend fun renameFolder(folder: FolderEntity, name: String): FolderEntity {
        val updated = folder.copy(name = name.trim(), updatedAt = System.currentTimeMillis())
        folderDao.upsert(updated)
        return updated
    }

    /** Sets (or clears, with null) a folder's cover colour (spec §12 folder covers). */
    suspend fun setFolderColor(folder: FolderEntity, colorArgb: Int?): FolderEntity {
        val updated = folder.copy(colorArgb = colorArgb, updatedAt = System.currentTimeMillis())
        folderDao.upsert(updated)
        return updated
    }

    suspend fun deleteFolder(folder: FolderEntity) {
        // Phase 3c: sync tombstone (see NoteRepository.delete).
        app.nota.data.sync.SyncStore(db).recordTombstone(
            app.nota.data.sync.EntityHash.ENTITY_FOLDER,
            folder.id,
            System.currentTimeMillis(),
        )
        folderDao.delete(folder)
    }

    suspend fun createTag(name: String, colorArgb: Int? = null): TagEntity {
        val cleaned = name.trim()
        tagDao.getByName(cleaned)?.let { return it }
        val tag = TagEntity(
            id = UUID.randomUUID().toString(),
            name = cleaned,
            colorArgb = colorArgb,
            createdAt = System.currentTimeMillis(),
        )
        tagDao.upsert(tag)
        return tag
    }

    suspend fun renameTag(tag: TagEntity, name: String): TagEntity {
        val updated = tag.copy(name = name.trim())
        tagDao.upsert(updated)
        return updated
    }

    suspend fun deleteTag(tag: TagEntity) {
        // Phase 3c: sync tombstone (see NoteRepository.delete).
        app.nota.data.sync.SyncStore(db).recordTombstone(
            app.nota.data.sync.EntityHash.ENTITY_TAG,
            tag.id,
            System.currentTimeMillis(),
        )
        tagDao.delete(tag)
    }
}
