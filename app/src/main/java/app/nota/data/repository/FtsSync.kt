package app.nota.data.repository

import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteFtsEntity
import app.nota.data.db.NotaDatabase

/**
 * The one builder of FTS rows (Search Quality Pass, v0.27.0).
 *
 * Until this pass, each write path assembled its NoteFtsEntity
 * inline — fine when a row was just (title, plainText), but the
 * v8 row also denormalises the note's tag names and folder name,
 * and those change through paths that never touch the note
 * itself (tag edits, folder renames). Every FTS write in the
 * app — note upserts, tag assignment, folder/tag management in
 * LibraryRepository, the backup/guest-transfer index rebuild —
 * now goes through here, so the row's contents have exactly one
 * definition:
 *
 * - a trashed note has NO row (search never surfaces trash);
 * - a locked note's plainText is EMPTY (the sealed body is
 *   never indexed) while its title, tag names and folder name
 *   stay searchable — 1.x's exact exposure;
 * - tag names join with single spaces, the same haystack shape
 *   the scorer ([NoteSearch.score]) reads.
 */
internal suspend fun buildFtsEntity(db: NotaDatabase, note: NoteEntity): NoteFtsEntity? {
    if (note.trashedAt != null) return null
    val tagNames = db.tagDao().getTagNamesForNote(note.id)
    val folderName = note.folderId?.let { db.folderDao().getById(it)?.name } ?: ""
    return NoteFtsEntity(
        noteId = note.id,
        title = note.title,
        plainText = if (note.locked) "" else note.plainText,
        tagsText = tagNames.joinToString(" "),
        folderName = folderName,
    )
}

/**
 * Replaces [note]'s FTS row with a freshly built one (or with
 * nothing, when the note is trashed). Callers run this inside
 * their write transaction, like every FTS write before it.
 */
internal suspend fun NotaDatabase.refreshFtsEntry(note: NoteEntity) {
    val ftsDao = noteFtsDao()
    ftsDao.deleteForNote(note.id)
    buildFtsEntity(this, note)?.let { ftsDao.insert(it) }
}

/**
 * [refreshFtsEntry] for a set of note ids, re-reading each note
 * first — the shape the folder/tag refresh paths need, where
 * the note rows themselves may have just changed (a folder
 * delete nulls folderId through the FK before the refresh runs,
 * so the builder must see the post-delete row).
 */
internal suspend fun NotaDatabase.refreshFtsEntries(noteIds: Collection<String>) {
    if (noteIds.isEmpty()) return
    val noteDao = noteDao()
    for (id in noteIds) {
        val note = noteDao.getById(id) ?: continue
        refreshFtsEntry(note)
    }
}
