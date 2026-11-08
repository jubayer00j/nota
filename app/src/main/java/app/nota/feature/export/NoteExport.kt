package app.nota.feature.export

import app.nota.data.html.ChartSegment
import app.nota.data.html.CodeSegment
import app.nota.data.html.DividerSegment
import app.nota.data.html.DocSegment
import app.nota.data.html.FileSegment
import app.nota.data.html.ImageSegment
import app.nota.data.html.NoteDocument
import app.nota.data.html.OpaqueSegment
import app.nota.data.html.QuoteSegment
import app.nota.data.html.TableSegment
import app.nota.data.html.TaskListSegment
import app.nota.data.html.TextSegment

/**
 * Per-note export (Phase 6, Per-note Export #1): turn one note's
 * live document into a portable file — a standalone HTML document,
 * Markdown, or plain text — that can be shared with anyone and
 * opened anywhere, no Nota required. Noma 1.x parity: its
 * note-export.ts is the semantic reference for everything here.
 *
 * The converters walk the [DocSegment] list the editor holds (what
 * the user sees at tap time, unsaved edits included) — never a
 * fresh database read. They are hand-rolled over a small HTML
 * fragment parser for the inline dialect text segments carry
 * (paragraphs, headings, lists, marks, links, colour spans): no
 * new parsing dependency, in the same spirit as 1.x.
 *
 * Images live outside the content as attachment rows; content
 * refers to them as `nota-attachment://<id>`. Callers pre-resolve
 * the ids a document references (see [collectAttachmentIds]) and
 * pass a sync resolver — resolved images embed as data URLs so
 * the exported file is self-contained, unresolved ones degrade
 * to an "[Image]" placeholder.
 */

enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    HTML("html", "text/html", "HTML"),
    MARKDOWN("md", "text/markdown", "Markdown"),
    TEXT("txt", "text/plain", "Plain text"),
}

/** Resolves a local attachment id to a data URL, or null when unavailable. */
typealias AttachmentResolver = (attachmentId: String) -> String?

data class NoteExportArtifact(
    val fileName: String,
    val mimeType: String,
    val content: String,
)

object NoteExport {

    /** Builds the final export file (name, MIME type, content) for a format. */
    fun build(
        title: String,
        segments: List<DocSegment>,
        format: ExportFormat,
        resolveImage: AttachmentResolver = { null },
    ): NoteExportArtifact {
        // Resolved values are spliced into exported markup and
        // link destinations, and the data URLs behind them are
        // built from attachment metadata a restored backup supplies
        // unvalidated — a crafted mime type can carry an attribute
        // breakout (`image/png" onerror="…`). Guard the resolver
        // once, here, so every format and every sink consumes the
        // same checked resolver.
        val guarded = guardResolver(resolveImage)
        val content = when (format) {
            ExportFormat.HTML -> noteToHtml(title, segments, guarded)
            ExportFormat.MARKDOWN -> noteToMarkdown(title, segments, guarded)
            ExportFormat.TEXT -> noteToPlainText(title, segments, guarded)
        }
        return NoteExportArtifact(
            fileName = exportFilename(title, format),
            mimeType = format.mimeType,
            content = content,
        )
    }

    /**
     * Wraps a resolver so only markup-safe values pass: a real
     * `data:<mime>;base64,<payload>` never contains a quote, an
     * angle bracket, whitespace or a control character, so a
     * resolved value carrying any of those is treated as
     * unresolved (null → the "[Image]" placeholder paths).
     */
    private fun guardResolver(resolve: AttachmentResolver): AttachmentResolver = { id ->
        resolve(id)?.takeIf { value ->
            value.none { ch ->
                ch == '"' || ch == '\'' || ch == '<' || ch == '>' ||
                    ch.isWhitespace() || ch.isISOControl()
            }
        }
    }

    /** The note's display title (blank falls back, as everywhere in Nota). */
    fun exportTitle(title: String): String = title.trim().ifBlank { "Untitled note" }

    /**
     * A filesystem-safe file name from the note title: path-hostile
     * and control characters stripped, whitespace collapsed, capped
     * at ~80 characters AND at 255 UTF-8 bytes for the whole name,
     * extension included (see below). Unicode letters survive — a
     * Bengali title stays Bengali. (1.x's exportFilename, verbatim
     * rule, plus the byte cap.)
     */
    fun exportFilename(title: String, format: ExportFormat): String {
        val cleaned = exportTitle(title)
            .replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001F\\u007F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', ' ')
        var capped = cleaned.codePoints().limit(80).toArray()
            .let { String(it, 0, it.size) }
            .trim()
        // The character cap alone can still overflow a disk: file
        // name components are limited to 255 BYTES on the
        // filesystems Android uses, and 80 four-byte emoji are 320
        // bytes before the extension — the share cache write then
        // fails outright. Truncate by whole code points (never
        // splitting one) until name + extension fits, and re-trim
        // whatever the cut exposes.
        val suffix = ".${format.extension}"
        val budget = 255 - suffix.toByteArray(Charsets.UTF_8).size
        if (capped.toByteArray(Charsets.UTF_8).size > budget) {
            val out = StringBuilder()
            var bytes = 0
            var i = 0
            while (i < capped.length) {
                val cp = capped.codePointAt(i)
                val cpBytes = String(Character.toChars(cp))
                    .toByteArray(Charsets.UTF_8).size
                if (bytes + cpBytes > budget) break
                out.appendCodePoint(cp)
                bytes += cpBytes
                i += Character.charCount(cp)
            }
            capped = out.toString().trim().trimEnd('.', ' ')
        }
        return "${capped.ifBlank { "Untitled note" }}$suffix"
    }

    /**
     * Attachment ids a document references, in first-use order:
     * standalone image segments plus `nota-attachment://` sources
     * inside text, quote, table-cell and task-item HTML.
     */
    fun collectAttachmentIds(segments: List<DocSegment>): List<String> {
        val ids = LinkedHashSet<String>()
        val prefix = NoteDocument.ATTACHMENT_SRC_PREFIX
        val inlineRe = Regex("""nota-attachment://([A-Za-z0-9_-]+)""")
        fun scan(html: String) {
            for (match in inlineRe.findAll(html)) ids += match.groupValues[1]
        }
        for (segment in segments) {
            when (segment) {
                is ImageSegment -> {
                    if (segment.src.startsWith(prefix)) {
                        ids += segment.src.removePrefix(prefix)
                    }
                }
                is TextSegment -> scan(segment.html)
                is QuoteSegment -> scan(segment.innerHtml)
                is TableSegment -> segment.rows.forEach { row -> row.forEach(::scan) }
                is TaskListSegment -> segment.items.forEach { scan(it.contentHtml) }
                is OpaqueSegment -> scan(segment.rawHtml)
                else -> Unit
            }
        }
        return ids.toList()
    }

