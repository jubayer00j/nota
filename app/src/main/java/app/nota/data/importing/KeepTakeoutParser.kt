package app.nota.data.importing

import app.nota.data.text.PlainTextCodec
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Keep Takeout parsing — a port of 1.x Noma's
 * `parseKeepTakeout` (`src/lib/noma/importers.ts`).
 *
 * Every `*.json` under any `Keep/` folder is a note; if the ZIP has no
 * Keep folder, every `.json` entry is tried (and guarded — entries that
 * don't look like Keep notes are skipped, not imported as junk).
 *
 * Nota divergences: task lists use Nota's own serialiser shape (see
 * MarkdownImport's header), text bodies go through [PlainTextCodec],
 * and attachment bytes are staged to files (three walks of the archive:
 * entry names first, then one JSON at a time, then only the attachments
 * the notes reference — at most one JSON string and zero image byte
 * arrays are ever retained in memory, so a large Takeout can't OOM
 * the import on a 1 GB-RAM device). Each attachment entry is staged
 * once and shared by every note that references it, so a shared image
 * never multiplies into per-note copies on disk.
 */
object KeepTakeoutParser {

    private class AttachmentSpec(
        val entryName: String,
        val filePath: String,
        val mimeType: String,
    )

    private class KeepDraft(
        val title: String,
        val contentHtml: String,
        val createdAt: Long,
        val updatedAt: Long,
        val pinned: Boolean,
        val archived: Boolean,
        val trashedAt: Long?,
        val tagNames: List<String>,
        val sourceName: String,
        val attachments: List<AttachmentSpec>,
    )

    fun parse(zipFile: File, stagingDir: File): ImportParseResult {
        val result = ImportParseResult()
        if (!isZipFile(zipFile)) {
            result.skippedFiles += SkippedImportFile(
                "Takeout ZIP",
                "That file couldn't be read as a ZIP archive.",
            )
            return result
        }
        stagingDir.mkdirs()
        val reader = ZipImportReader(zipFile)

        // Pass 1: entry names only. The walker retains names (8 MiB
        // budget) and counts every byte under the archive quota; no
        // file contents are buffered at all.
        try {
            reader.walk { /* names only; the walker drains + counts */ }
        } catch (e: ImportZipException) {
            result.skippedFiles += SkippedImportFile("Takeout ZIP", e.message ?: "That ZIP couldn't be read.")
            return result
        }

        // A truncated name inventory means some .json entries were
        // never listed, so their notes would silently never import —
        // say so in the report instead (the visible notes still import).
        if (reader.namesTruncated) {
            result.skippedFiles += SkippedImportFile(
                "Takeout ZIP",
                "The archive's file-name list was truncated, so some notes may not have been imported.",
            )
        }

        val jsonNames = reader.entryNames.filter { it.lowercase().endsWith(".json") }
        val inKeep = jsonNames.filter { name ->
            name.split('/').any { segment -> segment.equals("keep", ignoreCase = true) }
        }
        val candidates = if (inKeep.isNotEmpty()) inKeep else jsonNames
        val candidateSet = candidates.toSet()

        // Pass 2: convert one JSON at a time. Each raw string is
        // dropped as soon as its draft is built, so at most one JSON
        // is ever retained — a Takeout of many large notes no longer
        // holds hundreds of MiB alive while parsing. Attachment
        // resolution uses the complete name list from pass 1, so it
        // works no matter where an attachment sits in the archive.
        // The raw entry is read under its OWN bound (Codex R10-F2),
        // not the remaining aggregate budget: the aggregate counts
        // CONVERTED note text, but the raw JSON carries field names,
        // timestamps and attachment metadata that are never retained
        // as note text — bounding the read by what remains falsely
        // skipped short notes late in a run. The raw bound is the
        // 16M-char retained-text ceiling (the same safe ceiling the
        // aggregate uses), so an entry past it costs no parse work
        // and is skipped with its own reason — and a >100 MiB entry
        // still reports the per-file reason. The aggregate budget is
        // enforced on the DRAFT below (converted chars, so HTML
        // expansion — & → &amp;, paragraph markup — counts against
        // it) and again by addNote: the true check still runs when
        // the draft becomes a note.
        val drafts = mutableListOf<KeepDraft>()
        var retainedConvertedChars = 0L
        // A .json that occurs twice is the same logical note (Codex
        // R13-F2): the first occurrence wins, so the walk never
        // parses — and double-counts — the same path.
        val parsedJson = HashSet<String>()
        try {
            reader.walk { entry ->
                if (entry.name !in candidateSet) return@walk
                if (!parsedJson.add(entry.name)) return@walk
                val base = importBaseName(entry.name)
                if (drafts.size >= ImportCaps.MAX_IMPORT_NOTES) {
                    result.skippedFiles += SkippedImportFile(
                        base,
                        "Import limit reached (5,000 notes per run).",
                    )
                    return@walk
                }
                when (val read = entry.readTextUpToChars(
                    ImportCaps.MAX_RAW_ENTRY_CHARS,
                    ImportCaps.MAX_IMPORT_FILE_BYTES,
                )) {
                    is CharBoundedText.OverBytes -> {
                        result.skippedFiles += SkippedImportFile(base, "File is larger than 100 MB.")
                        return@walk
                    }
                    is CharBoundedText.OverChars -> {
                        result.skippedFiles += SkippedImportFile(
                            base,
                            ImportCaps.RAW_ENTRY_TOO_LARGE_REASON,
                        )
                        return@walk
                    }
                    is CharBoundedText.Ok -> {
                        val raw = read.text
                        val json = try {
                            JSONObject(raw)
                        } catch (e: Exception) {
                            result.skippedFiles += SkippedImportFile(base, "Not valid JSON — skipped.")
                            return@walk
                        }
                        val draft = keepJsonToDraft(
                            json, base, reader.entryNames, result,
                            reader.duplicateEntryNames,
                        )
                        if (draft != null) {
                            // The per-note bound, mirrored from addNote
                            // so a doomed draft is not retained through
                            // pass 3's attachment staging — and so a
                            // single huge note reports its own reason
                            // instead of the aggregate budget's.
                            if (noteRowBytes(draft.title, draft.contentHtml) >
                                ImportCaps.MAX_NOTE_CONTENT_BYTES
                            ) {
                                result.skippedFiles += SkippedImportFile(
                                    base,
                                    ImportCaps.NOTE_TOO_LARGE_REASON,
                                )
                                return@walk
                            }
                            if (retainedConvertedChars + draft.contentHtml.length >
                                ImportCaps.MAX_AGGREGATE_TEXT_CHARS
                            ) {
                                result.skippedFiles += SkippedImportFile(
                                    base,
                                    ImportCaps.AGGREGATE_TEXT_BUDGET_REASON,
                                )
                                return@walk
                            }
                            drafts += draft
                            retainedConvertedChars += draft.contentHtml.length
                        }
                    }
                }
            }
        } catch (e: ImportZipException) {
            // Pass 1 already counted the same bytes under the quota, so
            // this is unreachable in practice; the whole archive is
            // abandoned rather than partially applied.
            result.skippedFiles += SkippedImportFile("Takeout ZIP", e.message ?: "That ZIP couldn't be read.")
            return result
        }

        // Pass 3: stream each referenced attachment entry straight to
        // ONE staged file — image bytes are never held in memory, and
        // every note that references the entry shares that file. (The
        // old shape copied it once per note, so a shared image
        // referenced by many notes multiplied into gigabytes of cache
        // before import began.) The staged name comes from the entry
        // itself, sanitised + uniquified like every other staged
        // file, so the stored attachment keeps the image's own name.
        // The shared file is never deleted per note
        // (deleteAfterUse = false): the engine only reads it, and the
        // run-level staging-dir cleanup in importKeepZip owns it.
        val wanted = drafts.flatMap { it.attachments }.map { it.entryName }.toSet()
        val stagedByEntry = HashMap<String, File?>()
        if (wanted.isNotEmpty()) {
            val usedNames = mutableSetOf<String>()
            try {
                reader.walk { entry ->
                    if (entry.name in wanted) {
                        // A path that occurs twice in the archive is
                        // ambiguous (Codex R13-F2): staging it would
                        // silently embed whichever payload appears
                        // last, so it is not staged at all — the
                        // attachment is counted as skipped below.
                        if (entry.name in reader.duplicateEntryNames) return@walk
                        val staged = File(stagingDir, stagingFileName(entry.name, usedNames))
                        val ok = entry.copyToUpTo(staged, ImportCaps.MAX_IMPORT_FILE_BYTES)
                        // copyToUpTo deletes the target when over the cap.
                        stagedByEntry[entry.name] = if (ok) staged else null
                    }
                }
            } catch (e: ImportZipException) {
                // The archive was already counted twice under the quota,
                // so this is unreachable in practice; anything
                // unextracted is counted as skipped below.
            }
        }

        for (draft in drafts) {
            val images = mutableListOf<ImportedImage>()
            for (spec in draft.attachments) {
                val staged = stagedByEntry[spec.entryName]
                if (staged == null) {
                    result.attachmentsSkipped += 1
                    continue
                }
                images += ImportedImage(
                    name = importBaseName(spec.filePath),
                    mimeType = spec.mimeType,
                    stagedFile = staged,
                    sizeBytes = staged.length(),
                    alt = importBaseName(spec.filePath),
                    token = null, // appended at the end of the content, in order
                    deleteAfterUse = false, // shared by every referencing note
                )
            }
            // addNote is the true aggregate-budget check on the
            // converted body (the pass-2 estimate above is the raw
            // JSON length, which overstates it).
            result.addNote(
                ImportedNote(
                    title = draft.title,
                    contentHtml = draft.contentHtml,
                    createdAt = draft.createdAt,
                    updatedAt = draft.updatedAt,
                    pinned = draft.pinned,
                    archived = draft.archived,
                    trashedAt = draft.trashedAt,
                    tagNames = draft.tagNames,
                    folderName = null, // Keep imports get no folder — labels carry the organisation.
                    images = images,
                    sourceName = draft.sourceName,
                ),
            )
        }
        return result
    }

    /** ZIP entry lookup by full path, then by "ends with", then by basename.
     *  A fallback match resolves only when it is UNIQUE (Codex R9-F2):
     *  two entries sharing a suffix or basename — e.g. duplicate
     *  `photo.jpg` in separate folders — are ambiguous, and silently
     *  embedding the first would put the wrong photo in the note. The
     *  same holds for the full path itself (Codex R13-F2): when the
     *  path occurs twice in the archive, [duplicateNames] marks it and
     *  the lookup is Ambiguous — the attachment is counted and its
     *  file name kept in the note, never embedded from either payload.
     *  A suffix ambiguity is never rescued by the basename
     *  arm: basename matches are a superset of suffix matches, so an
     *  ambiguous suffix is at least as ambiguous by basename. */
    private sealed interface EntryLookup {
        data class Found(val entryName: String) : EntryLookup
        object Missing : EntryLookup
        object Ambiguous : EntryLookup
    }

    private fun findEntry(
        names: List<String>,
        ref: String,
        duplicateNames: Set<String> = emptySet(),
    ): EntryLookup {
        val wanted = ref.replace('\\', '/')
        if (wanted in names) {
            if (wanted in duplicateNames) return EntryLookup.Ambiguous
            return EntryLookup.Found(wanted)
        }
        val suffix = "/$wanted"
        val suffixMatches = names.filter { it.endsWith(suffix) }
        if (suffixMatches.size == 1) return EntryLookup.Found(suffixMatches[0])
        if (suffixMatches.size > 1) return EntryLookup.Ambiguous
        val base = importBaseName(wanted).lowercase()
        val baseMatches = names.filter { importBaseName(it).lowercase() == base }
        if (baseMatches.size == 1) return EntryLookup.Found(baseMatches[0])
        if (baseMatches.size > 1) return EntryLookup.Ambiguous
        return EntryLookup.Missing
    }

    private fun usecToMs(value: Any?): Long? {
        val number = value as? Number ?: return null
        val usec = number.toLong()
        if (usec <= 0) return null
        return usec / 1000
    }

    private fun keepJsonToDraft(
        json: JSONObject,
        sourceName: String,
        entryNames: List<String>,
        result: ImportParseResult,
        duplicateEntryNames: Set<String> = emptySet(),
    ): KeepDraft? {
        val title = json.opt("title") as? String ?: ""
        val textContent = json.opt("textContent") as? String ?: ""
        val listContent = json.opt("listContent") as? JSONArray ?: JSONArray()
        val attachments = json.opt("attachments") as? JSONArray ?: JSONArray()

        // Guard for the fallback "any .json in the ZIP" path: an entry
        // with no Keep fields at all isn't a note.
        if (title.isEmpty() && textContent.isEmpty() &&
            listContent.length() == 0 && attachments.length() == 0
        ) {
            result.skippedFiles += SkippedImportFile(sourceName, "Doesn't look like a Keep note.")
            return null
        }

        // Unknown timestamps are a stable 0, not the wall clock
        // (Codex R13-F1 — same defect as the Markdown fallback):
        // the dedupe key is (title, createdAt), so a wall-clock
        // fallback gives the same note a different key on every
        // import and reimporting it duplicates the note.
        val createdAt = usecToMs(json.opt("createdTimestampUsec"))
            ?: usecToMs(json.opt("userEditedTimestampUsec"))
            ?: 0L
        val updatedAt = usecToMs(json.opt("userEditedTimestampUsec")) ?: createdAt
        val trashed = json.opt("isTrashed") as? Boolean == true

        var contentHtml = ""
        if (listContent.length() > 0) {
            val items = mutableListOf<Pair<String, Boolean>>()
            for (i in 0 until listContent.length()) {
                val item = listContent.optJSONObject(i)
                val text = (item?.opt("text") as? String)?.replace(Regex("\\s*\\n\\s*"), " ") ?: ""
                val checked = item?.opt("isChecked") as? Boolean == true
                items += escapeImportHtml(text) to checked
            }
            contentHtml = taskListHtml(items)
        } else if (textContent.isNotEmpty()) {
            contentHtml = PlainTextCodec.textToHtml(textContent)
        }

        val tagNames = mutableListOf<String>()
        val labels = json.opt("labels") as? JSONArray
        if (labels != null) {
            for (i in 0 until labels.length()) {
                val name = labels.optJSONObject(i)?.opt("name") as? String
                if (!name.isNullOrBlank()) tagNames += name.trim()
            }
        }

        val specs = mutableListOf<AttachmentSpec>()
        for (i in 0 until attachments.length()) {
            val attachment = attachments.optJSONObject(i)
            val filePath = attachment?.opt("filePath") as? String ?: ""
            val declaredMime = attachment?.opt("mimetype") as? String ?: ""
            val mimeType = declaredMime.ifEmpty { filePath.takeIf { it.isNotEmpty() }?.let { importMimeFromName(it) } ?: "" }
            if (filePath.isEmpty() || !mimeType.startsWith("image/")) {
                // Audio memos and other non-images: the editor has no
                // player for them — counted and reported, never
                // silently dropped.
                result.attachmentsSkipped += 1
                continue
            }
            val entryName = when (
                val found = findEntry(entryNames, filePath, duplicateEntryNames)
            ) {
                is EntryLookup.Found -> found.entryName
                EntryLookup.Missing -> {
                    result.attachmentsSkipped += 1
                    continue
                }
                EntryLookup.Ambiguous -> {
                    // Two entries share the fallback name: embedding
                    // either could put the wrong photo in the note
                    // (Codex R9-F2). Leave it unresolved — counted —
                    // and keep the file name in the note, like the
                    // Markdown resolver keeps an unresolved
                    // reference's alt text.
                    result.attachmentsSkipped += 1
                    contentHtml += "<p>${escapeImportHtml(importBaseName(filePath))}</p>"
                    continue
                }
            }
            specs += AttachmentSpec(entryName, filePath, mimeType)
        }

        val color = json.opt("color") as? String
        if (!color.isNullOrEmpty() && color != "DEFAULT") {
            result.coloredNotes += 1
        }

        return KeepDraft(
            title = title,
            contentHtml = contentHtml,
            createdAt = createdAt,
            updatedAt = updatedAt,
            pinned = json.opt("isPinned") as? Boolean == true,
            archived = json.opt("isArchived") as? Boolean == true,
            trashedAt = if (trashed) updatedAt else null,
            tagNames = tagNames,
            sourceName = sourceName,
            attachments = specs,
        )
    }

    /** One task list in Nota's own serialiser shape (see MarkdownImport). */
    private fun taskListHtml(items: List<Pair<String, Boolean>>): String {
        val lis = items.joinToString("") { (html, checked) ->
            "<li data-type=\"taskItem\" data-checked=\"${if (checked) "true" else "false"}\">" +
                "<div><p>$html</p></div></li>"
        }
        return "<ul data-type=\"taskList\">$lis</ul>"
    }
}
