package app.nota.data.repository

import androidx.room.withTransaction
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
        db.withTransaction {
            folderDao.upsert(updated)
            // Search Quality Pass: the folder's name is
            // denormalised into every filed note's FTS row — a
            // rename re-indexes the folder's notes, or search
            // keeps matching the old name (and missing the new).
            db.refreshFtsEntries(db.noteDao().getIdsInFolder(folder.id))
        }
        return updated
    }

    /** Sets (or clears, with null) a folder's cover colour (spec §12 folder covers). */
    suspend fun setFolderColor(folder: FolderEntity, colorArgb: Int?): FolderEntity {
        val updated = folder.copy(colorArgb = colorArgb, updatedAt = System.currentTimeMillis())
        folderDao.upsert(updated)
        return updated
    }

    suspend fun deleteFolder(folder: FolderEntity) {
        // Search Quality Pass: capture the filed notes BEFORE
        // the delete — the FK's SET_NULL unfiles them, and their
        // FTS rows must lose the folder name in the same write.
        val affectedIds = db.noteDao().getIdsInFolder(folder.id)
        // Phase 3c: sync tombstone (see NoteRepository.delete).
        app.nota.data.sync.SyncStore(db).recordTombstone(
            app.nota.data.sync.EntityHash.ENTITY_FOLDER,
            folder.id,
            System.currentTimeMillis(),
        )
        folderDao.delete(folder)
        db.refreshFtsEntries(affectedIds)
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
        db.withTransaction {
            tagDao.upsert(updated)
            // Search Quality Pass: tag names are denormalised
            // into their notes' FTS rows (see renameFolder).
            db.refreshFtsEntries(tagDao.getNoteIdsForTag(tag.id))
        }
        return updated
    }

    suspend fun deleteTag(tag: TagEntity) {
        // Search Quality Pass: capture the tagged notes before
        // the cascade removes their links (see deleteFolder).
        val affectedIds = tagDao.getNoteIdsForTag(tag.id)
        // Phase 3c: sync tombstone (see NoteRepository.delete).
        app.nota.data.sync.SyncStore(db).recordTombstone(
            app.nota.data.sync.EntityHash.ENTITY_TAG,
            tag.id,
            System.currentTimeMillis(),
        )
        tagDao.delete(tag)
        db.refreshFtsEntries(affectedIds)
    }
}
