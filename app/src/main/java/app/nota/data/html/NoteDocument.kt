package app.nota.data.html

import app.nota.data.text.PlainTextCodec

/**
 * Nota's document model + HTML guard layer (SPIKE-REPORT condition 1).
 *
 * Stored note bodies are HTML-canonical (spec §3/§4), but Compose Rich
 * Editor 1.2.1 silently destroys several block structures on a
 * setHtml -> toHtml round trip: tables, task lists, blockquotes, <pre>
 * code blocks, <hr> dividers, and image alt text (Phase 0 probe
 * evidence). So stored HTML is NEVER handed to the editor whole. It is
 * first split into segments:
 *
 *  - [TextSegment]s hold the shapes the library round-trips losslessly
 *    (paragraphs, headings, inline marks, links, bullet/numbered lists)
 *    and are the only parts an editor state ever owns.
 *  - Every fragile shape becomes a typed block segment with its own
 *    model and its own small editor (table grid, checklist, image...).
 *  - Anything unrecognised becomes an [OpaqueSegment] whose raw HTML is
 *    preserved byte-for-byte and written back verbatim — the guard's
 *    prime directive is that no stored shape is ever silently lost.
 *
 * parse -> serialize is stable: serialising a parsed document and parsing
 * it again yields the same segments, and canonical inputs (this file's
 * own serialised forms) round-trip byte-for-byte. Cell/item content HTML
 * is preserved verbatim inside table and task-list segments.
 */
sealed interface DocSegment

/** HTML the rich text editor may own (lossless shapes only). */
data class TextSegment(val html: String) : DocSegment

/** A table. [rows] are cell inner-HTML strings; [headerRow] marks row 0 as <th>. */
data class TableSegment(val rows: List<List<String>>, val headerRow: Boolean) : DocSegment

data class TaskItem(val contentHtml: String, val checked: Boolean)

/** A Tiptap task list (<ul data-type="taskList">). */
data class TaskListSegment(val items: List<TaskItem>) : DocSegment

/** A standalone image. [src] may be an attachment id URI (nota-attachment://<id>). */
data class ImageSegment(val src: String, val alt: String = "") : DocSegment

/** A blockquote; [innerHtml] is edited as rich text like a text segment. */
data class QuoteSegment(val innerHtml: String) : DocSegment

/** A <pre> code block; [text] is the raw code text (unescaped). */
data class CodeSegment(val text: String) : DocSegment

/** An <hr> divider. */
data object DividerSegment : DocSegment

/**
 * A chart block. [rawHtml] is preserved verbatim; [payload] is the
 * data-payload attribute when present. The chart editor is Phase 6 —
 * Phase 1 renders a placeholder and never rewrites the payload.
 */
data class ChartSegment(val payload: String, val rawHtml: String) : DocSegment

/** Any top-level element the guard does not model. Preserved verbatim. */
data class OpaqueSegment(val rawHtml: String) : DocSegment

/** One entry of the in-note outline (headings inside text segments). */
data class OutlineEntry(val segmentIndex: Int, val level: Int, val title: String)

object NoteDocument {

    const val ATTACHMENT_SRC_PREFIX = "nota-attachment://"

    // ---------------------------------------------------------------- parse

