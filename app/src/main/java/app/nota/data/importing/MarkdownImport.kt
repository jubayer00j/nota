package app.nota.data.importing

import app.nota.data.backup.BackupQuota
import app.nota.data.text.PlainTextCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Markdown / plain-text import — a structure-for-structure Kotlin port
 * of 1.x Noma's `parseMarkdownFiles` / `parseImportZip` and its
 * escape-first Markdown→HTML converter (`src/lib/noma/importers.ts`).
 *
 * Nota divergences (deliberate, behaviour parity kept):
 *  - task lists are emitted in Nota's own serialiser shape
 *    (`<li data-type="taskItem" data-checked="…"><div><p>…</p></div></li>`),
 *    not 1.x's Tiptap `<label><input>` shape — imported content must
 *    land as the HTML Nota's editor itself produces;
 *  - plain-text bodies go through Nota's own [PlainTextCodec];
 *  - image bytes are staged files, not in-memory blobs;
 *  - front-matter dates use a scoped java.time format chain where 1.x
 *    uses JS `Date.parse`.
 *  - mixed-marker list nesting: a deeper-indented item of another kind
 *    nests under the item above it (1.x flattens such items into
 *    sibling lists). Each nested level renders in its own kind, and a
 *    nested level containing a task item renders as a task list — so
 *    imported lists keep their structure where 1.x loses it. A plain
 *    nested bullet under a task parent likewise stays a plain bullet
 *    (1.x renders it as an unchecked task item).
 */

/* ---------------- Escaping (1.x verbatim) ---------------- */

internal fun escapeImportHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

internal fun escapeImportAttr(text: String): String =
    escapeImportHtml(text).replace("\"", "&quot;")

/** Reverses the entities escapeImportHtml/escapeImportAttr introduce (for URL refs). */
private fun unescapeEntities(text: String): String =
    text.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

/* ---------------- Names ---------------- */

private val IMAGE_MIME_BY_EXT = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "avif" to "image/avif",
    "svg" to "image/svg+xml",
    "bmp" to "image/bmp",
)

internal fun importMimeFromName(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return IMAGE_MIME_BY_EXT[ext]
}

internal fun importBaseName(path: String): String {
    // (Codex R44-F2): was split('/').filter { it.isNotEmpty() }
    // .lastOrNull() — a full split of the whole path on every call,
    // so a 64 KiB slash-heavy target paid ~32k segments per resolve,
    // in the same per-occurrence shape as resolveRef's parent split.
    // The basename is the last non-empty '/'-segment, found by
    // scanning back from the end — no allocation but the returned
    // substring. Equivalent to the old shape on every input: trailing
    // slashes are skipped, a path of only slashes (or empty) returns
    // itself, and backslashes are not separators (callers replace
    // them first where they matter).
    var end = path.length
    while (end > 0 && path[end - 1] == '/') end--
    if (end == 0) return path
    val start = path.lastIndexOf('/', end - 1) + 1
    return path.substring(start, end)
}

/** A ZIP member / picked file that parses as a Markdown or text note. */
internal fun isMarkdownTextEntry(name: String): Boolean =
    Regex("\\.(md|markdown|txt)$", RegexOption.IGNORE_CASE).containsMatchIn(name)

/** A plain-text note: it goes through PlainTextCodec, which only
 *  escapes — it never resolves image references. */
internal fun isPlainTextEntry(name: String): Boolean =
    Regex("\\.txt$", RegexOption.IGNORE_CASE).containsMatchIn(name)

/** Android's per-component filename limit (255 bytes): a staged
 *  name past it makes File.outputStream() throw (ENAMETOOLONG). */
internal const val MAX_STAGED_NAME_BYTES = 255

/** Filesystem-safe staging name from an entry/pick name (basename
 *  only; only path separators and control characters are replaced, so
 *  the staged name stays as close to the original as possible).
 *  Bounded to [MAX_STAGED_NAME_BYTES] UTF-8 bytes (Codex R17-F2):
 *  the stem is truncated, the extension kept whole where it fits,
 *  and the uniqueness suffix still applies — so an overlong image
 *  name stages under a shortened name instead of failing the run. */
internal fun stagingFileName(rawName: String, usedNames: MutableSet<String>): String {
    val base = importBaseName(rawName.replace('\\', '/'))
        .replace(Regex("[\u0000-\u001f/\\\\]"), "_")
        .ifBlank { "file" }
    val dot = base.lastIndexOf('.')
    val stem = if (dot > 0) base.substring(0, dot) else base
    val ext = if (dot > 0) base.substring(dot) else ""
    var n = 2
    var candidate = fitStagedName(stem, "", ext)
    while (!usedNames.add(candidate)) {
        candidate = fitStagedName(stem, "-$n", ext)
        n++
    }
    return candidate
}

/** [stem] + [suffix] + [ext] fitted to at most [MAX_STAGED_NAME_BYTES]
 *  UTF-8 bytes, never splitting a multi-byte character. The extension
 *  is kept whole unless it alone would overflow the component — then
 *  it is truncated and the stem drops to a single character. */
private fun fitStagedName(stem: String, suffix: String, ext: String): String {
    val suffixBytes = suffix.toByteArray(Charsets.UTF_8).size
    val extBytes = ext.toByteArray(Charsets.UTF_8).size
    if (extBytes + suffixBytes + 1 > MAX_STAGED_NAME_BYTES) {
        return "f" + suffix + truncateUtf8Bytes(ext, MAX_STAGED_NAME_BYTES - suffixBytes - 1)
    }
    val stemBudget = MAX_STAGED_NAME_BYTES - extBytes - suffixBytes
    val fittedStem = truncateUtf8Bytes(stem, stemBudget).ifEmpty {
        truncateUtf8Bytes("file", stemBudget)
    }
    return fittedStem + suffix + ext
}

/** Longest prefix of [value] fitting in [maxBytes] UTF-8 bytes, never
 *  splitting a multi-byte character. */
private fun truncateUtf8Bytes(value: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return value
    var end = maxBytes
    // Back off over continuation bytes (10xxxxxx), then over the lead
    // byte of the sequence they belong to if it no longer fits whole.
    while (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) end--
    if (end > 0) {
        val lead = bytes[end - 1].toInt() and 0xFF
        val seqLen = when {
            lead < 0x80 -> 1
            lead < 0xE0 -> 2
            lead < 0xF0 -> 3
            else -> 4
        }
        if (end - 1 + seqLen > maxBytes) end--
    }
    return bytes.decodeToString(0, end)
}

/* ---------------- Paths + image resolver ---------------- */

/** Normalises a relative path: resolves "." / "..", strips leading "/". */
fun normaliseImportPath(path: String): String {
    val out = mutableListOf<String>()
    for (segment in path.replace('\\', '/').split('/')) {
        if (segment.isEmpty() || segment == ".") continue
        if (segment == "..") {
            if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            continue
        }
        out += segment
    }
    return out.joinToString("/")
}

/** decodeURIComponent with 1.x's fallback: an undecodable ref stays raw. */
private fun percentDecode(value: String): String {
    if ('%' !in value) return value
    val bytes = ByteArrayOutputStream()
    val literal = StringBuilder()
    fun flushLiteral() {
        if (literal.isNotEmpty()) {
            bytes.write(literal.toString().toByteArray(Charsets.UTF_8))
            literal.clear()
        }
    }
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%') {
            if (i + 2 >= value.length) return value
            val decoded = value.substring(i + 1, i + 3).toIntOrNull(16) ?: return value
            flushLiteral()
            bytes.write(decoded)
            i += 3
        } else {
            literal.append(c)
            i++
        }
    }
    flushLiteral()
    return bytes.toByteArray().toString(Charsets.UTF_8)
}

/** The normalised parent directory of [fromPath], computed once per
 *  member (Codex R44-F2): the old resolveRef used to split and
 *  re-join the whole parent path on every call, so a deep path ×
 *  thousands of repeated refs paid the full split/join/normalise per
 *  occurrence.
 *  Exactly the parent the old shape used, normalised once: the raw
 *  dir is fromPath up to its last '/', and normalising it first
 *  changes nothing downstream — normalise processes segments
 *  left-to-right with a stack, so normalising the parent and then
 *  the joined path gives the same result as normalising the joined
 *  path directly (a ".." in the parent pops the same segment either
 *  way, and one past the root is dropped either way). A fromPath
 *  with no '/' (or only backslashes, which split('/') does not see)
 *  has an empty parent, exactly as before. */
internal fun normalisedParentDir(fromPath: String): String {
    val dir = fromPath.split('/').dropLast(1).joinToString("/")
    return normaliseImportPath(dir)
}

/** Whether [decoded] needs no normalisation against its parent: no
 *  backslashes, and every '/'-separated segment non-empty and
 *  neither "." nor "..". Such a ref appends to an already-normalised
 *  parent unchanged — normalise would return the joined string as
 *  it stands. Anything else takes the slow path (full normalise of
 *  the joined string), which stays exactly correct; clean refs are
 *  the overwhelming case, exotic ones are rare. */
private fun isCleanRef(decoded: String): Boolean {
    if ('\\' in decoded) return false
    for (segment in decoded.split('/')) {
        if (segment.isEmpty() || segment == "." || segment == "..") return false
    }
    return true
}

/** Resolves [ref] against a precomputed normalised parent dir — the
 *  per-occurrence half of the old resolveRef, with the parent work
 *  hoisted out (Codex R44-F2). The fast path (clean ref, non-empty
 *  parent) is a single concatenation; the slow path runs the existing
 *  normalise over the joined string, so "." / ".." / "//" /
 *  backslash refs resolve exactly as before. An absolute ref never
 *  touches the parent; an empty parent normalises the ref alone. */
internal fun resolveRefWithParent(parentDir: String, ref: String): String {
    val decoded = percentDecode(ref)
    if (decoded.startsWith("/")) return normaliseImportPath(decoded)
    if (parentDir.isEmpty()) return normaliseImportPath(decoded)
    if (isCleanRef(decoded)) return "$parentDir/$decoded"
    return normaliseImportPath("$parentDir/$decoded")
}

class ResolvedImportImage(
    val name: String,
    val mimeType: String,
    val stagedFile: File,
    val sizeBytes: Long,
)

/**
 * Resolves a relative image reference from the importing file's path to
 * image bytes within the picked set / ZIP. Returns null when the
 * reference can't be resolved (the alt text is kept and counted).
 *
 * [isAmbiguous] reports whether a reference matches more than one
 * DIFFERENT file in this resolver's set: a combiner needs to tell a
 * reference with no match at all (fall through to the next resolver)
 * from one that is ambiguous here (stay unresolved — Codex R22-F1).
 * A reference with no match is not ambiguous.
 */
fun interface ImportImageResolver {
    fun resolve(fromPath: String, ref: String): ResolvedImportImage?

    fun isAmbiguous(fromPath: String, ref: String): Boolean = false
}

/**
 * Builds a resolver over a path → staged-file list (picked files or ZIP
 * entries). Exact normalised-path match first, then a unique basename
 * match (flat exports often lose the folder structure). A reference is
 * ambiguous when two DIFFERENT files share the matched name — e.g. two
 * picked images both called `image.png` — and stays unresolved (the
 * reference is counted at the call site) instead of silently embedding
 * whichever file was staged last. The same staged file listed twice
 * is one candidate, not an ambiguity.
 *
 * [ambiguousNames] are paths that are ambiguous by duplication but
 * have no staged payload (Codex R42-F1): a ZIP path that occurs twice
 * is never staged — staging it would embed whichever payload appears
 * last — so it never appears in [files], yet a reference to it must
 * still stay unresolved instead of falling through to another
 * resolver's guess. The names are matched the same way staged files
 * are — by normalised path first, then by lowercase basename — so a
 * ref for the full duplicate path and a ref for just its basename
 * both stay ambiguous.
 */
fun createImportImageResolver(
    files: List<Pair<String, File>>,
    ambiguousNames: Set<String> = emptySet(),
): ImportImageResolver {
    // (normalised path, display name, staged file) per entry — matching
    // runs on the ORIGINAL paths/names, never on staging file names.
    val entries = files.distinct().map { (path, file) ->
        Triple(normaliseImportPath(path), importBaseName(path.replace('\\', '/')), file)
    }
    val byPath = entries.groupBy { it.first }
    // (Codex R30-F2): the basename arm used to be a full linear scan
    // (entries.filter, re-lowercasing every name per call) — with
    // 20,000 entries and tens of thousands of references, hundreds of
    // millions of comparisons. The index is built once here, alongside
    // byPath; groupBy preserves the filter's order and duplicates, so
    // the unique/ambiguous semantics are unchanged.
    val byBasename = entries.groupBy { it.second.lowercase() }
    // (Codex R42-F1): paths ambiguous by duplication, with no staged
    // payload to resolve to. A duplicate ZIP path is never staged, so
    // it is absent from [byPath]/[byBasename] — without this set the
    // resolver would report "no match" for it and a combiner would
    // fall through to another resolver's file. Both arms are covered,
    // exactly like staged entries: a direct-path match decides on its
    // own only when the path is NOT a duplicate, and a basename match
    // counts only when no duplicate shares it.
    val ambiguousPaths = ambiguousNames.map { normaliseImportPath(it) }.toSet()
    val ambiguousBasenames = ambiguousNames.map {
        importBaseName(it.replace('\\', '/')).lowercase()
    }.toSet()
    fun pick(name: String, file: File): ResolvedImportImage = ResolvedImportImage(
        name = name,
        mimeType = importMimeFromName(name) ?: "",
        stagedFile = file,
        sizeBytes = file.length(),
    )
    // The exact match test, shared by resolve and isAmbiguous so the
    // two can never disagree about a reference: a direct-path match
    // decides on its own (size 1 resolves, more is ambiguous), and
    // only when there is no direct match does the basename count.
    // A duplicate name short-circuits both arms: it has no payload,
    // so it can never resolve, and it is ambiguous by definition.
    fun isDuplicatePath(target: String): Boolean = target in ambiguousPaths

    fun isDuplicateBasename(target: String): Boolean =
        importBaseName(target).lowercase() in ambiguousBasenames

    fun directMatches(target: String): List<Triple<String, String, File>>? = byPath[target]

    fun basenameMatches(target: String): List<Triple<String, String, File>> {
        val base = importBaseName(target).lowercase()
        return byBasename[base] ?: emptyList()
    }
    // (Codex R44-F2): the parent directory, computed once per member
    // rather than once per reference — resolve/isAmbiguous are called
    // per image occurrence, and the parent split used to run on every
    // one. A single-entry memo keyed by fromPath is enough: the
    // resolver is used sequentially (one member converts at a time,
    // rendering is synchronous, the combiner delegates in order), so
    // consecutive calls for one member hit the memo and a new member
    // recomputes once. Instance-local, never shared across parses.
    var cachedFromPath: String? = null
    var cachedParentDir: String = ""
    fun parentDirFor(fromPath: String): String {
        if (fromPath != cachedFromPath) {
            cachedFromPath = fromPath
            cachedParentDir = normalisedParentDir(fromPath)
        }
        return cachedParentDir
    }
    return object : ImportImageResolver {
        override fun resolve(fromPath: String, ref: String): ResolvedImportImage? {
            val target = resolveRefWithParent(parentDirFor(fromPath), ref)
            if (isDuplicatePath(target)) return null
            val direct = directMatches(target)
            if (direct != null) {
                return if (direct.size == 1) pick(importBaseName(target), direct[0].third) else null
            }
            if (isDuplicateBasename(target)) return null
            val matches = basenameMatches(target)
            return if (matches.size == 1) pick(matches[0].second, matches[0].third) else null
        }

        override fun isAmbiguous(fromPath: String, ref: String): Boolean {
            val target = resolveRefWithParent(parentDirFor(fromPath), ref)
            if (isDuplicatePath(target)) return true
            val direct = directMatches(target)
            if (direct != null) return direct.size > 1
            if (isDuplicateBasename(target)) return true
            return basenameMatches(target).size > 1
        }
    }
}

