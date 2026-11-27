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
import app.nota.data.db.NoteLinkEntity
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

/** The image-token prefix the Markdown converter emits
 *  (MarkdownImport.kt: `%%NOMA_IMPORT_IMAGE_<salt>_<seq>%%`):
 *  every token starts with it and ends with `%%`, with no `%%`
 *  inside, so a single scan can find each token's span. */
private const val IMAGE_TOKEN_PREFIX = "%%NOMA_IMPORT_IMAGE_"

/**
 * Thrown inside a note's write transaction (Codex R45-F1) when
 * the per-note dedupe recheck finds a twin that landed after
 * the run's one-time seed — a Merge restore or a sync apply
 * writing mid-import. The import loop counts it as a duplicate,
 * not a failure: the note is already in the library, just under
 * another id. Nothing is committed when it throws: the check
 * runs before the note row exists, and the transaction rolls
 * back — including a folder row resolved just before it.
 */
internal class DuplicateImportedNoteException(message: String) : Exception(message)

/**
 * The result of one note's write (Codex R45-F1): images that
 * failed to save, plus the stored row's id — the run tracks its
 * own ids so the dedupe recheck can tell a concurrent twin from
 * a note this run just wrote (a dateless twin and a run note
 * share (title, folder); only the id tells them apart).
 */
internal data class ImportedNoteWrite(
    val saveFailures: Int,
    val noteId: String,
)

/**
 * Replaces every %%NOMA_IMPORT_IMAGE_...%% token in [content]
 * with its replacement in a single scan (Codex R36-F2). The old
 * per-image `content.replace(token, embed)` scanned and copied
 * the whole body once per DISTINCT image — quadratic for a note
 * with thousands of images (the R30-F1 dedupe only collapses
 * repeats of the same file). A token with no replacement is
 * copied through unchanged, and text after a prefix with no
 * closing `%%` is copied through too — the same as a replace
 * that matches nothing. Each token is unique per occurrence
 * (Codex R38-F2), so the map holds one replacement per token.
 * A replacement is never re-scanned, so a token-like string
 * inside an alt text stays literal.
 */
internal fun replaceImageTokensOnce(
    content: String,
    replacements: Map<String, String>,
): String {
    if (replacements.isEmpty()) return content
    val out = StringBuilder(content.length)
    var cursor = 0
    while (true) {
        val start = content.indexOf(IMAGE_TOKEN_PREFIX, cursor)
        if (start == -1) {
            out.append(content, cursor, content.length)
            break
        }
        val end = content.indexOf("%%", start + IMAGE_TOKEN_PREFIX.length)
        if (end == -1) {
            out.append(content, cursor, content.length)
            break
        }
        out.append(content, cursor, start)
        val token = content.substring(start, end + 2)
        out.append(replacements[token] ?: token)
        cursor = end + 2
    }
    return out.toString()
}

/**
 * The (token, alt) occurrences of one image spec (Codex R38-F2):
 * the spec's own list when the Markdown sink built it, or the
 * spec's single (token, alt) when a caller constructed it
 * directly (Keep path, tests) — a null token has no occurrences
 * and is never passed here.
 */
