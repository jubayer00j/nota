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
 * and attachment bytes are staged to files (two walks of the archive:
 * notes first, then only the attachments they reference).
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

        // Pass 1: read every .json entry (bounded per file); all other
        // entries are drained + counted by the walker.
        val jsonTexts = LinkedHashMap<String, String?>()
        try {
            reader.walk { entry ->
                if (entry.name.endsWith("/")) return@walk
                if (!entry.name.lowercase().endsWith(".json")) return@walk
                jsonTexts[entry.name] = entry.readTextUpTo(ImportCaps.MAX_IMPORT_FILE_BYTES)
            }
        } catch (e: ImportZipException) {
            result.skippedFiles += SkippedImportFile("Takeout ZIP", e.message ?: "That ZIP couldn't be read.")
            return result
        }

        val jsonNames = jsonTexts.keys.toList()
        val inKeep = jsonNames.filter { name ->
            name.split('/').any { segment -> segment.equals("keep", ignoreCase = true) }
        }
        val candidates = if (inKeep.isNotEmpty()) inKeep else jsonNames

        // Convert the JSONs; image attachments are resolved to entry
        // names now and extracted in pass 2.
        val drafts = mutableListOf<KeepDraft>()
        for (name in candidates) {
            val base = importBaseName(name)
            if (drafts.size >= ImportCaps.MAX_IMPORT_NOTES) {
                result.skippedFiles += SkippedImportFile(
                    base,
                    "Import limit reached (5,000 notes per run).",
                )
                continue
            }
            val raw = jsonTexts[name]
            if (raw == null) {
                result.skippedFiles += SkippedImportFile(base, "File is larger than 100 MB.")
                continue
            }
            val json = try {
                JSONObject(raw)
            } catch (e: Exception) {
                result.skippedFiles += SkippedImportFile(base, "Not valid JSON — skipped.")
                continue
            }
            val draft = keepJsonToDraft(json, base, reader.entryNames, result)
            if (draft != null) drafts += draft
        }

        // Pass 2: extract the referenced attachment entries. Bytes are
        // read once per entry and written per referencing note (each
        // note owns its staged copy — the engine deletes it after the
        // save attempt).
        val wanted = drafts.flatMap { it.attachments }.map { it.entryName }.toSet()
        val extracted = HashMap<String, ByteArray?>()
        if (wanted.isNotEmpty()) {
            try {
                reader.walk { entry ->
                    if (entry.name in wanted) {
                        extracted[entry.name] = entry.readBytesUpTo(ImportCaps.MAX_IMPORT_FILE_BYTES)
                    }
                }
            } catch (e: ImportZipException) {
                // Pass 1 already counted the whole archive under the
                // quota, so this is unreachable in practice; anything
                // unextracted is counted as skipped below.
            }
        }

        val usedNames = mutableSetOf<String>()
        for (draft in drafts) {
            val images = mutableListOf<ImportedImage>()
            for (spec in draft.attachments) {
                val bytes = extracted[spec.entryName]
                if (bytes == null) {
                    result.attachmentsSkipped += 1
                    continue
                }
                val staged = File(stagingDir, stagingFileName(spec.filePath, usedNames))
                try {
                    staged.writeBytes(bytes)
                } catch (e: Exception) {
                    result.attachmentsSkipped += 1
                    continue
                }
                images += ImportedImage(
                    name = importBaseName(spec.filePath),
                    mimeType = spec.mimeType,
                    stagedFile = staged,
                    sizeBytes = bytes.size.toLong(),
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
