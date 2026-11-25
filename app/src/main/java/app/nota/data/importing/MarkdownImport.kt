package app.nota.data.importing

import app.nota.data.backup.BackupQuota
import app.nota.data.text.PlainTextCodec
import java.io.ByteArrayOutputStream
import java.io.File

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
    val parts = path.split('/').filter { it.isNotEmpty() }
    return parts.lastOrNull() ?: path
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
        .replace(Regex("[\u0000-\u001f/\\]"), "_")
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
    while (end > 0 && bytes[end - 1] and 0xC0.toByte() == 0x80.toByte()) end--
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

private fun resolveRef(fromPath: String, ref: String): String {
    val decoded = percentDecode(ref)
    if (decoded.startsWith("/")) return normaliseImportPath(decoded)
    val dir = fromPath.split('/').dropLast(1).joinToString("/")
    return normaliseImportPath(if (dir.isNotEmpty()) "$dir/$decoded" else decoded)
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
 */
fun interface ImportImageResolver {
    fun resolve(fromPath: String, ref: String): ResolvedImportImage?
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
 */
fun createImportImageResolver(files: List<Pair<String, File>>): ImportImageResolver {
    // (normalised path, display name, staged file) per entry — matching
    // runs on the ORIGINAL paths/names, never on staging file names.
    val entries = files.distinct().map { (path, file) ->
        Triple(normaliseImportPath(path), importBaseName(path.replace('\\', '/')), file)
    }
    val byPath = entries.groupBy { it.first }
    fun pick(name: String, file: File): ResolvedImportImage = ResolvedImportImage(
        name = name,
        mimeType = importMimeFromName(name) ?: "",
        stagedFile = file,
        sizeBytes = file.length(),
    )
    return ImportImageResolver { fromPath, ref ->
        val target = resolveRef(fromPath, ref)
        val direct = byPath[target]
        if (direct != null) {
            if (direct.size == 1) pick(importBaseName(target), direct[0].third) else null
        } else {
            val base = importBaseName(target).lowercase()
            val matches = entries.filter { it.second.lowercase() == base }
            if (matches.size == 1) pick(matches[0].second, matches[0].third) else null
        }
    }
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

    val lines = block.split(Regex("\r?\n"))
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val kv = Regex("^([A-Za-z][\\w-]*)\\s*:\\s*(.*)$").find(line)
        if (kv == null) {
            i++
            continue
        }
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
                    tags += value.substring(1, value.length - 1).split(',')
                        .map { stripQuotes(it.trim()) }.filter { it.isNotEmpty() }
                } else if (value.isNotEmpty()) {
                    tags += value.split(',')
                        .map { stripQuotes(it.trim()) }.filter { it.isNotEmpty() }
                } else {
                    // Block list:
                    //   tags:
                    //     - one
                    //     - two
                    var j = i + 1
                    while (j < lines.size) {
                        val item = Regex("^\\s*-\\s+(.*)$").find(lines[j]) ?: break
                        val tag = stripQuotes(item.groupValues[1].trim())
                        if (tag.isNotEmpty()) tags += tag
                        i = j
                        j++
                    }
                }
            }
        }
        i++
    }
    return FrontMatter(title, createdAt, tags, body)
}

/* ---------------- Inline rendering (escape-first) ---------------- */