    private val CHART_TYPE_LABELS = mapOf(
        "bar" to "Bar",
        "line" to "Line",
        "area" to "Area",
        "pie" to "Pie",
        "donut" to "Donut",
        "radar" to "Radar",
        "composed" to "Composed",
    )

    /** "[Bar chart]" for a known chart type, "[Chart]" otherwise (1.x). */
    fun chartPlaceholder(segment: ChartSegment): String {
        val fromAttr = Regex("""data-chart-type\s*=\s*"([^"]+)"""")
            .find(segment.rawHtml)?.groupValues?.get(1)
        val fromPayload = Regex(""""type"\s*:\s*"([A-Za-z]+)"""")
            .find(segment.payload)?.groupValues?.get(1)
        val label = CHART_TYPE_LABELS[(fromAttr ?: fromPayload)?.lowercase()]
        return if (label != null) "[$label chart]" else "[Chart]"
    }

    // ------------------------------------------------------------ Markdown

    fun noteToMarkdown(
        title: String,
        segments: List<DocSegment>,
        resolveImage: AttachmentResolver = { null },
    ): String {
        val blocks = mutableListOf("# ${escapeMdText(exportTitle(title))}")
        for (segment in segments) blocks += mdSegment(segment, resolveImage)
        return blocks.filter { it.isNotBlank() }.joinToString("\n\n") + "\n"
    }

    private fun mdSegment(segment: DocSegment, resolve: AttachmentResolver): List<String> =
        when (segment) {
            is TextSegment -> mdBlocks(parseFragment(segment.html), resolve)
            is QuoteSegment -> {
                val inner = mdBlocks(parseFragment(segment.innerHtml), resolve)
                    .joinToString("\n\n")
                if (inner.isBlank()) {
                    emptyList()
                } else {
                    listOf(
                        inner.split("\n").joinToString("\n") { line ->
                            if (line.isEmpty()) ">" else "> $line"
                        },
                    )
                }
            }
            is TaskListSegment -> {
                val lines = segment.items.map { item ->
                    val marker = if (item.checked) "- [x] " else "- [ ] "
                    marker + mdInline(parseFragment(item.contentHtml), resolve).trim()
                }
                if (lines.isEmpty()) emptyList() else listOf(lines.joinToString("\n"))
            }
            is TableSegment -> {
                val table = mdTable(segment, resolve)
                if (table.isBlank()) emptyList() else listOf(table)
            }
            is CodeSegment -> listOf(mdFence(segment.text))
            is ImageSegment -> listOf(mdImage(segment.src, segment.alt, resolve))
            is FileSegment -> listOf("[File: ${segment.fileName}]")
            DividerSegment -> listOf("---")
            is ChartSegment -> listOf(chartPlaceholder(segment))
            is OpaqueSegment -> {
                val text = textContent(parseFragment(segment.rawHtml)).trim()
                listOf(if (text.isNotEmpty()) text else "[Block]")
            }
        }

    private fun mdFence(text: String): String {
        val content = text.trimEnd('\n')
        // The fence must outrun the longest backtick run inside the
        // code, or that run closes the block early — the rule
        // mdCodeSpan already applies to inline code.
        val longestRun = Regex("`+").findAll(content).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longestRun + 1))
        return "$fence\n$content\n$fence"
    }

    private fun mdTable(segment: TableSegment, resolve: AttachmentResolver): String {
        if (segment.rows.isEmpty()) return ""
        val grid = segment.rows.map { row ->
            row.map { cell ->
                mdInline(parseFragment(cell), resolve)
                    .replace("|", "\\|")
                    .replace(Regex("\\s*\\n\\s*"), " ")
                    .trim()
            }
        }
        val columns = grid.maxOfOrNull { it.size } ?: 0
        if (columns == 0) return ""
        fun renderRow(cells: List<String>): String =
            "| " + (0 until columns).joinToString(" | ") { cells.getOrElse(it) { "" } } + " |"
        val separator = renderRow(List(columns) { "---" })
        return if (segment.headerRow) {
            val (header, body) = grid.first() to grid.drop(1)
            (listOf(renderRow(header), separator) + body.map(::renderRow))
                .joinToString("\n")
        } else {
            // Markdown needs a header row; a headerless table gets
            // an empty one so no data row is promoted into it.
            (listOf(renderRow(emptyList()), separator) + grid.map(::renderRow))
                .joinToString("\n")
        }
    }

    private fun mdImage(src: String, alt: String, resolve: AttachmentResolver): String {
        val cleanAlt = alt.replace(Regex("[\\[\\]]"), "")
        val prefix = NoteDocument.ATTACHMENT_SRC_PREFIX
        return when {
            src.startsWith(prefix) -> {
                val data = resolve(src.removePrefix(prefix))
                if (data != null) "![$cleanAlt](${mdLinkDestination(data)})" else "[Image]"
            }
            src.isNotBlank() -> "![$cleanAlt](${mdLinkDestination(src)})"
            else -> "[Image]"
        }
    }

    /** Blocks from the top level of a parsed fragment. */
    private fun mdBlocks(nodes: List<HtmlNode>, resolve: AttachmentResolver): List<String> {
        val blocks = mutableListOf<String>()
        val stray = mutableListOf<HtmlNode>()
        fun flushStray() {
            val text = mdInline(stray, resolve).trim()
            if (text.isNotEmpty()) blocks += text
            stray.clear()
        }
        for (node in nodes) {
            val el = node as? HtmlElement
            when {
                el == null -> stray += node
                el.name in setOf("p", "h1", "h2", "h3", "h4") -> {
                    flushStray()
                    val text = mdInline(el.children, resolve).trim()
                    if (text.isNotEmpty()) {
                        blocks += if (el.name == "p") {
                            text
                        } else {
                            "#".repeat(el.name[1] - '0') + " " + text
                        }
                    }
                }
                el.name == "ul" || el.name == "ol" -> {
                    flushStray()
                    val list = mdList(el, 0, resolve)
                    if (list.isNotEmpty()) blocks += list.joinToString("\n")
                }
                el.name == "blockquote" -> {
                    flushStray()
                    val inner = mdBlocks(el.children, resolve).joinToString("\n\n")
                    if (inner.isNotEmpty()) {
                        blocks += inner.split("\n").joinToString("\n") { line ->
                            if (line.isEmpty()) ">" else "> $line"
                        }
                    }
                }
                el.name == "pre" -> {
                    flushStray()
                    blocks += mdFence(textContent(listOf(el)))
                }
                el.name == "hr" -> {
                    flushStray()
                    blocks += "---"
                }
                el.name == "img" -> {
                    flushStray()
                    blocks += mdImage(
                        el.attrs["src"] ?: "",
                        el.attrs["alt"] ?: "",
                        resolve,
                    )
                }
                el.name in BLOCK_CONTAINERS -> {
                    flushStray()
                    blocks += mdBlocks(el.children, resolve)
                }
                else -> stray += node
            }
        }
        flushStray()
        return blocks
    }

