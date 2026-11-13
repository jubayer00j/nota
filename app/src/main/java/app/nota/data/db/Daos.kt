package app.nota.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Phase 6.1: the lightweight note projection every list-shaped surface
 * renders from. Deliberately NOT a [NoteEntity]: no contentHtml, no
 * lockEnvelope/lockRecordJson, and the plain text arrives pre-cut to a
 * snippet ([excerpt], 240 chars in SQL). A locked note's excerpt is
 * empty by construction (its stored plainText is empty under the seal),
 * so a row can never leak locked body text.
 */
data class NoteListRow(
    val id: String,
    val folderId: String?,
    val title: String,
    val excerpt: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val favourite: Boolean,
    val archived: Boolean,
    val trashedAt: Long?,
    val colorArgb: Int?,
    val dailyDate: String?,
    val locked: Boolean,
)

/** Phase 6.1: the (id, title, plainText) input of the semantic index's
 *  eligibility count — the only columns that check reads. */
data class NoteTextRow(
    val id: String,
    val title: String,
    val plainText: String,
)

/** Performance chapter 2 (F3/F6): one aggregate row describing the
 *  whole notes table, cheap enough to read on every search keystroke
 *  and every semantic reconcile, used as the invalidation key for
 *  the corpus cache and the semantic reconcile stamp gate.
 *
 *  The five aggregates describe the library's shape — note count,
 *  newest edit, total edit-time mass, total title/body lengths —
 *  but on their own they cannot see every change: an edit that
 *  swaps title/body text for different text of the SAME lengths
 *  with updatedAt unchanged (same-millisecond successive writes,
 *  or a sync/restore payload whose updatedAt ties the local
 *  row's) moves none of them. [writeGeneration] is the component
 *  that makes the stamp change-sensitive: schema v9's SQLite
 *  triggers on the notes table increment it on EVERY insert,
 *  update or delete, whichever code path wrote (repository,
 *  SyncApplier, applyBackup, lock/unlock — see [LibraryMetaEntity]),
 *  so any write to the notes table moves the stamp and pure reads
 *  never do. The aggregates stay as the descriptive summary (and
 *  keep pre-generation readers honest); the counter is the proof. */
data class LibraryStampRow(
    val noteCount: Long,
    val maxUpdatedAt: Long,
    val sumUpdatedAt: Long,
    val sumTitleLength: Long,
    val sumPlainTextLength: Long,
    val writeGeneration: Long = 0,
)

/** Search Quality Pass: one note's scoring haystacks at text
 *  weight — the search layer scores (and windows snippets from)
 *  exactly these columns, fetched for candidate ids only, or for
 *  the whole eligible corpus when the substring fallback runs. */
data class SearchTextRow(
    val id: String,
    val title: String,
    val plainText: String,
    val locked: Boolean,
)

/** Search Quality Pass: one (note, tag name) pair — the batch
 *  shape tag haystacks are assembled from. */
data class NoteTagNameRow(
    val noteId: String,
    val tagName: String,
)

/** Phase 6.1: a [[backlink]] edge endpoint at footer weight — the
 *  editor's Links / Linked-from lists render titles and open by id. */
data class NoteLinkRow(
    val id: String,
    val title: String,
    val updatedAt: Long,
)

/** Phase 6.1: a [[ ]] autocomplete target — id + title are all the
 *  suggestion list reads. */
data class NoteLinkTarget(
    val id: String,
    val title: String,
)

@Dao
interface NoteDao {
    /** Phase 5.1: every note incl. trashed/archived — the reminder
     *  reconciler and the Reminders screen need trash state, which
     *  observeActive deliberately hides. */
    @Query("SELECT * FROM notes")
    fun observeAllNotes(): Flow<List<NoteEntity>>

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

    /** Every note, observed (Phase 4.4: the link graph's node metadata). */
    @Query("SELECT * FROM notes")
    fun observeAll(): Flow<List<NoteEntity>>

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

    // --- Phase 6.1: lightweight list rows --------------------------------
    // The list-shaped surfaces (notes list, Today, search) render title,
    // a <=3-line snippet, dates and flags only — yet the SELECT * queries
    // above materialise every note's full HTML body, full plain text and
    // lock columns on every emission. The row queries below project only
    // what those surfaces read; the excerpt is cut in SQL so even the
    // plain-text column crosses the cursor at snippet size. Locked notes
    // keep the seal trivially: their stored plainText is already empty,
    // and the excerpt can never contain body text the row did not hold.