/**
 * Combines an archive resolver with a selection (external) resolver
 * (Codex R22-F1): the archive wins on conflict — a reference the
 * archive resolves is never re-resolved externally — and the
 * selection resolver is the fallback for references the archive does
 * not hold at all. A reference that is AMBIGUOUS in the archive
 * (two different archive files share the matched name) stays
 * unresolved instead of falling through: the ambiguity rule is about
 * the name, and silently embedding the picked file would be the
 * guess the rule exists to prevent. The combined resolver is itself
 * ambiguous exactly where the archive is, or where the archive has
 * no candidate and the selection is ambiguous — so a further
 * combination keeps the same guarantee.
 */
fun combineImportImageResolvers(
    archive: ImportImageResolver,
    external: ImportImageResolver,
): ImportImageResolver = object : ImportImageResolver {
    override fun resolve(fromPath: String, ref: String): ResolvedImportImage? =
        archive.resolve(fromPath, ref)
            ?: if (archive.isAmbiguous(fromPath, ref)) null
            else external.resolve(fromPath, ref)

    override fun isAmbiguous(fromPath: String, ref: String): Boolean =
        archive.isAmbiguous(fromPath, ref) ||
            (archive.resolve(fromPath, ref) == null && external.isAmbiguous(fromPath, ref))
}

/* ---------------- Front matter ---------------- */

private class FrontMatter(
    val title: String?,
    val createdAt: Long?,
    val tags: List<String>,
    val body: String,
)

