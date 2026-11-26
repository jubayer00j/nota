package app.nota.data.importing

import android.content.Context
import androidx.room.withTransaction
import app.nota.data.attachments.AttachmentStore
import app.nota.data.backup.rebuildLibraryIndexes
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.FolderEntity
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteTagCrossRef
import app.nota.data.db.TagEntity
import app.nota.data.html.NoteDocument
import app.nota.data.repository.refreshFtsEntry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A picked file staged into a temp directory by the import UI. */
class StagedImportFile(
    val name: String,
    val file: File,
    val lastModified: Long,
)

/** The engine's write report plus the image-save failure count. */
data class ImportRunResult(
    val report: ImportReport,
    val imageSaveFailures: Int,
)

/**
 * The import engine — 1.x `importNotes` rebuilt on Nota's bulk-write
 * discipline (the applyBackup pattern):
 *
 *  - additive only; a note whose (title, createdAt) matches an existing
 *    note in ANY state (including trashed, and notes created earlier in
 *    this run) is skipped as a duplicate;
 *  - notes are written with direct DAO writes, never through the
 *    snapshot-taking edit paths, so imported notes start with NO
 *    version history (1.x's stated rationale). `NoteRepository.upsert`
 *    per note was rejected: its title-change relink rescan is O(library)
 *    per note — O(N²) on a 5,000-note import;
 *  - images ride the editor's own pipeline
 *    ([AttachmentStore.importImageFile]) and land as
 *    `nota-attachment://` embeds in Nota's serialiser shape;
 *  - each note is failure-isolated: a failed note commits nothing
 *    — its row, FTS entry, attachment rows and folder/tag rows all
 *    land in one transaction after its images are staged and its
 *    final content is known (Codex R24-F1/F3) — and is named in the
 *    report;
 *  - the run ends with the backup pipeline's index rebuild + attachment
 *    reconcile, so backlinks and the file sweep are truthful. The
 *    semantic index needs nothing: its stamp-gated reconcile picks new
 *    notes up, and nothing is embedded during an import.
 */
