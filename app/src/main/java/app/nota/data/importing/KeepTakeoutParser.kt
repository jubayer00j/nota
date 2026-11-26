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
        /** Full entry path plus a content hash (Codex R28-F2) — the
         *  run identity for unknown-date dedupe; the basename alone
         *  collapses two nested JSONs sharing it. Null means the
         *  source name plays that role (never here: the caller
         *  always sets it). */
        val sourceIdentity: String? = null,
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
        var entriesSeen = 0
        try {
            reader.walk { entriesSeen++ }
        } catch (e: ImportZipException) {
            result.skippedFiles += SkippedImportFile("Takeout ZIP", e.message ?: "That ZIP couldn't be read.")
            return result
        }

        // A file with a valid local-header signature that yields no
        // entries at all is truncated or corrupt, not an empty
        // archive (Codex R23-F3 — the Markdown parser's R11-F2
        // guard, same shape): ZipInputStream returns null for the
        // first entry without throwing, so without this the run
        // would report a normal "Imported 0 notes". A valid empty
        // archive (end-of-central-directory record only)
        // legitimately yields no entries.
        if (entriesSeen == 0 && !isEmptyZipArchive(zipFile)) {
            result.skippedFiles += SkippedImportFile(
                "Takeout ZIP",
                "That file couldn't be read as a ZIP archive.",
            )
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

        // (Codex R30-F3): the entry-name index for attachment lookup,
        // built ONCE per archive from pass 1's inventory — pass 2's
        // per-attachment lookups are map probes, not linear scans.
        val entryIndex = EntryNameIndex(reader.entryNames, reader.duplicateEntryNames)

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
        // enforced on the DRAFT below (converted title + body chars,
        // so HTML expansion — & → &amp;, paragraph markup — counts
        // against it, and so does a long title — Codex R19-F1) and
        // again by addNote: the true check still runs when the draft
        // becomes a note.
        val drafts = mutableListOf<KeepDraft>()
        var retainedConvertedChars = 0L
        // One budget for the whole parse (Codex R31-F2): every draft
        // shares it, so the run's retained specs stay bounded even
        // when each note is under its own per-note cap.
        val attachmentBudget = AttachmentSpecBudget()
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
                        // (Codex R29-F1): bound the JSON structure
                        // BEFORE the tree exists. Every guard below
                        // runs on the built tree, but a raw-compliant
                        // entry of tiny objects materialises a
                        // HashMap-backed object per item — several
                        // times the raw size — before any of them
                        // sees it. The count runs on the raw text
                        // and retains nothing.
                        if (jsonStructureExceeds(raw)) {
                            result.skippedFiles += SkippedImportFile(
                                base,
                                ImportCaps.JSON_STRUCTURE_TOO_LARGE_REASON,
                            )
                            return@walk
                        }
                        val json = try {
                            JSONObject(raw)
                        } catch (e: Exception) {
                            result.skippedFiles += SkippedImportFile(base, "Not valid JSON — skipped.")
                            return@walk
                        }
                        val draft = keepJsonToDraft(
                            json, base, entryIndex, result, attachmentBudget,
                            // (Codex R28-F2, R26-F2 shape): the run
                            // dedupe identity for a dateless Keep note
                            // is its full entry path plus a content
                            // hash — the basename alone collapses two
                            // nested JSONs sharing it, and the path
                            // alone collapses the same entry path in
                            // two archives of one selection. The text
                            // is already in memory here.
                            "${entry.name}\n${contentHashHex(raw)}",
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
                            // The aggregate budget on the draft (Codex
                            // R19-F1): title AND body — the title is
                            // known here, and a ~1 MB title with a
                            // tiny body must trip it just like a big
                            // body does. addNote re-checks the same
                            // sum when the draft becomes a note. Tag
                            // names count too (Codex R27-F2): they are
                            // retained strings as well.
                            if (retainedConvertedChars + draft.title.length +
                                draft.contentHtml.length +
                                draft.tagNames.sumOf { it.length.toLong() } >
                                ImportCaps.MAX_AGGREGATE_TEXT_CHARS
                            ) {
                                result.skippedFiles += SkippedImportFile(
                                    base,
                                    ImportCaps.AGGREGATE_TEXT_BUDGET_REASON,
                                )
                                return@walk
                            }
                            drafts += draft
                            retainedConvertedChars += draft.title.length + draft.contentHtml.length +
                                draft.tagNames.sumOf { it.length.toLong() }
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
                        // (Codex R17-F2, defence in depth): staging can
                        // still fail for a reason the name bound doesn't
                        // cover — an IOException here counts the
                        // attachment as skipped instead of failing the
                        // whole import. Only IOException is caught: a
                        // quota trip (ImportZipException) must still
                        // abort the archive, as designed.
                        val ok = try {
                            entry.copyToUpTo(staged, ImportCaps.MAX_IMPORT_FILE_BYTES)
                        } catch (e: java.io.IOException) {
                            runCatching { staged.delete() }
                            false
                        }
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
                    sourceIdentity = draft.sourceIdentity,
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

    /**
     * The pass-1 entry-name inventory, indexed once per archive
     * (Codex R30-F3): the old lookup ran two full linear scans
     * (suffix, then basename — recomputing basename + lowercase per
     * entry per call) for every attachment in pass 2, so thousands of
     * attachments × 20,000 entries ran into hundreds of millions of
     * comparisons. Each lookup here is a map probe.
     *
     * The indexes preserve the old scan's semantics exactly:
     *  - [exact] is the name set (the old `wanted in names` was a
     *    linear contains);
     *  - [bySuffix] keys are the suffixes after each '/' boundary, so
     *    "a/b/c.jpg" contributes "b/c.jpg" and "c.jpg" — exactly the
     *    strings the old `endsWith("/$wanted")` matched — in name
     *    order, so a unique/ambiguous verdict is the same. The keys
     *    are [SuffixKey]s — (name, offset) pairs, not copied
     *    strings: materialising every suffix (Codex R31-F1) retained
     *    the sum of all suffix lengths per name, quadratic in the
     *    name length, before any note was parsed;
     *  - [byBasename] groups by lowercase basename, as the old
     *    filter did. */
    /** A suffix of an entry name, addressed as (name, start) so the
     *  suffix index never copies a suffix string (Codex R31-F1):
     *  the old map keyed on `substring` after every '/' boundary,
     *  so a slash-heavy 65 KiB name retained the sum of all its
     *  suffix lengths — quadratic — before any note was parsed. The
     *  name itself is already retained in [EntryNameIndex]'s exact
     *  set; a key holds only a reference + an offset, and
     *  hash/equals compare the suffix characters in place. The hash
     *  is precomputed once per key (Codex R34-F1): hashCode() used
     *  to rescan the whole suffix for every key, so building the
     *  index for a slash-heavy name paid the sum of all its suffix
     *  lengths — quadratic again, in character work instead of
     *  retained bytes — before any note was parsed. The build
     *  computes every suffix hash of a name in one reverse pass
     *  (each suffix hash is the hash of the suffix after it times
     *  31 plus its first character — the same 31-polynomial
     *  String.hashCode() uses, so the values match); a lookup key
     *  hashes its single string once. */
    private class SuffixKey(val name: String, val start: Int, private val hash: Int) {
        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (other !is SuffixKey) return false
            if (hash != other.hash) return false
            if (name.length - start != other.name.length - other.start) return false
            for (i in 0 until name.length - start) {
                if (name[start + i] != other.name[other.start + i]) return false
            }
            return true
        }
    }

    /** Run-level ceiling on retained attachment specs (Codex
     *  R31-F2): one budget per parse, shared by every
     *  [keepJsonToDraft] call, so the run's specs stay bounded even
     *  when each note is under its own per-note cap. */
    private class AttachmentSpecBudget(
        private val maxSpecs: Int = ImportCaps.MAX_ATTACHMENTS_PER_RUN,
    ) {
        private var used = 0

        /** True when another spec may be retained. */
        fun take(): Boolean {
            if (used >= maxSpecs) return false
            used++
            return true
        }
    }

    private class EntryNameIndex(
        names: List<String>,
        private val duplicateNames: Set<String>,
    ) {
        private val exact: Set<String> = names.toSet()

        private val bySuffix: Map<SuffixKey, List<String>> = run {
            val map = HashMap<SuffixKey, MutableList<String>>()
            // (Codex R35-F1): bound the index expansion — every slash
            // in every name used to contribute a map key + backing
            // list, so 8 MiB of slash-heavy names could hold millions
            // of entries before any note was parsed. Past the ceiling
            // a suffix is simply not indexed; its lookup falls
            // through to the basename arm, which stays correct.
            var indexedSuffixes = 0
            for (name in names) {
                // (Codex R34-F1): every suffix hash of this name in
                // one reverse pass — suffixHashes[i] is the standard
                // 31-polynomial hash of the suffix starting at i —
                // so each key below costs O(1) instead of rescanning
                // its whole suffix. All arithmetic wraps mod 2^32,
                // exactly as String.hashCode() does, so the values
                // match the lookup key's.
                val suffixHashes = IntArray(name.length + 1)
                var h = 0
                var pow = 1
                for (i in name.length - 1 downTo 0) {
                    h += name[i].code * pow
                    suffixHashes[i] = h
                    pow *= 31
                }
                var slash = name.indexOf('/')
                while (slash >= 0 && indexedSuffixes < ImportCaps.MAX_SUFFIX_INDEX_ENTRIES) {
                    map.getOrPut(SuffixKey(name, slash + 1, suffixHashes[slash + 1])) { mutableListOf() } += name
                    indexedSuffixes++
                    slash = name.indexOf('/', slash + 1)
                }
            }
            map
        }

        private val byBasename: Map<String, List<String>> =
            names.groupBy { importBaseName(it).lowercase() }

        fun find(ref: String): EntryLookup {
            val wanted = ref.replace('\\', '/')
            if (wanted in exact) {
                if (wanted in duplicateNames) return EntryLookup.Ambiguous
                return EntryLookup.Found(wanted)
            }
            val suffixMatches = bySuffix[SuffixKey(wanted, 0, wanted.hashCode())] ?: emptyList()
            if (suffixMatches.size == 1) return EntryLookup.Found(suffixMatches[0])
            if (suffixMatches.size > 1) return EntryLookup.Ambiguous
            val base = importBaseName(wanted).lowercase()
            val baseMatches = byBasename[base] ?: emptyList()
            if (baseMatches.size == 1) return EntryLookup.Found(baseMatches[0])
            if (baseMatches.size > 1) return EntryLookup.Ambiguous
            return EntryLookup.Missing
        }
    }

    private fun usecToMs(value: Any?): Long? {
        val number = value as? Number ?: return null
        val usec = number.toLong()
        if (usec <= 0) return null
        return usec / 1000
    }

    /**
     * Whether [text] exceeds the structural bounds in [ImportCaps]
     * (Codex R29-F1), counted WITHOUT materialising the JSON tree:
     * every object, array, string (key or value) and scalar counts
     * as one node, and open containers count as depth. The scan is
     * a single pass with string/escape awareness — brackets inside
     * strings are not structure — and it stops at the first bound
     * tripped, so even the counting retains O(1) state. Object keys
     * are counted like values: org.json retains them as HashMap
     * keys, so they cost a node either way. Over-counting is safe
     * (it can only refuse data the tree parse or a later guard
     * would have refused anyway); for well-formed JSON the count
     * is exact, and malformed text is rejected by the tree parse
     * right after, as before.
     */
    private fun jsonStructureExceeds(text: String): Boolean {
        var nodes = 0L
        var depth = 0
        var inString = false
        var escaped = false
        // True when the next token begins a value: at the start of
        // the input and after '[', ',' or ':'. Object keys arrive
        // in the same state (after '{' or ','), so they are counted
        // by the string branch below.
        var expectValue = true
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            when (c) {
                '"' -> {
                    if (expectValue) {
                        nodes++
                        if (nodes > ImportCaps.MAX_JSON_STRUCTURE_NODES) return true
                        expectValue = false
                    }
                    inString = true
                }
                '{', '[' -> {
                    nodes++
                    if (nodes > ImportCaps.MAX_JSON_STRUCTURE_NODES) return true
                    depth++
                    if (depth > ImportCaps.MAX_JSON_STRUCTURE_DEPTH) return true
                    expectValue = true
                }
                '}', ']' -> {
                    depth--
                    expectValue = false
                }
                ',', ':' -> expectValue = true
                else -> {
                    if (expectValue && !c.isWhitespace()) {
                        // A number or a true/false/null literal:
                        // one node, consumed whole so its letters
                        // are never re-read as structure.
                        nodes++
                        if (nodes > ImportCaps.MAX_JSON_STRUCTURE_NODES) return true
                        expectValue = false
                        while (i < text.length &&
                            (text[i].isLetterOrDigit() || text[i] == '+' ||
                                text[i] == '-' || text[i] == '.')
                        ) i++
                        continue
                    }
                }
            }
            i++
        }
        return false
    }

    private fun keepJsonToDraft(
        json: JSONObject,
        sourceName: String,
        entryIndex: EntryNameIndex,
        result: ImportParseResult,
        attachmentBudget: AttachmentSpecBudget,
        sourceIdentity: String? = null,
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
            // (Codex R25-F1): bound the task-list expansion WHILE
            // iterating. Each item expands into a ~70-byte <li>
            // wrapper around its escaped text, so a raw-limit-compliant
            // listContent of many tiny items expands into megabytes of
            // HTML before addNote's size check ever runs (the check
            // only sees the finished draft). Track the exact output
            // bytes as we go — title + <ul> wrapper + per-item wrapper
            // + escaped text, all measured from the literals
            // taskListHtml emits — and refuse the note whole, with the
            // same per-note reason addNote would give, as soon as the
            // estimate passes the bound. The accounting is exact, so a
            // note that fits is never refused early; the items list
            // itself never holds more than one bound's worth.
            val items = mutableListOf<Pair<String, Boolean>>()
            val checkedWrapperBytes = utf8ByteLength(taskItemHtml("", true))
            val uncheckedWrapperBytes = utf8ByteLength(taskItemHtml("", false))
            var listBytes = utf8ByteLength(title) +
                utf8ByteLength("<ul data-type=\"taskList\"></ul>")
            var overBudget = false
            for (i in 0 until listContent.length()) {
                val item = listContent.optJSONObject(i)
                val text = (item?.opt("text") as? String)?.replace(Regex("\\s*\\n\\s*"), " ") ?: ""
                val checked = item?.opt("isChecked") as? Boolean == true
                val escaped = escapeImportHtml(text)
                listBytes += utf8ByteLength(escaped) +
                    if (checked) checkedWrapperBytes else uncheckedWrapperBytes
                if (listBytes > ImportCaps.MAX_NOTE_CONTENT_BYTES) {
                    overBudget = true
                    break
                }
                items += escaped to checked
            }
            if (overBudget) {
                result.skippedFiles += SkippedImportFile(sourceName, ImportCaps.NOTE_TOO_LARGE_REASON)
                return null
            }
            contentHtml = taskListHtml(items)
        } else if (textContent.isNotEmpty()) {
            // (Codex R25-F1, sibling guard): PlainTextCodec escapes
            // text (& → &amp; is 5x) and wraps paragraphs before any
            // size check runs, so a raw-compliant textContent of
            // mostly '&' expands to ~5x its size first. Refuse early
            // when even a lower bound on the output — the escaped
            // bytes of the non-whitespace content, which trimming and
            // paragraph-splitting can never drop — already passes the
            // per-note bound. Whitespace-heavy bulk never trips it.
            // Under-counting is safe: the caller's post-check still
            // refuses anything that slips through.
            if (utf8ByteLength(title) + escapedNonWhitespaceBytes(textContent) >
                ImportCaps.MAX_NOTE_CONTENT_BYTES
            ) {
                result.skippedFiles += SkippedImportFile(sourceName, ImportCaps.NOTE_TOO_LARGE_REASON)
                return null
            }
            contentHtml = PlainTextCodec.textToHtml(textContent)
        }

        val tagNames = mutableListOf<String>()
        val labels = json.opt("labels") as? JSONArray
        if (labels != null) {
            // (Codex R27-F2): labels used to accumulate unbounded
            // while no text budget counted them — a huge labels
            // array with a tiny body retains millions of strings.
            // Bound the count and each name's bytes; extras are
            // dropped, never truncated — the note still imports
            // with the labels that fit.
            for (i in 0 until labels.length()) {
                if (tagNames.size >= ImportCaps.MAX_TAGS_PER_NOTE) break
                val name = labels.optJSONObject(i)?.opt("name") as? String
                if (!name.isNullOrBlank()) {
                    val trimmed = name.trim()
                    if (utf8ByteLength(trimmed) <= ImportCaps.MAX_TAG_NAME_BYTES) {
                        tagNames += trimmed
                    }
                }
            }
        }

        val specs = mutableListOf<AttachmentSpec>()
        // One attachment per resolved entry (Codex R26-F3): the same
        // image referenced twice is the same attachment, and the
        // engine writes one row per spec — decoding, re-encoding
        // and writing a separate attachment for every occurrence
        // before the expanded-size check — so repeats would burn
        // time and disk for nothing. Pass 3 already stages one
        // file per entry name; the specs match it.
        val seenEntryNames = HashSet<String>()
        // (Codex R34-F2): basenames already kept for ambiguous
        // references, and how many such paragraphs have been
        // appended — see the Ambiguous branch below.
        val seenAmbiguousBasenames = HashSet<String>()
        var ambiguousParagraphs = 0
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
                val found = entryIndex.find(filePath)
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
                    // reference's alt text. (Codex R34-F2): the
                    // append used to run once per occurrence, before
                    // the R26-F3 dedupe and R31-F2 caps — which only
                    // cover resolved entries — so a repeated
                    // ambiguous reference copied the whole growing
                    // body each time. A repeat appends nothing, and
                    // the paragraphs are capped per note: repeats
                    // and past-cap references are still counted as
                    // skipped, never silently dropped.
                    result.attachmentsSkipped += 1
                    val baseName = importBaseName(filePath)
                    if (seenAmbiguousBasenames.add(baseName) &&
                        ambiguousParagraphs < ImportCaps.MAX_AMBIGUOUS_ATTACHMENT_PARAGRAPHS
                    ) {
                        ambiguousParagraphs += 1
                        contentHtml += "<p>${escapeImportHtml(baseName)}</p>"
                    }
                    continue
                }
            }
            if (!seenEntryNames.add(entryName)) continue
            // (Codex R31-F2): bound the retained specs — the per-note
            // ceiling and the run ceiling. Past either, the reference
            // is counted as skipped, like a missing one: the note
            // still imports with the images that fit. Repeats never
            // reach here (the dedupe above skips them), so they
            // consume neither cap.
            if (specs.size >= ImportCaps.MAX_ATTACHMENTS_PER_NOTE ||
                !attachmentBudget.take()
            ) {
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
            sourceIdentity = sourceIdentity,
            attachments = specs,
        )
    }

    /** One task list in Nota's own serialiser shape (see MarkdownImport). */
    private fun taskListHtml(items: List<Pair<String, Boolean>>): String {
        val lis = items.joinToString("") { (html, checked) -> taskItemHtml(html, checked) }
        return "<ul data-type=\"taskList\">$lis</ul>"
    }

    /** One task item: the <li> wrapper taskListHtml emits. Kept separate
     *  so the R25-F1 output budget can measure the wrapper from the same
     *  literal the renderer uses, instead of hand-counting bytes. */
    private fun taskItemHtml(html: String, checked: Boolean): String =
        "<li data-type=\"taskItem\" data-checked=\"${if (checked) "true" else "false"}\">" +
            "<div><p>$html</p></div></li>"

    /**
     * Lower bound on PlainTextCodec.textToHtml's output bytes for [text]:
     * the escaped UTF-8 bytes of its non-whitespace characters (chars
     * `<= ' '`, the only ones trimming and paragraph-splitting can drop,
     * contribute nothing). Markup only adds bytes, so the true output is
     * never smaller — refusing on this bound can never refuse a note
     * that fits. (Codex R25-F1 sibling guard.)
     */
    private fun escapedNonWhitespaceBytes(text: String): Long {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c > ' ') {
                bytes += when (c) {
                    '&' -> 5L // &amp;
                    '<', '>' -> 4L // &lt; / &gt;
                    '"' -> 6L // &quot;
                    else -> when {
                        c < '\u0080' -> 1L
                        c < '\u0800' -> 2L
                        c.isHighSurrogate() && i + 1 < text.length &&
                            text[i + 1].isLowSurrogate() -> {
                            i++
                            4L
                        }
                        else -> 3L
                    }
                }
            }
            i++
        }
        return bytes
    }
}