    @Query(
        """
        SELECT id, folderId, title, substr(plainText, 1, 240) AS excerpt,
               createdAt, updatedAt, pinned, favourite, archived, trashedAt,
               colorArgb, dailyDate, locked
        FROM notes WHERE trashedAt IS NULL AND archived = 0
        ORDER BY pinned DESC, updatedAt DESC
        """,
    )
    fun observeActiveRows(): Flow<List<NoteListRow>>

    @Query(
        """
        SELECT id, folderId, title, substr(plainText, 1, 240) AS excerpt,
               createdAt, updatedAt, pinned, favourite, archived, trashedAt,
               colorArgb, dailyDate, locked
        FROM notes WHERE trashedAt IS NULL AND archived = 1
        ORDER BY updatedAt DESC
        """,
    )
    fun observeArchivedRows(): Flow<List<NoteListRow>>

    @Query(
        """
        SELECT id, folderId, title, substr(plainText, 1, 240) AS excerpt,
               createdAt, updatedAt, pinned, favourite, archived, trashedAt,
               colorArgb, dailyDate, locked
        FROM notes WHERE trashedAt IS NOT NULL
        ORDER BY trashedAt DESC
        """,
    )
    fun observeTrashedRows(): Flow<List<NoteListRow>>

    @Query(
        """
        SELECT id, folderId, title, substr(plainText, 1, 240) AS excerpt,
               createdAt, updatedAt, pinned, favourite, archived, trashedAt,
               colorArgb, dailyDate, locked
        FROM notes WHERE id = :id
        """,
    )
    suspend fun getRowById(id: String): NoteListRow?

    /** Search Quality Pass: [getRowById] for a candidate id set
     *  (the substring fallback's finds, which have no FTS row in
     *  the result list yet). */
    @Query(
        """
        SELECT id, folderId, title, substr(plainText, 1, 240) AS excerpt,
               createdAt, updatedAt, pinned, favourite, archived, trashedAt,
               colorArgb, dailyDate, locked
        FROM notes WHERE id IN (:ids)
        """,
    )
    suspend fun getRowsByIds(ids: List<String>): List<NoteListRow>

    /** FTS keyword search at row weight (Phase 6.1): same match and
     *  ordering as [searchFts], projected for the search screen. */
    @Query(
        """
        SELECT n.id, n.folderId, n.title, substr(n.plainText, 1, 240) AS excerpt,
               n.createdAt, n.updatedAt, n.pinned, n.favourite, n.archived,
               n.trashedAt, n.colorArgb, n.dailyDate, n.locked
        FROM notes n
        JOIN notes_fts f ON f.noteId = n.id
        WHERE notes_fts MATCH :query AND n.trashedAt IS NULL AND n.archived = 0
        ORDER BY n.updatedAt DESC
        """,
    )
    suspend fun searchFtsRows(query: String): List<NoteListRow>

    /** Change-signal query (Phase 6.1): the process-lifetime background
     *  triggers in NotaApplication use the active list purely as a
     *  "something changed" pulse and discard the payload. Observing ids
     *  keeps Room's invalidation semantics (an emission per notes-table
     *  write) while collapsing the per-emission cost to one small string
     *  per note — no bodies, no lock columns. */
    @Query("SELECT id FROM notes WHERE trashedAt IS NULL AND archived = 0")
    fun observeActiveIds(): Flow<List<String>>

    /** Semantic-index eligibility input (Phase 6.1): exactly the columns
     *  the eligibility + blank checks read, for notes that pass the
     *  lock/trash/archive gate in SQL. Replaces a full-entity getAll()
     *  on the indexer's status path. */
    @Query(
        "SELECT id, title, plainText FROM notes " +
            "WHERE locked = 0 AND trashedAt IS NULL AND archived = 0",
    )
    suspend fun getIndexCandidates(): List<NoteTextRow>

    /** Performance chapter 2 (F7): every note's link-scan input at
     *  text weight — the [[ ]] backfill reads titles and plain text
     *  only, and must not load full entities (contentHtml, lock
     *  envelopes) for a whole library on a cold start. */
    @Query("SELECT id, title, plainText FROM notes")
    suspend fun getAllNoteTexts(): List<NoteTextRow>

    /** Performance chapter 2 (F3/F6): the library stamp — see
     *  [LibraryStampRow]. One aggregate query over the notes table
     *  plus the trigger-maintained write generation as a scalar
     *  subquery; COALESCEs keep an empty library (or a database
     *  whose counter row was never seeded) at all-zeroes. */
    @Query(
        "SELECT COUNT(*) AS noteCount, " +
            "COALESCE(MAX(updatedAt), 0) AS maxUpdatedAt, " +
            "COALESCE(SUM(updatedAt), 0) AS sumUpdatedAt, " +
            "COALESCE(SUM(LENGTH(title)), 0) AS sumTitleLength, " +
            "COALESCE(SUM(LENGTH(plainText)), 0) AS sumPlainTextLength, " +
            "COALESCE((SELECT notesWriteGeneration FROM library_meta WHERE id = 1), 0) AS writeGeneration " +
            "FROM notes",
    )
    suspend fun getLibraryStampRow(): LibraryStampRow

