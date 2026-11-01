package app.nota.data.repository

import androidx.room.withTransaction
import app.nota.data.db.NoteDao
import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteFtsDao
import app.nota.data.db.NoteFtsEntity
import app.nota.data.db.NotaDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Notes repository — the single write path for notes in Phase 0.
 * Every upsert/delete keeps the FTS4 index in sync inside the same transaction,
 * the discipline 1.x learned with its search index (stale vectors must never
 * surface notes that no longer match).
 */
class NoteRepository(
    private val db: NotaDatabase,
    private val noteDao: NoteDao,
    private val ftsDao: NoteFtsDao,
) {
    fun observeActive(): Flow<List<NoteEntity>> = noteDao.observeActive()

    fun observeById(id: String): Flow<NoteEntity?> = noteDao.observeById(id)

    suspend fun upsert(note: NoteEntity) {
        db.withTransaction {
            noteDao.upsert(note)
            ftsDao.deleteForNote(note.id)
            if (note.trashedAt == null && !note.locked) {
                ftsDao.insert(
                    NoteFtsEntity(noteId = note.id, title = note.title, plainText = note.plainText),
                )
            }
        }
    }

    suspend fun delete(note: NoteEntity) {
        db.withTransaction {
            noteDao.delete(note)
            ftsDao.deleteForNote(note.id)
        }
    }

    suspend fun search(query: String): List<NoteEntity> {
        // Tokens are wrapped in double quotes to form FTS phrases, so strip any
        // embedded quotes first — a raw `"` would unbalance the phrase and make
        // SQLite reject the MATCH expression outright.
        val match = query.trim().split(Regex("\\s+"))
            .map { it.replace("\"", "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "\"$it\"*" }
        if (match.isEmpty()) return emptyList()
        return noteDao.searchFts(match)
    }
}
