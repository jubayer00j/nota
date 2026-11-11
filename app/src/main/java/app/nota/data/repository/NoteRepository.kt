package app.nota.data.repository

import androidx.room.withTransaction
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.FolderEntity
import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteLinkEntity
import app.nota.data.db.NoteTagCrossRef
import app.nota.data.db.NoteVersionEntity
import app.nota.data.db.NotaDatabase
import app.nota.data.db.SavedSearchEntity
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
 *
 * Phase 3a: DAOs are resolved through [DatabaseProvider] at call time,
 * never captured — the active database changes with the signed-in
 * account, and a captured DAO would keep reading the previous
 * account's library after a switch.
 */
class NoteRepository(
    private val provider: DatabaseProvider,
) {
    private val db: NotaDatabase get() = provider.current
    private val noteDao get() = db.noteDao()
    private val ftsDao get() = db.noteFtsDao()
    private val folderDao get() = db.folderDao()
    private val tagDao get() = db.tagDao()
    private val attachmentDao get() = db.attachmentDao()
    private val savedSearchDao get() = db.savedSearchDao()
    private val noteLinkDao get() = db.noteLinkDao()
    // --- Observation -------------------------------------------------------
    fun observeActive(): Flow<List<NoteEntity>> = noteDao.observeActive()
    fun observeArchived(): Flow<List<NoteEntity>> = noteDao.observeArchived()
    fun observeTrashed(): Flow<List<NoteEntity>> = noteDao.observeTrashed()
    fun observeByFolder(folderId: String): Flow<List<NoteEntity>> = noteDao.observeByFolder(folderId)
    fun observeByTag(tagId: String): Flow<List<NoteEntity>> = noteDao.observeByTag(tagId)
    fun observeFavourites(): Flow<List<NoteEntity>> = noteDao.observeFavourites()
    fun observeDaily(date: String): Flow<List<NoteEntity>> = noteDao.observeDaily(date)
    fun observeById(id: String): Flow<NoteEntity?> = noteDao.observeById(id)

    /** Every note in the library (Phase 4.4: link graph node metadata). */
    fun observeAllNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()
    fun observeFolders(): Flow<List<FolderEntity>> = folderDao.observeAll()
    fun observeTags(): Flow<List<TagEntity>> = tagDao.observeAll()
    fun observeTagsForNote(noteId: String): Flow<List<TagEntity>> = tagDao.observeForNote(noteId)
    fun observeAllCrossRefs(): Flow<List<NoteTagCrossRef>> = tagDao.observeAllCrossRefs()
    fun observeAttachments(): Flow<List<AttachmentEntity>> = attachmentDao.observeAll()
    fun observeAttachmentsForNote(noteId: String): Flow<List<AttachmentEntity>> =
        attachmentDao.observeForNote(noteId)
    fun observeSavedSearches(): Flow<List<SavedSearchEntity>> = savedSearchDao.observeAll()

    suspend fun getById(id: String): NoteEntity? = noteDao.getById(id)

    // --- Phase 6.1: lightweight rows + change signal ---------------------
    fun observeActiveRows(): Flow<List<app.nota.data.db.NoteListRow>> =
        noteDao.observeActiveRows()
    fun observeArchivedRows(): Flow<List<app.nota.data.db.NoteListRow>> =
        noteDao.observeArchivedRows()
    fun observeTrashedRows(): Flow<List<app.nota.data.db.NoteListRow>> =
        noteDao.observeTrashedRows()
    suspend fun getRowById(id: String): app.nota.data.db.NoteListRow? =
        noteDao.getRowById(id)

    /** "Something in the active list changed" pulse for the background
     *  triggers (sync / semantic / widget in NotaApplication): the
     *  emission cadence of [observeActive] at id-only weight. */
    fun observeActiveIds(): Flow<List<String>> = noteDao.observeActiveIds()

    /** [[ ]] autocomplete targets at (id, title) weight (Phase 6.1). */
    fun observeLinkableTargets(): Flow<List<app.nota.data.db.NoteLinkTarget>> =
        noteDao.observeLinkableTargets()

    // --- Core write path (FTS-synced) --------------------------------------
    suspend fun upsert(note: NoteEntity) {
        db.withTransaction {
            val previousTitle = noteDao.getById(note.id)?.title
            noteDao.upsert(note)
            // The FTS row (title, body, tag names, folder name —
            // with the Phase 2b seal discipline: a locked note's
            // body is never indexed, its title is) is built by the
            // one shared builder; see FtsSync.
            db.refreshFtsEntry(note)
            relink(note)
            if (previousTitle != note.title) {
                // Codex fix G5a: creating a note or renaming one changes
                // which [[title]] references resolve. Re-run extraction
                // for notes referencing the old or new title, so their
                // edges track the rename instead of going stale.
                relinkReferencingNotes(previousTitle, note.title, excludeId = note.id)
            }
        }
    }

    /**
     * Re-runs [relink] for every note whose body references [oldTitle] or
     * [newTitle] (either may be the side a rename just changed). Runs
     * inside the caller's transaction. Locked/trashed notes are skipped:
     * [relink] clears their edges and their own next upsert rebuilds them.
     */
    private suspend fun relinkReferencingNotes(oldTitle: String?, newTitle: String, excludeId: String) {
        val titles = listOfNotNull(oldTitle, newTitle).toSet()
        if (titles.isEmpty()) return
        for (other in noteDao.getAll()) {
            if (other.id == excludeId || other.locked || other.trashedAt != null) continue
            if (NoteDocument.backlinkTitles(other.plainText).any { it in titles }) {
                relink(other)
            }
        }
    }

    /**
     * One-time backfill for the v2->v3 migration (Codex fix G5b):
     * MIGRATION_2_3 created note_links EMPTY, so notes written before
     * Phase 1c have no edges until each is individually re-saved. When
     * the table is empty but notes exist, relink every note that
     * mentions a [[title]]. Cheap and idempotent: once any edge exists
     * this returns immediately, and notes without "[[" are skipped
     * without a relink, so a library with no links at all only pays a
     * substring scan per note per launch. Returns true when it ran.
     */
    suspend fun backfillLinksIfNeeded(): Boolean {
        if (noteLinkDao.count() > 0) return false
        val notes = noteDao.getAll()
        if (notes.isEmpty()) return false
        db.withTransaction {
            for (note in notes) {
                if (note.plainText.contains("[[")) relink(note)
            }
        }
        return true
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
            // Phase 3c: hard deletes leave sync tombstones (the note
            // plus everything the FK cascades are about to remove —
            // its attachments and reminders), so the deletion can
            // propagate to other devices sharing the Drive archive.
            // Trashing is NOT a hard delete and never lands here.
            val syncStore = app.nota.data.sync.SyncStore(db)
            val now = System.currentTimeMillis()
            syncStore.recordTombstone(app.nota.data.sync.EntityHash.ENTITY_NOTE, note.id, now)
            for (attachment in attachmentDao.getForNote(note.id)) {
                syncStore.recordTombstone(
                    app.nota.data.sync.EntityHash.ENTITY_ATTACHMENT, attachment.id, now,
                )
            }
            for (reminder in db.reminderDao().getForNote(note.id)) {
                syncStore.recordTombstone(
                    app.nota.data.sync.EntityHash.ENTITY_REMINDER, reminder.id, now,
                )
            }
            // Phase 6 (PR #43): permanent deletion clears the note's
            // version history (1.x clearVersions) — stated explicitly
            // rather than left to the FK cascade alone.
            db.noteVersionDao().deleteForNote(note.id)
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

    // --- Version history (Phase 6, PR #43) ---------------------------

    /**
     * The version-history hook (1.x snapshots inside `updateNote`;
     * Nota's counterparts are the three edit entry points below).
     * The STORED row is what gets snapshotted — the caller's copy
     * can be stale — and only when the title or the content
     * actually differs from it, so a no-change save produces
     * nothing. [newContentHtml] is null for a title-only save
     * (content untouched by definition). Deliberately NOT in
     * [upsert]: sync applies, backup restores, lock-session sealed
     * saves and the guest transfer all flow through upsert and
     * must never snapshot (see [VersionHistory]).
     */
    private suspend fun snapshotStoredIfChanged(
        noteId: String,
        newTitle: String,
        newContentHtml: String?,
    ) {
        val stored = noteDao.getById(noteId) ?: return
        val contentChanged = newContentHtml != null && stored.contentHtml != newContentHtml
        if (stored.title == newTitle && !contentChanged) return
        VersionHistory.snapshotIfSettled(db, stored)
    }

    /** A note's earlier states, newest first (see [VersionHistory]). */
    fun observeVersions(noteId: String): Flow<List<NoteVersionEntity>> =
        VersionHistory.observeVersions(db, noteId)

    /** Deletes every version of a note (the history dialog's Clear). */
    suspend fun clearVersions(noteId: String) = VersionHistory.clearVersions(db, noteId)

    /**
     * Restores a version: the current state is force-snapshotted
     * first (so the restore can itself be undone), then the
     * version's title + content are written back through [upsert] —
     * the same plain-text / FTS / backlink derivation every edit
     * gets. The note-row change is also what the semantic index's
     * maintenance observer keys on, so restored content becomes
     * what search matches (1.x refreshes the note's vector
     * explicitly after its direct write, for the same reason).
     * Returns false when the version or its note is gone — or the
     * note is locked, in which case nothing is written at all:
     * plaintext must never land on a sealed record.
     */
    suspend fun restoreVersion(versionId: String): Boolean {
        val version = db.noteVersionDao().getById(versionId) ?: return false
        val note = noteDao.getById(version.noteId) ?: return false
        if (note.locked) return false
        VersionHistory.forceSnapshot(db, note)
        val restored = note.copy(
            title = version.title,
            contentHtml = version.contentHtml,
            plainText = NoteDocument.plainText(NoteDocument.parse(version.contentHtml)),
            updatedAt = System.currentTimeMillis(),
        )
        upsert(restored)
        return true
    }

    suspend fun updateBody(note: NoteEntity, title: String, plainBody: String): NoteEntity {
        // Phase 2b defence-in-depth: a locked note's body may only be
        // written through NoteLockRepository.saveLockedContent (sealed).
        // Any other path landing here with plaintext would silently
        // break the seal, so it fails loudly instead.
        require(!note.locked) { "Note is locked; body writes must go through the lock session." }
        val newContentHtml = PlainTextCodec.textToHtml(plainBody)
        snapshotStoredIfChanged(note.id, title, newContentHtml)
        val updated = note.copy(
            title = title,
            contentHtml = newContentHtml,
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
        snapshotStoredIfChanged(note.id, title, newContentHtml = null)
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
        // Phase 2b: see updateBody — plaintext must never overwrite a seal.
        require(!note.locked) { "Note is locked; body writes must go through the lock session." }
        snapshotStoredIfChanged(note.id, title, contentHtml)
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

    /** Notes that [noteId] links TO via [[title]] — the footer's "Links" list. */
    fun observeOutgoing(noteId: String): Flow<List<NoteEntity>> = noteLinkDao.observeOutgoing(noteId)

    /** [observeLinkedFrom] at footer weight (Phase 6.1). */
    fun observeLinkedFromRows(noteId: String): Flow<List<app.nota.data.db.NoteLinkRow>> =
        noteLinkDao.observeIncomingRows(noteId)

    /** [observeOutgoing] at footer weight (Phase 6.1). */
    fun observeOutgoingRows(noteId: String): Flow<List<app.nota.data.db.NoteLinkRow>> =
        noteLinkDao.observeOutgoingRows(noteId)

    /** Every stored [[backlink]] edge (Phase 4.4: the link graph map). */
    fun observeAllLinks(): Flow<List<NoteLinkEntity>> = noteLinkDao.observeAll()

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
            // Search Quality Pass: tag names live in the note's
            // FTS row — a tag assignment is a search-index write
            // too, or the new tag would not find its note.
            noteDao.getById(noteId)?.let { db.refreshFtsEntry(it) }
        }
    }

    // --- Daily notes -------------------------------------------------------------
    /** True when a daily note for [date] already exists (never creates one). */
    suspend fun hasDailyNote(date: String): Boolean = noteDao.getDaily(date) != null

    /**
     * Returns the daily note for [date] ("yyyy-MM-dd"), creating it if needed.
     *
     * The lookup-and-create runs inside a single transaction with the
     * recheck inside it, so concurrent callers (e.g. a double-tap) cannot
     * both miss the lookup and create duplicates: a later transaction
     * observes the earlier one's note. A unique index on dailyDate would
     * enforce this at the schema level, but that is deferred to a
     * schema v4 migration (the schema is owned by the 1c branch).
     */
    suspend fun ensureDailyNote(date: String): NoteEntity = db.withTransaction {
        val existing = noteDao.getDaily(date)
        if (existing != null) return@withTransaction existing
        val now = System.currentTimeMillis()
        val note = NoteEntity(
            id = UUID.randomUUID().toString(),
            title = date,
            contentHtml = PlainTextCodec.textToHtml(""),
            plainText = "",
            createdAt = now,
            updatedAt = now,
            dailyDate = date,
        )
        noteDao.upsert(note)
        db.refreshFtsEntry(note)
        note
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
    /**
     * Keyword search (Search Quality Pass, v0.27.0): 1.x's ranked
     * local search over the FTS index.
     *
     * Pipeline: FTS4 proposes candidates by prefix, as before.
     * When it returns fewer than [SUBSTRING_FALLBACK_THRESHOLD]
     * rows the candidate set widens to the whole eligible corpus
     * — 1.x matches raw substrings, so a mid-word fragment
     * ("xam", "ook") must still find its note, and the prefix
     * pass alone cannot see those. Either way, EVERY candidate
     * is then scored by [NoteSearch.score] over its real
     * haystacks (1.x's weights, AND across terms), sorted
     * score-then-recency, and given a match-window snippet.
     * A prefix match is always a substring match in the same
     * field, so the FTS hits score in the same pass — one
     * pipeline, no duplicate rows. Locked notes score with an
     * empty body and show no snippet; archived notes stay out
     * (Nota's standing rule, kept).
     */
    suspend fun searchNotes(raw: String): List<NoteSearchResult> {
        val terms = NoteSearch.terms(raw)
        if (terms.isEmpty()) return emptyList()
        val match = FtsQueryBuilder.build(raw) ?: return emptyList()
        val ftsRows = noteDao.searchFtsRows(match)

        // Id-list queries run in chunks: SQLite's bound-variable
        // ceiling on older Android is 999, and a big library's
        // candidate set must never lean on it.
        val texts = if (ftsRows.size < SUBSTRING_FALLBACK_THRESHOLD) {
            noteDao.getSearchTexts()
        } else {
            ftsRows.map { it.id }.chunked(ID_QUERY_CHUNK)
                .flatMap { noteDao.getSearchTexts(it) }
        }
        if (texts.isEmpty()) return emptyList()
        val textById = texts.associateBy { it.id }

        val tagNamesById = HashMap<String, List<String>>()
        for (chunk in textById.keys.chunked(ID_QUERY_CHUNK)) {
            for ((noteId, names) in tagDao.getTagNamesForNotes(chunk)
                .groupBy({ it.noteId }, { it.tagName })) {
                tagNamesById[noteId] = names
            }
        }
        val folderNamesById = folderDao.getAll().associate { it.id to it.name }

        val rowsById = HashMap<String, app.nota.data.db.NoteListRow>()
        for (row in ftsRows) rowsById[row.id] = row
        val missingRowIds = textById.keys.filter { it !in rowsById }
        for (chunk in missingRowIds.chunked(ID_QUERY_CHUNK)) {
            for (row in noteDao.getRowsByIds(chunk)) rowsById[row.id] = row
        }

        val results = ArrayList<NoteSearchResult>()
        for ((id, text) in textById) {
            val row = rowsById[id] ?: continue
            val score = NoteSearch.score(
                title = text.title,
                body = if (text.locked) "" else text.plainText,
                tagNames = tagNamesById[id] ?: emptyList(),
                folderName = row.folderId?.let { folderNamesById[it] },
                terms = terms,
            ) ?: continue
            results += NoteSearchResult(
                row = row,
                score = score.toDouble(),
                snippet = if (text.locked) "" else NoteSearch.snippet(text.plainText, terms),
            )
        }
        return results.sortedWith(
            compareByDescending<NoteSearchResult> { it.score }
                .thenByDescending { it.row.updatedAt }
                .thenBy { it.id },
        )
    }

    /** Phase 0 name, kept as a delegate. */
    suspend fun search(query: String): List<NoteSearchResult> = searchNotes(query)

    private companion object {
        /**
         * How thin the FTS prefix pass may come back before the
         * substring pass widens the candidate set to the whole
         * corpus. 30 is 1.x's own render cap: a prefix pass that
         * already fills it has saturated what the user can scan,
         * and below it the corpus pass is one bounded scan over
         * eligible notes.
         */
        const val SUBSTRING_FALLBACK_THRESHOLD = 30

        /** Chunk size for id-list (IN) queries — comfortably
         *  under SQLite's 999 bound-variable ceiling on the
         *  oldest Android versions Nota still runs on. */
        const val ID_QUERY_CHUNK = 500
    }
}