private class InlineImageSink(
    private val result: ImportParseResult,
    private val resolver: ImportImageResolver?,
    private val fromPath: String,
    private val images: MutableList<ImportedImage>,
) {
    private var imageSeq = 0

    fun addImage(alt: String, ref: String): String {
        // Remote images are never hot-linked into a note: Nota only
        // embeds local attachments, so an http(s)/data reference keeps
        // its alt text and is counted.
        if (Regex("^(https?:|data:)", RegexOption.IGNORE_CASE).containsMatchIn(ref) || resolver == null) {
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
        val token = "%%NOMA_IMPORT_IMAGE_${imageSeq++}%%"
        images += ImportedImage(
            name = resolved.name,
            mimeType = resolved.mimeType,
            stagedFile = resolved.stagedFile,
            sizeBytes = resolved.sizeBytes,
            alt = alt,
            token = token,
            deleteAfterUse = false, // resolver files can be shared across notes
        )
        return token
    }
}

private val CODE_SPAN = Regex("(`[^`]+`)")
private val INLINE_IMAGE =
    Regex("""!\[([^\]]*)\]\(\s*([^)\s]+)(?:\s+&quot;[^&]*&quot;)?\s*\)""")

/**
 * Image references in raw (unescaped) Markdown text, for the
 * reference pre-scan in [MarkdownImporter.parseImportZip] (Codex
 * R14-F1). Deliberately more permissive than [INLINE_IMAGE], which
 * runs on escaped text (its title is `&quot;…&quot;`): the pre-scan
 * must never MISS a reference the converter would use — an extra
 * staged image costs a little budget, a missed one loses the embed.
 * Code spans are not carved out here, so a reference inside backticks
 * may stage an image that is never embedded — harmless.
 */
private val RAW_IMAGE_REF =
    Regex("""!\[([^\]]*)\]\(\s*([^)\s]+)""")

/** Collects the image targets referenced by one Markdown member:
 *  exact normalised paths plus their basenames (the resolver falls
 *  back to a unique basename match). Remote references are never
 *  resolved, so they are not collected. */
private fun collectImageRefs(
    fromPath: String,
    text: String,
    targets: MutableSet<String>,
    basenames: MutableSet<String>,
) {
    for (match in RAW_IMAGE_REF.findAll(text)) {
        val ref = match.groupValues[2]
        if (Regex("^(https?:|data:)", RegexOption.IGNORE_CASE).containsMatchIn(ref)) continue
        val target = resolveRef(fromPath, ref)
        if (target.isEmpty()) continue
        targets += target
        basenames += importBaseName(target).lowercase()
    }
}
private val INLINE_LINK =
    Regex("""\[([^\]]+)\]\(\s*([^)\s]+)(?:\s+&quot;[^&]*&quot;)?\s*\)""")
private val SAFE_LINK = Regex("^(https?:|mailto:)", RegexOption.IGNORE_CASE)

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
    text = INLINE_IMAGE.replace(text) { match ->
        images.addImage(
            unescapeEntities(match.groupValues[1]),
            unescapeEntities(match.groupValues[2]),
        )
    }

    // Links: only http/https/mailto survive as links; anything else
    // degrades to its text (a file:// or javascript: ref is never emitted).
    text = INLINE_LINK.replace(text) { match ->
        val label = match.groupValues[1]
        val rawHref = unescapeEntities(match.groupValues[2])
        if (SAFE_LINK.containsMatchIn(rawHref)) {
            "<a href=\"${escapeImportAttr(rawHref)}\">$label</a>"
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
 */
private fun confirmLetteredStart(
    lines: List<String>,
    idx: Int,
    candidate: LetteredCandidate,
): String? {
    val types = mutableListOf<String>()
    if (candidate.roman != null) types += if (candidate.upper) "I" else "i"
    if (candidate.alpha != null) types += if (candidate.upper) "A" else "a"
    for (j in idx + 1 until lines.size) {
        val raw = lines[j]
        if (raw.isBlank()) return null
        if (LIST_LINE.containsMatchIn(raw)) {
            // A bullet/numeric line: only a deeper-indented (nested)
            // one can sit between a candidate and its successor.
            if (lineIndent(raw) <= candidate.indent) return null
            continue
        }
        val next = parseLetteredCandidate(raw)
        if (next != null) {
            if (next.indent < candidate.indent) return null
            if (next.indent > candidate.indent) continue // nested content, keep scanning
            for (type in types) {
                val first = letteredValue(candidate, type)
                val second = letteredValue(next, type)
                if (first != null && second != null && second == first + 1) return type
            }
            return null
        }
        // Ordinary line: wrapped item text is indented deeper than the
        // candidate; anything at or above its indent ends the search.
        if (lineIndent(raw) <= candidate.indent) return null
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
    val type = confirmLetteredStart(lines, idx, candidate) ?: return LetteredDecision.NotAnItem
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
    val blocks = mutableListOf<String>()
    var i = 0

    fun isBlank(line: String) = line.isBlank()
    // A lettered/roman line opens a list block only when sequence-confirmed.
    fun letteredStartAt(idx: Int): String? {
        val candidate = parseLetteredCandidate(lines[idx]) ?: return null
        return confirmLetteredStart(lines, idx, candidate)
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
                    when (val decision = letteredItem(lines, i, candidate, items)) {
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
        while (idx <= text.length) {
            val end = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
            val inFrontMatter = lineNo <= fmEndLine
            // Per-line byte counts. Front-matter lines count only
            // toward the title bound.
            var lineUtf8 = 0L
            var i = idx
            while (i < end) {
                val c = text[i]
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
        // expansion is bounded separately, see below).
        if (result.retainedTextChars + file.text.length > ImportCaps.MAX_AGGREGATE_TEXT_CHARS) {
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
                ImportCaps.NOTE_TOO_LARGE_REASON,
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
            )
        }

        val frontMatter = parseFrontMatter(file.text.removePrefix("\uFEFF"))
        var body = frontMatter.body
        var title = frontMatter.title

        // A leading H1 becomes the title when front matter didn't
        // provide one — and is consumed, not duplicated in the body.
        if (title == null) {
            val h1 = Regex("^\\s*#\\s+([^\\n]+)").find(body)
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
     *  to 512 MiB by its own uncompressed quota. */
    fun parseImportZip(
        zipFile: File,
        stagingDir: File,
        imageBudget: ExtractedImageBudget = ExtractedImageBudget(),
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
            // `images/pic.png`. A member past the char bound is
            // skipped by walk 4 anyway, so its references don't
            // matter; a `.txt` member goes through PlainTextCodec,
            // which never resolves images, so it is not scanned. An
            // image no note mentions is never staged and never
            // counted — there is nothing to report about it.
            val referencedTargets = HashSet<String>()
            val referencedBasenames = HashSet<String>()
            val scannedText = HashSet<String>()
            reader.walk { entry ->
                if (entry.name !in textMembers) return@walk
                if (!scannedText.add(entry.name)) return@walk
                if (isPlainTextEntry(entry.name)) return@walk
                when (val read = entry.readTextUpToChars(
                    ImportCaps.MAX_AGGREGATE_TEXT_CHARS,
                    ImportCaps.MAX_IMPORT_FILE_BYTES,
                )) {
                    is CharBoundedText.Ok ->
                        collectImageRefs(
                            entry.name,
                            read.text,
                            referencedTargets,
                            referencedBasenames,
                        )
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
                if (normaliseImportPath(entry.name) !in referencedTargets &&
                    importBaseName(entry.name.replace('\\', '/')).lowercase() !in referencedBasenames
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
            val resolver = createImportImageResolver(imageFiles.toList())
            // A text member that occurs twice is the same logical file
            // (Codex R13-F2): the first occurrence wins, so the walk
            // never converts — and double-counts — the same path.
            val convertedText = HashSet<String>()
            reader.walk { entry ->
                if (entry.name !in textMembers) return@walk
                if (!convertedText.add(entry.name)) return@walk
                val base = importBaseName(entry.name)
                if (result.notes.size >= ImportCaps.MAX_IMPORT_NOTES) {
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
                val remaining = (ImportCaps.MAX_AGGREGATE_TEXT_CHARS - result.retainedTextChars)
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
                        )
                        appendParsedFile(result, input, resolver)
                    }
                }
            }
        } catch (e: ImportZipException) {
            // A quota trip aborts the whole archive: nothing partial
            // is applied from it (same contract as the backup reader).
            result.notes.clear()
            result.skippedFiles += SkippedImportFile("ZIP archive", e.message ?: "That ZIP couldn't be read.")
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
 *  readable archive at all (Codex R11-F2). */
internal fun isEmptyZipArchive(file: File): Boolean {
    return zipMagic(file) == 0x06054b50
}