    /** Search Quality Pass: the scoring corpus for the substring
     *  fallback — every note keyword search may surface, with the
     *  text the scorer reads. Locked notes ride along (their
     *  stored plainText is already empty under the seal, and the
     *  scorer blanks it again by rule). */
    @Query(
        "SELECT id, title, plainText, locked FROM notes " +
            "WHERE trashedAt IS NULL AND archived = 0",
    )
    suspend fun getSearchTexts(): List<SearchTextRow>

    /** Search Quality Pass: scoring texts for a candidate id set
     *  (the FTS pass's hits), at the same weight as [getSearchTexts]. */
    @Query("SELECT id, title, plainText, locked FROM notes WHERE id IN (:ids)")
    suspend fun getSearchTexts(ids: List<String>): List<SearchTextRow>

    /** Search Quality Pass: ids of the notes filed in a folder —
     *  the set a folder rename/delete must re-index for search. */
    @Query("SELECT id FROM notes WHERE folderId = :folderId")
    suspend fun getIdsInFolder(folderId: String): List<String>

    /** [[ ]] autocomplete targets at (id, title) weight (Phase 6.1):
     *  same eligibility and ordering as [observeLinkable]. */
    @Query(
        "SELECT id, title FROM notes WHERE trashedAt IS NULL AND locked = 0 " +
            "ORDER BY title COLLATE NOCASE ASC",
    )
    fun observeLinkableTargets(): Flow<List<NoteLinkTarget>>
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

    /** Search Quality Pass: a note's tag names — the FTS row's
     *  tagsText and the scorer's tag haystack read the same list. */
    @Query(
        "SELECT t.name FROM tags t JOIN note_tags nt ON nt.tagId = t.id " +
            "WHERE nt.noteId = :noteId ORDER BY t.name ASC",
    )
    suspend fun getTagNamesForNote(noteId: String): List<String>

    /** Search Quality Pass: tag names for a candidate id set,
     *  one row per (note, tag) pair. */
    @Query(
        "SELECT nt.noteId AS noteId, t.name AS tagName FROM note_tags nt " +
            "JOIN tags t ON t.id = nt.tagId WHERE nt.noteId IN (:noteIds)",
    )
    suspend fun getTagNamesForNotes(noteIds: List<String>): List<NoteTagNameRow>

    /** Search Quality Pass: ids of the notes carrying a tag —
     *  the set a tag rename/delete must re-index for search. */
    @Query("SELECT noteId FROM note_tags WHERE tagId = :tagId")
    suspend fun getNoteIdsForTag(tagId: String): List<String>

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

    /** Phase 5.1: every reminder row (pending + spent), for the
     *  Reminders screen and the alarm reconciler. */
    @Query("SELECT * FROM reminders ORDER BY triggerAt ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    /** Phase 5.1: a note's reminder rows as they change (editor state). */
    @Query("SELECT * FROM reminders WHERE noteId = :noteId ORDER BY triggerAt ASC")
    fun observeForNote(noteId: String): Flow<List<ReminderEntity>>

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()

    @Upsert
    suspend fun upsert(reminder: ReminderEntity)

    @Delete
    suspend fun delete(reminder: ReminderEntity)

    /** PR #53 (Codex R3, tightened R4, rule-guarded R6): the fire
     *  path's conditional advance — moves the trigger only while
     *  the row is still enabled AND still at [expectedTriggerAt],
     *  the trigger the caller read, AND still carrying
     *  [expectedRepeatRule], the rule the caller read (the IS
     *  comparison is null-safe). A reminder the user completed
     *  (or removed) after the caller's read is never resurrected
     *  by a stale snapshot, one they RESCHEDULED keeps the user's
     *  new time instead of being overwritten by the stale-derived
     *  next occurrence, and one whose RULE alone they edited
     *  (daily → weekly at the same time) keeps the user's rule —
     *  the stale-rule advance loses rather than landing an extra
     *  occurrence the new rule would never have produced.
     *  Returns the number of rows written: 1 = advanced, 0 = the
     *  row was completed, deleted, rescheduled, or re-ruled in
     *  between. */
    @Query("UPDATE reminders SET triggerAt = :triggerAt WHERE id = :id AND enabled = 1 AND triggerAt = :expectedTriggerAt AND repeatRule IS :expectedRepeatRule")
    suspend fun advanceTriggerIfEnabled(
        id: String,
        triggerAt: Long,
        expectedTriggerAt: Long,
        expectedRepeatRule: String?,
    ): Int

    /** PR #53 (Codex R3): the targeted spend — flips ONLY the
     *  enabled flag on the live row, so fields a concurrent edit
     *  changed after a caller's read survive the spend. */
    @Query("UPDATE reminders SET enabled = 0 WHERE id = :id")
    suspend fun spendById(id: String): Int
}

@Dao
interface NoteVersionDao {
    @Query("SELECT * FROM note_versions WHERE noteId = :noteId ORDER BY createdAt DESC")
    fun observeForNote(noteId: String): Flow<List<NoteVersionEntity>>