    private fun mdList(list: HtmlElement, depth: Int, resolve: AttachmentResolver): List<String> {
        val isTaskList = list.attrs["data-type"] == "taskList"
        val ordered = list.name == "ol"
        val start = (list.attrs["start"]?.toIntOrNull() ?: 1)
        val type = list.attrs["type"]
        val indent = "  ".repeat(depth)
        val lines = mutableListOf<String>()
        var counter = start
        for (li in list.children) {
            if (li !is HtmlElement || li.name != "li") continue
            val marker = when {
                isTaskList -> if (li.attrs["data-checked"] == "true") "- [x] " else "- [ ] "
                ordered -> "${orderedMarker(counter, type)}. "
                else -> "- "
            }
            counter++
            val inlineNodes = mutableListOf<HtmlNode>()
            val nested = mutableListOf<HtmlElement>()
            for (child in li.children) {
                when {
                    child is HtmlElement && (child.name == "ul" || child.name == "ol") ->
                        nested += child
                    child is HtmlElement && child.name == "p" ->
                        inlineNodes += child.children
                    child is HtmlElement && (child.name == "label" || child.name == "input") ->
                        Unit // task checkbox chrome, not content
                    else -> inlineNodes += child
                }
            }
            lines += indent + marker + mdInline(inlineNodes, resolve).trim()
            for (sub in nested) lines += mdList(sub, depth + 1, resolve)
        }
        return lines
    }

    private fun mdInline(nodes: List<HtmlNode>, resolve: AttachmentResolver): String {
        val out = StringBuilder()
        for (node in nodes) {
            when (node) {
                is HtmlText -> out.append(escapeMdText(plainTextLinks(node.text)))
                is HtmlElement -> {
                    val inner = mdInline(node.children, resolve)
                    when (node.name) {
                        "strong", "b" ->
                            if (inner.isNotBlank()) out.append("**").append(inner).append("**")
                        "em", "i" ->
                            if (inner.isNotBlank()) out.append("*").append(inner).append("*")
                        "u" -> out.append("<u>").append(inner).append("</u>")
                        "s", "strike", "del" ->
                            if (inner.isNotBlank()) out.append("~~").append(inner).append("~~")
                        "mark" -> out.append("<mark>").append(inner).append("</mark>")
                        "code" -> out.append(mdCodeSpan(textContent(node.children)))
                        "a" -> {
                            val href = node.attrs["href"]
                            if (!href.isNullOrEmpty()) {
                                out.append("[").append(inner).append("](")
                                    .append(mdLinkDestination(href)).append(")")
                            } else {
                                out.append(inner)
                            }
                        }
                        "br" -> out.append("  \n")
                        "img" -> out.append(
                            mdImage(node.attrs["src"] ?: "", node.attrs["alt"] ?: "", resolve),
                        )
                        else -> out.append(inner)
                    }
                }
            }
        }
        return out.toString()
    }

    /** Escapes the characters that would re-parse as Markdown structure. */
    private fun escapeMdText(text: String): String {
        val escaped = text.replace(Regex("([\\\\`*_\\[\\]<])"), "\\\\$1")
        // Inline escaping is not enough: a literal line that STARTS
        // with a block marker (heading, quote, list, thematic break)
        // would re-parse as that structure instead of the note's
        // words, so each line's leading marker is escaped too.
        return escaped.split('\n').joinToString("\n") { escapeMdLineStart(it) }
    }

    /**
     * Escapes a block-level marker at a line start (up to three
     * leading spaces, per CommonMark). Markers mid-line, and marker
     * characters that would not form a block construct ("#tag",
     * "1.5"), are left alone.
     */
    private fun escapeMdLineStart(line: String): String {
        val indent = line.takeWhile { it == ' ' }
        if (indent.length > 3) return line
        val rest = line.substring(indent.length)
        if (rest.isEmpty()) return line
        // Thematic break: only -, * or _ (and spaces), three or more.
        val compact = rest.replace(" ", "")
        if (compact.length >= 3 &&
            (compact.all { it == '-' } || compact.all { it == '*' } || compact.all { it == '_' })
        ) {
            return indent + "\\" + rest
        }
        val first = rest[0]
        // Block markers bind when followed by a space or end of line.
        fun markerBinds(end: Int): Boolean =
            end >= rest.length || rest[end] == ' ' || rest[end] == '\t'
        return when {
            first == '#' -> {
                val hashes = rest.takeWhile { it == '#' }.length
                if (hashes <= 6 && markerBinds(hashes)) indent + "\\" + rest else line
            }
            first == '>' -> indent + "\\" + rest
            first == '-' || first == '+' ->
                if (markerBinds(1)) indent + "\\" + rest else line
            first.isDigit() -> {
                val digits = rest.takeWhile { it.isDigit() }.length
                val punct = digits
                if (digits <= 9 && punct < rest.length &&
                    (rest[punct] == '.' || rest[punct] == ')') && markerBinds(punct + 1)
                ) {
                    indent + rest.substring(0, punct) + "\\" + rest.substring(punct)
                } else {
                    line
                }
            }
            else -> line
        }
    }

    /**
     * A Markdown link destination: whitespace is percent-encoded
     * first (a spaced destination breaks in several renderers,
     * and a line ending is invalid even inside the angle form),
     * then the CommonMark angle form is used when parentheses
     * remain — a raw ")" would end the destination early and
     * truncate the target.
     */
    private fun mdLinkDestination(href: String): String {
        val encoded = buildString(href.length) {
            for (ch in href) {
                when (ch) {
                    ' ' -> append("%20")
                    '\t' -> append("%09")
                    '\n' -> append("%0A")
                    '\r' -> append("%0D")
                    else -> append(ch)
                }
            }
        }
        return if (encoded.any { it == '(' || it == ')' }) {
            "<" + encoded.replace("<", "\\<").replace(">", "\\>") + ">"
        } else {
            encoded
        }
    }

    private fun mdCodeSpan(text: String): String {
        val longestRun = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(longestRun + 1)
        // Padding is needed at backtick boundaries (the fence
        // must not touch a backtick) and at space boundaries:
        // CommonMark strips one space from each end of a span
        // whose content starts AND ends with a space, so an
        // unpadded span holding " foo " would read back as "foo".
        val needsPadding = text.startsWith("`") || text.endsWith("`") ||
            (text.startsWith(" ") && text.endsWith(" ") && text.isNotBlank())
        return if (needsPadding) {
            "$fence $text $fence"
        } else {
            fence + text + fence
        }
    }