class NoteImportRepository(
    private val context: Context,
    private val provider: DatabaseProvider,
    private val attachmentStore: AttachmentStore,
) {

    suspend fun importKeepZip(
        zipFile: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImportOutcome {
        // Capture the target account BEFORE parsing (Codex R18-F1):
        // parsing a big Takeout takes a while, and a switch during
        // it used to land the import in whichever account was
        // current after parsing — the fix-13 storm-test capture
        // race. The capture is the live instance at import start;
        // importNotes opens its dedicated handle on that instance's
        // file, so the run always lands where it started. Only the
        // file name is read from the captured instance (a config
        // string, valid even after a switch closes it) — no DAO
        // call ever touches it, so the R8-F1 closed-handle race
        // cannot reappear.
        val targetDb = provider.current
        val stagingDir = File(context.cacheDir, "import-keep-${UUID.randomUUID()}")
        return try {
            val parse = KeepTakeoutParser.parse(zipFile, stagingDir)
            val run = importNotes(parse.notes, targetDb = targetDb, onProgress = onProgress)
            ImportOutcome(parse, run.report, run.imageSaveFailures)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    suspend fun importMarkdownZip(
        zipFile: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImportOutcome {
        // Same capture-before-parse as importKeepZip (Codex R18-F1).
        val targetDb = provider.current
        val stagingDir = File(context.cacheDir, "import-mdzip-${UUID.randomUUID()}")
        return try {
            val parse = MarkdownImporter.parseImportZip(zipFile, stagingDir)
            val run = importNotes(parse.notes, targetDb = targetDb, onProgress = onProgress)
            ImportOutcome(parse, run.report, run.imageSaveFailures)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /**
     * A Markdown-mode selection: any mix of .md/.markdown/.txt files,
     * ZIPs (each parsed as a Markdown ZIP) and image files (resolver
     * fodder). Anything else picked is reported as a skipped file —
     * every picked file is accounted for. All sources are parsed first
     * and written in ONE engine run (1.x's merge-then-import shape, so
     * progress and dedupe span the whole selection).
     */
    suspend fun importMarkdownSelection(
        staged: List<StagedImportFile>,
        preselectedSkipped: List<SkippedImportFile> = emptyList(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        imageBudget: ExtractedImageBudget = ExtractedImageBudget(),
    ): ImportOutcome {
        // Capture the target account BEFORE parsing (Codex R18-F1):
        // a multi-file selection parses for a while, and a switch
        // during it used to land the import in whichever account
        // was current after parsing. Same anchor as importKeepZip.
        val targetDb = provider.current
        val stagingDir = File(context.cacheDir, "import-mdsel-${UUID.randomUUID()}")
        return try {
            val merged = ImportParseResult()
            // One extracted-image budget for the whole selection
            // (Codex R11-F1): each ZIP's own 512 MiB archive quota
            // bounds that archive alone, so without a shared budget
            // several highly compressible ZIPs could expand into GiB
            // of staged images in this directory. The budget is
            // consumed as images are staged; an image past it is not
            // staged, so its references stay unresolved (counted)
            // instead. The parameter exists for tests, which pass a
            // small budget; production callers use the default.
            // Picks the UI already rejected before copying (unsupported
            // type, or over the staging cap) still belong in the
            // report — never silently dropped.
            merged.skippedFiles += preselectedSkipped
            // A LIST, not a map: two picked images can share a display
            // name, and a map would silently keep only the last one —
            // the resolver treats a duplicated name as ambiguous and
            // leaves the reference unresolved (counted) instead.
            val resolverFiles = staged.map { it.name to it.file }
            val resolver = createImportImageResolver(resolverFiles)
            var zipIndex = 0
            var looseTextSeen = 0
            for (file in staged) {
                val lower = file.name.lowercase()
                when {
                    lower.endsWith(".zip") -> {
                        val subDir = File(stagingDir, "zip-${zipIndex++}")
                        merged.absorb(
                            // The selection resolver travels into the ZIP
                            // (Codex R22-F1): a note in the archive may
                            // reference a separately picked image, which
                            // the archive-only resolver could never see.
                            // The archive resolver stays primary — the
                            // selection one is only its fallback.
                            // The running note total travels in too
                            // (Codex R27-F1): the pre-scan and the
                            // note-cap check count against notes
                            // earlier archives already contributed,
                            // so a later archive neither scans refs
                            // for members past the run cap nor
                            // converts them. The running text total
                            // travels in as well (Codex R28-F4): the
                            // pre-scan simulates the remaining
                            // aggregate budget against the retained
                            // chars earlier archives contributed, so
                            // a later archive neither scans refs for
                            // members the merge will refuse on the
                            // budget nor stages their images.
                            MarkdownImporter.parseImportZip(
                                file.file,
                                subDir,
                                imageBudget,
                                resolver,
                                merged.notes.size,
                                merged.retainedTextChars,
                            ),
                        )
                        // Enforce the shared caps as sources are
                        // merged (Codex R12-F1): each ZIP's own
                        // 16M-char budget starts fresh, so several
                        // small ZIPs could otherwise accumulate
                        // hundreds of MiB of retained note strings
                        // before the post-loop check trims them.
                        // Trimming after each absorb keeps the merged
                        // result inside the budget at all times; the
                        // post-loop calls below stay as defence in
                        // depth. Dropped notes are the just-absorbed
                        // tail, reported with the budget reason.
                        merged.enforceNoteCap()
                        merged.enforceContentBudget()
                    }
                    lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt") -> {
                        if (file.file.length() > ImportCaps.MAX_IMPORT_FILE_BYTES) {
                            merged.skippedFiles += SkippedImportFile(
                                file.name,
                                "File is larger than 100 MB.",
                            )
                        } else if (merged.notes.size >= ImportCaps.MAX_IMPORT_NOTES) {
                            // The run cap applies as files are read, so
                            // a many-file selection stops retaining text
                            // once the cap is reached (mirrors
                            // parseImportZip's per-member check).
                            merged.skippedFiles += SkippedImportFile(
                                file.name,
                                "Import limit reached (5,000 notes per run).",
                            )
                        } else {
                            looseTextSeen++
                            // Converted immediately and the text dropped:
                            // a loose-file selection never holds every
                            // file's text at once (the R2 ZIP-path fix,
                            // same shape). The aggregate text budget is
                            // checked BEFORE the file is materialized
                            // (Codex R9-F1, same shape as the ZIP walk 3
                            // and the Keep pass 2): a loose file under
                            // the 100 MB per-file cap but past the
                            // remaining run budget must not be read in
                            // full first — an ASCII file near the cap
                            // would allocate ~200 MB as a UTF-16 String
                            // on a 1 GB device. Over-budget files are
                            // reported as skipped, never silently
                            // dropped — and never parsed, so no image
                            // counts leak from a note that never imports.
                            // The filename title is the one title
                            // source outside the file text (a
                            // front-matter/H1 title is inside it), so
                            // room is reserved for it too (Codex
                            // R19-F1) — file.name.length is a
                            // conservative upper bound on it (the
                            // title is the name minus its extension).
                            // The true check is addNote, which counts
                            // title + body.
                            val remaining =
                                (ImportCaps.MAX_AGGREGATE_TEXT_CHARS - merged.retainedTextChars -
                                    file.name.length)
                                    .coerceAtLeast(0L)
                            when (val read = withContext(Dispatchers.IO) {
                                file.file.readTextUpToChars(
                                    remaining,
                                    ImportCaps.MAX_IMPORT_FILE_BYTES,
                                )
                            }) {
                                is CharBoundedText.OverBytes -> merged.skippedFiles += SkippedImportFile(
                                    file.name,
                                    "File is larger than 100 MB.",
                                )
                                is CharBoundedText.OverChars -> merged.skippedFiles += SkippedImportFile(
                                    file.name,
                                    ImportCaps.AGGREGATE_TEXT_BUDGET_REASON,
                                )
                                is CharBoundedText.Ok -> {
                                    val input = MarkdownFileInput(
                                        name = file.name,
                                        path = file.name,
                                        text = read.text,
                                        lastModified = file.lastModified,
                                        // The display name is not an
                                        // identity for a loose pick
                                        // (Codex R23-F1): two SAF picks
                                        // from different folders can
                                        // share it, and the run dedupe
                                        // key for dateless notes would
                                        // collapse them. Carry the
                                        // content hash as the note's
                                        // source identity instead —
                                        // different contents, different
                                        // keys; identical contents are
                                        // the same note and still
                                        // dedupe.
                                        sourceIdentity = contentHashHex(read.text),
                                    )
                                    merged.absorb(MarkdownImporter.parseMarkdownFile(input, resolver))
                                    // Same merge-time trim as the ZIP
                                    // branch above (Codex R12-F1):
                                    // conversion can expand the text
                                    // past the pre-read source-length
                                    // estimate, so the merged result is
                                    // re-checked after every absorb,
                                    // not just at the end of the loop.
                                    merged.enforceNoteCap()
                                    merged.enforceContentBudget()
                                }
                            }
                        }
                    }
                    importMimeFromName(file.name) != null -> {
                        // Resolver fodder only (already in resolverFiles).
                    }
                    else -> merged.skippedFiles += SkippedImportFile(
                        file.name,
                        "Not a Markdown, text or ZIP file.",
                    )
                }
            }
            if (merged.notes.isEmpty() && merged.skippedFiles.isEmpty() &&
                looseTextSeen == 0 && zipIndex == 0
            ) {
                // The selection was only images (resolver fodder with
                // nothing to resolve into) — 1.x's "No Markdown or text
                // files in that selection", as a report entry.
                merged.skippedFiles += SkippedImportFile(
                    "Selection",
                    "No Markdown or text files in that selection.",
                )
            }
            // The per-source caps (5,000 per ZIP / per file list) do
            // not bound the merged run on their own — enforce the caps
            // across the whole selection here (note count, then the
            // aggregate text budget, which several already-budgeted
            // sources can still exceed in sum).
            merged.enforceNoteCap()
            merged.enforceContentBudget()
            val run = importNotes(merged.notes, targetDb = targetDb, onProgress = onProgress)
            ImportOutcome(merged, run.report, run.imageSaveFailures)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /** Dedupe key: the separator can't appear in the numeric timestamp,
     *  so (title, createdAt) pairs are uniquely recoverable from the key. */
    private fun dedupeKey(title: String, createdAt: Long): String = "$title $createdAt"

    /**
     * Dedupe key for unknown-date notes (createdAt == 0, Codex R21-F1):
     * (title, folder, source). A dateless note's only stable identity
     * is where it came from, so the key carries the folder and — for
     * notes imported in this run — the source identity. The seeded
     * half passes sourceName = null: the library stores no per-source
     * identity for a note, so an existing note can only be matched on
     * (title, folder); the run half adds the identity, which is what
     * keeps two same-title dateless notes in one run apart. The
     * identity is the source name for Keep notes (their paths are
     * identities) and a content hash for loose picks (Codex R23-F1
     * — a display name is not an identity) or the full entry path
     * plus the content hash for ZIP members (Codex R26-F2).
     * The folder component is normalized with the same trim-and-
     * lowercase rule as folder resolution (Codex R26-F1):
     * resolveFolderId reuses an existing folder case-insensitively,
     * so the stored note's folder name ("notes") and the incoming
     * item's folder name ("Notes") must key the same, or reimport
     * inserts a duplicate.
     * Components are length-prefixed: titles and folder names are free
     * text, so a plain separator could collide ("x 0 y" + "z" vs "x" +
     * "y 0 z"). Dated notes keep the plain (title, createdAt) key.
     *
     * Residual, by design: two dateless notes with the same title in
     * the SAME folder, imported in separate runs, still collapse
     * against the seeded key — nothing in the stored row tells them
     * apart. Within one run the source identity keeps them apart.
     */
    private fun dedupeKeyUnknownDate(
        title: String,
        folderName: String?,
        sourceName: String?,
    ): String {
        val folder = folderName?.trim()?.lowercase().orEmpty()
        val source = sourceName.orEmpty()
        return "U|${title.length}:$title|${folder.length}:$folder|${source.length}:$source"
    }

    /**
     * Writes [imported] into the target account's library.
     *
     * @param targetDb the database instance captured at import
     *  start by the entry points (Codex R18-F1): the dedicated
     *  handle is opened on ITS file, so a switch during parsing
     *  cannot move the run to another account. Null (direct
     *  callers, which have no parse phase) captures at run start,
     *  which is the same moment. It sits BEFORE [onProgress] so
     *  the trailing-lambda call shape keeps working.
     */
    suspend fun importNotes(
        imported: List<ImportedNote>,
        targetDb: NotaDatabase? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImportRunResult = withContext(Dispatchers.IO) {
        // The whole run — seed reads, note writes, image rows, and
        // the finish — runs on ONE dedicated handle on the starting
        // account's file: a sibling instance of the live database,
        // opened here and closed in the finally below
        // (openDedicatedOnSameFileAs). The anchor is the instance
        // captured at import start (Codex R18-F1), not the account
        // current now: parsing happens before this call, and a
        // switch during parsing must not move the run. A dedicated handle makes the
        // run switch-proof with no lock held at all (Codex R8-F1/F2,
        // retiring the R5/R6 account pinning and the switch mutex):
        //  - switchTo only ever closes the LIVE instance, so no
        //    switch — away, back, or a storm of them — can close
        //    this handle mid-note (the R6-F3 race stays closed);
        //  - the handle never depends on the current account, so an
        //    away-and-back switch (A→B→A) cannot hand back a closed
        //    instance the way the old account-id check did: it
        //    returned the captured handle whenever the id matched
        //    again, even though the first switch had closed it and
        //    the second had published a new instance (R8-F1);
        //  - no switch lock is held across the write phase, so the
        //    image pipeline's decode/scale/encode work inside a note
        //    write can never stall a sign-in/out on the main thread
        //    (R8-F2 — the R7-F2 ANR shape, one level down);
        //  - the finish (whole-library rebuild + reconcile) runs on
        //    the same handle, still outside any lock (R7-F2).
        // SQLite serializes the two connections on the same file,
        // and the guest→account copy already writes through an
        // additional instance the same way.
        // The anchor may have been closed by a switch during parsing
        // — that is the point of capturing it: only its file name
        // is read (openDedicatedOnSameFileAs derives the account
        // from the name), and the fresh dedicated handle built from
        // it is never the closed instance, so the R8-F1 race (an
        // account-id check handing back a closed handle) stays
        // closed.
        val runDb = provider.openDedicatedOnSameFileAs(targetDb ?: provider.current)
        try {
            var importedCount = 0
            var duplicates = 0
            var failed = 0
            var imageSaveFailures = 0
            val failedNames = mutableListOf<String>()

            // Folder names by id, for the unknown-date dedupe keys
            // (Codex R21-F1): a stored note's folder is the only
            // source identity the library keeps.
            val folderNameById = HashMap<String, String>()
            for (folder in runDb.folderDao().getAll()) {
                folderNameById[folder.id] = folder.name
            }

            val seen = HashSet<String>()
            for (note in runDb.noteDao().getAll()) {
                seen += if (note.createdAt == 0L) {
                    dedupeKeyUnknownDate(note.title, folderNameById[note.folderId], null)
                } else {
                    dedupeKey(note.title, note.createdAt)
                }
            }
            // Keys of notes imported by this run. `seen` is seeded
            // from the library only and never grows during the run:
            // an unknown-date note's seeded key carries no source
            // name, so adding it here would collapse a second
            // same-title note in the same folder.
            val seenRun = HashSet<String>()

            // Per-run folder/tag resolution (Codex R24-F3): the
            // caches are seeded from the run database here, and the
            // resolvers run inside each note's write transaction —
            // a failed write rolls its metadata back too, so no
            // orphan folder or tag survives it. See [RunMetadata].
            val meta = RunMetadata(runDb)
            meta.seed()

            // Defence in depth: the run-level cap (enforced at the merge
            // by every selection path) is re-checked here so no caller can
            // push more than 5,000 notes through the engine. Excess notes
            // are reported as failed, never silently dropped.
            val overCap = imported.drop(ImportCaps.MAX_IMPORT_NOTES)
            val capped = imported.take(ImportCaps.MAX_IMPORT_NOTES)

            val total = capped.size
            var done = 0
            for (item in capped) {
                if (noteRowBytes(item.title, item.contentHtml) > ImportCaps.MAX_NOTE_CONTENT_BYTES) {
                    // Defence in depth for direct callers: the
                    // parsers refuse an over-limit note in addNote,
                    // so one never reaches the engine through them —
                    // but a note written past the bound would break
                    // the run-end rebuild (getAll() cannot read a row
                    // past Android's ~2 MiB CursorWindow). Reported
                    // as failed, never silently dropped, and never
                    // written.
                    failed++
                    failedNames += item.sourceName
                    done++
                    onProgress(done, total)
                    continue
                }
                try {
                    // No lock, no pin check (R8): the write runs on
                    // the run's dedicated handle, which a switch can
                    // never close — a switch between or during notes
                    // can neither fail a note nor split the run across
                    // accounts.
                    val isUnknownDate = item.createdAt == 0L
                    val isDuplicate = if (isUnknownDate) {
                        // (Codex R21-F1): the seeded key has no source
                        // name — it only says a dateless note with
                        // this title already sits in this folder —
                        // while the run key adds the source identity,
                        // which is what keeps two same-title dateless
                        // notes in one run apart. The identity is the
                        // full entry path plus a content hash for ZIP
                        // members (Codex R26-F2) and for Keep notes
                        // (Codex R28-F2), and a content hash for loose
                        // picks (Codex R23-F1 — a display name is not
                        // an identity).
                        val seedKey = dedupeKeyUnknownDate(item.title, item.folderName, null)
                        val runKey = dedupeKeyUnknownDate(
                            item.title,
                            item.folderName,
                            item.sourceIdentity ?: item.sourceName,
                        )
                        seedKey in seen || runKey in seenRun
                    } else {
                        val key = dedupeKey(item.title, item.createdAt)
                        key in seen || key in seenRun
                    }
                    if (isDuplicate) {
                        duplicates++
                    } else {
                        imageSaveFailures += writeImportedNote(runDb, item, meta)
                        if (isUnknownDate) {
                            seenRun += dedupeKeyUnknownDate(
                                item.title,
                                item.folderName,
                                item.sourceIdentity ?: item.sourceName,
                            )
                        } else {
                            seenRun += dedupeKey(item.title, item.createdAt)
                        }
                        importedCount++
                    }
                } catch (e: Exception) {
                    failed++
                    failedNames += item.sourceName
                }
                done++
                onProgress(done, total)
            }

            if (overCap.isNotEmpty()) {
                failed += overCap.size
                failedNames += overCap.map { it.sourceName }
            }

            if (importedCount > 0 || failed > 0) {
                // The finish (whole-library rebuild + reconcile)
                // runs on the run's dedicated handle, not the live
                // instance and not under any switch lock (Codex
                // R7-F2, kept by R8): a switch can never close the
                // handle mid-rebuild, and no sign-in/out is stalled
                // for the length of a rebuild.
                rebuildLibraryIndexes(runDb)
                attachmentStore.reconcile(
                    allProvider = { runDb.attachmentDao().getAll() },
                    extraKnownPaths = { provider.allAttachmentLocalPaths() },
                    db = runDb,
                )
                // (Codex R17-F1, fix-20 decision — ACCEPT +
                // DOCUMENT) No refresh here: the run writes through
                // a dedicated sibling handle, and Room 2.8.5's
                // invalidation log is a per-connection TEMP table —
                // a sibling instance's writes can never be seen by
                // the live instance's tracker, so
                // refreshVersionsAsync()/refreshAsync() cannot
                // surface them (fix-19 diagnostic proved it from
                // Room's own source; round 18's multi-instance
                // invalidation attempt was harness-incompatible
                // and reverted). The calls were removed as dead
                // code. Documented limitation: imported notes
                // appear in the library when its collector restarts
                // (e.g. navigating back from Settings) — the same
                // as restore/sync, which use the same sibling-handle
                // pattern.
            }
            ImportRunResult(
                report = ImportReport(importedCount, duplicates, failed, failedNames),
                imageSaveFailures = imageSaveFailures,
            )
        } finally {
            // The run handle is a fresh instance, never the live
            // one, so this always closes exactly it.
            provider.closeAdditional(runDb)
        }
    }

    /**
     * Per-run folder/tag resolution state (Codex R24-F3).
     *
     * The caches are seeded from the run database by [seed] and
     * read by the resolvers; a resolver that has to create a row
     * upserts it — the caller runs it inside the note's write
     * transaction — but does NOT update the cache. The caller
     * applies [noteCommitted] only after that transaction commits,
     * so a rollback can never poison the cache with an id that was
     * never persisted (a later note resolving the same name would
     * otherwise upsert a note pointing at a folder row that does
     * not exist). Within one note the caller dedupes tag names
     * before resolving, so the deferred cache update can never
     * create the same new tag twice in one note.
     */
    private class RunMetadata(private val db: NotaDatabase) {
        private val folderByName = HashMap<String, String>()
        private val tagByExact = HashMap<String, TagEntity>()
        private val tagByLower = HashMap<String, TagEntity>()

        suspend fun seed() {
            // Folder resolution mirrors 1.x resolveFolderId (seeded
            // map, case-insensitive trimmed names, create on demand).
            for (folder in db.folderDao().getAll()) {
                folderByName.putIfAbsent(folder.name.trim().lowercase(), folder.id)
            }
            // Tag resolution mirrors NoteRepository.setNoteTags'
            // lookup spirit: exact name first, then a
            // case-insensitive match, then create (first-typed form
            // is what gets stored).
            for (tag in db.tagDao().getAllTags()) {
                tagByExact.putIfAbsent(tag.name, tag)
                tagByLower.putIfAbsent(tag.name.trim().lowercase(), tag)
            }
        }

        /** Resolves one folder inside the caller's transaction. */
        suspend fun resolveFolderId(folderName: String?): FolderResolution {
            val trimmed = folderName?.trim().orEmpty()
            if (trimmed.isEmpty()) return FolderResolution(null, null)
            val key = trimmed.lowercase()
            folderByName[key]?.let { return FolderResolution(it, null) }
            val now = System.currentTimeMillis()
            val folder = FolderEntity(
                id = UUID.randomUUID().toString(),
                name = trimmed,
                createdAt = now,
                updatedAt = now,
            )
            db.folderDao().upsert(folder)
            return FolderResolution(folder.id, folder)
        }

        /** Resolves one tag inside the caller's transaction. */
        suspend fun resolveTag(rawName: String): TagResolution {
            val name = rawName.trim()
            tagByExact[name]?.let { return TagResolution(it, null) }
            tagByLower[name.lowercase()]?.let { return TagResolution(it, null) }
            val tag = TagEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                createdAt = System.currentTimeMillis(),
            )
            db.tagDao().upsert(tag)
            return TagResolution(tag, tag)
        }

        /** Applies the rows a committed note created to the run
         *  caches. Call only after the transaction that created
         *  them has committed. */
        fun noteCommitted(createdFolder: FolderEntity?, createdTags: List<TagEntity>) {
            createdFolder?.let { folderByName[it.name.trim().lowercase()] = it.id }
            for (tag in createdTags) {
                tagByExact[tag.name] = tag
                tagByLower.putIfAbsent(tag.name.trim().lowercase(), tag)
            }
        }
    }

    /** A folder resolution: its id, plus the row when it was created
     *  by this call (null when it already existed). */
    private data class FolderResolution(val id: String?, val created: FolderEntity?)

    /** A tag resolution: the tag, plus itself when it was created
     *  by this call (null when it already existed). */
    private data class TagResolution(val tag: TagEntity, val created: TagEntity?)

    /**
     * Writes one note; returns the number of images that failed to save.
     * Throws on any other failure — and nothing is committed then: the
     * note row, its FTS entry, its attachment rows and its folder/tag
     * rows all land in ONE transaction, after every image file is
     * staged and the final content is known (Codex R24-F1/F3). A
     * process killed before that transaction commits leaves no note
     * row at all — never a committed body full of placeholder tokens
     * — and staged files with no rows are swept by the run-end
     * reconcile.
     */
    private suspend fun writeImportedNote(
        db: NotaDatabase,
        item: ImportedNote,
        meta: RunMetadata,
    ): Int {
        val noteDao = db.noteDao()
        val noteId = UUID.randomUUID().toString()
        var saveFailures = 0
        val stagedAttachments = mutableListOf<AttachmentEntity>()
        var content = item.contentHtml
        try {
            // One file-mutex hold spans the whole note write (Codex
            // R24-F1): staging writes files before their rows exist,
            // and a concurrent reconcile would mistake them for
            // orphans and delete them (Codex F9) — the lock is what
            // keeps file and row together. The DB transaction below
            // runs inside the same hold.
            attachmentStore.withFileLock {
                for (image in item.images) {
                    try {
                        // Stage first, insert later: the image row is
                        // only ever written in the final transaction,
                        // with the note row it belongs to — the note
                        // row is never committed while its body still
                        // holds the raw %%NOMA_IMPORT_IMAGE_...%%
                        // tokens (Codex R24-F1).
                        val saved = attachmentStore.stageImageFileLocked(noteId, image.stagedFile)
                        stagedAttachments += saved
                        val embed = "<img src=\"nota-attachment://${saved.id}\" " +
                            "alt=\"${escapeImportAttr(image.alt.ifBlank { saved.fileName })}\">"
                        // replace, not replaceFirst (Codex R30-F1 /
                        // R21-F2): one image spec now stands for every
                        // occurrence of the same image in the note, so
                        // its token is emitted once per occurrence and
                        // every occurrence is the embed. That is safe
                        // because the token is salted per file — literal
                        // text can't match it — so any occurrence in
                        // the content is an emitted placeholder.
                        // (R21-F2's replaceFirst was right when each
                        // token was emitted exactly once; with the
                        // R30-F1 dedupe it would leave raw tokens in
                        // the persisted body.)
                        content = if (image.token != null) {
                            content.replace(image.token, embed)
                        } else {
                            content + "<p>$embed</p>"
                        }
                    } catch (e: Exception) {
                        // The image itself failed to save (a format the
                        // pipeline can't decode, e.g. SVG): keep the alt
                        // text in its place rather than losing the note,
                        // and count it — no silent drops. A null token
                        // (Keep attachments, appended at the end) gets the
                        // same fallback as an appended paragraph; a blank
                        // alt has nothing to preserve.
                        saveFailures++
                        if (image.token != null) {
                            // Same replace rule as the success path
                            // (Codex R30-F1): every occurrence of the
                            // token becomes the alt text.
                            content = content.replace(image.token, escapeImportHtml(image.alt))
                        } else if (image.alt.isNotBlank()) {
                            content += "<p>${escapeImportHtml(image.alt)}</p>"
                        }
                    } finally {
                        if (image.deleteAfterUse) runCatching { image.stagedFile.delete() }
                    }
                }
                // Recheck the row size after image expansion (Codex
                // R12-F2): the 1 MB cap ran on the pre-expansion content,
                // but each embed replaces a short token with a longer
                // <img> tag — and Keep attachments append a whole
                // paragraph each — so the final row can exceed the bound
                // after the check already ran. A row past it breaks the
                // run-end rebuild (getAll() cannot read a row past
                // Android's ~2 MiB CursorWindow). It now runs BEFORE any
                // row exists (Codex R24-F1): a too-big note never touches
                // the database at all. Images are never dropped to fit —
                // the note is refused whole, with its name in the report.
                if (noteRowBytes(item.title, content) > ImportCaps.MAX_NOTE_CONTENT_BYTES) {
                    throw IllegalStateException(
                        "Imported note exceeds the size limit after expanding its images.",
                    )
                }
                // One transaction for the whole note (Codex R24-F1/F3):
                // note row + FTS entry + attachment rows + tag links,
                // with the folder/tag rows resolved inside it — a
                // failed write rolls the metadata back too, so a note
                // refused here leaves no orphan folder or tag behind.
                val (createdFolder, createdTags) = db.withTransaction {
                    val folderResolution = meta.resolveFolderId(item.folderName)
                    val tagResolutions = item.tagNames
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .map { meta.resolveTag(it) }
                    val tags = tagResolutions.map { it.tag }.distinctBy { it.id }
                    val entity = NoteEntity(
                        id = noteId,
                        folderId = folderResolution.id,
                        title = item.title,
                        contentHtml = content,
                        plainText = NoteDocument.plainText(NoteDocument.parse(content)),
                        createdAt = item.createdAt,
                        updatedAt = item.updatedAt,
                        pinned = item.pinned,
                        archived = item.archived,
                        trashedAt = item.trashedAt,
                    )
                    noteDao.upsert(entity)
                    db.refreshFtsEntry(entity)
                    for (attachment in stagedAttachments) {
                        db.attachmentDao().upsert(attachment)
                    }
                    val tagDao = db.tagDao()
                    for (tag in tags) tagDao.link(NoteTagCrossRef(noteId, tag.id))
                    folderResolution.created to tagResolutions.mapNotNull { it.created }
                }
                // Only now — the transaction committed — do the created
                // rows join the run caches (Codex R24-F3).
                meta.noteCommitted(createdFolder, createdTags)
            }
        } catch (e: Exception) {
            // Nothing was committed (the transaction is atomic), so
            // there is no note row or FTS entry to roll back — only
            // staged files to remove. The run-end reconcile is the
            // backstop for any that survive a kill.
            runCatching {
                File(context.filesDir, "attachments/$noteId").deleteRecursively()
            }
            for (image in item.images) {
                if (image.deleteAfterUse) runCatching { image.stagedFile.delete() }
            }
            throw e
        }
        return saveFailures
    }
}