private fun stripQuotes(value: String): String =
    if (value.length >= 2 &&
        ((value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'")))
    ) {
        value.substring(1, value.length - 1)
    } else {
        value
    }

/** Scoped stand-in for JS Date.parse: ISO-8601 instants, offsets,
 *  local date-times and plain dates. */
private fun parseFrontMatterDate(value: String): Long? {
    val v = value.trim()
    runCatching { return java.time.Instant.parse(v).toEpochMilli() }
    runCatching { return java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli() }
    runCatching {
        return java.time.LocalDateTime.parse(v.replace(' ', 'T'))
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    runCatching {
        return java.time.LocalDate.parse(v)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    return null
}

private fun parseFrontMatter(text: String): FrontMatter {
    var title: String? = null
    var createdAt: Long? = null
    val tags = mutableListOf<String>()
    if (!text.startsWith("---")) return FrontMatter(null, null, tags, text)
    val firstBreak = text.indexOf('\n')
    if (firstBreak == -1 || text.substring(0, firstBreak).trim() != "---") {
        return FrontMatter(null, null, tags, text)
    }
    val rest = text.substring(firstBreak + 1)
    val closeMatch = Regex("^---[ \t]*$", RegexOption.MULTILINE).find(rest)
        ?: return FrontMatter(null, null, tags, text)
    val block = rest.substring(0, closeMatch.range.first)
    var body = rest.substring(closeMatch.range.last + 1)
    if (body.startsWith("\r\n")) body = body.substring(2)
    else if (body.startsWith("\n")) body = body.substring(1)

    // (Codex R28-F3): iterate the block's lines by range instead of
    // splitting it: split() materialises one String + list entry per
    // line, so a crafted front-matter block of millions of short
    // lines exhausts a low-memory heap in line-object overhead
    // before any cap applies. Only one line string is alive at a
    // time here. A trailing line break yields no extra empty line,
    // which the loop would skip anyway.
    var cursor = 0
    // The line starting at [start] (without its break) and the start
    // of the next line, or null past the end of the block.
    fun lineAt(start: Int): Pair<String, Int>? {
        if (start >= block.length) return null
        val nl = block.indexOf('\n', start)
        val end = if (nl == -1) block.length else nl
        val lineEnd = if (end > start && block[end - 1] == '\r') end - 1 else end
        return block.substring(start, lineEnd) to if (nl == -1) block.length else nl + 1
    }
    // Comma-separated tag values, scanned by hand (Codex R27-F2):
    // split(',') would materialize every tag of a crafted line
    // before any cap applies. Stops adding at the per-note cap;
    // a name past the per-name byte cap is dropped, never
    // truncated — the note still imports with the tags that fit.
    fun addCommaTags(raw: String) {
        var start = 0
        var idx = 0
        while (idx <= raw.length) {
            if (idx == raw.length || raw[idx] == ',') {
                if (tags.size < ImportCaps.MAX_TAGS_PER_NOTE) {
                    val tag = stripQuotes(raw.substring(start, idx).trim())
                    if (tag.isNotEmpty() &&
                        utf8ByteLength(tag) <= ImportCaps.MAX_TAG_NAME_BYTES
                    ) {
                        tags += tag
                    }
                }
                start = idx + 1
            }
            idx++
        }
    }
    while (true) {
        val (line, next) = lineAt(cursor) ?: break
        cursor = next
        val kv = Regex("^([A-Za-z][\\w-]*)\\s*:\\s*(.*)$").find(line)
        if (kv == null) continue
        val key = kv.groupValues[1].lowercase()
        val value = kv.groupValues[2].trim()
        when (key) {
            "title" -> if (value.isNotEmpty()) title = stripQuotes(value)
            "date", "created" -> if (value.isNotEmpty()) {
                val parsed = parseFrontMatterDate(stripQuotes(value))
                if (parsed != null) createdAt = parsed
            }
            "tags" -> {
                if (value.startsWith("[") && value.endsWith("]")) {
                    addCommaTags(value.substring(1, value.length - 1))
                } else if (value.isNotEmpty()) {
                    addCommaTags(value)
                } else {
                    // Block list:
                    //   tags:
                    //     - one
                    //     - two
                    var inner = cursor
                    while (true) {
                        val (itemLine, itemNext) = lineAt(inner) ?: break
                        val item = Regex("^\\s*-\\s+(.*)$").find(itemLine) ?: break
                        val tag = stripQuotes(item.groupValues[1].trim())
                        if (tag.isNotEmpty() &&
                            tags.size < ImportCaps.MAX_TAGS_PER_NOTE &&
                            utf8ByteLength(tag) <= ImportCaps.MAX_TAG_NAME_BYTES
                        ) {
                            tags += tag
                        }
                        cursor = itemNext
                        inner = itemNext
                    }
                }
            }
        }
    }
    return FrontMatter(title, createdAt, tags, body)
}

/* ---------------- Inline rendering (escape-first) ---------------- */

/** Image references that are never resolved against local files:
 *  any URI scheme (`https:`, `ftp:`, `data:`, … — RFC 3986
 *  `scheme:`), and protocol-relative URLs (`//host/path` — it
 *  carries no scheme of its own, but it names a remote host, not
 *  a local file; Codex R29-F2). A non-http(s) scheme used to fall
 *  through to the resolver, whose unique-basename fallback could
 *  embed an unrelated local file that happened to share the name
 *  (Codex R43-F2). Both the render path ([InlineImageSink.addImage])
 *  and the reference pre-scan ([collectRefsFrom]) classify with this
 *  one pattern so the two can never disagree. Angle-bracketed
 *  destinations (`<...>` — the CommonMark form, which may contain
 *  spaces) are unwrapped by the shared bracket scanner
 *  ([scanBracketRefs] / [bracketedDestination]) before they reach
 *  this check, so a remote `<https://...>` is recognised here too
 *  (Codex R52-F1).
 *
 *  Edge: a relative filename containing a colon before any slash
 *  (a literal `note:1.png`) also matches the scheme shape and is
 *  treated as remote. That is accepted: a `scheme:` prefix in a
 *  Markdown image reference is overwhelmingly a URI (`data:`
 *  already proved scheme-prefixed refs are non-local), and colons
 *  are illegal in Windows filenames, so such a file could only
 *  come from a Unix export. */
private val REMOTE_IMAGE_REF = Regex("^(//|[a-zA-Z][a-zA-Z0-9+.-]*:)", RegexOption.IGNORE_CASE)

private class InlineImageSink(
    private val result: ImportParseResult,
    private val resolver: ImportImageResolver?,
    private val fromPath: String,
    private val images: MutableList<ImportedImage>,
) {
    private var imageSeq = 0

    // (Codex R30-F1): one spec per DISTINCT staged file, not per
    // occurrence — a note repeating one image thousands of times used
    // to append a spec per copy, and the engine staged (decode +
    // re-encode + file write) every one before the expanded-size
    // check could refuse the note. Keyed on the staged file: the same
    // file reached by two different spellings is still one attachment.
    // (Codex R38-F2): a repeat gets its OWN token and alt — the old
    // shape returned the first occurrence's token, so the engine
    // embedded the first alt at EVERY occurrence and later
    // descriptions were silently lost. The repeat appends an
    // occurrence to the same spec, so the file is still staged once
    // while each occurrence keeps its own alt text.
    private val specsByStagedFile = HashMap<File, ImportedImage>()

    // Per-file random salt for the image placeholder (Codex R21-F2):
    // the old fixed token %%NOMA_IMPORT_IMAGE_0%% is guessable, and
    // the engine replaced EVERY occurrence — so a literal token in
    // the source became an <img> at write time. The salt makes the
    // emitted token unguessable, so literal text can never match it.
    private val tokenSalt = UUID.randomUUID().toString().replace("-", "").take(12)

    fun addImage(alt: String, ref: String): String {
        // Remote images are never hot-linked into a note: Nota only
        // embeds local attachments, so a remote reference — any URI
        // scheme, or protocol-relative — keeps its alt text and is
        // counted.
        if (REMOTE_IMAGE_REF.containsMatchIn(ref) || resolver == null) {
            result.imagesUnresolved += 1
            return escapeImportHtml(alt)
        }
        val resolved = resolver.resolve(fromPath, ref)
        if (resolved == null ||
            !resolved.mimeType.startsWith("image/") ||
            resolved.sizeBytes > ImportCaps.MAX_IMPORT_FILE_BYTES
        ) {
            result.imagesUnresolved += 1
            return escapeImportHtml(alt)
        }
        // (Codex R30-F1 / R38-F2): a repeat of an image already
        // emitted in this note gets a NEW token carrying its own alt
        // text, appended to the same spec's occurrences — one staged
        // attachment, embedded at every occurrence with that
        // occurrence's description — and adds no second spec, so the
        // engine never stages the same file twice.
        val token = "%%NOMA_IMPORT_IMAGE_${tokenSalt}_${imageSeq++}%%"
        val existing = specsByStagedFile[resolved.stagedFile]
        if (existing != null) {
            existing.occurrences += ImageOccurrence(token, alt)
            return token
        }
        val spec = ImportedImage(
            name = resolved.name,
            mimeType = resolved.mimeType,
            stagedFile = resolved.stagedFile,
            sizeBytes = resolved.sizeBytes,
            alt = alt,
            token = token,
            deleteAfterUse = false, // resolver files can be shared across notes
            occurrences = mutableListOf(ImageOccurrence(token, alt)),
        )
        specsByStagedFile[resolved.stagedFile] = spec
        images += spec
        return token
    }
}

private val CODE_SPAN = Regex("(`[^`]+`)")

/**
 * Whitespace exactly as the old bracket-reference regexes saw it
 * (`\s`): space, tab, newline, vertical tab, form feed, carriage
 * return. (Kotlin's `Char.isWhitespace()` is broader — it would
 * also match U+00A0 and other Unicode spaces the regexes never
 * matched — so the set is spelled out here, mirroring
 * [estimateConvertedNoteBytes]'s `isWs`.)
 */
private fun isPatternWhitespace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

/**
 * The closing `)` of an image/link reference whose reference ends
 * at [p] (Codex R38-F1): the optional `&quot;`-title first, then
 * the bare close — the regex's greedy-optional backtrack order,
 * `(?:\s+&quot;[^&]*&quot;)?\s*\)`. Returns the index just past
 * the `)`, or -1 when neither shape closes here. The title's
 * `[^&]*` ends at the first `&`, so that `&` must start the
 * closing `&quot;`; an `&amp;` (or any other entity) there fails
 * the title outright, and the bare close is tried instead.
 */
private fun closeParenEnd(text: String, p: Int): Int {
    val n = text.length
    var r = p
    while (r < n && isPatternWhitespace(text[r])) r++
    if (r > p && text.startsWith("&quot;", r)) {
        val titleStart = r + 6
        val amp = text.indexOf('&', titleStart)
        if (amp >= 0 && text.startsWith("&quot;", amp)) {
            var s = amp + 6
            while (s < n && isPatternWhitespace(text[s])) s++
            if (s < n && text[s] == ')') return s + 1
        }
    }
    var s = p
    while (s < n && isPatternWhitespace(text[s])) s++
    if (s < n && text[s] == ')') return s + 1
    return -1
}

/**
 * Unwraps an angle-bracketed link/image destination starting at
 * [start] — the CommonMark `<...>` form — returning the inner
 * destination and the index just past the closing delimiter, or null
 * when the text there is not a well-formed bracketed destination.
 * Both spellings are recognised: raw `<`/`>` (the reference
 * pre-scan, which runs on raw text) and HTML-escaped `&lt;`/`&gt;`
 * (the render pass, which scans escaped text). The closing
 * delimiter must appear before the next `)` and before any line
 * ending, and a nested opener before the closer disqualifies it —
 * so a malformed `<...` falls back to the plain extraction, which
 * keeps today's behaviour for it. (Codex R52-F1: without this, the
 * leading `<` defeated the remote check, so a remote
 * `<https://...>` fell through to the resolver — and a local
 * `<my photo.png>` never even scanned, because the space inside
 * the brackets ended the reference.)
 */
private fun bracketedDestination(text: String, start: Int): Pair<String, Int>? {
    val n = text.length
    val openLen: Int
    val close: String
    when {
        start < n && text[start] == '<' -> {
            openLen = 1
            close = ">"
        }
        text.startsWith("&lt;", start) -> {
            openLen = 4
            close = "&gt;"
        }
        else -> return null
    }
    var q = start + openLen
    while (q < n) {
        if (text[q] == '\n' || text[q] == '\r' || text[q] == ')') return null
        if (text.startsWith(close, q)) {
            return text.substring(start + openLen, q) to (q + close.length)
        }
        if (text[q] == '<' || text.startsWith("&lt;", q)) return null
        q++
    }
    return null
}

/**
 * Bracketed image/link references, scanned in one left-to-right
 * pass (Codex R38-F1). This replaces the three regexes that all
 * contained `([^\]]*)` — the render pass's image and link patterns
 * and the pre-scan's image pattern. For an unmatched `![`/`[`
 * opener — no `]` anywhere ahead — the regex engine scans forward
 * to the end of the remaining text on every attempt, and `findAll`
 * tries at each opener, so ~30,000 openers in a 60 KB source cost
 * O(n²) character scans, twice per member (pre-scan and rendering).
 * This scanner advances once through the text and never rescans:
 * the index of the next `]` only ever moves forward, so a failed
 * opener costs O(1) amortised — and when no `]` exists anywhere
 * ahead, no later opener can match either, so the scan stops there.
 *
 * The match grammar is the regexes' grammar, unchanged: `[` (with
 * an optional leading `!` for images), a label of any chars except
 * `]` (at least [minLabel] of them), `(` immediately after the
 * `]`, optional whitespace, a reference of one or more chars that
 * are neither `)` nor whitespace — or an angle-bracketed destination
 * `<...>` (see [bracketedDestination]), which may contain spaces
 * and is unwrapped here, so the reference the callbacks see never
 * carries the brackets; when [requireCloseParen] the
 * match then needs the optional `&quot;`-title and a closing `)`
 * (see [closeParenEnd]). The pre-scan passes
 * `requireCloseParen = false`, so it stays deliberately more
 * permissive than the render pass, which runs on escaped text (its
 * title is `&quot;…&quot;`): the pre-scan must never MISS a
 * reference the converter would use — an extra staged image costs a
 * little budget, a missed one loses the embed. Matches are
 * leftmost-first and non-overlapping, exactly as `findAll`/`replace`
 * produced them; on a failed attempt the scan resumes one character
 * past the opener.
 *
 * Code is carved out before this runs (Codex R18-F2, see
 * [collectImageRefs]): a reference inside a fenced block or a code
 * span is never embedded, so it must not consume the shared
 * extracted-image budget. The earlier "harmless" reading — that an
 * unrendered reference merely stages an extra image — was refuted
 * in the budget sense: in a multi-ZIP selection it can starve a
 * later ZIP's real images.
 */
private fun scanBracketRefs(
    text: String,
    requireBang: Boolean,
    minLabel: Int,
    requireCloseParen: Boolean,
    onMatch: (label: String, ref: String, start: Int, end: Int) -> Unit,
) {
    val n = text.length
    var i = 0
    // First ']' at or after the current label start; -1 = not yet
    // known for this position. Only ever moves forward: the text
    // between the previous label start and this ']' holds no other
    // ']' by construction, so a later label start past it needs a
    // fresh search — and a label start before it reuses this one.
    var closeIdx = -1
    while (i < n) {
        val open = if (requireBang) text.indexOf("![", i) else text.indexOf('[', i)
        if (open < 0) break
        val labelStart = open + if (requireBang) 2 else 1
        if (closeIdx < labelStart) {
            closeIdx = text.indexOf(']', labelStart)
            if (closeIdx < 0) break
        }
        val close = closeIdx
        if (close - labelStart < minLabel) {
            i = open + 1
            continue
        }
        var p = close + 1
        if (p >= n || text[p] != '(') {
            i = open + 1
            continue
        }
        p++
        while (p < n && isPatternWhitespace(text[p])) p++
        val refStart = p
        // (Codex R52-F1): an angle-bracketed destination is unwrapped
        // here, in the one shared scanner, so the render pass and the
        // pre-scan can never disagree about it — and so a destination
        // with spaces inside the brackets scans whole instead of
        // ending at the first space.
        val bracketed = bracketedDestination(text, refStart)
        val ref: String
        if (bracketed != null) {
            ref = bracketed.first
            p = bracketed.second
        } else {
            while (p < n && text[p] != ')' && !isPatternWhitespace(text[p])) p++
            if (p == refStart) {
                i = open + 1
                continue
            }
            ref = text.substring(refStart, p)
        }
        val label = text.substring(labelStart, close)
        if (!requireCloseParen) {
            onMatch(label, ref, open, p)
            i = p
            continue
        }
        val end = closeParenEnd(text, p)
        if (end < 0) {
            i = open + 1
            continue
        }
        onMatch(label, ref, open, end)
        i = end
    }
}

/**
 * Rebuilds [text] with every bracketed reference replaced by
 * [replace]'s return, in the same leftmost-first, non-overlapping
 * order `Regex.replace` produced (Codex R38-F1). Unmatched text is
 * copied through verbatim.
 */
private fun replaceBracketRefs(
    text: String,
    requireBang: Boolean,
    minLabel: Int,
    requireCloseParen: Boolean,
    replace: (label: String, ref: String) -> String,
): String {
    val out = StringBuilder(text.length)
    var lastEnd = 0
    scanBracketRefs(text, requireBang, minLabel, requireCloseParen) { label, ref, start, end ->
        out.append(text, lastEnd, start)
        out.append(replace(label, ref))
        lastEnd = end
    }
    out.append(text, lastEnd, text.length)
    return out.toString()
}

/**
 * Splits raw Markdown text into the prose regions the converter
 * treats as renderable: fenced code blocks are excised whole (their
 * contents are literal — [renderMarkdownBlocks] consumes a fence
 * line through its closing fence, or to EOF when unclosed), and
 * each region keeps its newlines so a reference spanning lines
 * inside one paragraph still scans. A fence line is recognised
 * exactly as the converter recognises it ([FENCE]).
 */
private fun proseSegments(text: String): List<String> {
    val normalized = text.replace(Regex("\r\n?"), "\n")
    val lines = normalized.split("\n")
    val segments = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < lines.size) {
        if (FENCE.containsMatchIn(lines[i])) {
            if (current.isNotEmpty()) {
                segments += current.toString()
                current.clear()
            }
            i++
            while (i < lines.size && !FENCE.containsMatchIn(lines[i])) i++
            if (i < lines.size) i++ // closing fence
            continue
        }
        current.append(lines[i]).append('\n')
        i++
    }
    if (current.isNotEmpty()) segments += current.toString()
    return segments
}

/** Bounded collector for the pre-scan's referenced image targets
 *  (Codex R20-F1): the two sets live for the whole ZIP parse, and
 *  the scan's inputs are bounded only by the archive quota and the
 *  per-member text bound, so without a bound of their own a
 *  crafted archive could grow them to millions of entries and OOM
 *  before any later budget check runs. Past the bound a reference
 *  is simply not collected — its image is not staged, the
 *  reference stays unresolved (counted at the reference), and the
 *  note still imports. */
private class ReferencedImageCollector {
    val targets = HashSet<String>()
    val basenames = HashSet<String>()
    private var retainedChars = 0L

    /** Records [target] and its basename when the retained-reference
     *  budget still allows; a target already collected costs
     *  nothing more, and a basename already held is not charged
     *  twice. */
    fun add(target: String) {
        if (target in targets) return
        val basename = importBaseName(target).lowercase()
        val cost = target.length.toLong() +
            if (basename in basenames) 0L else basename.length.toLong()
        if (targets.size >= ImportCaps.MAX_RETAINED_IMAGE_REFS ||
            retainedChars + cost > ImportCaps.MAX_RETAINED_IMAGE_REF_CHARS
        ) return
        targets += target
        basenames += basename
        retainedChars += cost
    }
}

/** Collects the image targets referenced by one Markdown member:
 *  exact normalised paths plus their basenames (the resolver falls
 *  back to a unique basename match). Remote references are never
 *  resolved, so they are not collected.
 *
 *  Code awareness (Codex R18-F2): the scan runs per prose segment
 *  with inline code spans carved out exactly as [renderInline]
 *  carves them ([CODE_SPAN]), so a reference inside backticks or
 *  inside a fenced block is never collected — and a reference whose
 *  syntax is split by a code span is never collected either,
 *  because the segments either side are scanned separately and
 *  never re-joined (a text-substitution strip would join them into
 *  a false reference, or break a real reference adjacent to a
 *  span). A reference the converter would render is always inside
 *  one scanned segment, so it is never missed. */
private fun collectImageRefs(
    fromPath: String,
    text: String,
    collector: ReferencedImageCollector,
) {
    // (Codex R44-F2): the parent is computed once for the whole
    // member, not once per reference — every prose segment of this
    // member shares it.
    val parentDir = normalisedParentDir(fromPath)
    for (segment in proseSegments(text)) {
        // Carve code spans out first (renderInline's shape): the
        // prose between spans is scanned, the spans themselves
        // never are.
        var lastIndex = 0
        for (span in CODE_SPAN.findAll(segment)) {
            collectRefsFrom(segment.substring(lastIndex, span.range.first), parentDir, collector)
            lastIndex = span.range.last + 1
        }
        collectRefsFrom(segment.substring(lastIndex), parentDir, collector)
    }
}

private fun collectRefsFrom(
    text: String,
    parentDir: String,
    collector: ReferencedImageCollector,
) {
    scanBracketRefs(text, requireBang = true, minLabel = 0, requireCloseParen = false) { _, ref, _, _ ->
        if (REMOTE_IMAGE_REF.containsMatchIn(ref)) return@scanBracketRefs
        val target = resolveRefWithParent(parentDir, ref)
        if (target.isEmpty()) return@scanBracketRefs
        collector.add(target)
    }
}

private val SAFE_LINK = Regex("^(https?:|mailto:)", RegexOption.IGNORE_CASE)

/**
 * The leading H1 the converter consumes as the note title
 * (MarkdownImporter.markdownFileToImportedNote): exactly one '#',
 * after any leading whitespace including blank lines, followed by
 * the title text on the same line. Shared with the ZIP pre-scan
 * (Codex R41-F1), which must strip exactly the line the converter
 * consumes before collecting image refs — a reference inside it is
 * never embedded, so it must never be staged or charged against
 * the shared image budget. One constant, so the two can never
 * drift apart; MarkdownImporter.estimateConvertedNoteBytes
 * keeps its own byte-count mirror of the same rule.
 */
private val LEADING_H1 = Regex("^\\s*#\\s+([^\\n]+)")

/**
 * Renders inline Markdown to editor HTML. Raw source HTML is escaped,
 * never passed through. Code spans are carved out first so their
 * contents are never formatted or mistaken for images/links.
 */
private fun renderInline(raw: String, images: InlineImageSink): String {
    // JS splits on /(`[^`]+`)/ keeping the spans; findAll carving is the
    // Kotlin equivalent.
    val out = StringBuilder()
    var lastIndex = 0
    for (match in CODE_SPAN.findAll(raw)) {
        if (match.range.first > lastIndex) {
            out.append(renderInlineText(raw.substring(lastIndex, match.range.first), images))
        }
        val span = match.value
        out.append("<code>").append(escapeImportHtml(span.substring(1, span.length - 1))).append("</code>")
        lastIndex = match.range.last + 1
    }
    if (lastIndex < raw.length) out.append(renderInlineText(raw.substring(lastIndex), images))
    return out.toString()
}

private fun renderInlineText(raw: String, images: InlineImageSink): String {
    var text = escapeImportHtml(raw)

    // Images first (their syntax contains the link syntax).
    // Both passes scan bracketed references in one pass (Codex
    // R38-F1) — the link pattern shared the image patterns'
    // `([^\]]*)` hazard, so it uses the same scanner rather than
    // its own regex.
    text = replaceBracketRefs(text, requireBang = true, minLabel = 0, requireCloseParen = true) { alt, ref ->
        images.addImage(
            unescapeEntities(alt),
            unescapeEntities(ref),
        )
    }

    // Links: only http/https/mailto survive as links; anything else
    // degrades to its text (a file:// or javascript: ref is never emitted).
    text = replaceBracketRefs(text, requireBang = false, minLabel = 1, requireCloseParen = true) { label, rawHref ->
        val href = unescapeEntities(rawHref)
        if (SAFE_LINK.containsMatchIn(href)) {
            "<a href=\"${escapeImportAttr(href)}\">$label</a>"
        } else {
            label
        }
    }

    // Emphasis. Markers survive escaping untouched, so they can be
    // applied to the escaped text.
    text = Regex("""\*\*([^*]+)\*\*""").replace(text) { "<strong>${it.groupValues[1]}</strong>" }
    text = Regex("""__([^_]+)__""").replace(text) { "<strong>${it.groupValues[1]}</strong>" }
    text = Regex("""~~([^~]+)~~""").replace(text) { "<s>${it.groupValues[1]}</s>" }
    text = Regex("""\*([^*]+)\*""").replace(text) { "<em>${it.groupValues[1]}</em>" }
    text = Regex("""(^|[^\w])_([^_]+)_(?![\w])""").replace(text) {
        "${it.groupValues[1]}<em>${it.groupValues[2]}</em>"
    }
    return text
}

/* ---------------- Block rendering ---------------- */

/** The marker system of an ordered list, as carried by the OL `type` attribute. */
private data class ListItem(
    val indent: Int,
    val ordered: Boolean,
    /** Marker system for ordered items ("1"/"a"/"A"/"i"/"I"); null for bullets. */
    val listType: String?,
    /** Numeric value of the item's own marker (alpha/roman converted); null for bullets. */
    val value: Int?,
    val task: Boolean,
    val checked: Boolean,
    var text: String,
)

private val LIST_LINE = Regex("""^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$""")
private val LETTERED_LINE = Regex("""^(\s*)([A-Za-z]+)[.)]\s+(.*)$""")
private val TASK_MARKER = Regex("""^\[( |x|X)\]\s+(.*)$""")

private fun listIndent(whitespace: String): Int =
    Math.floorDiv(whitespace.replace("\t", "  ").length, 2)

private fun lineIndent(line: String): Int =
    listIndent(Regex("""^(\s*)""").find(line)!!.groupValues[1])

private val ROMAN_DIGITS = mapOf(
    'i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100, 'd' to 500, 'm' to 1000,
)
private val ROMAN_SHAPE = Regex("""^m{0,3}(cm|cd|d?c{0,3})(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$""")

/** Strict roman-numeral value of a marker (single case only), or null when it isn't one. */
private fun parseRomanNumeral(marker: String): Int? {
    if (marker.isEmpty() || !marker.all { it.lowercaseChar() in "ivxlcdm" }) return null
    val lower = marker.lowercase()
    if (marker != lower && marker != marker.uppercase()) return null // mixed case
    if (!ROMAN_SHAPE.matches(lower)) return null
    var total = 0
    for (k in lower.indices) {
        val current = ROMAN_DIGITS[lower[k]]!!
        val next = if (k + 1 < lower.length) ROMAN_DIGITS[lower[k + 1]]!! else 0
        total += if (current < next) -current else current
    }
    return total
}

/**
 * Spreadsheet-style (bijective base-26) value of a letter marker,
 * matching the exporter's `toAlpha`: a=1 … z=26, aa=27, az=52, ba=53.
 * Mixed-case markers belong to neither system and return null.
 */
private fun parseAlphaMarker(marker: String): Int? {
    if (marker != marker.lowercase() && marker != marker.uppercase()) return null
    val lower = marker.lowercase()
    var value = 0
    for (ch in lower) value = value * 26 + (ch - 'a' + 1)
    return value
}

private class LetteredCandidate(
    val indent: Int,
    val text: String,
    val upper: Boolean,
    /** Spreadsheet-style alpha value; null for mixed-case markers. */
    val alpha: Int?,
    /** Value when the marker is a valid roman numeral. */
    val roman: Int?,
)

/**
 * A line that LOOKS like a lettered/roman list item ("a. ", "iv) ").
 * Such lines become list items only when sequence-confirmed (see
 * [confirmLetteredStart]) — prose is full of "a. " and "i. " sentence
 * fragments that must not turn into lists.
 */
private fun parseLetteredCandidate(line: String): LetteredCandidate? {
    val match = LETTERED_LINE.find(line) ?: return null
    val marker = match.groupValues[2]
    val roman = parseRomanNumeral(marker)
    val alpha = parseAlphaMarker(marker)
    if (roman == null && alpha == null) return null // mixed-case: neither system
    return LetteredCandidate(
        indent = listIndent(match.groupValues[1]),
        text = match.groupValues[3],
        upper = marker == marker.uppercase(),
        alpha = alpha,
        roman = roman,
    )
}

/** The candidate's marker value read as the given list type, or null when it isn't one. */
private fun letteredValue(candidate: LetteredCandidate, type: String): Int? = when (type) {
    "a" -> if (candidate.upper) null else candidate.alpha
    "A" -> if (candidate.upper) candidate.alpha else null
    "i" -> if (candidate.upper) null else candidate.roman
    "I" -> if (candidate.upper) candidate.roman else null
    else -> null
}

/**
 * Sequence confirmation for a would-be lettered/roman list: the
 * candidate at lines[idx] starts a list only when the next
 * list-candidate line at the same indent continues the sequence (+1 in
 * the same system and case). Roman is tried before alpha, so
 * "i. ii. iii." reads as roman while "c. d." (100 → 500 is not +1)
 * falls through to alpha. A lone lettered line is never confirmed —
 * that false-positive guard is deliberate, so ordinary prose keeps its
 * paragraphs.
 *
 * The verdict is decided entirely by the first line that would END a
 * forward scan — the first line at or above the candidate's indent,
 * or a blank line — so the scan is answered from [stopAt], the
 * next-stopping-line index renderMarkdownBlocks precomputes once per
 * conversion (Codex R31-F3): the old loop rescanned from idx+1 for
 * every candidate, re-parsing each following line, so N
 * increasing-indent lettered lines cost O(N^2) regex work and a note
 * under the size cap could look hung.
 */
private fun confirmLetteredStart(
    lines: List<String>,
    idx: Int,
    candidate: LetteredCandidate,
    stopAt: IntArray,
): String? {
    val types = mutableListOf<String>()
    if (candidate.roman != null) types += if (candidate.upper) "I" else "i"
    if (candidate.alpha != null) types += if (candidate.upper) "A" else "a"
    // The first stopping line decides, exactly as the old forward scan
    // did: a same-indent lettered candidate that continues the
    // sequence confirms the list; a blank line, a bullet/numeric line
    // at or above the indent, a shallower candidate, or ordinary text
    // at or above the indent all end it — and a line deeper than the
    // candidate can never be the stopping line, so it is skipped here
    // the same way the scan skipped it.
    val j = stopAt[idx]
    if (j >= lines.size) return null
    val next = parseLetteredCandidate(lines[j]) ?: return null
    if (next.indent != candidate.indent) return null
    for (type in types) {
        val first = letteredValue(candidate, type)
        val second = letteredValue(next, type)
        if (first != null && second != null && second == first + 1) return type
    }
    return null
}

private sealed interface LetteredDecision {
    class Item(val item: ListItem) : LetteredDecision
    object Break : LetteredDecision
    object NotAnItem : LetteredDecision
}

/**
 * Decides a lettered candidate line during list collection:
 * - an Item when it continues the live lettered run at its indent, or
 *   starts a new sequence-confirmed run;
 * - Break when it breaks the live run's sequence — the caller ends the
 *   collection there and the line is re-evaluated as ordinary content;
 * - NotAnItem when it isn't a list item at all (the caller falls
 *   through to the indented-continuation rules).
 */
private fun letteredItem(
    lines: List<String>,
    idx: Int,
    candidate: LetteredCandidate,
    items: List<ListItem>,
    stopAt: IntArray,
): LetteredDecision {
    var prev: ListItem? = null
    for (k in items.indices.reversed()) {
        if (items[k].indent <= candidate.indent) {
            if (items[k].indent == candidate.indent) prev = items[k]
            break
        }
    }
    if (prev != null && prev.ordered && prev.listType != null && prev.listType != "1") {
        val value = letteredValue(candidate, prev.listType)
        return if (value != null && prev.value != null && value == prev.value + 1) {
            LetteredDecision.Item(
                ListItem(candidate.indent, true, prev.listType, value, false, false, candidate.text),
            )
        } else {
            LetteredDecision.Break
        }
    }
    val type = confirmLetteredStart(lines, idx, candidate, stopAt) ?: return LetteredDecision.NotAnItem
    return LetteredDecision.Item(
        ListItem(candidate.indent, true, type, letteredValue(candidate, type), false, false, candidate.text),
    )
}

private val FENCE = Regex("""^```""")
private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val HEADING_START = Regex("""^#{1,6}\s""")
private val HR = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")

private fun renderMarkdownBlocks(markdown: String, images: InlineImageSink): String {
    val lines = markdown.replace(Regex("\r\n?"), "\n").split("\n")
    // (Codex R31-F3): for each line, the next line that would END a
    // lettered-sequence scan — the first line after it at or below
    // its indent (blank lines count as indent -1, so they always end
    // one), computed once per conversion with a monotone stack
    // (next-smaller-or-equal, one reverse pass). confirmLetteredStart
    // answers from this array in O(1) instead of rescanning.
    val effIndents = IntArray(lines.size) { if (lines[it].isBlank()) -1 else lineIndent(lines[it]) }
    val stopAt = IntArray(lines.size)
    val stopStack = ArrayDeque<Int>()
    for (idx in lines.size - 1 downTo 0) {
        while (stopStack.isNotEmpty() && effIndents[stopStack.last()] > effIndents[idx]) {
            stopStack.removeLast()
        }
        stopAt[idx] = stopStack.lastOrNull() ?: lines.size
        stopStack.addLast(idx)
    }
    val blocks = mutableListOf<String>()
    var i = 0

    fun isBlank(line: String) = line.isBlank()
    // A lettered/roman line opens a list block only when sequence-confirmed.
    fun letteredStartAt(idx: Int): String? {
        val candidate = parseLetteredCandidate(lines[idx]) ?: return null
        return confirmLetteredStart(lines, idx, candidate, stopAt)
    }
    fun startsBlock(line: String, idx: Int): Boolean =
        FENCE.containsMatchIn(line) ||
            HEADING_START.containsMatchIn(line) ||
            HR.matches(line) ||
            line.startsWith(">") ||
            LIST_LINE.containsMatchIn(line) ||
            letteredStartAt(idx) != null

    while (i < lines.size) {
        val line = lines[i]
        if (isBlank(line)) {
            i++
            continue
        }

        // Fenced code block — contents are literal, never formatted.
        if (FENCE.containsMatchIn(line)) {
            val code = mutableListOf<String>()
            i++
            while (i < lines.size && !FENCE.containsMatchIn(lines[i])) {
                code += lines[i]
                i++
            }
            i++ // closing fence (or EOF)
            blocks += "<pre><code>${escapeImportHtml(code.joinToString("\n"))}</code></pre>"
            continue
        }

        // Heading. The editor schema caps headings at level 3, so
        // deeper levels clamp to H3 instead of being stripped by the
        // editor on first open.
        val heading = HEADING.find(line)
        if (heading != null) {
            val level = minOf(heading.groupValues[1].length, 3)
            blocks += "<h$level>${renderInline(heading.groupValues[2].trim(), images)}</h$level>"
            i++
            continue
        }

        // Horizontal rule.
        if (HR.matches(line)) {
            blocks += "<hr>"
            i++
            continue
        }

        // Blockquote: consecutive > lines, blank quote lines split paragraphs.
        if (line.startsWith(">")) {
            val paragraphs = mutableListOf<MutableList<String>>(mutableListOf())
            while (i < lines.size && lines[i].startsWith(">")) {
                val content = lines[i].replace(Regex("""^>\s?"""), "")
                if (content.isBlank()) paragraphs += mutableListOf<String>()
                else paragraphs[paragraphs.lastIndex] += content
                i++
            }
            val inner = paragraphs.filter { it.isNotEmpty() }
                .joinToString("") { p -> "<p>${renderInline(p.joinToString(" "), images)}</p>" }
            if (inner.isNotEmpty()) blocks += "<blockquote>$inner</blockquote>"
            continue
        }

        // Lists (incl. task lists), one indent level of nesting.
        if (LIST_LINE.containsMatchIn(line) || letteredStartAt(i) != null) {
            val items = mutableListOf<ListItem>()
            while (i < lines.size) {
                val current = lines[i]
                val match = LIST_LINE.find(current)
                if (match != null) {
                    val marker = match.groupValues[2]
                    val ordered = marker[0].isDigit()
                    var text = match.groupValues[3]
                    var task = false
                    var checked = false
                    if (!ordered) {
                        val taskMatch = TASK_MARKER.find(text)
                        if (taskMatch != null) {
                            task = true
                            checked = taskMatch.groupValues[1] != " "
                            text = taskMatch.groupValues[2]
                        }
                    }
                    items += ListItem(
                        indent = listIndent(match.groupValues[1]),
                        ordered = ordered,
                        listType = if (ordered) "1" else null,
                        value = if (ordered) marker.trimEnd('.', ')').toIntOrNull() else null,
                        task = task,
                        checked = checked,
                        text = text,
                    )
                    i++
                    continue
                }
                // Lettered/roman lines join only as confirmed sequences.
                val candidate = parseLetteredCandidate(current)
                if (candidate != null) {
                    when (val decision = letteredItem(lines, i, candidate, items, stopAt)) {
                        is LetteredDecision.Break -> break
                        is LetteredDecision.Item -> {
                            items += decision.item
                            i++
                            continue
                        }
                        is LetteredDecision.NotAnItem -> Unit
                    }
                }
                // Indented continuation of the previous item.
                if (!isBlank(current) &&
                    Regex("""^\s{2,}\S""").containsMatchIn(current) &&
                    items.isNotEmpty() &&
                    !startsBlock(current, i)
                ) {
                    items[items.lastIndex].text += " ${current.trim()}"
                    i++
                    continue
                }
                break
            }
            blocks += renderList(items, images)
            continue
        }

        // Paragraph: consecutive lines until a blank line or another block.
        val paragraph = mutableListOf(line)
        i++
        while (i < lines.size && !isBlank(lines[i]) && !startsBlock(lines[i], i)) {
            paragraph += lines[i]
            i++
        }
        blocks += "<p>${renderInline(paragraph.joinToString(" "), images)}</p>"
    }
    return blocks.joinToString("")
}

/** Splits a list block into kind-runs and renders each. A kind
 *  change only starts a new list at the run's base indent: a
 *  deeper-indented item of another kind is a nested child of the item
 *  above it, not a sibling list. Also used for nested levels (from
 *  [renderListRun]): a nested run mixing markers renders each kind
 *  under its own list type instead of taking the first child's. */
private fun renderList(items: List<ListItem>, images: InlineImageSink): String {
    val out = StringBuilder()
    var run = mutableListOf<ListItem>()
    var runBaseIndent = 0
    fun flush() {
        if (run.isEmpty()) return
        out.append(renderListRun(run, images))
        run = mutableListOf()
    }
    // The run kind is the marker system, not just ordered-vs-bullet: a
    // numeric run and a lettered run are separate lists, and so are the
    // a / A / i / I systems among themselves.
    fun runKey(item: ListItem) = if (item.ordered) "ol:${item.listType}" else "ul"
    for (item in items) {
        if (run.isNotEmpty() && runKey(run[0]) != runKey(item) &&
            item.indent <= runBaseIndent
        ) flush()
        if (run.isEmpty()) runBaseIndent = item.indent
        else runBaseIndent = minOf(runBaseIndent, item.indent)
        run += item
    }
    flush()
    return out.toString()
}

/**
 * The `<ol>` attributes carrying a list's marker semantics: the marker
 * type (omitted for the default numeric system) and the start (omitted
 * when 1, and outside the 1–9999 range the editor would keep anyway).
 * The start comes from the run's first item only — later markers in
 * the source do not renumber the list.
 */
private fun orderedListAttrs(item: ListItem): String {
    var attrs = ""
    if (item.listType != null && item.listType != "1") attrs += " type=\"${item.listType}\""
    if (item.value != null && item.value > 1 && item.value <= 9999) {
        attrs += " start=\"${item.value}\""
    }
    return attrs
}

/**
 * Renders one top-level list run with one level of nesting (deeper
 * indents clamp to the first nested level). A run may mix marker kinds
 * in its nested items — each nested level renders in its own kind.
 * Task-ness is decided per level, from that level's own items: a level
 * of unordered items containing any task item becomes a task list —
 * plain items at that level ride along as unchecked tasks, since task
 * lists can't mix item types. So a nested ordered list under a task
 * parent stays an ordered list, and a nested task under an ordered
 * parent stays a task list. Task items use Nota's own serialiser shape
 * (see the file header).
 */
private fun renderListRun(items: List<ListItem>, images: InlineImageSink): String {
    val baseIndent = items.minOf { it.indent }
    val normalised = items.map { it.copy(indent = minOf(it.indent, baseIndent + 1)) }

    fun renderItems(list: List<ListItem>): String {
        val out = StringBuilder()
        // Task-ness is decided by the level's own items only: this
        // flat list also carries the nested items, so a nested task
        // must not make its plain parent a task list (Codex R15-F1).
        // The level's own items are the ones at the list's minimum
        // indent — children always sit deeper than their parent.
        val levelIndent = list.minOf { it.indent }
        val levelTasks = !list[0].ordered && list.any { it.indent == levelIndent && it.task }
        var idx = 0
        while (idx < list.size) {
            val item = list[idx]
            val children = mutableListOf<ListItem>()
            var next = idx + 1
            while (next < list.size && list[next].indent > item.indent) {
                children += list[next]
                next++
            }
            val textHtml = renderInline(item.text, images)
            // Children split into kind-runs at their own level, like
            // the top level does: a nested bullet-then-numbered run
            // renders each kind under its own list type.
            val childHtml = if (children.isNotEmpty()) renderList(children, images) else ""
            if (levelTasks) {
                out.append("<li data-type=\"taskItem\" data-checked=\"")
                    .append(if (item.checked) "true" else "false")
                    .append("\"><div><p>").append(textHtml).append("</p>")
                    .append(childHtml).append("</div></li>")
            } else {
                out.append("<li><p>").append(textHtml).append("</p>")
                    .append(childHtml).append("</li>")
            }
            idx = next
        }
        if (levelTasks) return "<ul data-type=\"taskList\">$out</ul>"
        val first = list[0]
        if (!first.ordered) return "<ul>$out</ul>"
        return "<ol${orderedListAttrs(first)}>$out</ol>"
    }

    return renderItems(normalised)
}

/* ---------------- File-level parsing ---------------- */

data class MarkdownFileInput(
    /** Base name, e.g. "note.md". */
    val name: String,
    /** Full relative path within the picked set, e.g. "folder/sub/note.md". */
    val path: String,
    val text: String,
    val lastModified: Long,
    /** Stable per-source identity for the run dedupe of
     *  unknown-date notes (Codex R23-F1, R26-F2) — see
     *  [ImportedNote.sourceIdentity]. Null means the source name
     *  plays that role. The loose-file path sets it to a content
     *  hash; a ZIP member sets it to its full entry path plus a
     *  content hash — the bare path alone is not an identity
     *  across archives (the same entry path can appear in two
     *  ZIPs of one selection), and the bare basename is not one
     *  at all (two members can share it from different parents). */
    val sourceIdentity: String? = null,
)

private fun folderNameFromPath(path: String): String? {
    val parts = normaliseImportPath(path).split('/').filter { it.isNotEmpty() }
    if (parts.size < 2) return null
    return parts[parts.size - 2]
}

private fun titleFromFileName(name: String): String =
    importBaseName(name).replace(Regex("\\.(md|markdown|txt)$", RegexOption.IGNORE_CASE), "")

object MarkdownImporter {

    fun parseMarkdownFiles(
        files: List<MarkdownFileInput>,
        resolver: ImportImageResolver?,
    ): ImportParseResult {
        // One shared result on purpose: appendParsedFile's note-cap
        // check reads result.notes.size, so routing each file through
        // a fresh result would never trip the cap.
        val result = ImportParseResult()
        for (file in files) appendParsedFile(result, file, resolver)
        return result
    }

    /** One file's parse, as a standalone result — the loose-file
     *  selection path converts each file as it is read (see
     *  NoteImportRepository.importMarkdownSelection) so a multi-file
     *  selection never retains every file's text at once. */
    fun parseMarkdownFile(
        file: MarkdownFileInput,
        resolver: ImportImageResolver?,
    ): ImportParseResult {
        val result = ImportParseResult()
        appendParsedFile(result, file, resolver)
        return result
    }

    /** Whether [text] has more converter lines than
     *  [ImportCaps.MAX_LINES_PER_NOTE] — the same count the estimator
     *  trips on: \n breaks plus lone CRs (the converter normalises
     *  \r\n? to \n, so a CR before a \n is one break, not two), plus
     *  one for the final line. Range-based with an early exit, so it
     *  costs nothing on an ordinary file; only called on the refusal
     *  path, to pick the honest reason between a byte trip and a
     *  line trip. */
    internal fun exceedsLineCap(text: String): Boolean {
        var lines = 1L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                lines++
                if (lines > ImportCaps.MAX_LINES_PER_NOTE) return true
            } else if (c == '\r' && (i + 1 >= text.length || text[i + 1] != '\n')) {
                lines++
                if (lines > ImportCaps.MAX_LINES_PER_NOTE) return true
            }
            i++
        }
        return lines > ImportCaps.MAX_LINES_PER_NOTE
    }

    /**
     * Upper bound on the UTF-8 bytes of the note [markdownFileToImportedNote]
     * would build from [file] (title + contentHtml), computed from the
     * source WITHOUT converting it (Codex R10-F1). The 1 MB per-note cap
     * is otherwise enforced only by [ImportParseResult.addNote] AFTER
     * conversion, and a ~16M-char file of escapable characters converts
     * into ~80M chars of HTML before it is rejected — hundreds of MiB
     * on a 1 GB device.
     *
     * Every term is a provable over-estimate of what the converter can
     * emit, so a file the estimator clears is always safe to convert;
     * [ImportParseResult.addNote] remains the exact check, and a file
     * the estimator rejects is skipped with the per-note reason before
     * any conversion work. The bound is deliberately structured, not a
     * flat per-line allowance: block markup is charged per construct
     * (headings, lists by kind-run, quotes, fences), because a flat
     * allowance would falsely skip line-heavy files that convert
     * small. Soundness is pinned by the property test in
     * MarkdownImportTest, which checks the estimate against the real
     * converter on adversarial inputs.
     *
     * Per-construct bounds (all terms are UTF-8 bytes):
     * - Escaped text: every `&` becomes `&amp;` (+4), `<`/`>` become
     *   `&lt;`/`&gt;` (+3), `"` becomes `&quot;` in hrefs (+5). The
     *   .txt codec escapes all four; the .md converter escapes the
     *   first three in body text and `"` in hrefs — counting all four
     *   everywhere is still an upper bound.
     * - Inline markup (.md only): each inline construct's net tag
     *   bytes per marker character is at most 7 (worst case a code
     *   span: `` `&` `` → `<code>&amp;</code>`, 11 net bytes for 2
     *   backticks). Markers are `` ` ``, `*`, `_`, `~`, `[`, `]`,
     *   `(`, `)`, `!`. Markers that form nothing stay literal, so
     *   counting them always over-counts, never under.
     * - Block markup (.md only): headings +9, rules +4, fences +24
     *   per block, quotes +27 per block and +7 per line (one inner
     *   paragraph each, worst case), paragraphs +7 each, list items
     *   +15 plain / +70 task, +35 per list kind-run, nested items
     *   +105 (tags + their own kind-run wrapper). A list block's
     *   task-ness is charged at block end, when the level is known:
     *   one task item makes the whole level task items.
     * - Title: a .txt title is the file name; a .md title is exactly
     *   one of the front-matter title, the leading H1, or the file
     *   name — the estimator reads the same one the converter
     *   would, so a huge body line is never mistaken for the title.
     *   Front matter is not body text (mirrors [parseFrontMatter];
     *   .txt files never have it), so its lines contribute nothing
     *   but the title.
     *
     * Line count (Codex R44-F1): the converter and the pre-scan both
     * split the source into a line list, so a blank-line-heavy file
     * the byte bound would admit (blank lines cost almost no HTML)
     * must not reach them with ~1M entries. Lines are counted in the
     * walk below — \n breaks plus lone CRs, the way the converter
     * splits them — and past [ImportCaps.MAX_LINES_PER_NOTE] the
     * estimator returns [ImportCaps.MAX_NOTE_CONTENT_BYTES] + 1: a
     * refusal signal, not a byte estimate, so both call sites refuse
     * with the per-note reason before any conversion or pre-scan
     * work. The early return also bounds the estimator's own walk on
     * a pathological file.
     */
    internal fun estimateConvertedNoteBytes(file: MarkdownFileInput): Long {
        val text = file.text
        val isTxt = Regex("\\.txt$", RegexOption.IGNORE_CASE).containsMatchIn(file.name)

        // Whitespace in the converter's regexes (\s): space, tab, CR,
        // vertical tab, form feed. Lines never contain \n.
        fun isWs(c: Char): Boolean =
            c == ' ' || c == '\t' || c == '\r' || c == '\u000B' || c == '\u000C'

        // A line's leading-whitespace indent the way listIndent
        // computes it (tabs count double, then halved).
        fun lineIndent(start: Int, end: Int): Int {
            var w = 0
            var i = start
            while (i < end && isWs(text[i])) {
                w += if (text[i] == '\t') 2 else 1
                i++
            }
            return w / 2
        }

        // First non-whitespace index in [start, end), or end.
        fun firstContent(start: Int, end: Int): Int {
            var i = start
            while (i < end && isWs(text[i])) i++
            return i
        }

        fun isBlankLine(start: Int, end: Int): Boolean = firstContent(start, end) == end

        // Closing front-matter fence (mirrors parseFrontMatter's
        // ^---[ \t]*$): exactly three dashes, then only spaces/tabs.
        fun isClosingFence(start: Int, end: Int): Boolean {
            if (end - start < 3) return false
            if (text[start] != '-' || text[start + 1] != '-' || text[start + 2] != '-') return false
            for (i in start + 3 until end) {
                if (text[i] != ' ' && text[i] != '\t') return false
            }
            return true
        }

        // UTF-8 bytes of text[start, end) — utf8ByteLength over a range.
        fun rangeUtf8(start: Int, end: Int): Long {
            var bytes = 0L
            var i = start
            while (i < end) {
                val c = text[i]
                bytes += when {
                    c < '\u0080' -> 1L
                    c < '\u0800' -> 2L
                    c.isHighSurrogate() && i + 1 < end && text[i + 1].isLowSurrogate() -> {
                        i++
                        4L
                    }
                    else -> 3L
                }
                i++
            }
            return bytes
        }

        // A front-matter `title:` value's UTF-8 bytes (mirrors
        // parseFrontMatter's ^([A-Za-z][\w-]*)\s*:\s*(.*)$ with a
        // "title" key), or null when the line is not a non-empty
        // title. The value is kept WITH quotes (stripQuotes only
        // shortens), so its trimmed bytes bound the title.
        fun frontMatterTitleBytes(start: Int, end: Int): Long? {
            var i = start
            if (i >= end) return null
            val first = text[i]
            if (first !in 'A'..'Z' && first !in 'a'..'z') return null
            i++
            while (i < end) {
                val c = text[i]
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-') i++
                else break
            }
            val keyEnd = i
            while (i < end && isWs(text[i])) i++
            if (i >= end || text[i] != ':') return null
            i++
            while (i < end && isWs(text[i])) i++
            var valueEnd = end
            while (valueEnd > i && isWs(text[valueEnd - 1])) valueEnd--
            if (valueEnd <= i) return null
            if (keyEnd - start != 5) return null
            val key = "title"
            for (k in key.indices) {
                if (text[start + k].lowercaseChar() != key[k]) return null
            }
            return rangeUtf8(i, valueEnd)
        }

        // Front matter (mirrors parseFrontMatter; .txt files never
        // have it — the .txt path returns before front-matter
        // parsing): an opening "---" line and the first later line
        // that closes it. Lines inside it are not body text. The
        // converter strips a leading BOM before parsing, so the
        // detection starts past one. The last non-empty `title:` value
        // in the block is the note's title (it wins over an H1).
        var fmEndLine = -1
        var fmTitleBytes: Long? = null
        var bodyStart = if (text.startsWith("\uFEFF")) 1 else 0
        if (!isTxt) {
            val firstBreak = text.indexOf('\n', bodyStart)
            val firstEnd = if (firstBreak < 0) text.length else firstBreak
            val firstIsFence = firstEnd - bodyStart >= 3 &&
                text[bodyStart] == '-' && text[bodyStart + 1] == '-' && text[bodyStart + 2] == '-' &&
                text.substring(bodyStart, firstEnd).trim() == "---"
            if (firstIsFence) {
                var idx = if (firstBreak < 0) text.length else firstBreak + 1
                var lineNo = 1
                while (idx < text.length) {
                    val end = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
                    if (isClosingFence(idx, end)) {
                        fmEndLine = lineNo
                        bodyStart = if (end < text.length) end + 1 else text.length
                        break
                    }
                    frontMatterTitleBytes(idx, end)?.let { fmTitleBytes = it }
                    idx = end + 1
                    lineNo++
                }
            }
        }

        // The leading H1 becomes the title when front matter didn't
        // provide one (mirrors markdownFileToImportedNote's
        // ^\s*#\s+([^\n]+) on the body — exactly one '#', after any
        // leading whitespace including blank lines). The captured text
        // is trimmed (the trailing-hash strip only shortens), so its
        // bytes bound the title.
        var h1TitleBytes: Long? = null
        if (!isTxt && fmTitleBytes == null) {
            var i = bodyStart
            while (i < text.length && (isWs(text[i]) || text[i] == '\n')) i++
            if (i + 1 < text.length && text[i] == '#' && (isWs(text[i + 1]) || text[i + 1] == '\n')) {
                var j = i + 1
                while (j < text.length && (isWs(text[j]) || text[j] == '\n')) j++
                val capStart = j
                var capEnd = text.indexOf('\n', j).let { if (it < 0) text.length else it }
                while (capEnd > capStart && isWs(text[capEnd - 1])) capEnd--
                if (capEnd > capStart) h1TitleBytes = rangeUtf8(capStart, capEnd)
            }
        }

        var bodyUtf8 = 0L
        var escapeExtra = 0L
        var markers = 0L
        var bodyNewlines = 0L
        var blockMarkup = 0L

        // List-block accumulators (charged at block end, when the
        // level's task-ness is known).
        var inList = false
        var topItems = 0
        var topHasTask = false
        var topRuns = 0
        var curRunKind: String? = null
        var runBaseIndent = 0
        var nestedItems = 0

        var inParagraph = false
        var inQuote = false
        var inFence = false

        fun flushList() {
            if (!inList) return
            blockMarkup += topItems * (if (topHasTask) 70L else 15L) +
                35L * topRuns + 105L * nestedItems
            inList = false
            topItems = 0
            topHasTask = false
            topRuns = 0
            curRunKind = null
            nestedItems = 0
        }

        // A list item's marker, hand-read from [start, end) — the
        // same shapes LIST_LINE / LETTERED_LINE match. Returns null
        // when the line is not a list item. Lettered candidates are
        // always treated as items: the real parser only takes them
        // when sequence-confirmed, so treating prose as an item only
        // over-counts. Each lettered item is charged its own run
        // (see handleListItem).
        // kind: "ul" for bullets, "ol:1" for numeric, "lettered".
        data class ListMarker(
            val indent: Int,
            val ordered: Boolean,
            val kind: String,
            val isLettered: Boolean,
            val isTask: Boolean,
        )

        fun listMarker(start: Int, end: Int): ListMarker? {
            val content = firstContent(start, end)
            var i = content
            val indent = lineIndent(start, end)
            if (i >= end) return null
            val ordered: Boolean
            val isLettered: Boolean
            when (val c = text[i]) {
                '-', '*', '+' -> {
                    if (i + 1 >= end || !isWs(text[i + 1])) return null
                    ordered = false
                    isLettered = false
                    i++
                }
                in '0'..'9' -> {
                    var j = i
                    var digits = 0
                    while (j < end && text[j] in '0'..'9' && digits < 9) {
                        j++
                        digits++
                    }
                    if (digits == 0 || digits > 9) return null
                    if (j >= end || (text[j] != '.' && text[j] != ')')) return null
                    if (j + 1 >= end || !isWs(text[j + 1])) return null
                    ordered = true
                    isLettered = false
                    i = j + 1
                }
                in 'a'..'z', in 'A'..'Z' -> {
                    var j = i
                    while (j < end && (text[j] in 'a'..'z' || text[j] in 'A'..'Z')) j++
                    if (j >= end || (text[j] != '.' && text[j] != ')')) return null
                    if (j + 1 >= end || !isWs(text[j + 1])) return null
                    ordered = true
                    isLettered = true
                    i = j + 1
                }
                else -> return null
            }
            // Item text starts after the whitespace following the marker.
            while (i < end && isWs(text[i])) i++
            val isTask = !ordered && i + 3 < end &&
                text[i] == '[' && (text[i + 1] == ' ' || text[i + 1] == 'x' || text[i + 1] == 'X') &&
                text[i + 2] == ']' && isWs(text[i + 3])
            val kind = when {
                isLettered -> "lettered"
                ordered -> "ol:1"
                else -> "ul"
            }
            return ListMarker(indent, ordered, kind, isLettered, isTask)
        }

        fun handleListItem(marker: ListMarker) {
            if (!inList) {
                inList = true
                topItems = 0
                topHasTask = false
                topRuns = 0
                curRunKind = null
                runBaseIndent = marker.indent
                nestedItems = 0
                inParagraph = false
                inQuote = false
            }
            // Deeper than the run's base indent: a child of an earlier
            // item (renderItems groups every following deeper item as
            // a child). It carries its own tags plus its own nested
            // kind-run wrapper.
            if (marker.indent > runBaseIndent) {
                nestedItems++
                return
            }
            if (marker.isLettered) {
                // Each lettered item starts its own run: sequence
                // confirmation may merge same-system neighbours, but
                // over-counting runs is safe.
                topRuns++
                curRunKind = "lettered"
                runBaseIndent = marker.indent
            } else if (topItems == 0 || marker.kind != curRunKind) {
                topRuns++
                curRunKind = marker.kind
                runBaseIndent = marker.indent
            } else {
                runBaseIndent = minOf(runBaseIndent, marker.indent)
            }
            topItems++
            if (!marker.ordered && marker.isTask) topHasTask = true
        }

        // Heading (mirrors HEADING = ^(#{1,6})\s+(.*)$): 1–6 '#'
        // at column 0, then whitespace. A line with 7+ '#' is prose.
        fun isHeadingLine(start: Int, end: Int): Boolean {
            var i = start
            var hashes = 0
            while (i < end && text[i] == '#' && hashes < 7) {
                hashes++
                i++
            }
            return hashes in 1..6 && i < end && isWs(text[i])
        }

        // Horizontal rule (mirrors HR = ^\s*(-{3,}|\*{3,}|_{3,})\s*$):
        // leading whitespace, then 3+ of one of -, *, _ and nothing
        // but whitespace after. A "- - -" line is a list item, not a
        // rule — the run must be one repeated character.
        fun isHrLine(start: Int, end: Int): Boolean {
            var i = firstContent(start, end)
            if (i >= end) return false
            val c = text[i]
            if (c != '-' && c != '*' && c != '_') return false
            var count = 0
            while (i < end && text[i] == c) {
                count++
                i++
            }
            if (count < 3) return false
            while (i < end) {
                if (!isWs(text[i])) return false
                i++
            }
            return true
        }

        // Indented continuation of a list item (mirrors the
        // collection loop's ^\s{2,}\S): at least two whitespace
        // CHARACTERS (a tab is one character here, unlike indent),
        // then non-whitespace. Block starts are checked before this,
        // so a fence/heading/rule/quote/list line never counts.
        fun isContinuation(start: Int, end: Int): Boolean {
            var i = start
            var ws = 0
            while (i < end && isWs(text[i])) {
                ws++
                i++
            }
            return ws >= 2 && i < end
        }

        var idx = 0
        var lineNo = 0
        // (Codex R44-F1): lone CRs seen so far — a lone CR is a line
        // break too (the converter normalises \r\n? to \n), so the
        // line total is lineNo + loneCr + 1. Counted in the per-char
        // loop below, next to the byte counts.
        var loneCr = 0
        while (idx <= text.length) {
            val end = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
            val inFrontMatter = lineNo <= fmEndLine
            // Per-line byte counts. Front-matter lines count only
            // toward the title bound.
            var lineUtf8 = 0L
            var i = idx
            while (i < end) {
                val c = text[i]
                // (Codex R44-F1): a CR immediately before this line's
                // \n (i + 1 == end, and a \n follows) is part of that
                // break, not a second one; a CR at the very end of
                // the text (end == text.length) has nothing after it,
                // so it is lone. Any other CR in the line is lone —
                // the next char cannot be a \n, since end is the
                // first one.
                if (c == '\r' && (i + 1 < end || end == text.length)) loneCr++
                when (c) {
                    '&' -> {
                        lineUtf8 += 1
                        if (!inFrontMatter) escapeExtra += 4
                    }
                    '<' -> {
                        lineUtf8 += 1
                        if (!inFrontMatter) escapeExtra += 3
                    }
                    '>' -> {
                        lineUtf8 += 1
                        if (!inFrontMatter) escapeExtra += 3
                    }
                    '"' -> {
                        lineUtf8 += 1
                        if (!inFrontMatter) escapeExtra += 5
                    }
                    '`', '*', '_', '~', '[', ']', '(', ')', '!' -> {
                        lineUtf8 += 1
                        if (!inFrontMatter) markers++
                    }
                    else -> {
                        lineUtf8 += when {
                            c < '\u0080' -> 1L
                            c < '\u0800' -> 2L
                            c.isHighSurrogate() && i + 1 < end && text[i + 1].isLowSurrogate() -> {
                                i++
                                4L
                            }
                            else -> 3L
                        }
                    }
                }
                i++
            }
            if (!inFrontMatter) {
                bodyUtf8 += lineUtf8
                if (end < text.length) {
                    // The newline itself: kept as a space (or a
                    // newline in fenced code), so it costs a byte.
                    bodyUtf8 += 1
                    bodyNewlines++
                }
            }

            // Block structure (.md only — .txt markup is a flat
            // per-newline allowance, charged at the end).
            if (!isTxt && !inFrontMatter) {
                if (inFence) {
                    if (end - idx >= 3 && text[idx] == '`' && text[idx + 1] == '`' && text[idx + 2] == '`') {
                        inFence = false
                    }
                } else if (isBlankLine(idx, end)) {
                    flushList()
                    inParagraph = false
                    inQuote = false
                } else if (end - idx >= 3 && text[idx] == '`' && text[idx + 1] == '`' && text[idx + 2] == '`') {
                    flushList()
                    inParagraph = false
                    inQuote = false
                    blockMarkup += 24
                    inFence = true
                } else if (isHeadingLine(idx, end)) {
                    flushList()
                    inParagraph = false
                    inQuote = false
                    blockMarkup += 9
                } else if (isHrLine(idx, end)) {
                    flushList()
                    inParagraph = false
                    inQuote = false
                    blockMarkup += 4
                } else if (text[idx] == '>') {
                    flushList()
                    inParagraph = false
                    if (!inQuote) {
                        blockMarkup += 27
                        inQuote = true
                    }
                    blockMarkup += 7
                } else {
                    val marker = listMarker(idx, end)
                    if (marker != null) {
                        handleListItem(marker)
                    } else if (inList && isContinuation(idx, end)) {
                        // Appended to the previous item's text: no markup.
                    } else {
                        flushList()
                        inQuote = false
                        if (!inParagraph) {
                            blockMarkup += 7
                            inParagraph = true
                        }
                    }
                }
            }

            // (Codex R44-F1): the line cap — lineNo counts the
            // \n-terminated lines before this one, loneCr the lone
            // CRs seen so far, and the +1 is this line. The final
            // total can only grow from here (more breaks ahead), so
            // tripping now is exact, and it also stops the estimator's
            // own walk early on a pathological file. The return is
            // the refusal signal documented on the estimator: both
            // call sites refuse with the per-note reason.
            if (lineNo + loneCr + 1 > ImportCaps.MAX_LINES_PER_NOTE) {
                return ImportCaps.MAX_NOTE_CONTENT_BYTES + 1
            }

            if (end >= text.length) break
            idx = end + 1
            lineNo++
        }
        flushList()

        // The title is exactly one of: the front-matter title, the
        // leading H1, or the file name — never a body line. (An
        // earlier version bounded it by the longest line, which
        // double-counted a huge body line as the title and falsely
        // skipped every large note.)
        val titleBound = if (isTxt) {
            utf8ByteLength(file.name)
        } else {
            fmTitleBytes ?: h1TitleBytes ?: utf8ByteLength(file.name)
        }
        val markupBound = if (isTxt) {
            // PlainTextCodec: each newline becomes <br> (+3) inside a
            // paragraph and each paragraph adds <p></p> (7); a
            // paragraph boundary needs two newlines, so 7 per newline
            // plus one wrap covers both.
            7L * bodyNewlines + 7L
        } else {
            blockMarkup + 7L * markers
        }
        return titleBound + bodyUtf8 + escapeExtra + markupBound
    }

    /** One file's parse, shared by the list path and the ZIP path
     *  (which converts each member as it is read so no archive's text
     *  is ever retained in full). */
    private fun appendParsedFile(
        result: ImportParseResult,
        file: MarkdownFileInput,
        resolver: ImportImageResolver?,
    ) {
        if (result.notes.size >= ImportCaps.MAX_IMPORT_NOTES) {
            result.skippedFiles += SkippedImportFile(
                file.name,
                "Import limit reached (5,000 notes per run).",
            )
            return
        }
        // Defence in depth for direct callers: the ZIP path
        // already enforces this cap in bytes at read time (see
        // parseImportZip). String.length is UTF-16 units, not
        // UTF-8 bytes, so it can only ever trip for text supplied
        // pre-built, never for ZIP members.
        if (file.text.length > ImportCaps.MAX_IMPORT_FILE_BYTES) {
            result.skippedFiles += SkippedImportFile(file.name, "File is larger than 100 MB.")
            return
        }
        // Aggregate text budget (Codex R6-F1): estimated from the
        // source length BEFORE conversion, so an over-budget file
        // costs no parse work and leaves no image counts behind.
        // The true check runs in addNote below (HTML can expand the
        // source — for Markdown the raw text IS the body, so the
        // source length is a fair estimate here; the per-note
        // expansion is bounded separately, see below). The title
        // counts too (Codex R19-F1): a front-matter/H1 title is
        // inside the source text already, so only the filename
        // fallback adds anything — its length is a conservative
        // upper bound, exact when the title falls back to the
        // filename and over-counting by at most the filename
        // length when front matter/H1 supplies the title instead.
        if (result.retainedTextChars + file.text.length +
            titleFromFileName(file.name).length > ImportCaps.MAX_AGGREGATE_TEXT_CHARS
        ) {
            result.skippedFiles += SkippedImportFile(
                file.name,
                ImportCaps.AGGREGATE_TEXT_BUDGET_REASON,
            )
            return
        }
        // Per-note bound, estimated BEFORE conversion (Codex R10-F1):
        // conversion escapes text (& → &amp;) and adds block markup,
        // so a source that fits the aggregate budget can still
        // convert into many megabytes of HTML that addNote would
        // reject only after building it. A file the estimator
        // rejects reports the same per-note reason addNote would.
        if (estimateConvertedNoteBytes(file) > ImportCaps.MAX_NOTE_CONTENT_BYTES) {
            result.skippedFiles += SkippedImportFile(
                file.name,
                // (Codex R44-F1): the estimator returns the same
                // over-bound signal for a line-cap trip — the line
                // count, not the byte count, is what refused it.
                if (exceedsLineCap(file.text)) ImportCaps.TOO_MANY_LINES_REASON
                else ImportCaps.NOTE_TOO_LARGE_REASON,
            )
            return
        }
        result.addNote(markdownFileToImportedNote(file, resolver, result))
    }

    private fun markdownFileToImportedNote(
        file: MarkdownFileInput,
        resolver: ImportImageResolver?,
        result: ImportParseResult,
    ): ImportedNote {
        val images = mutableListOf<ImportedImage>()
        val sink = InlineImageSink(result, resolver, file.path, images)
        // Unknown timestamps are a stable 0, not the wall clock
        // (Codex R13-F1): the dedupe key is (title, createdAt), so a
        // wall-clock fallback gives the same source a different key on
        // every import and reimporting it duplicates the note. 0 is
        // stable across runs, and a note with no known date sorts as
        // the oldest — honest, since its date really is unknown.
        val fallbackStamp = if (file.lastModified > 0) file.lastModified else 0L

        if (isPlainTextEntry(file.name)) {
            return ImportedNote(
                title = titleFromFileName(file.name),
                contentHtml = PlainTextCodec.textToHtml(file.text),
                createdAt = fallbackStamp,
                updatedAt = fallbackStamp,
                pinned = false,
                archived = false,
                trashedAt = null,
                tagNames = emptyList(),
                folderName = folderNameFromPath(file.path),
                images = images,
                sourceName = file.name,
                sourceIdentity = file.sourceIdentity,
            )
        }

        val frontMatter = parseFrontMatter(file.text.removePrefix("\uFEFF"))
        var body = frontMatter.body
        var title = frontMatter.title

        // A leading H1 becomes the title when front matter didn't
        // provide one — and is consumed, not duplicated in the body.
        if (title == null) {
            val h1 = LEADING_H1.find(body)
            if (h1 != null) {
                title = h1.groupValues[1].trim().replace(Regex("\\s#+\\s*$"), "")
                body = body.substring(h1.range.last + 1)
            }
        }

        val contentHtml = renderMarkdownBlocks(body, sink)
        val createdAt = frontMatter.createdAt ?: fallbackStamp
        return ImportedNote(
            title = title?.takeIf { it.isNotEmpty() } ?: titleFromFileName(file.name),
            contentHtml = contentHtml,
            createdAt = createdAt,
            updatedAt = createdAt,
            pinned = false,
            archived = false,
            trashedAt = null,
            tagNames = frontMatter.tags,
            folderName = folderNameFromPath(file.path),
            images = images,
            sourceName = file.name,
            sourceIdentity = file.sourceIdentity,
        )
    }

    /** Parses a ZIP of Markdown/text files (the folder-in-a-ZIP case).
     *  Image entries are staged into [stagingDir] for the resolver.
     *
     *  Four walks, each streaming the archive once, so at most one
     *  member's text is ever in memory: walk 1 inventories the text
     *  members (names + times only, bounded by the same retained-name
     *  budget as the reader — names past it are dropped and the
     *  truncation is reported), walk 2 pre-scans the text members
     *  for image references, walk 3 stages only the referenced
     *  images, walk 4 converts one text member at a time and drops
     *  its string right after. The old shape kept every decoded
     *  string in a list until the whole archive was read — up to
     *  ~1 GiB of UTF-16 for a 512 MiB archive — before the note
     *  cap applied.
     *
     *  [imageBudget] is the aggregate extracted-image budget for the
     *  whole selection (Codex R11-F1): callers parsing several ZIPs
     *  share one instance, so the staged images across all of them
     *  stay under [ImportCaps.MAX_EXTRACTED_IMAGE_BYTES]. A single
     *  ZIP parsed on its own gets a fresh budget, which changes
     *  nothing for it — one archive's image walk is already bounded
     *  to 512 MiB by its own uncompressed quota.
     *
     *  [externalResolver] is the selection-level resolver (Codex
     *  R22-F1): a Markdown selection may also hold separately picked
     *  images, and a ZIP note referencing one of those used to import
     *  with the reference unresolved, because the ZIP built its
     *  resolver from the archive alone. The selection resolver is the
     *  fallback behind the archive resolver — the archive wins on
     *  conflict, and a name ambiguous in the archive stays
     *  unresolved (see [combineImportImageResolvers]). A ZIP parsed
     *  on its own passes nothing, which changes nothing for it.
     *
     *  [alreadyImportedNotes] is the selection-level running total
     *  (Codex R27-F1): how many notes earlier archives of this
     *  selection already contributed. Walk 2's pre-scan and walk 4's
     *  note-cap check both count against it, so a later archive
     *  neither scans refs for members past the run cap nor converts
     *  them. A single-ZIP parse passes nothing and is unchanged.
     *
     *  [alreadyRetainedTextChars] is the selection-level running
     *  total (Codex R28-F4): how many retained text chars earlier
     *  archives of this selection already contributed (their
     *  converted titles + bodies, as [ImportParseResult] counts
     *  them). Walk 2's pre-scan simulates the remaining aggregate
     *  budget against it, so a later archive neither scans refs for
     *  members the merge will refuse on the budget nor stages their
     *  images. A single-ZIP parse passes nothing and is unchanged. */
    fun parseImportZip(
        zipFile: File,
        stagingDir: File,
        imageBudget: ExtractedImageBudget = ExtractedImageBudget(),
        externalResolver: ImportImageResolver? = null,
        alreadyImportedNotes: Int = 0,
        alreadyRetainedTextChars: Long = 0,
    ): ImportParseResult {
        val result = ImportParseResult()
        if (!isZipFile(zipFile)) {
            result.skippedFiles += SkippedImportFile(
                "ZIP archive",
                "That file couldn't be read as a ZIP archive.",
            )
            return result
        }
        stagingDir.mkdirs()
        val reader = ZipImportReader(zipFile)
        val textMembers = LinkedHashMap<String, Long>()
        val imageFiles = LinkedHashMap<String, File>()
        val usedNames = mutableSetOf<String>()
        // The inventory is bounded by the same retained-name budget
        // as the reader's own list (R3-F3): names past the budget are
        // dropped one by one — never collected, never imported — and
        // the truncation is reported below, so a quota-compliant
        // archive of many long names can't exhaust the heap through
        // the inventory alone. The budget check mirrors
        // ZipImportReader.retainName: a name that doesn't fit is
        // skipped, later names are still tried.
        var textMemberNameBytes = 0L
        var inventoryTruncated = false
        var entriesSeen = 0
        try {
            reader.walk { entry ->
                entriesSeen++
                if (entry.name.endsWith("/")) return@walk
                if (isMarkdownTextEntry(entry.name)) {
                    val nameBytes = entry.name.toByteArray(Charsets.UTF_8).size.toLong()
                    if (textMemberNameBytes + nameBytes > BackupQuota.MAX_RETAINED_NAME_BYTES) {
                        inventoryTruncated = true
                        return@walk
                    }
                    textMemberNameBytes += nameBytes
                    textMembers.putIfAbsent(
                        entry.name,
                        if (entry.time > 0) entry.time else 0L,
                    )
                }
            }
            // A file with a valid local-header signature that yields
            // no entries at all is truncated or corrupt, not an empty
            // archive (Codex R11-F2): ZipInputStream returns null for
            // the first entry without throwing, so without this the
            // run would report a normal "Imported 0 notes". A valid
            // empty archive (end-of-central-directory record only)
            // legitimately yields no entries.
            if (entriesSeen == 0 && !isEmptyZipArchive(zipFile)) {
                result.skippedFiles += SkippedImportFile(
                    "ZIP archive",
                    "That file couldn't be read as a ZIP archive.",
                )
                return result
            }
            // Reference pre-scan (Codex R14-F1): walk 3 used to stage
            // EVERY image entry and charge it against the shared
            // budget before walk 4 discovered which images any note
            // actually references — an earlier ZIP full of
            // unreferenced images could exhaust the budget and leave
            // a later ZIP's referenced images unstaged. Collect the
            // referenced paths first and stage only those. Both the
            // exact normalised targets and their basenames are kept:
            // the resolver falls back to a unique basename match, so
            // a reference to `pic.png` can resolve to
            // `images/pic.png`. The kept references are themselves
            // bounded (Codex R20-F1, see ReferencedImageCollector):
            // past the bound a reference is not collected, so its
            // image is not staged and the reference stays unresolved.
            // A member past the char bound is
            // skipped by walk 4 anyway, so its references don't
            // matter; a `.txt` member goes through PlainTextCodec,
            // which never resolves images, so it is not scanned. An
            // image no note mentions is never staged and never
            // counted — there is nothing to report about it.
            val referenced = ReferencedImageCollector()
            val scannedText = HashSet<String>()
            // (Codex R27-F1): the pre-scan must apply the same
            // eligibility walk 4 applies, against the same running
            // totals — the note cap counts notes already imported
            // from earlier archives of this selection plus the
            // members this archive will convert. Without it, an
            // earlier archive with >5,000 members gets its
            // beyond-cap members' refs collected and their images
            // staged against the shared selection budget, starving
            // a later ZIP's importable notes.
            // (Codex R28-F4): the aggregate budget is simulated the
            // same way — the remaining budget is the full cap minus
            // the selection-level total from earlier archives
            // ([alreadyRetainedTextChars], converted titles + bodies
            // as the engine counts them) minus what this archive's
            // pre-scan already accepted. A member is charged what
            // walk 4's addNote will charge for it (Codex R49-F1):
            // the converted size the estimator bounds — the ACTUAL
            // title (front-matter, consumed H1, or file name) plus
            // the converted body — plus its tag names; for .txt,
            // the estimator's file-name title plus converted body
            // (a .txt note has no tags). The estimator returns
            // UTF-8 bytes, an upper bound on the converted note
            // (pinned by MarkdownImportTest's
            // estimateNeverUnderstatesConvertedNote), and bytes are
            // never fewer than the UTF-16 units addNote counts, so
            // a charge never understates walk 4's. A member whose
            // charge would push the simulated total past the cap is
            // one walk 4 (or the merge) refuses on the budget: it
            // is not scanned and not charged, exactly as walk 4
            // charges nothing for it. Before R49-F1 the charge was
            // the RAW body length plus the file-name title's length
            // — it understated walk 4 twice over (escaping and
            // block markup expand the rendered body past the raw
            // length, and a front-matter title was reserved at the
            // file name's length though it can be arbitrarily
            // long), so a refused member's images were staged
            // against the shared selection budget and later,
            // importable notes' images went unresolved. The
            // simulation is exact for the earlier archives' share
            // (converted, counted by the engine) and for the read
            // decision (raw length); within this archive it can
            // over-scan by the estimator's own over-count (the H1
            // counted as both title and body, bytes counted for
            // non-ASCII text) — an unresolved reference, counted,
            // never a lost note.
            var eligibleScanned = alreadyImportedNotes
            var scannedTextChars = 0L
            reader.walk { entry ->
                if (entry.name !in textMembers) return@walk
                if (!scannedText.add(entry.name)) return@walk
                if (eligibleScanned >= ImportCaps.MAX_IMPORT_NOTES) return@walk
                val base = importBaseName(entry.name)
                // The simulated remaining budget for this member —
                // the same shape as walk 4's per-member remaining,
                // with the selection-level total in place of the
                // local result's.
                val remaining = (ImportCaps.MAX_AGGREGATE_TEXT_CHARS -
                    alreadyRetainedTextChars - scannedTextChars -
                    titleFromFileName(base).length).coerceAtLeast(0L)
                if (isPlainTextEntry(entry.name)) {
                    // No image refs to scan, but the member still
                    // becomes a note and consumes aggregate budget —
                    // read it against the same simulated remaining,
                    // so a .txt member walk 4 refuses on the budget
                    // doesn't leave the simulation overstated for
                    // later members.
                    when (val read = entry.readTextUpToChars(
                        remaining,
                        ImportCaps.MAX_IMPORT_FILE_BYTES,
                    )) {
                        is CharBoundedText.Ok -> {
                            // (Codex R49-F1): charge the converted
                            // size, not the raw length — a .txt body
                            // escapes through PlainTextCodec just
                            // like a Markdown body (& → &amp; is 5x),
                            // so the raw length understates walk 4's
                            // charge the same way. The estimator's
                            // .txt bound is the file-name title plus
                            // the converted body; a .txt note has no
                            // tags. A member the estimator refuses
                            // per-note, or whose charge would push
                            // the simulated total past the cap, is
                            // one walk 4 refuses too — it is charged
                            // nothing, as walk 4 charges nothing.
                            val estimate = estimateConvertedNoteBytes(
                                MarkdownFileInput(
                                    name = base,
                                    path = entry.name,
                                    text = read.text,
                                    lastModified = textMembers[entry.name] ?: 0L,
                                ),
                            )
                            if (estimate <= ImportCaps.MAX_NOTE_CONTENT_BYTES &&
                                alreadyRetainedTextChars + scannedTextChars + estimate <=
                                ImportCaps.MAX_AGGREGATE_TEXT_CHARS
                            ) {
                                scannedTextChars += estimate
                                eligibleScanned++
                            }
                        }
                        else -> { /* walk 4 refuses this member too */ }
                    }
                    return@walk
                }
                when (val read = entry.readTextUpToChars(
                    remaining,
                    ImportCaps.MAX_IMPORT_FILE_BYTES,
                )) {
                    is CharBoundedText.Ok -> {
                        // The per-note bound, estimated before the
                        // scan: appendParsedFile refuses this member
                        // on the same estimate, so scanning its refs
                        // would stage images for a note that never
                        // imports.
                        val input = MarkdownFileInput(
                            name = base,
                            path = entry.name,
                            text = read.text,
                            lastModified = textMembers[entry.name] ?: 0L,
                        )
                        val estimate = estimateConvertedNoteBytes(input)
                        if (estimate > ImportCaps.MAX_NOTE_CONTENT_BYTES) return@walk
                        // Front matter is metadata, never body
                        // (Codex R23-F2): parseFrontMatter strips it
                        // before rendering, so an image reference
                        // inside a front-matter field must not be
                        // staged against the shared extracted-image
                        // budget — it never appears in an imported
                        // note. Scan the body the converter renders
                        // (same BOM strip as markdownFileToImportedNote);
                        // a member with no front matter is unchanged,
                        // since parseFrontMatter returns its text whole.
                        val frontMatter = parseFrontMatter(
                            read.text.removePrefix("\uFEFF"),
                        )
                        // (Codex R49-F1): the simulated charge is what
                        // walk 4's addNote will charge — the
                        // estimator's converted bound (the ACTUAL
                        // title: front-matter, consumed H1, or file
                        // name — plus the converted body) plus the tag
                        // names. (Codex R36-F1's retained-size rule
                        // still holds: front matter is stripped before
                        // rendering, so a member with huge front
                        // matter and a tiny body is charged almost
                        // nothing — the estimator reads only its title
                        // out of the front matter, never its raw
                        // length.) A member whose charge would push
                        // the simulated total past the cap is one
                        // walk 4 (or the merge) refuses on the
                        // aggregate budget: its refs are not collected
                        // and it is not charged, exactly as walk 4
                        // charges nothing for it.
                        val charge = estimate +
                            frontMatter.tags.sumOf { it.length.toLong() }
                        if (alreadyRetainedTextChars + scannedTextChars + charge >
                            ImportCaps.MAX_AGGREGATE_TEXT_CHARS
                        ) return@walk
                        // The consumed H1 is a title, never body
                        // (Codex R41-F1): the converter drops the
                        // leading H1 line when front matter didn't
                        // provide a title, so an image reference
                        // inside it is never embedded — but the
                        // pre-scan used to collect it anyway and
                        // charge its staging against the shared
                        // image budget, starving later members'
                        // real images. Strip exactly the line the
                        // converter consumes ([LEADING_H1], shared
                        // so the two can never drift apart), and
                        // only when it consumes one: with a
                        // front-matter title the H1 stays in the
                        // body and its references are real.
                        val scanBody = if (frontMatter.title == null) {
                            LEADING_H1.find(frontMatter.body)?.let {
                                frontMatter.body.substring(it.range.last + 1)
                            } ?: frontMatter.body
                        } else {
                            frontMatter.body
                        }
                        collectImageRefs(
                            entry.name,
                            scanBody,
                            referenced,
                        )
                        scannedTextChars += charge
                        eligibleScanned++
                    }
                    else -> { /* walk 4 skips this member too */ }
                }
            }
            reader.walk { entry ->
                if (entry.name.endsWith("/")) return@walk
                if (isMarkdownTextEntry(entry.name)) return@walk
                if (importMimeFromName(entry.name) == null) return@walk
                // A path that occurs twice in the archive is ambiguous
                // (Codex R13-F2): staging it would silently embed
                // whichever payload appears last, so it is not staged
                // at all — references to it stay unresolved (counted
                // at the reference) instead.
                if (entry.name in reader.duplicateEntryNames) return@walk
                // Only stage images a note references (Codex R14-F1):
                // the pre-scan collected the exact targets and the
                // basenames the resolver can fall back to. Anything
                // else is never staged — it would only consume the
                // shared budget for a file no note mentions.
                if (normaliseImportPath(entry.name) !in referenced.targets &&
                    importBaseName(entry.name.replace('\\', '/')).lowercase() !in referenced.basenames
                ) return@walk
                val staged = File(stagingDir, stagingFileName(entry.name, usedNames))
                // The copy cap is the smaller of the 100 MiB per-file
                // cap and what the selection's aggregate extracted-
                // image budget still allows (Codex R11-F1): several
                // highly compressible ZIPs must not expand into GiB
                // of staged images in the shared staging directory.
                // (Codex R17-F2, defence in depth): staging can still
                // fail for a reason the name bound doesn't cover, so
                // an IOException here leaves the reference unresolved
                // (counted at the reference) instead of failing the
                // whole import. Only IOException is caught: a quota
                // trip (ImportZipException) must still abort the
                // archive, as designed.
                val stagedOk = try {
                    entry.copyToUpTo(staged, imageBudget.nextImageCapBytes())
                } catch (e: java.io.IOException) {
                    runCatching { staged.delete() }
                    false
                }
                if (stagedOk) {
                    imageBudget.record(staged.length())
                    imageFiles[entry.name] = staged
                }
                // An unreadable/oversize image just leaves its
                // references unresolved (counted at the reference) —
                // and one past the run's aggregate extracted-image
                // budget is not staged at all.
            }
            // A Markdown selection may also hold separately picked
            // images (Codex R22-F1): the archive resolver alone
            // cannot see them, so the selection resolver is the
            // fallback behind it — archive wins on conflict, and a
            // name ambiguous in the archive stays unresolved (see
            // combineImportImageResolvers). The pre-scan above only
            // stages archive images, which is unchanged: picked
            // images are already staged files on disk.
            // (Codex R42-F1): duplicate paths are never staged
            // (walk 3 skips them), so they never appear in
            // imageFiles — but a reference to one must still stay
            // unresolved instead of falling through to a picked
            // image. Their names travel into the resolver's
            // ambiguity state; resolve still returns null for them
            // (no payload was staged).
            val archiveResolver = createImportImageResolver(
                imageFiles.toList(),
                reader.duplicateEntryNames,
            )
            val resolver = externalResolver?.let {
                combineImportImageResolvers(archiveResolver, it)
            } ?: archiveResolver
            // A text member that occurs twice is the same logical file
            // (Codex R13-F2): the first occurrence wins, so the walk
            // never converts — and double-counts — the same path.
            val convertedText = HashSet<String>()
            reader.walk { entry ->
                if (entry.name !in textMembers) return@walk
                if (!convertedText.add(entry.name)) return@walk
                val base = importBaseName(entry.name)
                // The running total counts notes already imported
                // from earlier archives of this selection (Codex
                // R27-F1): the cap is per run, not per archive.
                if (alreadyImportedNotes + result.notes.size >= ImportCaps.MAX_IMPORT_NOTES) {
                    result.skippedFiles += SkippedImportFile(
                        base,
                        "Import limit reached (5,000 notes per run).",
                    )
                    return@walk
                }
                // The per-file cap (100 MiB) and the aggregate text
                // budget are both checked BEFORE the member is
                // materialized (Codex R7-F3): readTextUpToChars stops
                // retaining as soon as the decoded text exceeds the
                // remaining budget, and a >100 MiB member still
                // reports the per-file reason. (The old code buffered
                // the member under the 100 MiB byte cap, then checked
                // String.length — a multibyte file could pass the
                // character count while far exceeding 100 MiB on disk
                // — and an over-budget member was buffered in full
                // before the budget check rejected it.) The remaining
                // budget is the right bound here, unlike the Keep
                // parser (Codex R10-F2): for Markdown the raw text
                // IS the body, so it fairly estimates the converted
                // size — a Keep JSON's overhead is not body text.
                // The filename title is the one title source outside
                // the member text (a front-matter/H1 title is inside
                // it), so room is reserved for it too (Codex R19-F1)
                // — conservative by at most the filename length when
                // front matter/H1 supplies the title instead. The
                // true check is addNote, which counts title + body.
                val remaining = (ImportCaps.MAX_AGGREGATE_TEXT_CHARS - result.retainedTextChars -
                    titleFromFileName(base).length)
                    .coerceAtLeast(0L)
                when (val read = entry.readTextUpToChars(remaining, ImportCaps.MAX_IMPORT_FILE_BYTES)) {
                    is CharBoundedText.OverBytes -> {
                        result.skippedFiles += SkippedImportFile(
                            base,
                            "File is larger than 100 MB.",
                        )
                        return@walk
                    }
                    is CharBoundedText.OverChars -> {
                        result.skippedFiles += SkippedImportFile(
                            base,
                            ImportCaps.AGGREGATE_TEXT_BUDGET_REASON,
                        )
                        return@walk
                    }
                    is CharBoundedText.Ok -> {
                        val input = MarkdownFileInput(
                            name = base,
                            path = entry.name,
                            text = read.text,
                            lastModified = textMembers[entry.name] ?: 0L,
                            // (Codex R26-F2): the run dedupe identity
                            // for a dateless ZIP member is its full
                            // entry path plus a content hash — the
                            // basename alone collapses
                            // home/notes/meeting.md and
                            // work/notes/meeting.md, and the path
                            // alone collapses the same entry path in
                            // two archives of one selection. The text
                            // is already in memory here.
                            sourceIdentity = "${entry.name}\n${contentHashHex(read.text)}",
                        )
                        appendParsedFile(result, input, resolver)
                    }
                }
            }
        } catch (e: ImportZipException) {
            // A quota trip aborts the whole archive: nothing partial
            // is applied from it (same contract as the backup reader).
            // (Codex R50-F1): clearing the notes alone left the
            // retained-text count behind, and the merge absorbs it —
            // abandon() resets both, so the archive contributes
            // nothing to the run's aggregate budget.
            result.abandon()
            result.skippedFiles += SkippedImportFile("ZIP archive", e.message ?: "That ZIP couldn't be read.")
            return result
        } catch (e: java.io.IOException) {
            // (Codex R43-F1): a ZIP with a valid header but corrupt
            // entry data (bad CRC, truncated deflate stream) makes
            // ZipInputStream throw mid-read. Only quota trips were
            // translated, so the raw IOException escaped parseImportZip
            // and failed the whole multi-file selection, losing its
            // other valid picks. A read error abandons this archive
            // like a quota trip does — nothing partial is applied —
            // but it is reported as a damaged file, not a quota trip.
            // ImportZipException is not an IOException, so the two
            // catches cannot overlap: a quota trip still takes the
            // branch above. (Codex R50-F1): abandon() resets the
            // retained-text count with the notes, as above.
            result.abandon()
            result.skippedFiles += SkippedImportFile("ZIP archive", "That ZIP file is damaged and couldn't be read.")
            return result
        }
        if (inventoryTruncated) {
            result.skippedFiles += SkippedImportFile(
                "ZIP archive",
                "The archive's file-name list was truncated, so some notes may not have been imported.",
            )
        }
        return result
    }
}

/** The first four bytes of [file] as a little-endian int, or null
 *  when the file is shorter than four bytes or unreadable. */
private fun zipMagic(file: File): Int? {
    if (!file.exists()) return null
    return try {
        file.inputStream().use { input ->
            val sig = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = input.read(sig, read, 4 - read)
                if (n < 0) break
                read += n
            }
            if (read < 4) {
                null
            } else {
                (sig[0].toInt() and 0xFF) or
                    ((sig[1].toInt() and 0xFF) shl 8) or
                    ((sig[2].toInt() and 0xFF) shl 16) or
                    ((sig[3].toInt() and 0xFF) shl 24)
            }
        }
    } catch (e: Exception) {
        null
    }
}

