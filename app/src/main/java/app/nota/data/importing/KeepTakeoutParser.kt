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
 * the import on a 1 GB-RAM device).
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
        val drafts = mutableListOf<KeepDraft>()
        try {
            reader.walk { entry ->
                if (entry.name !in candidateSet) return@walk
                val base = importBaseName(entry.name)
                if (drafts.size >= ImportCaps.MAX_IMPORT_NOTES) {
                    result.skippedFiles += SkippedImportFile(
                        base,
                        "Import limit reached (5,000 notes per run).",
                    )
                    return@walk
                }
                val raw = entry.readTextUpTo(ImportCaps.MAX_IMPORT_FILE_BYTES)
                if (raw == null) {
                    result.skippedFiles += SkippedImportFile(base, "File is larger than 100 MB.")
                    return@walk
                }
                val json = try {
                    JSONObject(raw)
                } catch (e: Exception) {
                    result.skippedFiles += SkippedImportFile(base, "Not valid JSON — skipped.")
                    return@walk
                }
                val draft = keepJsonToDraft(json, base, reader.entryNames, result)
                if (draft != null) drafts += draft
            }
        } catch (e: ImportZipException) {
            // Pass 1 already counted the same bytes under the quota, so
            // this is unreachable in practice; the whole archive is
            // abandoned rather than partially applied.
            result.skippedFiles += SkippedImportFile("Takeout ZIP", e.message ?: "That ZIP couldn't be read.")
            return result
        }

        // Pass 3: stream each referenced attachment entry straight to
        // a per-entry temp file — image bytes are never held in memory.
        // Each note then gets its own staged copy of the temp file (the
        // engine deletes it after the save attempt, so notes can't
        // share one file).
        val wanted = drafts.flatMap { it.attachments }.map { it.entryName }.toSet()
        val stagedByEntry = HashMap<String, File?>()
        if (wanted.isNotEmpty()) {
            var entryIndex = 0
            try {
                reader.walk { entry ->
                    if (entry.name in wanted) {
                        val temp = File(stagingDir, "keep-entry-${entryIndex++}.tmp")
                        val ok = entry.copyToUpTo(temp, ImportCaps.MAX_IMPORT_FILE_BYTES)
                        // copyToUpTo deletes the target when over the cap.
                        stagedByEntry[entry.name] = if (ok) temp else null
                    }
                }
            } catch (e: ImportZipException) {
                // The archive was already counted twice under the quota,
                // so this is unreachable in practice; anything
                // unextracted is counted as skipped below.
            }
        }

        val usedNames = mutableSetOf<String>()
        for (draft in drafts) {
            val images = mutableListOf<ImportedImage>()
            for (spec in draft.attachments) {
                val temp = stagedByEntry[spec.entryName]
                if (temp == null) {
                    result.attachmentsSkipped += 1
                    continue
                }
                val staged = File(stagingDir, stagingFileName(spec.filePath, usedNames))
                try {
                    temp.copyTo(staged, overwrite = true)
                } catch (e: Exception) {
                    result.attachmentsSkipped += 1
                    continue
                }
                images += ImportedImage(
                    name = importBaseName(spec.filePath),
                    mimeType = spec.mimeType,
                    stagedFile = staged,
                    sizeBytes = temp.length(),
                    alt = importBaseName(spec.filePath),
                    token = null, // appended at the end of the content, in order
                )
            }
            result.notes += ImportedNote(
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
            )
        }
        return result
    }

    /** ZIP entry lookup by full path, then by "ends with", then by basename. */
    private fun findEntry(names: List<String>, ref: String): String? {
        val wanted = ref.replace('\\', '/')
        if (wanted in names) return wanted
        val suffix = "/$wanted"
        names.firstOrNull { it.endsWith(suffix) }?.let { return it }
        val base = importBaseName(wanted).lowercase()
        return names.firstOrNull { importBaseName(it).lowercase() == base }
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

        val createdAt = usecToMs(json.opt("createdTimestampUsec"))
            ?: usecToMs(json.opt("userEditedTimestampUsec"))
            ?: System.currentTimeMillis()
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
            val entryName = findEntry(entryNames, filePath)
            if (entryName == null) {
                result.attachmentsSkipped += 1
                continue
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