    /** Phase 3a: bulk read for the guest→account library copy. */
    @Query("SELECT * FROM note_versions")
    suspend fun getAll(): List<NoteVersionEntity>

    /** Phase 6 (PR #43): one version by id, for restore. */
    @Query("SELECT * FROM note_versions WHERE id = :id")
    suspend fun getById(id: String): NoteVersionEntity?

    /** Phase 6 (PR #43): a note's versions, newest first — the
     *  prune's input (1.x reads the rows and prunes in code too). */
    @Query("SELECT * FROM note_versions WHERE noteId = :noteId ORDER BY createdAt DESC")
    suspend fun getForNote(noteId: String): List<NoteVersionEntity>

    @Insert
    suspend fun insert(version: NoteVersionEntity)

    @Delete
    suspend fun delete(version: NoteVersionEntity)

    /** Phase 6 (PR #43): clearVersions — permanent deletion and
     *  locking wipe a note's history outright (1.x versions.ts). */
    @Query("DELETE FROM note_versions WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: String)
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

    /**
     * Notes that [noteId] links TO ("Links"), newest first. The target
     * side filters trash only: a locked target stays listed by title,
     * mirroring how the notes list shows locked notes (opening it
     * lands on its lock gate).
     */
    @Query(
        """
        SELECT n.* FROM notes n
        JOIN note_links l ON l.targetId = n.id
        WHERE l.sourceId = :noteId AND n.trashedAt IS NULL
        ORDER BY n.updatedAt DESC
        """,
    )
    fun observeOutgoing(noteId: String): Flow<List<NoteEntity>>

    /** [observeIncoming] at footer weight (Phase 6.1): same join, same
     *  filters, same order — (id, title, updatedAt) only. */
    @Query(
        """
        SELECT n.id, n.title, n.updatedAt FROM notes n
        JOIN note_links l ON l.sourceId = n.id
        WHERE l.targetId = :noteId AND n.trashedAt IS NULL AND n.locked = 0
        ORDER BY n.updatedAt DESC
        """,
    )
    fun observeIncomingRows(noteId: String): Flow<List<NoteLinkRow>>

    /** [observeOutgoing] at footer weight (Phase 6.1). */
    @Query(
        """
        SELECT n.id, n.title, n.updatedAt FROM notes n
        JOIN note_links l ON l.targetId = n.id
        WHERE l.sourceId = :noteId AND n.trashedAt IS NULL
        ORDER BY n.updatedAt DESC
        """,
    )
    fun observeOutgoingRows(noteId: String): Flow<List<NoteLinkRow>>

    /** Every stored edge (Phase 4.4: the link graph's raw material). */
    @Query("SELECT * FROM note_links")
    fun observeAll(): Flow<List<NoteLinkEntity>>

    @Query("SELECT COUNT(*) FROM note_links")
    suspend fun count(): Int

    @Query("DELETE FROM note_links WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    @Query("DELETE FROM note_links")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(links: List<NoteLinkEntity>)
}

/**
 * Sync bookkeeping DAO (Phase 3c): tombstones + the key-value sync
 * state, both in the per-account database (see the entities' docs).
 */
@Dao
interface SyncDao {
    @Query("SELECT * FROM tombstones")
    suspend fun getTombstones(): List<TombstoneEntity>

    @Upsert
    suspend fun upsertTombstone(tombstone: TombstoneEntity)

    @Query("DELETE FROM tombstones")
    suspend fun deleteAllTombstones()

    @Query("DELETE FROM tombstones WHERE deletedAt < :cutoffMillis")
    suspend fun purgeTombstonesOlderThan(cutoffMillis: Long)

    @Query("SELECT value FROM sync_meta WHERE key = :key")
    suspend fun getMetaValue(key: String): String?

    @Upsert
    suspend fun putMeta(meta: SyncMetaEntity)
}