    /** Spreadsheet/roman/decimal markers for ordered lists (1.x). */
    private fun orderedMarker(index: Int, type: String?): String = when (type) {
        "a" -> toAlpha(index)
        "A" -> toAlpha(index).uppercase()
        "i" -> toRoman(index)
        "I" -> toRoman(index).uppercase()
        else -> index.toString()
    }

    private fun toAlpha(value: Int): String {
        var out = ""
        var n = value
        while (n > 0) {
            n -= 1
            out = ('a' + (n % 26)) + out
            n /= 26
        }
        return out.ifEmpty { "a" }
    }

    private fun toRoman(value: Int): String {
        if (value < 1 || value > 3999) return value.toString()
        val table = listOf(
            1000 to "m", 900 to "cm", 500 to "d", 400 to "cd",
            100 to "c", 90 to "xc", 50 to "l", 40 to "xl",
            10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i",
        )
        var out = ""
        var n = value
        for ((amount, glyph) in table) {
            while (n >= amount) {
                out += glyph
                n -= amount
            }
        }
        return out
    }

    // ------------------------------------------------------------ Plain text

    fun noteToPlainText(
        title: String,
        segments: List<DocSegment>,
        resolveImage: AttachmentResolver = { null },
    ): String {
        // Plain text never embeds data URLs — images become named
        // placeholders, so the resolver is accepted for signature
        // symmetry with 1.x but not consulted.
        val blocks = mutableListOf(exportTitle(title))
        for (segment in segments) blocks += textSegmentBlocks(segment)
        return blocks.filter { it.isNotBlank() }.joinToString("\n\n") + "\n"
    }