private fun imageOccurrences(image: ImportedImage): List<ImageOccurrence> =
    if (image.occurrences.isEmpty() && image.token != null) {
        listOf(ImageOccurrence(image.token, image.alt))
    } else {
        image.occurrences
    }

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
 *  - backlinks are created per note, inside the same transaction
 *    (Codex R53-F1): the note's own outgoing [[title]] edges, plus
 *    edges from existing notes that link to its title. The run-end
 *    rebuild used to be the only backstop, so a process killed
 *    before it ran left committed notes' edges missing — and the
 *    startup backfill never repairs them once any link row (or its
 *    done-marker) exists. The rebuild still runs at the end as the
 *    final reconcile (it also does FTS + the file sweep), but a
 *    killed run's graph is truthful without it. The semantic index
 *    needs nothing: its stamp-gated reconcile picks new notes up,
 *    and nothing is embedded during an import.
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
                            // same shape). The read is capped by the
                            // per-file cap only (Codex R54-F1) — never
                            // by the remaining run budget: a loose file
                            // whose raw text is past the remaining
                            // budget but whose converted note fits (a
                            // large front-matter block, .txt whitespace)
                            // imports now. The aggregate budget is
                            // enforced on the CONVERTED note instead —
                            // inside parseMarkdownFile by addNote, and
                            // at merge time by enforceContentBudget
                            // below, which drops tail notes past the
                            // budget with the aggregate reason.
                            // Over-budget files are reported as skipped,
                            // never silently dropped. The trade-off is
                            // the R9-F1 one, reversed: a file past the
                            // remaining budget in raw terms is now read
                            // in full (up to the 100 MB per-file cap,
                            // ~200 MB as a UTF-16 String on a 1 GB
                            // device) before the budget decision — the
                            // per-file byte cap still bounds the read.
                            // The char cap equals the byte cap, so
                            // OverChars below cannot fire (a file past
                            // both bounds reports OverBytes) and stays
                            // as defence in depth.
                            when (val read = withContext(Dispatchers.IO) {
                                file.file.readTextUpToChars(
                                    ImportCaps.MAX_IMPORT_FILE_BYTES,
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
                                    val parsed = MarkdownImporter.parseMarkdownFile(input, resolver)
                                    // (Codex R54-F1): the aggregate budget
                                    // is enforced on the CONVERTED note —
                                    // check the converted charge against
                                    // the merged remaining BEFORE
                                    // absorbing, so a note that doesn't
                                    // fit never contributes its image
                                    // counts to the merged total (the
                                    // R6-F1/R9-F1 property: a refused
                                    // file leaves no image counts
                                    // behind). The merge-time trim below
                                    // stays as the backstop for sums
                                    // across sources.
                                    if (merged.retainedTextChars + parsed.retainedTextChars >
                                        ImportCaps.MAX_AGGREGATE_TEXT_CHARS
                                    ) {
                                        for (note in parsed.notes) {
                                            merged.skippedFiles += SkippedImportFile(
                                                note.sourceName,
                                                ImportCaps.AGGREGATE_TEXT_BUDGET_REASON,
                                            )
                                        }
                                        merged.skippedFiles += parsed.skippedFiles
                                    } else {
                                        merged.absorb(parsed)
                                    }
                                    // Same merge-time trim as the ZIP
                                    // branch above (Codex R12-F1): the
                                    // merged result is re-checked after
                                    // every absorb, not just at the end
                                    // of the loop.
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

            // The dedupe seed reads title, createdAt and folderId
            // only — through the narrow projection (Codex R32-F1),
            // never a full-entity getAll(): a single oversized
            // existing row can exceed Android's CursorWindow (2 MB)
            // and crash the import before the first note is
            // processed.
            val seen = HashSet<String>()
            for (row in runDb.noteDao().getAllDedupeRows()) {
                seen += if (row.createdAt == 0L) {
                    dedupeKeyUnknownDate(row.title, folderNameById[row.folderId], null)
                } else {
                    dedupeKey(row.title, row.createdAt)
                }
            }
            // Keys of notes imported by this run. `seen` is seeded
            // from the library only and never grows during the run:
            // an unknown-date note's seeded key carries no source
            // name, so adding it here would collapse a second
            // same-title note in the same folder.
            val seenRun = HashSet<String>()

            // Ids of notes this run wrote (Codex R45-F1): the
            // per-note dedupe recheck in writeImportedNote must
            // tell a concurrent twin from a note this run just
            // wrote — a dateless twin and a run note share
            // (title, folder), so only the id separates them.
            val runNoteIds = HashSet<String>()

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
                        val write = writeImportedNote(runDb, item, meta, runNoteIds)
                        imageSaveFailures += write.saveFailures
                        runNoteIds += write.noteId
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
                } catch (e: DuplicateImportedNoteException) {
                    // (Codex R45-F1): the per-note recheck found a
                    // twin a concurrent restore or sync apply wrote
                    // after the seed — a duplicate, not a failure.
                    // The note is already in the library. The write
                    // transaction rolled back, so no rows were
                    // written for this item; staged files are
                    // removed by writeImportedNote's cleanup or
                    // swept by the run-end reconcile.
                    duplicates++
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
     * case-insensitively (first spelling wins) before resolving,
     * so the deferred cache update can never create the same new
     * tag twice in one note.
     */
    // Internal, not private, so tests can drive one note's write
    // directly (Codex R37-F1 — see writeImportedNote).
    internal class RunMetadata(private val db: NotaDatabase) {
        private val folderByName = HashMap<String, String>()
        private val tagByExact = HashMap<String, TagEntity>()
        private val tagByLower = HashMap<String, TagEntity>()

        /**
         * Pre-existing notes that link to each title (Codex R53-F1):
         * title → ids of library notes whose plain text mentions
         * `[[title]]`, seeded once at run start from the index
         * projection. When a note of that title is imported, its
         * incoming edges are created from this map inside the note's
         * transaction — no per-note library scan. Locked or trashed
         * sources are excluded at seed time, mirroring
         * [app.nota.data.repository.NoteRepository.relink]; a source
         * deleted, trashed or locked after the seed is caught again
         * by [createBacklinks]' per-linker check.
         *
         * Cost: one extra projection pass at run start — the same
         * pass the run-end rebuild already pays once. It reads the
         * same [getAllIndexRows] rows, so a `[[title]]` mention past
         * the plain-text cut is invisible here exactly as it is to
         * the rebuild. Staleness: a concurrent writer adding a linker
         * mid-import is not in the map — the same class as the
         * dedupe seed's staleness, and the writer's own end-rebuild
         * (restore/sync) is the backstop for a completed run.
         */
        private val linkersByTitle = HashMap<String, MutableList<String>>()

        /**
         * Same-run linkers (Codex R53-F1): title → ids of notes this
         * run already wrote whose `[[title]]` did not resolve at
         * their write time. Recorded only after the note's
         * transaction commits (see [recordUnresolvedLinker]), so a
         * rolled-back note can never leave a phantom linker behind.
         */
        private val unresolvedByTitle = HashMap<String, MutableList<String>>()

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
            // Backlink linkers (Codex R53-F1): one pass over the
            // index projection — id, title, plain text, locked,
            // trashedAt — recording, per title, the notes that
            // mention it. Only unlocked, untrashed sources link.
            for (row in db.noteDao().getAllIndexRows()) {
                if (row.locked || row.trashedAt != null) continue
                for (title in NoteDocument.backlinkTitles(row.plainText)) {
                    linkersByTitle.getOrPut(title) { mutableListOf() } += row.id
                }
            }
        }

        /**
         * Records [noteId] as a linker for [titles] whose `[[title]]`
         * did not resolve at its write time. Call only after the
         * note's transaction has committed — the ids are only
         * meaningful once the note row exists.
         */
        fun recordUnresolvedLinker(noteId: String, titles: List<String>) {
            for (title in titles) {
                unresolvedByTitle.getOrPut(title) { mutableListOf() } += noteId
            }
        }

        /**
         * Creates this note's backlinks inside its write transaction
         * (Codex R53-F1) — call after the note row, tag links and
         * FTS refresh have landed, so the edges are atomic with the
         * note itself. Returns the `[[title]]` mentions that did not
         * resolve, for [recordUnresolvedLinker] after commit.
         *
         * Outgoing: each distinct `[[title]]` in [plainText] resolves
         * through the row-weight target lookup; a missing or locked
         * target (and a self-mention) yields no edge, mirroring
         * NoteRepository.relink — the first two are recorded as
         * unresolved, so a later note of that title links back.
         * Incoming: notes linking to [title] — pre-existing ones from
         * [linkersByTitle], same-run ones from [unresolvedByTitle] —
         * gain an edge to this note, but only when the per-linker
         * check passes (the source still exists, unlocked and
         * untrashed, and links to no note of this title yet — first
         * target wins, as the rebuild's LIMIT 1 resolves it).
         * A trashed note holds no edges in either direction.
         * The run-end rebuild still re-derives everything, so these
         * per-note edges are additive, never a replacement.
         */
        suspend fun createBacklinks(
            db: NotaDatabase,
            noteId: String,
            title: String,
            plainText: String,
            trashedAt: Long?,
        ): List<String> {
            if (trashedAt != null) return emptyList()
            val linkDao = db.noteLinkDao()
            val noteDao = db.noteDao()
            val unresolved = mutableListOf<String>()
            for (mentioned in NoteDocument.backlinkTitles(plainText)) {
                val target = noteDao.getLinkTargetByTitle(mentioned)
                when {
                    target == null || target.locked -> unresolved += mentioned
                    target.id != noteId -> linkDao.insertAll(
                        listOf(NoteLinkEntity(noteId, target.id)),
                    )
                    // A self-mention resolves to this note: no edge,
                    // and not unresolved either — the rebuild's LIMIT 1
                    // also lands on this note first, so a later
                    // same-title note must not steal the link.
                }
            }
            if (title.isNotEmpty()) {
                val linkers = (linkersByTitle[title] ?: emptyList()) +
                    (unresolvedByTitle[title] ?: emptyList())
                for (linkerId in linkers) {
                    if (linkerId == noteId) continue
                    if (linkDao.canLinkToTitle(linkerId, title) > 0) {
                        linkDao.insertAll(listOf(NoteLinkEntity(linkerId, noteId)))
                    }
                }
            }
            return unresolved
        }

        /** Resolves one folder inside the caller's transaction. */
        suspend fun resolveFolderId(folderName: String?): FolderResolution {
            val trimmed = folderName?.trim().orEmpty()
            if (trimmed.isEmpty()) return FolderResolution(null, null)
            val key = trimmed.lowercase()
            folderByName[key]?.let { cachedId ->
                // (Codex R48-F1): the cache is seeded once at run
                // start, and the folder can be deleted after the
                // seed — the user deleting it from the library UI
                // while a long import is parsing, or a Merge restore
                // landing mid-import (a Replace restore wipes
                // folders, which stales EVERY cached id). Returning
                // the dead id would violate the note's foreign key
                // to folders and fail the whole note write.
                // Revalidate inside this transaction: the row still
                // exists under the same name → reuse it. The name
                // must match too, not just the id: a rename after
                // the seed leaves a live id pointing at a different
                // name, and the note belongs in a folder of the
                // imported name.
                db.folderDao().getById(cachedId)?.let { existing ->
                    if (existing.name.trim().lowercase() == key) {
                        return FolderResolution(existing.id, null)
                    }
                }
            }
            // Re-resolve by name: another writer may have recreated
            // a same-named folder after the seed (or after deleting
            // the cached one) — reuse it instead of creating a
            // second folder that differs only by case. A row found
            // here already exists, so adopt it into the cache now:
            // it is persisted by another writer and exists
            // regardless of this transaction's outcome.
            db.folderDao().getByNameIgnoreCase(trimmed)?.let { existing ->
                folderByName[key] = existing.id
                return FolderResolution(existing.id, null)
            }
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
            tagByExact[name]?.let { cached ->
                // (Codex R48-F1): the same staleness shape as
                // folders — a tag deleted after the seed leaves a
                // dead id in the cache, and the note_tags link would
                // violate its foreign key to tags and fail the whole
                // note write. Revalidate inside this transaction: a
                // live row under the same name is reused, and
                // anything else falls through to the re-read below
                // (which also reuses a same-named row another writer
                // recreated). The name must match too: a rename
                // after the seed leaves a live id pointing at a
                // different name.
                db.tagDao().getById(cached.id)?.let { existing ->
                    if (existing.name == name) return TagResolution(existing, null)
                }
            }
            tagByLower[name.lowercase()]?.let { cached ->
                db.tagDao().getById(cached.id)?.let { existing ->
                    if (existing.name.trim().lowercase() == name.lowercase()) {
                        return TagResolution(existing, null)
                    }
                }
            }
            // (Codex R46-F2): the caches are seeded once at run start,
            // and a Merge restore can add a tag after the seed — the
            // fix-48 recheck covers notes only, not metadata. Re-read
            // the database before creating: the lookup runs inside the
            // note's write transaction, which holds the write lock
            // from its start (BEGIN IMMEDIATE, see the R45-F1 recheck
            // comment in writeImportedNote), so no concurrent writer
            // can commit between the lookup and the upsert below. The
            // exact lookup first, then a case-insensitive one (first
            // spelling wins, Codex R33-F1) — a raced case-variant
            // reuses the existing row instead of creating a second tag
            // that differs only by case. A row found here already
            // exists, so it is adopted into the caches and returned
            // with created = null: it is not this run's creation, and
            // noteCommitted must not record it as one. (Adopting it
            // now cannot poison the cache on a rollback — the row is
            // persisted by another writer, so it exists regardless of
            // this transaction's outcome.) Mirrors
            // NoteRepository.setNoteTags, which does the same
            // getByName-before-create inside its own transaction.
            val existing = db.tagDao().getByName(name)
                ?: db.tagDao().getByNameIgnoreCase(name)
            if (existing != null) {
                tagByExact[existing.name] = existing
                tagByLower.putIfAbsent(existing.name.trim().lowercase(), existing)
                return TagResolution(existing, null)
            }
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
     *  by this call (null when it already existed). Internal, not
     *  private: [RunMetadata] is internal (Codex R37-F1 test seam),
     *  and its resolvers return these types. */
    internal data class FolderResolution(val id: String?, val created: FolderEntity?)

    /** A tag resolution: the tag, plus itself when it was created
     *  by this call (null when it already existed). Internal for
     *  the same reason as [FolderResolution]. */
    internal data class TagResolution(val tag: TagEntity, val created: TagEntity?)

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
     *
     * Internal, not private, so tests can assert on one note's write
     * without the run-end rebuild (Codex R37-F1): the rebuild would
     * mask a stale FTS row, so the ordering fix is only observable
     * before it runs.
     */
    internal suspend fun writeImportedNote(
        db: NotaDatabase,
        item: ImportedNote,
        meta: RunMetadata,
        // (Codex R45-F1): ids of notes this run already wrote.
        // The per-note dedupe recheck below must not mistake them
        // for concurrent twins — a dateless run note and a dateless
        // twin share (title, folder), so only the id separates
        // them. Direct callers pass nothing: with no run in
        // flight, every matching row is a twin.
        excludeNoteIds: Set<String> = emptySet(),
    ): ImportedNoteWrite {
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
                // (Codex R36-F2): the substitutions for every image
                // token are collected here and applied in ONE pass
                // over the body below. The old loop called
                // content.replace(token, embed) once per image, and
                // each call scanned and copied the whole body — a
                // note with thousands of DISTINCT images paid a
                // whole-body copy per image (the R30-F1 dedupe only
                // collapses repeats of the same file). Staging still
                // runs per image: each embed needs its staged id, and
                // a failed save needs its alt text. Null-token images
                // (Keep attachments) append their paragraph at the
                // end, as before — collected here, appended after the
                // pass, in image order.
                val tokenReplacements = LinkedHashMap<String, String>()
                val appendedParagraphs = StringBuilder()
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
                        // (Codex R38-F2): one embed per occurrence,
                        // each with its own alt text — a note repeating
                        // the same image with different alts keeps
                        // every description; the old shape embedded the
                        // first alt at every occurrence. The file is
                        // staged once, above; only the substitutions
                        // multiply. Tokens are unique per occurrence
                        // (salted per file, R21-F2), so putIfAbsent is
                        // a safety net, not the dedupe.
                        if (image.token != null) {
                            for (occurrence in imageOccurrences(image)) {
                                val embed = "<img src=\"nota-attachment://${saved.id}\" " +
                                    "alt=\"${escapeImportAttr(occurrence.alt.ifBlank { saved.fileName })}\">"
                                tokenReplacements.putIfAbsent(occurrence.token, embed)
                            }
                        } else {
                            val embed = "<img src=\"nota-attachment://${saved.id}\" " +
                                "alt=\"${escapeImportAttr(image.alt.ifBlank { saved.fileName })}\">"
                            appendedParagraphs.append("<p>$embed</p>")
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
                            // Same replace rule as the success path:
                            // each occurrence's token becomes ITS alt
                            // text (Codex R38-F2) — a repeated image
                            // that fails to save keeps every
                            // description, not just the first.
                            for (occurrence in imageOccurrences(image)) {
                                tokenReplacements.putIfAbsent(
                                    occurrence.token,
                                    escapeImportHtml(occurrence.alt),
                                )
                            }
                        } else if (image.alt.isNotBlank()) {
                            appendedParagraphs.append(
                                "<p>${escapeImportHtml(image.alt)}</p>",
                            )
                        }
                    } finally {
                        if (image.deleteAfterUse) runCatching { image.stagedFile.delete() }
                    }
                }
                content = replaceImageTokensOnce(content, tokenReplacements) +
                    appendedParagraphs.toString()
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
                val (createdFolder, createdTags, unresolvedTitles) = db.withTransaction {
                    val folderResolution = meta.resolveFolderId(item.folderName)
                    // (Codex R45-F1): recheck dedupe against the
                    // database, inside this transaction. The run's
                    // one-time seed predates any restore or sync
                    // apply that landed mid-import, and the
                    // in-memory sets cannot see it — so a twin
                    // committed after the seed would otherwise be
                    // inserted again under a new id. The check runs
                    // here, after folder resolution (a dateless
                    // twin is matched on its resolved folder) and
                    // before any tag or note row exists, and the
                    // transaction holds the write lock from its
                    // start (BEGIN IMMEDIATE), so no concurrent
                    // writer can commit between the check and the
                    // insert below. The queries are id-only
                    // projections, never full entities (Codex
                    // R32-F1), and the caller filters its own run's
                    // ids in Kotlin — a second same-title dateless
                    // note in one run (different source identity)
                    // still imports (Codex R21-F1). A match throws
                    // DuplicateImportedNoteException: the loop counts
                    // it as a duplicate, and the rollback takes the
                    // resolved folder row with it.
                    val twinIds = if (item.createdAt == 0L) {
                        noteDao.findDatelessIdsByTitleAndFolder(item.title, folderResolution.id)
                    } else {
                        noteDao.findIdsByTitleAndCreatedAt(item.title, item.createdAt)
                    }
                    if (twinIds.any { it !in excludeNoteIds }) {
                        throw DuplicateImportedNoteException(
                            "A note titled \"${item.title}\" already exists in the library.",
                        )
                    }
                    val tagResolutions = item.tagNames
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        // Case-insensitive dedupe, first spelling wins
                        // (Codex R33-F1, mirroring
                        // NoteRepository.setNoteTags): `.distinct()`
                        // only removed exact duplicates, so `Foo` and
                        // `foo` both reached resolveTag and each
                        // created its own tag row, because the new tag
                        // only joins the run cache after the
                        // transaction commits.
                        .distinctBy { it.lowercase() }
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
                    for (attachment in stagedAttachments) {
                        db.attachmentDao().upsert(attachment)
                    }
                    val tagDao = db.tagDao()
                    for (tag in tags) tagDao.link(NoteTagCrossRef(noteId, tag.id))
                    // (Codex R37-F1): the FTS row must be built AFTER
                    // the tag links land — buildFtsEntity denormalises
                    // tag names from the cross-ref table, so refreshing
                    // first persists a row without the imported tags,
                    // and the run-end rebuild is the only backstop: a
                    // process killed before it runs leaves the
                    // committed note unsearchable by tag. setNoteTags
                    // links first and refreshes second, for the same
                    // reason ("a tag assignment is a search-index
                    // write too").
                    db.refreshFtsEntry(entity)
                    // (Codex R53-F1): backlinks are created here, in
                    // the same transaction as the note row — the
                    // outgoing [[title]] edges, plus edges from
                    // existing notes linking to this note's title.
                    // The returned titles are the mentions that did
                    // not resolve; they are recorded below, after the
                    // commit, so a later note of that title links
                    // back to this one.
                    val unresolvedTitles = meta.createBacklinks(
                        db,
                        noteId,
                        entity.title,
                        entity.plainText,
                        entity.trashedAt,
                    )
                    Triple(
                        folderResolution.created,
                        tagResolutions.mapNotNull { it.created },
                        unresolvedTitles,
                    )
                }
                // Only now — the transaction committed — do the created
                // rows join the run caches (Codex R24-F3).
                meta.noteCommitted(createdFolder, createdTags)
                meta.recordUnresolvedLinker(noteId, unresolvedTitles)
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
        return ImportedNoteWrite(saveFailures, noteId)
    }
}
