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
    ): ImportOutcome {
        val stagingDir = File(context.cacheDir, "import-mdsel-${UUID.randomUUID()}")
        return try {
            val merged = ImportParseResult()
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
                        merged.absorb(MarkdownImporter.parseImportZip(file.file, subDir))
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
                            // same shape).
                            val input = MarkdownFileInput(
                                name = file.name,
                                path = file.name,
                                text = withContext(Dispatchers.IO) { file.file.readText() },
                                lastModified = file.lastModified,
                            )
                            merged.absorb(MarkdownImporter.parseMarkdownFile(input, resolver))
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
            // not bound the merged run on their own — enforce the cap
            // across the whole selection here.
            merged.enforceNoteCap()
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
        val startAccountId = provider.accountId.value
        // The run is pinned to the account it started in. Resolving
        // the database once and holding it would write into a closed
        // instance after a switch (DatabaseProvider closes the previous
        // instance in switchTo); resolving per note would split the run
        // across two accounts. So the handle follows a switch back to
        // the starting account's file: normally the live instance
        // itself, and a separately-opened handle only once a switch has
        // happened (openAdditional/closeAdditional is the documented
        // protocol for exactly this — the guest→account copy uses it).
        // The folder/tag caches seeded below stay valid across the
        // pin: the pinned handle opens the same database file.
        var db = provider.current
        var pinnedDb: NotaDatabase? = null
        fun dbForWrite(): NotaDatabase {
            if (provider.accountId.value == startAccountId) return db
            if (pinnedDb == null) {
                pinnedDb = provider.openAdditional(startAccountId)
                db = pinnedDb!!
            }
            return db
        }
        try {
            val noteDao = db.noteDao()

            var importedCount = 0
            var duplicates = 0
            var failed = 0
            var imageSaveFailures = 0
            val failedNames = mutableListOf<String>()

            val seen = HashSet<String>()
            for (note in noteDao.getAll()) seen += dedupeKey(note.title, note.createdAt)

            // Folder resolution mirrors 1.x resolveFolderId (seeded map,
            // case-insensitive trimmed names, create on demand).
            val folderByName = HashMap<String, String>()
            for (folder in db.folderDao().getAll()) {
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
                // Resolved per call: after a switch the captured DAO
                // belongs to the closed instance.
                dbForWrite().folderDao().upsert(folder)
                folderByName[key] = folder.id
                return folder.id
            }

            // Tag resolution mirrors NoteRepository.setNoteTags' lookup
            // spirit: exact name first, then a case-insensitive match, then
            // create (first-typed form is what gets stored).
            val tagByExact = HashMap<String, TagEntity>()
            val tagByLower = HashMap<String, TagEntity>()
            for (tag in db.tagDao().getAllTags()) {
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
                dbForWrite().tagDao().upsert(tag)
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
                try {
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
                        imageSaveFailures += writeImportedNote(dbForWrite(), item, folderId, tags)
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
                val finishDb = dbForWrite()
                rebuildLibraryIndexes(finishDb)
                attachmentStore.reconcile(
                    allProvider = { finishDb.attachmentDao().getAll() },
                    extraKnownPaths = { provider.allAttachmentLocalPaths() },
                    db = finishDb,
                )
            }
            ImportRunResult(
                report = ImportReport(importedCount, duplicates, failed, failedNames),
                imageSaveFailures = imageSaveFailures,
            )
        } finally {
            // closeAdditional is a no-op when the pinned handle IS the
            // live instance, so this only ever closes the extra one.
            pinnedDb?.let { provider.closeAdditional(it) }
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