    fun parse(html: String): List<DocSegment> {
        if (html.isBlank()) return listOf(TextSegment(""))
        val out = mutableListOf<DocSegment>()
        val text = StringBuilder()

        fun flushText() {
            val chunk = text.toString().trim()
            text.clear()
            if (chunk.isNotEmpty()) out += TextSegment(chunk)
        }

        var i = 0
        val n = html.length
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                text.append(html.substring(i))
                break
            }
            text.append(html.substring(i, lt))
            if (html.startsWith("<!--", lt)) {
                val end = html.indexOf("-->", lt + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            val tag = readTag(html, lt) ?: run {
                text.append('<')
                i = lt + 1
                continue
            }
            if (tag.closing) {
                text.append(html.substring(lt, tag.end))
                i = tag.end
                continue
            }
            when (tag.name) {
                "table" -> {
                    val close = findClose(html, tag.end, "table")
                    if (close == null) {
                        text.append(html.substring(lt, tag.end)); i = tag.end
                    } else {
                        val parsed = parseTable(html.substring(lt, close.second))
                        if (parsed != null) {
                            flushText(); out += parsed
                        } else {
                            flushText(); out += OpaqueSegment(html.substring(lt, close.second))
                        }
                        i = close.second
                    }
                }
                "ul", "ol" -> {
                    val close = findClose(html, tag.end, tag.name)
                    if (close == null) {
                        text.append(html.substring(lt, tag.end)); i = tag.end
                    } else {
                        val whole = html.substring(lt, close.second)
                        val inner = html.substring(tag.end, close.first)
                        if (isTaskList(tag.attrs, inner)) {
                            flushText(); out += TaskListSegment(parseTaskItems(inner))
                        } else {
                            text.append(whole)
                        }
                        i = close.second
                    }
                }
                "blockquote" -> {
                    val close = findClose(html, tag.end, "blockquote")
                    if (close == null) {
                        text.append(html.substring(lt, tag.end)); i = tag.end
                    } else {
                        flushText()
                        out += QuoteSegment(html.substring(tag.end, close.first).trim())
                        i = close.second
                    }
                }
                "pre" -> {
                    val close = findClose(html, tag.end, "pre")
                    if (close == null) {
                        text.append(html.substring(lt, tag.end)); i = tag.end
                    } else {
                        flushText()
                        out += CodeSegment(extractCodeText(html.substring(tag.end, close.first)))
                        i = close.second
                    }
                }
                "hr" -> {
                    flushText(); out += DividerSegment; i = tag.end
                }
                "img" -> {
                    flushText()
                    out += ImageSegment(attr(tag.attrs, "src"), attr(tag.attrs, "alt"))
                    i = tag.end
                }
                "p" -> {
                    val close = findClose(html, tag.end, "p")
                    if (close == null) {
                        text.append(html.substring(lt, tag.end)); i = tag.end
                    } else {
                        val inner = html.substring(tag.end, close.first)
                        if (inner.contains("<img", ignoreCase = true)) {
                            splitParagraphWithImages(inner, out, text, ::flushText)
                        } else {
                            text.append(html.substring(lt, close.second))
                        }
                        i = close.second
                    }
                }
                "div", "figure" -> {
                    val close = findClose(html, tag.end, tag.name)
                    if (close == null) {
                        text.append(html.substring(lt, tag.end)); i = tag.end
                    } else {
                        val whole = html.substring(lt, close.second)
                        val inner = html.substring(tag.end, close.first)
                        when {
                            isChartBlock(tag.attrs) -> {
                                flushText(); out += ChartSegment(attr(tag.attrs, "data-payload"), whole)
                            }
                            tag.name == "figure" && inner.contains("<img", ignoreCase = true) -> {
                                flushText()
                                val img = Regex("(?is)<img[^>]*>").find(inner)
                                if (img != null) {
                                    val imgTag = readTag(inner, img.range.first)
                                    if (imgTag != null) {
                                        out += ImageSegment(attr(imgTag.attrs, "src"), attr(imgTag.attrs, "alt"))
                                    }
                                }
                                val caption = Regex("(?is)<figcaption[^>]*>(.*?)</figcaption>")
                                    .find(inner)?.groupValues?.get(1)?.trim()
                                if (!caption.isNullOrEmpty()) text.append("<p>$caption</p>")
                            }
                            else -> {
                                flushText(); out += OpaqueSegment(whole)
                            }
                        }
                        i = close.second
                    }
                }
                else -> {
                    text.append(html.substring(lt, tag.end))
                    i = tag.end
                }
            }
        }
        flushText()
        return if (out.isEmpty()) listOf(TextSegment("")) else out
    }

    private fun splitParagraphWithImages(
        inner: String,
        out: MutableList<DocSegment>,
        text: StringBuilder,
        flushText: () -> Unit,
    ) {
        var idx = 0
        while (idx < inner.length) {
            val imgStart = inner.indexOf("<img", idx, ignoreCase = true)
            if (imgStart < 0) {
                val rest = inner.substring(idx)
                if (rest.isNotBlank()) text.append("<p>$rest</p>")
                break
            }
            val before = inner.substring(idx, imgStart)
            if (before.isNotBlank()) text.append("<p>$before</p>")
            val imgTag = readTag(inner, imgStart)
            if (imgTag == null) {
                text.append(inner.substring(imgStart))
                break
            }
            flushText()
            out += ImageSegment(attr(imgTag.attrs, "src"), attr(imgTag.attrs, "alt"))
            idx = imgTag.end
        }
    }

    // ------------------------------------------------------------ serialize

    fun serialize(segments: List<DocSegment>): String = buildString {
        for (segment in segments) {
            when (segment) {
                is TextSegment -> append(segment.html)
                is TableSegment -> append(serializeTable(segment))
                is TaskListSegment -> append(serializeTaskList(segment))
                is ImageSegment -> append("<p><img src=\"")
                    .append(escapeAttr(segment.src)).append("\" alt=\"")
                    .append(escapeAttr(segment.alt)).append("\"></p>")
                is QuoteSegment -> append("<blockquote>").append(segment.innerHtml).append("</blockquote>")
                is CodeSegment -> append("<pre><code>").append(escapeText(segment.text)).append("</code></pre>")
                DividerSegment -> append("<hr>")
                is ChartSegment -> append(segment.rawHtml)
                is OpaqueSegment -> append(segment.rawHtml)
            }
        }
    }