/** ZIP magic check: ZipInputStream silently yields zero entries for a
 *  non-ZIP file, which would otherwise look like an empty archive.
 *  A bare "PK" prefix is not enough (Codex R11-F2): a corrupt or
 *  truncated file starting with PK but with no valid ZIP structure
 *  passes a two-byte check and then yields zero entries with no
 *  unreadable-file reason. Require a complete supported signature at
 *  offset 0 — the local-file header PK\x03\x04 that every non-empty
 *  ZIP starts with, or the end-of-central-directory record PK\x05\x06
 *  that a valid empty archive consists of. */
internal fun isZipFile(file: File): Boolean {
    return when (zipMagic(file)) {
        0x04034b50, 0x06054b50 -> true
        else -> false
    }
}

/** A valid empty archive is just its end-of-central-directory record
 *  (PK\x05\x06 …); anything else that yields zero entries is not a
 *  readable archive at all (Codex R11-F2). The record is 22 bytes
 *  minimum (Codex R46-F1): a file truncated after the 4-byte signature
 *  passes a magic-only check and then reports a successful zero-note
 *  import. Require the complete record — the 22 fixed bytes with zero
 *  total entries, and a total size of exactly 22 + the comment length
 *  the record itself declares, so an empty archive with a comment is
 *  still accepted when complete. */