    private fun textSegmentBlocks(segment: DocSegment): List<String> = when (segment) {
        is TextSegment -> textBlocks(parseFragment(segment.html))
        is QuoteSegment -> {
            val inner = textBlocks(parseFragment(segment.innerHtml)).joinToString("\n\n")
            if (inner.isBlank()) {
                emptyList()
            } else {
                listOf(
                    inner.split("\n").joinToString("\n") { line ->
                        if (line.isEmpty()) ">" else "> $line"
                    },
                )
            }
        }
        is TaskListSegment -> {
            val lines = segment.items.map { item ->
                val marker = if (item.checked) "- [x] " else "- [ ] "
                marker + textInline(parseFragment(item.contentHtml)).replace(Regex("\\s+"), " ").trim()
            }
            if (lines.isEmpty()) emptyList() else listOf(lines.joinToString("\n"))
        }
        is TableSegment -> {
            if (segment.rows.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    segment.rows.joinToString("\n") { row ->
                        row.joinToString(" | ") { cell ->
                            textInline(parseFragment(cell)).replace(Regex("\\s+"), " ").trim()
                        }
                    },
                )
            }
        }
        is CodeSegment -> listOf(segment.text.trimEnd('\n'))
        is ImageSegment -> listOf(textImage(segment.alt))
        is FileSegment -> listOf("[File: ${segment.fileName}]")
        DividerSegment -> listOf("---")
        is ChartSegment -> listOf(chartPlaceholder(segment))
        is OpaqueSegment -> {
            val text = textContent(parseFragment(segment.rawHtml)).trim()
            listOf(if (text.isNotEmpty()) text else "[Block]")
        }
    }

    private fun textImage(alt: String): String =
        if (alt.isNotBlank()) "[Image: ${alt.trim()}]" else "[Image]"

    private fun textBlocks(nodes: List<HtmlNode>): List<String> {
        val blocks = mutableListOf<String>()
        val stray = mutableListOf<HtmlNode>()
        fun flushStray() {
            val text = textInline(stray).trim()
            if (text.isNotEmpty()) blocks += text
            stray.clear()
        }
        for (node in nodes) {
            val el = node as? HtmlElement
            when {
                el == null -> stray += node
                el.name in setOf("p", "h1", "h2", "h3", "h4") -> {
                    flushStray()
                    val text = textInline(el.children).trim()
                    if (text.isNotEmpty()) blocks += text
                }
                el.name == "ul" || el.name == "ol" -> {
                    flushStray()
                    val list = textList(el, 0)
                    if (list.isNotEmpty()) blocks += list.joinToString("\n")
                }
                el.name == "blockquote" -> {
                    flushStray()
                    val inner = textBlocks(el.children).joinToString("\n")
                    if (inner.isNotEmpty()) {
                        blocks += inner.split("\n").joinToString("\n") { line ->
                            if (line.isEmpty()) ">" else "> $line"
                        }
                    }
                }
                el.name == "pre" -> {
                    flushStray()
                    blocks += textContent(listOf(el)).trimEnd('\n')
                }
                el.name == "hr" -> {
                    flushStray()
                    blocks += "---"
                }
                el.name == "img" -> {
                    flushStray()
                    blocks += textImage(el.attrs["alt"] ?: "")
                }
                el.name in BLOCK_CONTAINERS -> {
                    flushStray()
                    blocks += textBlocks(el.children)
                }
                else -> stray += node
            }
        }
        flushStray()
        return blocks
    }

    private fun textList(list: HtmlElement, depth: Int): List<String> {
        val isTaskList = list.attrs["data-type"] == "taskList"
        val ordered = list.name == "ol"
        val start = list.attrs["start"]?.toIntOrNull() ?: 1
        val type = list.attrs["type"]
        val indent = "  ".repeat(depth)
        val lines = mutableListOf<String>()
        var counter = start
        for (li in list.children) {
            if (li !is HtmlElement || li.name != "li") continue
            val marker = when {
                isTaskList -> if (li.attrs["data-checked"] == "true") "- [x] " else "- [ ] "
                ordered -> "${orderedMarker(counter, type)}. "
                else -> "- "
            }
            counter++
            val inlineNodes = mutableListOf<HtmlNode>()
            val nested = mutableListOf<HtmlElement>()
            for (child in li.children) {
                when {
                    child is HtmlElement && (child.name == "ul" || child.name == "ol") ->
                        nested += child
                    child is HtmlElement && child.name == "p" ->
                        inlineNodes += child.children
                    child is HtmlElement && (child.name == "label" || child.name == "input") ->
                        Unit
                    else -> inlineNodes += child
                }
            }
            lines += indent + marker +
                textInline(inlineNodes).replace(Regex("\\s+"), " ").trim()
            for (sub in nested) lines += textList(sub, depth + 1)
        }
        return lines
    }

    private fun textInline(nodes: List<HtmlNode>): String {
        val out = StringBuilder()
        for (node in nodes) {
            when (node) {
                is HtmlText -> out.append(plainTextLinks(node.text))
                is HtmlElement -> when (node.name) {
                    "br" -> out.append("\n")
                    "a" -> {
                        val inner = textInline(node.children)
                        val href = node.attrs["href"]
                        if (!href.isNullOrEmpty() && href != inner.trim()) {
                            out.append(inner).append(" (").append(href).append(")")
                        } else {
                            out.append(inner)
                        }
                    }
                    "img" -> out.append(textImage(node.attrs["alt"] ?: ""))
                    else -> out.append(textInline(node.children))
                }
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ HTML

    fun noteToHtml(
        title: String,
        segments: List<DocSegment>,
        resolveImage: AttachmentResolver = { null },
    ): String {
        val escapedTitle = escapeHtmlText(exportTitle(title))
        val body = StringBuilder()
        for (segment in segments) {
            val html = htmlSegment(segment, resolveImage)
            if (html.isNotEmpty()) body.append(html).append('\n')
        }
        return listOf(
            "<!doctype html>",
            "<html lang=\"en\">",
            "<head>",
            "<meta charset=\"utf-8\">",
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
            "<title>$escapedTitle</title>",
            "<style>\n$EXPORT_CSS\n</style>",
            "</head>",
            "<body>",
            "<main>",
            "<h1>$escapedTitle</h1>",
            body.toString().trimEnd('\n'),
            "</main>",
            "</body>",
            "</html>",
            "",
        ).joinToString("\n")
    }

    private fun htmlSegment(segment: DocSegment, resolve: AttachmentResolver): String =
        when (segment) {
            is TextSegment ->
                serializeFragment(transformFragment(parseFragment(segment.html), resolve))
            is QuoteSegment ->
                "<blockquote>" +
                    serializeFragment(transformFragment(parseFragment(segment.innerHtml), resolve)) +
                    "</blockquote>"
            is TaskListSegment -> {
                val items = segment.items.joinToString("") { item ->
                    val checkedAttr = if (item.checked) " checked" else ""
                    val inner = serializeFragment(
                        transformFragment(parseFragment(item.contentHtml), resolve),
                    )
                    "<li data-checked=\"${item.checked}\">" +
                        "<label><input type=\"checkbox\"$checkedAttr disabled></label>" +
                        "<div>$inner</div></li>"
                }
                "<ul data-type=\"taskList\">$items</ul>"
            }
            is TableSegment -> htmlTable(segment, resolve)
            is CodeSegment -> "<pre><code>${escapeHtmlText(segment.text)}</code></pre>"
            is ImageSegment -> {
                val prefix = NoteDocument.ATTACHMENT_SRC_PREFIX
                val alt = escapeHtmlAttr(segment.alt)
                val width = if (segment.widthPercent in 1..99) {
                    " width=\"${segment.widthPercent}%\""
                } else {
                    ""
                }
                when {
                    segment.src.startsWith(prefix) -> {
                        val data = resolve(segment.src.removePrefix(prefix))
                        if (data != null) {
                            "<img src=\"${escapeHtmlAttr(data)}\" alt=\"$alt\"$width>"
                        } else {
                            "<span>[Image]</span>"
                        }
                    }
                    segment.src.isNotBlank() ->
                        "<img src=\"${escapeHtmlAttr(segment.src)}\" alt=\"$alt\"$width>"
                    else -> "<span>[Image]</span>"
                }
            }
            is FileSegment -> "<p>[File: ${escapeHtmlText(segment.fileName)}]</p>"
            DividerSegment -> "<hr>"
            is ChartSegment ->
                "<p class=\"noma-chart-placeholder\">${chartPlaceholder(segment)}</p>"
            is OpaqueSegment -> resolveOpaqueImages(sanitizeOpaqueHtml(segment.rawHtml), resolve)
        }

    private fun htmlTable(segment: TableSegment, resolve: AttachmentResolver): String {
        if (segment.rows.isEmpty()) return ""
        val out = StringBuilder("<table>")
        segment.rows.forEachIndexed { rowIndex, row ->
            out.append("<tr>")
            row.forEachIndexed { cellIndex, cell ->
                val header = segment.headerRow && rowIndex == 0
                val tag = if (header) "th" else "td"
                val span = segment.cellSpans.getOrNull(rowIndex)?.getOrNull(cellIndex)
                val spanAttrs = buildString {
                    if (span != null && span.rowSpan > 1) append(" rowspan=\"${span.rowSpan}\"")
                    if (span != null && span.colSpan > 1) append(" colspan=\"${span.colSpan}\"")
                }
                out.append("<").append(tag).append(spanAttrs).append(">")
                out.append(
                    serializeFragment(transformFragment(parseFragment(cell), resolve)),
                )
                out.append("</").append(tag).append(">")
            }
            out.append("</tr>")
        }
        out.append("</table>")
        return out.toString()
    }

    // ------------------------------------------------- Opaque scrubbing
    //
    // Opaque segments preserve foreign HTML byte-faithfully in the
    // note (that round-trip contract stands, untouched), but an
    // exported standalone HTML file gets opened in a browser by
    // whoever receives it — so on THIS path only, active content
    // is scrubbed out before anything is emitted: script-bearing
    // elements dropped whole, event-handler attributes stripped,
    // scriptable href/src targets neutralised. Benign markup
    // passes through byte-stable apart from the scrubbed bits.

    /** Elements dropped whole: open tag, contents and close tag. */
    private val OPAQUE_DROP_WITH_CONTENTS =
        Regex("""(?is)<(script|iframe|object|form)\b[^>]*>.*?</\1\s*>""")

    /** Dangerous void elements and any dangling tags of the set. */
    private val OPAQUE_DROP_TAG =
        Regex("""(?is)</?(script|iframe|object|embed|form|link|meta|base)\b[^>]*>""")

    private fun sanitizeOpaqueHtml(rawHtml: String): String {
        var out = OPAQUE_DROP_WITH_CONTENTS.replace(rawHtml, "")
        out = OPAQUE_DROP_TAG.replace(out, "")
        // Walk the surviving tags (quote-aware) and scrub their
        // attributes; text between tags is copied verbatim.
        val result = StringBuilder(out.length)
        var i = 0
        while (i < out.length) {
            val lt = out.indexOf('<', i)
            if (lt < 0) {
                result.append(out.substring(i))
                break
            }
            result.append(out, i, lt)
            if (out.startsWith("<!--", lt)) {
                val end = out.indexOf("-->", lt + 4)
                val stop = if (end < 0) out.length else end + 3
                result.append(out, lt, stop)
                i = stop
                continue
            }
            var j = lt + 1
            var quote: Char? = null
            while (j < out.length) {
                val ch = out[j]
                if (quote != null) {
                    if (ch == quote) quote = null
                } else if (ch == '"' || ch == '\'') {
                    quote = ch
                } else if (ch == '>') {
                    break
                }
                j++
            }
            if (j >= out.length) {
                result.append(out.substring(lt))
                break
            }
            result.append(scrubExportTag(out.substring(lt, j + 1)))
            i = j + 1
        }
        return result.toString()
    }

    /**
     * Scrubs one tag: drops every on* event attribute, and points
     * URL-attribute values (href/src/formaction, including
     * namespaced spellings like xlink:href) with a scriptable
     * scheme at "#" instead. Everything else — tag name,
     * attribute order, quoting style, benign values — is copied
     * byte for byte.
     */
    private fun scrubExportTag(tag: String): String {
        // Closing tags, declarations and processing instructions
        // carry no attributes worth scrubbing.
        if (tag.length < 3 || tag[1] == '/' || tag[1] == '!' || tag[1] == '?') return tag
        val out = StringBuilder(tag.length)
        var i = 0
        // Copy "<name".
        while (i < tag.length && !tag[i].isWhitespace() && tag[i] != '>' && tag[i] != '/') {
            out.append(tag[i])
            i++
        }
        while (i < tag.length) {
            // Buffer the whitespace run in front of the next token:
            // it is emitted only if what follows survives scrubbing.
            val wsStart = i
            while (i < tag.length && tag[i].isWhitespace()) i++
            val ws = tag.substring(wsStart, i)
            if (i >= tag.length) {
                out.append(ws)
                break
            }
            val ch = tag[i]
            if (ch == '>') {
                out.append(ws)
                out.append(tag.substring(i))
                break
            }
            if (ch == '/') {
                out.append(ws)
                out.append(ch)
                i++
                continue
            }
            // One attribute: name, then an optional = value.
            val nameStart = i
            while (i < tag.length && !tag[i].isWhitespace() &&
                tag[i] != '=' && tag[i] != '>' && tag[i] != '/'
            ) {
                i++
            }
            val name = tag.substring(nameStart, i)
            if (name.isEmpty()) {
                out.append(ws)
                out.append(tag[i])
                i++
                continue
            }
            var j = i
            while (j < tag.length && tag[j].isWhitespace()) j++
            var valueStart = -1
            var valueEnd = -1
            var valueQuote: Char? = null
            if (j < tag.length && tag[j] == '=') {
                j++
                while (j < tag.length && tag[j].isWhitespace()) j++
                if (j < tag.length && (tag[j] == '"' || tag[j] == '\'')) {
                    valueQuote = tag[j]
                    valueStart = j + 1
                    var k = valueStart
                    while (k < tag.length && tag[k] != valueQuote) k++
                    valueEnd = k
                    j = if (k < tag.length) k + 1 else k
                } else {
                    valueStart = j
                    var k = j
                    while (k < tag.length && !tag[k].isWhitespace() && tag[k] != '>') k++
                    valueEnd = k
                    j = k
                }
            }
            val attrEnd = if (valueStart >= 0) j else i
            val lower = name.lowercase()
            when {
                // Event handler: dropped, with its leading space.
                lower.startsWith("on") && name.length > 2 -> Unit
                isUrlAttr(lower) && valueStart >= 0 &&
                    isScriptableUrl(unescapeEntities(tag.substring(valueStart, valueEnd))) -> {
                    out.append(ws)
                    out.append(tag, nameStart, valueStart)
                    out.append('#')
                    if (valueQuote != null && valueEnd < tag.length) out.append(tag[valueEnd])
                }
                else -> {
                    out.append(ws)
                    out.append(tag, nameStart, attrEnd)
                }
            }
            i = attrEnd
        }
        return out.toString()
    }

    /**
     * URL-bearing attributes (href, src, formaction), matched by
     * LOCAL name: a namespaced spelling like xlink:href is the
     * same attribute to a browser, so it earns the same scheme
     * check. Callers pass the already-lowercased attribute name.
     */
    private fun isUrlAttr(lowerName: String): Boolean =
        lowerName.substringAfterLast(':') in URL_ATTRS

    private val URL_ATTRS = setOf("href", "src", "formaction")

    /**
     * A URL scheme that executes script in the file's context.
     * Whitespace and control characters are ignored for the check
     * (browsers strip them inside URLs), and the value is
     * entity-decoded first ("&#106;avascript:" is still
     * "javascript:" to a browser).
     */
    private fun isScriptableUrl(value: String): Boolean {
        val compact = value.filter { it > ' ' }.lowercase()
        return compact.startsWith("javascript:") || compact.startsWith("data:text/html")
    }

    /**
     * Attachment image sources in an opaque block are rewritten —
     * resolved ones to data URLs, unresolved ones to the "[Image]"
     * placeholder span. The block arrives here already scrubbed of
     * active content (see sanitizeOpaqueHtml); benign markup is
     * otherwise emitted as preserved.
     */
    private fun resolveOpaqueImages(rawHtml: String, resolve: AttachmentResolver): String {
        val imgRe = Regex("""(?is)<img\b[^>]*>""")
        return imgRe.replace(rawHtml) { match ->
            val tag = match.value
            val srcMatch = Regex("""(?i)\bsrc\s*=\s*"([^"]*)"""").find(tag)
                ?: Regex("""(?i)\bsrc\s*=\s*'([^']*)'""").find(tag)
            val src = srcMatch?.groupValues?.get(1) ?: return@replace tag
            val prefix = NoteDocument.ATTACHMENT_SRC_PREFIX
            if (!src.startsWith(prefix)) return@replace tag
            val data = resolve(src.removePrefix(prefix))
            if (data != null) {
                tag.replaceRange(srcMatch.range, "src=\"${escapeHtmlAttr(data)}\"")
            } else {
                "<span>[Image]</span>"
            }
        }
    }

    /** escapeHtml's rule from 1.x (& < >), kept local. */
    private fun escapeHtmlText(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeHtmlAttr(text: String): String =
        escapeHtmlText(text).replace("\"", "&quot;")

    /**
     * The standalone-file stylesheet, adapted from 1.x's export CSS:
     * inline styles only, a readable serif measure, and the taskList
     * + chart-placeholder shapes the converters emit.
     */
    private const val EXPORT_CSS = """
  :root { color-scheme: light; }
  * { box-sizing: border-box; }
  body { margin: 0; background: #ffffff; color: #1f1f23; }
  main {
    max-width: 44rem; margin: 0 auto; padding: 2.5rem 1.25rem 4rem;
    font-family: ui-serif, Georgia, "Times New Roman", serif;
    font-size: 17px; line-height: 1.7;
  }
  h1 {
    font-size: 1.9em; font-weight: 600; line-height: 1.25; letter-spacing: -0.01em;
    margin: 0 0 1em; padding-bottom: 0.4em; border-bottom: 1px solid #e7e7ea;
  }
  h2 { font-size: 1.4em; font-weight: 600; line-height: 1.25; margin-top: 1.6em; }
  h3 { font-size: 1.18em; font-weight: 600; line-height: 1.25; margin-top: 1.6em; }
  h4 { font-size: 1em; font-weight: 600; line-height: 1.25; margin-top: 1.6em; }
  main > * + * { margin-top: 0.85em; }
  ul, ol { padding-left: 1.4em; }
  li > p { margin: 0; }
  ul[data-type="taskList"] { list-style: none; padding-left: 0.15em; }
  ul[data-type="taskList"] li { display: flex; gap: 0.6em; align-items: flex-start; }
  ul[data-type="taskList"] input[type="checkbox"] { margin-top: 0.45em; }
  blockquote { border-left: 2px solid #d9d9de; padding-left: 1em; margin-left: 0; color: #55555c; font-style: italic; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.87em; background: #f4f4f5; padding: 0.12em 0.35em; border-radius: 4px; }
  pre { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; background: #f4f4f5; border: 1px solid #e4e4e7; border-radius: 8px; padding: 0.9em 1em; overflow-x: auto; font-size: 0.88em; }
  pre code { background: none; padding: 0; }
  mark { background: rgba(23, 69, 47, 0.18); color: inherit; border-radius: 3px; padding: 0 0.15em; }
  a { color: inherit; text-decoration: underline; text-underline-offset: 3px; }
  img { max-width: 100%; height: auto; border-radius: 8px; }
  table { border-collapse: collapse; }
  th, td { border: 1px solid #e4e4e7; padding: 0.4em 0.6em; text-align: left; }
  hr { border: none; border-top: 1px solid #e4e4e7; }
  .noma-chart-placeholder {
    border: 1px dashed #c6c6cc; border-radius: 8px; padding: 1em;
    text-align: center; color: #66666d; font-family: system-ui, sans-serif; font-size: 0.9em;
  }
"""

    // ------------------------------------------------------- Fragment model
    //
    // A deliberately small, tolerant HTML fragment parser for the
    // dialect the editor stores: elements, attributes and text,
    // with entity unescaping on text and attribute values. It is
    // NOT a general HTML parser — malformed input degrades to
    // literal text rather than throwing, and unknown elements are
    // kept and walked through transparently.

    private val BLOCK_CONTAINERS = setOf(
        "div", "section", "article", "figure", "main", "aside", "header", "footer",
    )

    private val VOID_ELEMENTS = setOf(
        "br", "hr", "img", "input", "meta", "link", "col", "wbr", "source",
    )

    /** Rewrites [[Title]] / [[Title|label]] note links to their label text. */
    private val NOTE_LINK_RE = Regex("""\[\[([^\]|]+)(?:\|([^\]]*))?]]""")

    private fun plainTextLinks(text: String): String =
        NOTE_LINK_RE.replace(text) { match ->
            match.groupValues[2].ifEmpty { match.groupValues[1].trim() }
        }

    private fun textContent(nodes: List<HtmlNode>): String {
        val out = StringBuilder()
        for (node in nodes) {
            when (node) {
                is HtmlText -> out.append(node.text)
                is HtmlElement -> out.append(textContent(node.children))
            }
        }
        return out.toString()
    }

    private fun parseFragment(html: String): List<HtmlNode> {
        val root = mutableListOf<HtmlNode>()
        val stack = mutableListOf<MutableList<HtmlNode>>() // children lists
        val openNames = mutableListOf<String>()
        val openAttrs = mutableListOf<Map<String, String>>()
        var current = root
        var i = 0
        val text = StringBuilder()
        fun flushText() {
            if (text.isNotEmpty()) {
                current += HtmlText(unescapeEntities(text.toString()))
                text.clear()
            }
        }
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                text.append(html.substring(i))
                break
            }
            text.append(html.substring(i, lt))
            if (html.startsWith("<!--", lt)) {
                val end = html.indexOf("-->", lt + 4)
                i = if (end < 0) html.length else end + 3
                continue
            }
            val tag = readTag(html, lt)
            if (tag == null) {
                text.append('<')
                i = lt + 1
                continue
            }
            if (tag.closing) {
                flushText()
                val idx = openNames.lastIndexOf(tag.name)
                if (idx >= 0) {
                    // Close everything up to and including the match.
                    while (openNames.size > idx) {
                        val name = openNames.removeAt(openNames.lastIndex)
                        val attrs = openAttrs.removeAt(openAttrs.lastIndex)
                        val children = stack.removeAt(stack.lastIndex)
                        val parent = stack.lastOrNull() ?: root
                        parent += HtmlElement(name, attrs, children)
                        current = parent
                    }
                }
                i = tag.end
                continue
            }
            flushText()
            if (tag.name in VOID_ELEMENTS || tag.selfClosing) {
                current += HtmlElement(tag.name, tag.attrs, emptyList())
            } else {
                stack += mutableListOf<HtmlNode>()
                openNames += tag.name
                openAttrs += tag.attrs
                current = stack.last()
            }
            i = tag.end
        }
        flushText()
        while (openNames.isNotEmpty()) {
            val name = openNames.removeAt(openNames.lastIndex)
            val attrs = openAttrs.removeAt(openAttrs.lastIndex)
            val children = stack.removeAt(stack.lastIndex)
            val parent = stack.lastOrNull() ?: root
            parent += HtmlElement(name, attrs, children)
        }
        return root
    }

    private data class ParsedTag(
        val name: String,
        val attrs: Map<String, String>,
        val closing: Boolean,
        val selfClosing: Boolean,
        val end: Int,
    )

    private fun readTag(s: String, start: Int): ParsedTag? {
        if (start >= s.length || s[start] != '<') return null
        var i = start + 1
        var closing = false
        if (i < s.length && s[i] == '/') {
            closing = true
            i++
        }
        if (i >= s.length || !s[i].isLetter()) return null
        val nameStart = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-')) i++
        val name = s.substring(nameStart, i).lowercase()
        val attrs = LinkedHashMap<String, String>()
        var selfClosing = false
        var done = false
        while (i < s.length && !done) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break
            when (s[i]) {
                '>' -> {
                    i++
                    done = true
                }
                '/' -> {
                    if (i + 1 < s.length && s[i + 1] == '>') {
                        selfClosing = true
                        i += 2
                        done = true
                    } else {
                        i++
                    }
                }
                else -> {
                    val attrStart = i
                    while (i < s.length && !s[i].isWhitespace() &&
                        s[i] != '=' && s[i] != '>' && s[i] != '/'
                    ) i++
                    val attrName = s.substring(attrStart, i).lowercase()
                    while (i < s.length && s[i].isWhitespace()) i++
                    var attrValue = ""
                    if (i < s.length && s[i] == '=') {
                        i++
                        while (i < s.length && s[i].isWhitespace()) i++
                        if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                            val quote = s[i]
                            i++
                            val valueStart = i
                            while (i < s.length && s[i] != quote) i++
                            attrValue = s.substring(valueStart, i)
                            if (i < s.length) i++
                        } else {
                            val valueStart = i
                            while (i < s.length && !s[i].isWhitespace() && s[i] != '>') i++
                            attrValue = s.substring(valueStart, i)
                        }
                    }
                    if (attrName.isNotEmpty()) {
                        attrs[attrName] = unescapeEntities(attrValue)
                    }
                }
            }
        }
        if (!done) return null
        return ParsedTag(name, attrs, closing, selfClosing, i)
    }

    private fun unescapeEntities(text: String): String {
        if (!text.contains('&')) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val amp = text.indexOf('&', i)
            if (amp < 0) {
                out.append(text.substring(i))
                break
            }
            out.append(text, i, amp)
            val semi = text.indexOf(';', amp)
            if (semi < 0 || semi - amp > 10) {
                out.append('&')
                i = amp + 1
                continue
            }
            val entity = text.substring(amp + 1, semi)
            val decoded: String? = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity == "nbsp" -> " "
                entity == "colon" -> ":"
                entity == "Tab" || entity == "tab" -> "\t"
                entity == "NewLine" || entity == "newline" -> "\n"
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.substring(2).toIntOrNull(16)?.let { decodeCodePoint(it) }
                entity.startsWith("#") ->
                    entity.substring(1).toIntOrNull()?.let { decodeCodePoint(it) }
                else -> null
            }
            if (decoded == null) {
                out.append('&')
                i = amp + 1
            } else {
                out.append(decoded)
                i = semi + 1
            }
        }
        return out.toString()
    }

    /**
     * One numeric character reference: a valid code point
     * decodes; an out-of-range one — where Character.toChars
     * would throw and abort the whole export — becomes U+FFFD
     * instead (the HTML5 error-handling rule).
     */
    private fun decodeCodePoint(codePoint: Int): String =
        if (codePoint in 0..Character.MAX_CODE_POINT) {
            String(Character.toChars(codePoint))
        } else {
            "\uFFFD"
        }

    /**
     * Active elements that never survive HTML export, dropped
     * whole (contents included) — the fragment-level twin of the
     * opaque path's drop lists (see sanitizeOpaqueHtml). The
     * stored fragment can carry foreign inline HTML inside an
     * ordinary paragraph, quote, task item or table cell; those
     * containers all serialise through transformFragment, so the
     * scrub lives here and covers every one of them at once.
     */
    private val FRAGMENT_DROP_ELEMENTS = setOf(
        "script", "iframe", "object", "embed", "form", "link", "meta", "base",
    )

    /**
     * The fragment-level twin of scrubExportTag: event-handler
     * attributes are dropped, scriptable targets of URL
     * attributes — matched by local name, so namespaced
     * spellings like xlink:href are checked too — are pointed
     * at "#", everything else passes through untouched.
     * Attribute values arrive entity-decoded from the parser,
     * exactly the shape isScriptableUrl expects. When nothing in
     * the map needs scrubbing the SAME map is returned, so benign
     * fragments serialise byte-identically to before.
     */
    private fun scrubFragmentAttrs(attrs: Map<String, String>): Map<String, String> {
        fun dirty(name: String, value: String): Boolean {
            val lower = name.lowercase()
            return (lower.startsWith("on") && name.length > 2) ||
                (isUrlAttr(lower) && isScriptableUrl(value))
        }
        if (attrs.none { (name, value) -> dirty(name, value) }) return attrs
        val out = LinkedHashMap<String, String>(attrs.size)
        for ((name, value) in attrs) {
            val lower = name.lowercase()
            when {
                lower.startsWith("on") && name.length > 2 -> Unit
                isUrlAttr(lower) && isScriptableUrl(value) ->
                    out[name] = "#"
                else -> out[name] = value
            }
        }
        return out
    }

    /**
     * The HTML-export transform over a parsed fragment (1.x's
     * resolveExportContent): attachment images resolve to data
     * URLs or an "[Image]" placeholder span, note-link chips
     * unwrap to their label, [[Title]] text links become labels.
     * Active content is scrubbed on the way (see
     * FRAGMENT_DROP_ELEMENTS and scrubFragmentAttrs).
     */
    private fun transformFragment(
        nodes: List<HtmlNode>,
        resolve: AttachmentResolver,
    ): List<HtmlNode> {
        val out = mutableListOf<HtmlNode>()
        for (node in nodes) {
            when (node) {
                is HtmlText -> out += HtmlText(plainTextLinks(node.text))
                is HtmlElement -> {
                    val prefix = NoteDocument.ATTACHMENT_SRC_PREFIX
                    when {
                        node.name in FRAGMENT_DROP_ELEMENTS -> Unit
                        node.name == "img" -> {
                            val src = node.attrs["src"] ?: ""
                            when {
                                src.startsWith(prefix) -> {
                                    val data = resolve(src.removePrefix(prefix))
                                    if (data != null) {
                                        val attrs =
                                            LinkedHashMap(scrubFragmentAttrs(node.attrs))
                                        attrs["src"] = data
                                        out += HtmlElement("img", attrs, emptyList())
                                    } else {
                                        out += HtmlElement(
                                            "span", emptyMap(), listOf(HtmlText("[Image]")),
                                        )
                                    }
                                }
                                src.isNotBlank() -> out += HtmlElement(
                                    "img", scrubFragmentAttrs(node.attrs), emptyList(),
                                )
                                else -> out += HtmlElement(
                                    "span", emptyMap(), listOf(HtmlText("[Image]")),
                                )
                            }
                        }
                        node.name == "span" && node.attrs.containsKey("data-note-link") ->
                            out += transformFragment(node.children, resolve)
                        else -> out += HtmlElement(
                            node.name,
                            scrubFragmentAttrs(node.attrs),
                            transformFragment(node.children, resolve),
                        )
                    }
                }
            }
        }
        return out
    }

    private fun serializeFragment(nodes: List<HtmlNode>): String {
        val out = StringBuilder()
        for (node in nodes) {
            when (node) {
                is HtmlText -> out.append(escapeHtmlText(node.text))
                is HtmlElement -> {
                    out.append('<').append(node.name)
                    for ((name, value) in node.attrs) {
                        out.append(' ').append(name).append("=\"")
                            .append(escapeHtmlAttr(value)).append('"')
                    }
                    out.append('>')
                    if (node.name !in VOID_ELEMENTS) {
                        out.append(serializeFragment(node.children))
                        out.append("</").append(node.name).append('>')
                    }
                }
            }
        }
        return out.toString()
    }
}

/** One node of the export fragment model (see NoteExport). */
private sealed interface HtmlNode

private data class HtmlText(val text: String) : HtmlNode

private data class HtmlElement(
    val name: String,
    val attrs: Map<String, String>,
    val children: List<HtmlNode>,
) : HtmlNode