    private fun serializeTable(table: TableSegment): String = buildString {
        append("<table><tbody>")
        table.rows.forEachIndexed { index, row ->
            append("<tr>")
            val cellTag = if (index == 0 && table.headerRow) "th" else "td"
            for (cell in row) {
                append('<').append(cellTag).append('>').append(cell)
                    .append("</").append(cellTag).append('>')
            }
            append("</tr>")
        }
        append("</tbody></table>")
    }

    private fun serializeTaskList(list: TaskListSegment): String = buildString {
        append("<ul data-type=\"taskList\">")
        for (item in list.items) {
            append("<li data-type=\"taskItem\" data-checked=\"")
                .append(item.checked.toString()).append("\"><div>")
            val content = item.contentHtml.trim()
            if (content.startsWith("<p", ignoreCase = true)) append(content) else {
                append("<p>").append(content).append("</p>")
            }
            append("</div></li>")
        }
        append("</ul>")
    }

    // ---------------------------------------------------------- projections

    /** Plain-text projection of the whole document (snippets + FTS + word count). */
    fun plainText(segments: List<DocSegment>): String {
        val parts = segments.map { segment ->
            when (segment) {
                is TextSegment -> PlainTextCodec.htmlToText(segment.html)
                is TableSegment -> segment.rows.joinToString("\n") { row ->
                    row.joinToString(" ") { PlainTextCodec.htmlToText(it) }.trim()
                }
                is TaskListSegment -> segment.items.joinToString("\n") {
                    PlainTextCodec.htmlToText(it.contentHtml)
                }
                is ImageSegment -> segment.alt
                is QuoteSegment -> PlainTextCodec.htmlToText(segment.innerHtml)
                is CodeSegment -> segment.text
                DividerSegment -> ""
                is ChartSegment -> ""
                is OpaqueSegment -> PlainTextCodec.htmlToText(segment.rawHtml)
            }
        }
        return parts.filter { it.isNotBlank() }.joinToString("\n\n")
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Headings inside text segments, in document order (in-note outline, §12). */
    fun headings(segments: List<DocSegment>): List<OutlineEntry> {
        val out = mutableListOf<OutlineEntry>()
        val headingRe = Regex("(?is)<h([1-3])[^>]*>(.*?)</h\\1>")
        segments.forEachIndexed { index, segment ->
            if (segment is TextSegment) {
                for (match in headingRe.findAll(segment.html)) {
                    val title = PlainTextCodec.htmlToText(match.groupValues[2]).trim()
                    if (title.isNotEmpty()) {
                        out += OutlineEntry(index, match.groupValues[1].toInt(), title)
                    }
                }
            }
        }
        return out
    }

    private val backlinkRe = Regex("""\[\[([^\]|]+)(?:\|[^\]]*)?\]\]""")

    /** Distinct [[Note title]] targets referenced by [plainText], in order. */
    fun backlinkTitles(plainText: String): List<String> =
        backlinkRe.findAll(plainText).map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }.distinct().toList()

    // -------------------------------------------------------------- helpers

    private data class Tag(
        val name: String,
        val attrs: String,
        val closing: Boolean,
        val end: Int,
    )

