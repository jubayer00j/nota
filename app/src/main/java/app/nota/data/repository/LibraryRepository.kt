package app.nota.data.repository

import app.nota.data.db.FolderDao
import app.nota.data.db.FolderEntity
import app.nota.data.db.NoteDao
import app.nota.data.db.NotaDatabase
import app.nota.data.db.TagDao
import app.nota.data.db.TagEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Folder/tag library management (Phase 1b). Deleting a folder relies on the
 * notes.folderId foreign key's SET_NULL action, so its notes survive,
 * unfiled. Deleting a tag cascades its note_tags links only.
 */
class LibraryRepository(
    @Suppress("unused") private val db: NotaDatabase,
    private val folderDao: FolderDao,
    private val tagDao: TagDao,
    @Suppress("unused") private val noteDao: NoteDao,
) {
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

    suspend fun deleteFolder(folder: FolderEntity) = folderDao.delete(folder)

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

    suspend fun deleteTag(tag: TagEntity) = tagDao.delete(tag)
}
