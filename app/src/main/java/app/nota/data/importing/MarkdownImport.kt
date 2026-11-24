package app.nota.data.importing

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

/** Filesystem-safe staging name from an entry/pick name (basename
 *  only; only path separators and control characters are replaced, so
 *  the staged name stays as close to the original as possible). */
internal fun stagingFileName(rawName: String, usedNames: MutableSet<String>): String {
    val base = importBaseName(rawName.replace('\\', '/'))
        .replace(Regex("[\u0000-\u001f/\\\\]"), "_")
        .ifBlank { "file" }
    var candidate = base
    var n = 2
    while (!usedNames.add(candidate)) {
        val dot = base.lastIndexOf('.')
        candidate = if (dot > 0) "${base.substring(0, dot)}-$n${base.substring(dot)}" else "$base-$n"
        n++
    }
    return candidate
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
 * Builds a resolver over a path → staged-file map (picked files or ZIP
 * entries). Exact normalised-path match first, then a unique basename
 * match (flat exports often lose the folder structure).
 */
fun createImportImageResolver(files: Map<String, File>): ImportImageResolver {
    // (normalised path, display name, staged file) per entry — matching
    // runs on the ORIGINAL paths/names, never on staging file names.
    val entries = files.map { (path, file) ->
        Triple(normaliseImportPath(path), importBaseName(path.replace('\\', '/')), file)
    }
    val byPath = entries.associate { (path, _, file) -> path to file }
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
            pick(importBaseName(target), direct)
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

/** Splits a list block into top-level lists and renders each. A kind
 *  change only starts a new list at the run's base indent: a
 *  deeper-indented item of another kind is a nested child of the item
 *  above it, not a sibling list. */
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
        val levelTasks = !list[0].ordered && list.any { it.task }
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
            val childHtml = if (children.isNotEmpty()) renderItems(children) else ""
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
        result.notes += markdownFileToImportedNote(file, resolver, result)
    }

    private fun markdownFileToImportedNote(
        file: MarkdownFileInput,
        resolver: ImportImageResolver?,
        result: ImportParseResult,
    ): ImportedNote {
        val images = mutableListOf<ImportedImage>()
        val sink = InlineImageSink(result, resolver, file.path, images)
        val fallbackStamp = if (file.lastModified > 0) file.lastModified else System.currentTimeMillis()

        if (Regex("\\.txt$", RegexOption.IGNORE_CASE).containsMatchIn(file.name)) {
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
     *  Three walks, each streaming the archive once, so at most one
     *  member's text is ever in memory: walk 1 inventories the text
     *  members (names + times only), walk 2 stages the images, walk 3
     *  converts one text member at a time and drops its string right
     *  after. The old shape kept every decoded string in a list until
     *  the whole archive was read — up to ~1 GiB of UTF-16 for a
     *  512 MiB archive — before the note cap applied. */
    fun parseImportZip(zipFile: File, stagingDir: File): ImportParseResult {
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
        try {
            reader.walk { entry ->
                if (entry.name.endsWith("/")) return@walk
                if (isMarkdownTextEntry(entry.name)) {
                    textMembers.putIfAbsent(
                        entry.name,
                        if (entry.time > 0) entry.time else 0L,
                    )
                }
            }
            reader.walk { entry ->
                if (entry.name.endsWith("/")) return@walk
                if (isMarkdownTextEntry(entry.name)) return@walk
                if (importMimeFromName(entry.name) == null) return@walk
                val staged = File(stagingDir, stagingFileName(entry.name, usedNames))
                if (entry.copyToUpTo(staged, ImportCaps.MAX_IMPORT_FILE_BYTES)) {
                    imageFiles[entry.name] = staged
                }
                // An unreadable/oversize image just leaves its
                // references unresolved (counted at the reference).
            }
            val resolver = createImportImageResolver(imageFiles)
            reader.walk { entry ->
                if (entry.name !in textMembers) return@walk
                val base = importBaseName(entry.name)
                if (result.notes.size >= ImportCaps.MAX_IMPORT_NOTES) {
                    result.skippedFiles += SkippedImportFile(
                        base,
                        "Import limit reached (5,000 notes per run).",
                    )
                    return@walk
                }
                // The per-file cap is enforced in BYTES before the
                // member is retained: readBytesUpTo returns null
                // past 100 MiB without buffering the whole member
                // (the old code buffered under the 512 MiB archive
                // cap, then checked String.length — a multibyte file
                // could pass the character count while far exceeding
                // 100 MiB on disk).
                val bytes = entry.readBytesUpTo(ImportCaps.MAX_IMPORT_FILE_BYTES)
                if (bytes == null) {
                    result.skippedFiles += SkippedImportFile(
                        base,
                        "File is larger than 100 MB.",
                    )
                    return@walk
                }
                val input = MarkdownFileInput(
                    name = base,
                    path = entry.name,
                    text = bytes.toString(Charsets.UTF_8),
                    lastModified = textMembers[entry.name] ?: 0L,
                )
                appendParsedFile(result, input, resolver)
            }
        } catch (e: ImportZipException) {
            // A quota trip aborts the whole archive: nothing partial
            // is applied from it (same contract as the backup reader).
            result.notes.clear()
            result.skippedFiles += SkippedImportFile("ZIP archive", e.message ?: "That ZIP couldn't be read.")
            return result
        }
        return result
    }
}

/** ZIP magic check: ZipInputStream silently yields zero entries for a
 *  non-ZIP file, which would otherwise look like an empty archive. */
internal fun isZipFile(file: File): Boolean {
    if (!file.exists() || file.length() < 2) return false
    return file.inputStream().use { input ->
        val first = input.read()
        val second = input.read()
        first == 'P'.code && second == 'K'.code
    }
}
