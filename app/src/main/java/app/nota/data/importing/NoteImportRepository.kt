package app.nota.data.importing

import android.content.Context
import androidx.room.withTransaction
import app.nota.data.attachments.AttachmentStore
import app.nota.data.backup.rebuildLibraryIndexes
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
 *  - each note is failure-isolated: a failed note is rolled back (the
 *    row delete cascades to attachment rows + tag links) and named in
 *    the report;
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
        val stagingDir = File(context.cacheDir, "import-keep-${UUID.randomUUID()}")
        return try {
            val parse = KeepTakeoutParser.parse(zipFile, stagingDir)
            val run = importNotes(parse.notes, onProgress)
            ImportOutcome(parse, run.report, run.imageSaveFailures)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    suspend fun importMarkdownZip(
        zipFile: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImportOutcome {
        val stagingDir = File(context.cacheDir, "import-mdzip-${UUID.randomUUID()}")
        return try {
            val parse = MarkdownImporter.parseImportZip(zipFile, stagingDir)
            val run = importNotes(parse.notes, onProgress)
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
                            MarkdownImporter.parseImportZip(file.file, subDir, imageBudget),
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
                            val remaining =
                                (ImportCaps.MAX_AGGREGATE_TEXT_CHARS - merged.retainedTextChars)
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
            val run = importNotes(merged.notes, onProgress)
            ImportOutcome(merged, run.report, run.imageSaveFailures)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /** Dedupe key: the separator can't appear in the numeric timestamp,
     *  so (title, createdAt) pairs are uniquely recoverable from the key. */
    private fun dedupeKey(title: String, createdAt: Long): String = "$title $createdAt"

    suspend fun importNotes(
        imported: List<ImportedNote>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImportRunResult = withContext(Dispatchers.IO) {
        // The whole run — seed reads, note writes, image rows, and
        // the finish — runs on ONE dedicated handle on the starting
        // account's file: a sibling instance of the live database,
        // opened here and closed in the finally below
        // (openDedicatedOnSameFileAs). A dedicated handle makes the
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
        val runDb = provider.openDedicatedOnSameFileAs(provider.current)
        try {
            var importedCount = 0
            var duplicates = 0
            var failed = 0
            var imageSaveFailures = 0
            val failedNames = mutableListOf<String>()

            val seen = HashSet<String>()
            for (note in runDb.noteDao().getAll()) {
                seen += dedupeKey(note.title, note.createdAt)
            }

            // Folder resolution mirrors 1.x resolveFolderId (seeded map,
            // case-insensitive trimmed names, create on demand).
            val folderByName = HashMap<String, String>()
            for (folder in runDb.folderDao().getAll()) {
                folderByName[folder.name.trim().lowercase()] = folder.id
            }
            suspend fun resolveFolderId(folderName: String?): String? {
                val trimmed = folderName?.trim().orEmpty()
                if (trimmed.isEmpty()) return null
                val key = trimmed.lowercase()
                folderByName[key]?.let { return it }
                val now = System.currentTimeMillis()
                val folder = FolderEntity(
                    id = UUID.randomUUID().toString(),
                    name = trimmed,
                    createdAt = now,
                    updatedAt = now,
                )
                runDb.folderDao().upsert(folder)
                folderByName[key] = folder.id
                return folder.id
            }

            // Tag resolution mirrors NoteRepository.setNoteTags' lookup
            // spirit: exact name first, then a case-insensitive match, then
            // create (first-typed form is what gets stored).
            val tagByExact = HashMap<String, TagEntity>()
            val tagByLower = HashMap<String, TagEntity>()
            for (tag in runDb.tagDao().getAllTags()) {
                tagByExact.putIfAbsent(tag.name, tag)
                tagByLower.putIfAbsent(tag.name.trim().lowercase(), tag)
            }
            suspend fun resolveTag(rawName: String): TagEntity {
                val name = rawName.trim()
                tagByExact[name]?.let { return it }
                tagByLower[name.lowercase()]?.let { return it }
                val tag = TagEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    createdAt = System.currentTimeMillis(),
                )
                runDb.tagDao().upsert(tag)
                tagByExact[name] = tag
                tagByLower.putIfAbsent(name.lowercase(), tag)
                return tag
            }

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
                    val key = dedupeKey(item.title, item.createdAt)
                    if (key in seen) {
                        duplicates++
                    } else {
                        val folderId = resolveFolderId(item.folderName)
                        val tags = item.tagNames
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                            .map { resolveTag(it) }
                            .distinctBy { it.id }
                        imageSaveFailures += writeImportedNote(runDb, item, folderId, tags)
                        seen += key
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
                // (Codex R17-F1) The run wrote through a dedicated
                // sibling handle, which the live instance's
                // invalidation tracker never hears about — Room has
                // no multi-instance invalidation here, so without
                // this a library Flow collecting from
                // provider.current keeps showing pre-import notes
                // until its collector restarts. If a switch landed
                // mid-run, provider.current is the new account's
                // instance and the refresh is harmless (the start
                // account's collectors are gone with the switch);
                // Room's close lock keeps a refresh from racing a
                // close. Never throws: a failed refresh must not
                // fail an import that already landed.
                // BISECT VERDICT: test-fast-b wedged with this call
                // disabled AND with all three round-17 tests
                // @Ignore'd (runs 45649915046, 45656523383,
                // 45658562154, 45660467711) — so neither this call
                // nor any round-17 test is the wedge. Restored.
                // ROUND-18 VERDICT: refreshVersionsAsync() ALONE
                // does not re-emit — the live Flow stayed at one
                // emission for 15 s even with Robolectric's main
                // looper pumped (runs 45681991679, 45686703979).
                // It only re-reads the modified-tables list; the
                // created Flows re-emit only when refreshAsync()
                // is called, which is the documented call for
                // writes made through another connection. Both
                // are kept: the first syncs the tracker's version
                // map from the log, the second emits to the Flows.
                runCatching {
                    val tracker = provider.current.invalidationTracker
                    tracker.refreshVersionsAsync()
                    tracker.refreshAsync()
                }
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
     * Writes one note; returns the number of images that failed to save.
     * Throws on any other failure — the partial note is rolled back in
     * the catch below before the throw propagates.
     */
    private suspend fun writeImportedNote(
        db: NotaDatabase,
        item: ImportedNote,
        folderId: String?,
        tags: List<TagEntity>,
    ): Int {
        val noteDao = db.noteDao()
        val noteId = UUID.randomUUID().toString()
        val entity = NoteEntity(
            id = noteId,
            folderId = folderId,
            title = item.title,
            contentHtml = item.contentHtml,
            plainText = NoteDocument.plainText(NoteDocument.parse(item.contentHtml)),
            createdAt = item.createdAt,
            updatedAt = item.updatedAt,
            pinned = item.pinned,
            archived = item.archived,
            trashedAt = item.trashedAt,
        )
        var saveFailures = 0
        try {
            db.withTransaction {
                noteDao.upsert(entity)
                db.refreshFtsEntry(entity)
            }
            var content = item.contentHtml
            for (image in item.images) {
                try {
                    // The image row must land in the same database as
                    // the note row: AttachmentStore resolves its DAO
                    // from provider.current, which may have moved.
                    val saved = attachmentStore.importImageFile(noteId, image.stagedFile, db)
                    val embed = "<img src=\"nota-attachment://${saved.id}\" " +
                        "alt=\"${escapeImportAttr(image.alt.ifBlank { saved.fileName })}\">"
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
            // run-end rebuild (getAll() cannot read past the ~2 MiB
            // CursorWindow). Throwing here rolls the note back
            // through the catch below: the first upsert + FTS entry
            // are deleted and the note is reported as failed, never
            // written oversize. Images are never dropped to fit —
            // the note is refused whole, with its name in the report.
            if (noteRowBytes(item.title, content) > ImportCaps.MAX_NOTE_CONTENT_BYTES) {
                throw IllegalStateException(
                    "Imported note exceeds the size limit after expanding its images.",
                )
            }
            val finalEntity = entity.copy(
                contentHtml = content,
                plainText = NoteDocument.plainText(NoteDocument.parse(content)),
            )
            db.withTransaction {
                noteDao.upsert(finalEntity)
                db.refreshFtsEntry(finalEntity)
                val tagDao = db.tagDao()
                for (tag in tags) tagDao.link(NoteTagCrossRef(noteId, tag.id))
            }
        } catch (e: Exception) {
            // Roll the note back: deleting the row cascades to its
            // attachment rows + tag links; orphaned files are swept by
            // the run-end reconcile.
            runCatching {
                db.withTransaction {
                    noteDao.delete(entity)
                    db.noteFtsDao().deleteForNote(noteId)
                }
            }
            for (image in item.images) {
                if (image.deleteAfterUse) runCatching { image.stagedFile.delete() }
            }
            throw e
        }
        return saveFailures
    }
}