internal fun isEmptyZipArchive(file: File): Boolean {
    if (!file.exists()) return false
    return try {
        file.inputStream().buffered().use { input ->
            val header = ByteArray(22)
            var read = 0
            while (read < 22) {
                val n = input.read(header, read, 22 - read)
                if (n < 0) break
                read += n
            }
            if (read < 22) return false
            val magic = (header[0].toInt() and 0xFF) or
                ((header[1].toInt() and 0xFF) shl 8) or
                ((header[2].toInt() and 0xFF) shl 16) or
                ((header[3].toInt() and 0xFF) shl 24)
            if (magic != 0x06054b50) return false
            // Total entries, little-endian u16 at offset 10: an empty
            // archive has none, and a file claiming entries that yields
            // none is damaged, not empty.
            val totalEntries = (header[10].toInt() and 0xFF) or
                ((header[11].toInt() and 0xFF) shl 8)
            if (totalEntries != 0) return false
            // Comment length, little-endian u16 at offset 20: the
            // record is only complete when the file holds exactly the
            // 22 fixed bytes plus the comment it declares.
            val commentLength = (header[20].toInt() and 0xFF) or
                ((header[21].toInt() and 0xFF) shl 8)
            file.length() == 22L + commentLength
        }
    } catch (e: Exception) {
        false
    }
}