    private fun readTag(s: String, start: Int): Tag? {
        if (start >= s.length || s[start] != '<') return null
        var i = start + 1
        var closing = false
        if (i < s.length && s[i] == '/') {
            closing = true
            i++
        }
        val nameStart = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-')) i++
        if (i == nameStart) return null
        val name = s.substring(nameStart, i).lowercase()
        var quote: Char? = null
        var j = i
        while (j < s.length) {
            val c = s[j]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                break
            }
            j++
        }
        if (j >= s.length) return null
        return Tag(name, s.substring(i, j), closing, j + 1)
    }

    /** Finds the matching close for an element opened before [from]:
     *  returns (contentEnd, elementEnd) or null when unbalanced. */
    private fun findClose(s: String, from: Int, name: String): Pair<Int, Int>? {
        var depth = 1
        var i = from
        while (i < s.length) {
            val lt = s.indexOf('<', i)
            if (lt < 0) return null
            val tag = readTag(s, lt) ?: return null
            if (tag.name == name) {
                if (tag.closing) {
                    depth--
                    if (depth == 0) return lt to tag.end
                } else if (!tag.attrs.trimEnd().endsWith("/")) {
                    depth++
                }
            }
            i = tag.end
        }
        return null
    }

    private fun attr(attrs: String, name: String): String {
        val doubleQuoted = Regex("""(?i)\b$name\s*=\s*"([^"]*)"""").find(attrs)
        if (doubleQuoted != null) return unescapeAttr(doubleQuoted.groupValues[1])
        val singleQuoted = Regex("""(?i)\b$name\s*=\s*'([^']*)'""").find(attrs)
        return singleQuoted?.let { unescapeAttr(it.groupValues[1]) } ?: ""
    }

    private fun isTaskList(ulAttrs: String, inner: String): Boolean =
        ulAttrs.contains("taskList", ignoreCase = true) ||
            inner.contains("data-checked", ignoreCase = true) ||
            inner.contains("taskItem", ignoreCase = true)

    private fun isChartBlock(attrs: String): Boolean =
        attrs.contains("data-nota-block", ignoreCase = true) ||
            attrs.contains("data-chart", ignoreCase = true) ||
            attrs.contains("nota-chart", ignoreCase = true)

    private fun parseTable(whole: String): TableSegment? {
        val rows = mutableListOf<List<String>>()
        var headerRow = false
        var idx = 0
        var firstRow = true
        while (true) {
            val trStart = whole.indexOf("<tr", idx, ignoreCase = true)
            if (trStart < 0) break
            val trTag = readTag(whole, trStart) ?: break
            val close = findClose(whole, trTag.end, "tr") ?: break
            val rowHtml = whole.substring(trTag.end, close.first)
            val cells = mutableListOf<String>()
            var allTh = true
            var cIdx = 0
            while (true) {
                val tdAt = rowHtml.indexOf("<td", cIdx, ignoreCase = true)
                val thAt = rowHtml.indexOf("<th", cIdx, ignoreCase = true)
                val cellAt = listOf(tdAt, thAt).filter { it >= 0 }.minOrNull() ?: break
                val cellTag = readTag(rowHtml, cellAt) ?: break
                val cellClose = findClose(rowHtml, cellTag.end, cellTag.name) ?: break
                cells += rowHtml.substring(cellTag.end, cellClose.first).trim()
                if (cellTag.name != "th") allTh = false
                cIdx = cellClose.second
            }
            if (cells.isNotEmpty()) {
                if (firstRow && allTh) headerRow = true
                rows += cells
            }
            firstRow = false
            idx = close.second
        }
        return if (rows.isEmpty()) null else TableSegment(rows, headerRow)
    }

    private fun parseTaskItems(inner: String): List<TaskItem> {
        val items = mutableListOf<TaskItem>()
        var idx = 0
        while (true) {
            val liStart = inner.indexOf("<li", idx, ignoreCase = true)
            if (liStart < 0) break
            val liTag = readTag(inner, liStart) ?: break
            val close = findClose(inner, liTag.end, "li") ?: break
            var content = inner.substring(liTag.end, close.first)
            val checked = liTag.attrs.contains("data-checked=\"true\"", ignoreCase = true) ||
                liTag.attrs.contains("data-checked='true'", ignoreCase = true) ||
                content.contains("checked", ignoreCase = true) &&
                content.contains("<input", ignoreCase = true)
            // Strip Tiptap's checkbox scaffolding: <label>...</label> and bare inputs.
            content = content.replace(Regex("(?is)<label[^>]*>.*?</label>"), "")
            content = content.replace(Regex("(?is)<input[^>]*>"), "")
            content = content.trim()
            // Unwrap one wrapping <div> (Tiptap wraps item content in a div).
            if (content.startsWith("<div", ignoreCase = true)) {
                val divTag = readTag(content, 0)
                val divClose = divTag?.let { findClose(content, it.end, "div") }
                if (divTag != null && divClose != null && divClose.second == content.length) {
                    content = content.substring(divTag.end, divClose.first).trim()
                }
            }
            items += TaskItem(content, checked)
            idx = close.second
        }
        return items
    }

    private fun extractCodeText(inner: String): String {
        var content = inner.trim()
        if (content.startsWith("<code", ignoreCase = true)) {
            val codeTag = readTag(content, 0)
            val close = codeTag?.let { findClose(content, it.end, "code") }
            if (codeTag != null && close != null) {
                content = content.substring(codeTag.end, close.first)
            }
        }
        return unescapeText(content)
    }

    private fun escapeText(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttr(value: String): String = value
        .replace("&", "&amp;").replace("\"", "&quot;")

    private fun unescapeAttr(value: String): String = value
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    private fun unescapeText(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&amp;", "&")
}
