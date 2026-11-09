package app.nota.data.html

import app.nota.data.text.PlainTextCodec
import kotlin.math.roundToInt

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
 *
 * One dialect is normalised on the way in (Phase 2.5 fix F1): Tiptap —
 * the 1.x editor — wraps every list item's content in a `<p>`
 * (`<li><p>Alpha</p></li>`), a shape the rich-text library's setHtml
 * cannot represent: its parser promotes the item's paragraph out of the
 * list and drops the `<ul>`/`<ol>` entirely, so the editor's first
 * autosave permanently rewrote a restored 1.x note's lists as plain
 * paragraphs. List blocks are therefore rewritten to the bare-item
 * shape the library round-trips losslessly (`<li>Alpha</li>` — also
 * the shape Nota's own editor emits) before any text segment reaches
 * the editor. See [normalizeListBlock].
 */
sealed interface DocSegment

/** HTML the rich text editor may own (lossless shapes only). */
data class TextSegment(val html: String) : DocSegment

/** Span of one table cell (1 = not merged in that direction). */
data class TableCellSpan(val rowSpan: Int = 1, val colSpan: Int = 1)

/**
 * A table. [rows] are cell inner-HTML strings; [headerRow] marks row 0
 * as <th>. [cellSpans] parallels [rows] cell-for-cell and carries each
 * cell's rowspan/colspan so merged cells survive parse -> serialize;
 * it is empty for tables built in the editor (all spans are 1 there).
 */
data class TableSegment(
    val rows: List<List<String>>,
    val headerRow: Boolean,
    val cellSpans: List<List<TableCellSpan>> = emptyList(),
) : DocSegment

data class TaskItem(val contentHtml: String, val checked: Boolean)

/** A Tiptap task list (<ul data-type="taskList">). */
data class TaskListSegment(val items: List<TaskItem>) : DocSegment

/**
 * A standalone image. [src] may be an attachment id URI
 * (nota-attachment://<id>). [widthPercent] is the persisted display
 * width (Phase 1.5 fix 3), serialised as the `width="N%"` attribute —
 * the attribute Tiptap's image node declares and round-trips in 1.x,
 * whereas a `style` width is dropped there. 100 = full width and is
 * omitted from the HTML so default images stay byte-for-byte canonical.
 */
data class ImageSegment(
    val src: String,
    val alt: String = "",
    val widthPercent: Int = 100,
) : DocSegment

/**
 * A file attachment placed IN the document (Phase 3.5, D4). Before
 * 3.5 a non-image attachment had no document presence at all — it
 * lived only in the editor's attachments section, effectively pinned
 * to the end of the note. As a segment it sits at the caret like any
 * block, can be dragged among the paragraphs, and its position is
 * the document order, so it persists and backs up with the body.
 * [fileName]/[mimeType] are a display snapshot from insert time; the
 * attachments table stays the source of truth for size/existence.
 * Serialised as a `<div data-nota-file="…">` — a shape 1.x does not
 * model (it preserves the div opaquely) and [RichBody] counts as
 * rich, so a note holding one never falls into the plain editor.
 */
data class FileSegment(
    val attachmentId: String,
    val fileName: String,
    val mimeType: String = "",
) : DocSegment

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

    /** Display-width bounds for [ImageSegment.widthPercent] (Phase 1.5 fix 3). */
    const val IMAGE_MIN_WIDTH_PERCENT = 25
    const val IMAGE_MAX_WIDTH_PERCENT = 100
    const val IMAGE_DEFAULT_WIDTH_PERCENT = 100

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
                            text.append(normalizeListBlock(whole))
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
                        out += QuoteSegment(
                            normalizeListsInFragment(html.substring(tag.end, close.first).trim()),
                        )
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
                    out += imageSegmentFrom(tag)
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
                            // Phase 3.5 (D4): an in-document file
                            // block. Checked before the chart/opaque
                            // fallbacks; a div whose data-nota-file
                            // id is blank is not ours and falls
                            // through to preservation as opaque.
                            attr(tag.attrs, "data-nota-file").isNotBlank() -> {
                                flushText()
                                out += FileSegment(
                                    attachmentId = attr(tag.attrs, "data-nota-file"),
                                    fileName = attr(tag.attrs, "data-name"),
                                    mimeType = attr(tag.attrs, "data-mime"),
                                )
                            }
                            isChartBlock(tag.attrs) -> {
                                flushText(); out += ChartSegment(attr(tag.attrs, "data-payload"), whole)
                            }
                            tag.name == "figure" && inner.contains("<img", ignoreCase = true) -> {
                                flushText()
                                val img = Regex("(?is)<img[^>]*>").find(inner)
                                if (img != null) {
                                    val imgTag = readTag(inner, img.range.first)
                                    if (imgTag != null) {
                                        out += imageSegmentFrom(imgTag)
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
                in UNKNOWN_BLOCK_TAGS -> {
                    // Codex fix G6: a BARE top-level unknown block element
                    // (video, iframe, ...) must not fall into the text path,
                    // where the editor would mangle it. Preserve the whole
                    // subtree verbatim as opaque, like div/figure above.
                    // <embed> is a void element: no close tag to find.
                    val close = if (tag.name == "embed" || tag.attrs.trimEnd().endsWith("/")) {
                        null
                    } else {
                        findClose(html, tag.end, tag.name)
                    }
                    when {
                        tag.name == "embed" || tag.attrs.trimEnd().endsWith("/") -> {
                            flushText(); out += OpaqueSegment(html.substring(lt, tag.end)); i = tag.end
                        }
                        close == null -> {
                            text.append(html.substring(lt, tag.end)); i = tag.end
                        }
                        else -> {
                            flushText(); out += OpaqueSegment(html.substring(lt, close.second)); i = close.second
                        }
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

    /**
     * Block-level tags the guard does not model. Handled tags (table, ul,
     * ol, blockquote, pre, hr, img, p, div, figure) and the text-path tags
     * (headings, lists items, inline marks) are deliberately absent: only
     * genuinely unknown blocks land here and become [OpaqueSegment]s.
     */
    private val UNKNOWN_BLOCK_TAGS = setOf(
        "video", "audio", "iframe", "object", "embed",
        "section", "article", "aside", "header", "footer", "nav", "main",
        "fieldset", "form", "details", "address", "picture",
    )

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
            out += imageSegmentFrom(imgTag)
            idx = imgTag.end
        }
    }

    // ------------------------------------------------- list dialect guard

    /**
     * Rewrites one `<ul>`/`<ol>` block to the bare-item dialect the
     * rich-text library round-trips losslessly (Phase 2.5 fix F1, see
     * the class doc): the root tag (attributes included — `start`,
     * `type`, …) is preserved verbatim, each `<li>` is normalised by
     * [normalizeListItem], stray nested lists recurse, and anything
     * else is copied verbatim. A block already in the bare dialect is
     * returned byte-for-byte unchanged.
     */
    private fun normalizeListBlock(whole: String): String {
        val root = readTag(whole, 0) ?: return whole
        if (root.name != "ul" && root.name != "ol") return whole
        val close = findClose(whole, root.end, root.name) ?: return whole
        val inner = whole.substring(root.end, close.first)
        return buildString {
            append(whole.substring(0, root.end))
            append(normalizeListItems(inner))
            append(whole.substring(close.first, close.second))
        }
    }

    /** The direct children of a `<ul>`/`<ol>`: every `<li>` is
     *  normalised, stray nested lists recurse, other markup is copied
     *  verbatim. Whitespace-only text between items is insignificant
     *  in list markup (Tiptap emits none) and is dropped. */
    private fun normalizeListItems(inner: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val lt = inner.indexOf('<', i)
            if (lt < 0) {
                val rest = inner.substring(i)
                if (rest.isNotBlank()) out.append(rest)
                break
            }
            val between = inner.substring(i, lt)
            if (between.isNotBlank()) out.append(between)
            val tag = readTag(inner, lt) ?: run {
                out.append(inner.substring(lt))
                break
            }
            when {
                tag.closing -> {
                    out.append(inner.substring(lt, tag.end)); i = tag.end
                }
                tag.name == "li" -> {
                    val close = findClose(inner, tag.end, "li")
                    if (close == null) {
                        out.append(inner.substring(lt, tag.end)); i = tag.end
                    } else {
                        out.append(inner.substring(lt, tag.end))
                        out.append(normalizeListItem(inner.substring(tag.end, close.first)))
                        out.append(inner.substring(close.first, close.second))
                        i = close.second
                    }
                }
                tag.name == "ul" || tag.name == "ol" -> {
                    val close = findClose(inner, tag.end, tag.name)
                    if (close == null) {
                        out.append(inner.substring(lt, tag.end)); i = tag.end
                    } else {
                        val whole = inner.substring(lt, close.second)
                        // A nested TASK list is preserved verbatim,
                        // exactly like a top-level one (Codex P2,
                        // Phase 2.5): its items' <div><p> scaffolding
                        // must not be unwrapped by normalisation.
                        out.append(
                            if (isTaskList(tag.attrs, inner.substring(tag.end, close.first))) {
                                whole
                            } else {
                                normalizeListBlock(whole)
                            },
                        )
                        i = close.second
                    }
                }
                else -> {
                    out.append(inner.substring(lt, tag.end)); i = tag.end
                }
            }
        }
        return out.toString()
    }

    /**
     * One list item's content with its `<p>` children unwrapped — the
     * actual dialect fix. Paragraphs become their inner HTML (several
     * in a row are joined by `<br>`, the bare dialect's line break),
     * nested lists are normalised recursively, and all other content
     * (inline markup, text) is preserved verbatim. An item with no
     * `<p>` child is returned untouched, so Nota-native items — and
     * the guard's byte-for-byte stability for canonical input — are
     * unaffected.
     */
    private fun normalizeListItem(inner: String): String {
        // Chunk kinds: 0 = raw span, 1 = paragraph, 2 = nested list.
        val chunks = mutableListOf<Pair<Int, String>>()
        val raw = StringBuilder()
        fun flushRaw() {
            if (raw.isNotEmpty()) {
                chunks += 0 to raw.toString()
                raw.clear()
            }
        }
        var i = 0
        while (i < inner.length) {
            val lt = inner.indexOf('<', i)
            if (lt < 0) {
                raw.append(inner.substring(i))
                break
            }
            raw.append(inner.substring(i, lt))
            val tag = readTag(inner, lt) ?: run {
                raw.append('<')
                i = lt + 1
                continue
            }
            when {
                !tag.closing && tag.name == "p" -> {
                    val close = findClose(inner, tag.end, "p")
                    if (close == null) {
                        raw.append(inner.substring(lt, tag.end)); i = tag.end
                    } else {
                        flushRaw()
                        chunks += 1 to inner.substring(tag.end, close.first)
                        i = close.second
                    }
                }
                !tag.closing && (tag.name == "ul" || tag.name == "ol") -> {
                    val close = findClose(inner, tag.end, tag.name)
                    if (close == null) {
                        raw.append(inner.substring(lt, tag.end)); i = tag.end
                    } else {
                        flushRaw()
                        val whole = inner.substring(lt, close.second)
                        // Nested TASK lists stay verbatim (see
                        // normalizeListItems) — never unwrapped.
                        chunks += 2 to if (
                            isTaskList(tag.attrs, inner.substring(tag.end, close.first))
                        ) {
                            whole
                        } else {
                            normalizeListBlock(whole)
                        }
                        i = close.second
                    }
                }
                else -> {
                    raw.append(inner.substring(lt, tag.end)); i = tag.end
                }
            }
        }
        flushRaw()
        if (chunks.none { it.first == 1 }) return inner
        val out = StringBuilder()
        var prevPara = false
        for ((kind, html) in chunks) {
            when (kind) {
                1 -> {
                    if (prevPara) out.append("<br>")
                    out.append(html)
                    prevPara = true
                }
                2 -> {
                    out.append(html)
                    prevPara = false
                }
                else -> {
                    // Whitespace between block chunks is insignificant.
                    if (html.isNotBlank()) {
                        out.append(html)
                        prevPara = false
                    }
                }
            }
        }
        return out.toString().trim()
    }

    /**
     * Applies [normalizeListBlock] to every non-task list inside an
     * HTML fragment. Quote bodies reach the editor as rich text too,
     * so their lists need the same guard; task lists are left verbatim
     * (they are segmented and modelled separately in [parse]).
     */
    private fun normalizeListsInFragment(html: String): String {
        if (!html.contains("<ul", ignoreCase = true) &&
            !html.contains("<ol", ignoreCase = true)
        ) {
            return html
        }
        val out = StringBuilder()
        var i = 0
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                out.append(html.substring(i))
                break
            }
            val tag = readTag(html, lt)
            if (tag == null || tag.closing || (tag.name != "ul" && tag.name != "ol")) {
                val end = if (tag == null) lt + 1 else tag.end
                out.append(html.substring(i, end))
                i = end
                continue
            }
            out.append(html.substring(i, lt))
            val close = findClose(html, tag.end, tag.name)
            if (close == null) {
                out.append(html.substring(lt, tag.end))
                i = tag.end
                continue
            }
            val whole = html.substring(lt, close.second)
            val inner = html.substring(tag.end, close.first)
            out.append(if (isTaskList(tag.attrs, inner)) whole else normalizeListBlock(whole))
            i = close.second
        }
        return out.toString()
    }

    // ------------------------------------------------------------ serialize

    fun serialize(segments: List<DocSegment>): String = buildString {
        for (segment in segments) {
            when (segment) {
                is TextSegment -> append(segment.html)
                is TableSegment -> append(serializeTable(segment))
                is TaskListSegment -> append(serializeTaskList(segment))
                is ImageSegment -> {
                    append("<p><img src=\"").append(escapeAttr(segment.src))
                        .append("\" alt=\"").append(escapeAttr(segment.alt)).append("\"")
                    val w = segment.widthPercent.coerceIn(
                        IMAGE_MIN_WIDTH_PERCENT, IMAGE_MAX_WIDTH_PERCENT,
                    )
                    if (w != IMAGE_DEFAULT_WIDTH_PERCENT) {
                        append(" width=\"").append(w).append("%\"")
                    }
                    append("></p>")
                }
                is FileSegment -> {
                    append("<div data-nota-file=\"").append(escapeAttr(segment.attachmentId))
                        .append("\" data-name=\"").append(escapeAttr(segment.fileName))
                        .append("\" data-mime=\"").append(escapeAttr(segment.mimeType))
                        .append("\"></div>")
                }
                is QuoteSegment -> append("<blockquote>").append(segment.innerHtml).append("</blockquote>")
                is CodeSegment -> append("<pre><code>").append(escapeText(segment.text)).append("</code></pre>")
                DividerSegment -> append("<hr>")
                is ChartSegment -> append(segment.rawHtml)
                is OpaqueSegment -> append(segment.rawHtml)
            }
        }
    }

    /** Builds an [ImageSegment] from a parsed `<img>` tag (all parse paths). */
    private fun imageSegmentFrom(tag: Tag): ImageSegment = ImageSegment(
        src = attr(tag.attrs, "src"),
        alt = attr(tag.attrs, "alt"),
        widthPercent = parseImageWidthPercent(tag.attrs),
    )

    /**
     * Reads the persisted display width from an `<img>` tag's attributes.
     * The canonical form is the percent `width="N%"` attribute; a
     * `style="width: N%"` written by another editor is rescued as a
     * fallback. Bare pixel widths (`width="600"`) are deliberately NOT
     * interpreted as percents — they fall back to the full-width default.
     * Out-of-range values are coerced into
     * [IMAGE_MIN_WIDTH_PERCENT]..[IMAGE_MAX_WIDTH_PERCENT] rather than
     * failing the parse.
     */
    private fun parseImageWidthPercent(attrs: String): Int {
        val w = attr(attrs, "width").trim()
        if (w.endsWith("%")) {
            w.dropLast(1).trim().toIntOrNull()?.let {
                return it.coerceIn(IMAGE_MIN_WIDTH_PERCENT, IMAGE_MAX_WIDTH_PERCENT)
            }
        }
        val style = attr(attrs, "style")
        // Property boundary required: without it, "max-width: 50%" or
        // "min-width: 30%" would match their "width:" suffix and be
        // rewritten as a fixed display width (Codex review, PR #6).
        Regex("""(?i)(?:^|;)\s*width\s*:\s*(\d+(?:\.\d+)?)\s*%""").find(style)?.let {
            return it.groupValues[1].toDouble().roundToInt()
                .coerceIn(IMAGE_MIN_WIDTH_PERCENT, IMAGE_MAX_WIDTH_PERCENT)
        }
        return IMAGE_DEFAULT_WIDTH_PERCENT
    }

    // --------------------------------------------- trailing caret segment

    /**
     * True when [segment] is a text segment with no visible content and no
     * embedded structure — "", `"<p></p>"`, `"<p><br></p>"`,
     * `"<p>&nbsp;</p>"`. A text segment holding a list, table or image is
     * user content even when its visible text is empty, and is NOT empty
     * by this definition (Phase 1.5 fix 2).
     */
    fun isEmptyTextSegment(segment: DocSegment): Boolean {
        if (segment !is TextSegment) return false
        if (segment.html.isBlank()) return true
        if (Regex("(?i)<(img|hr|table|ul|ol|input)[\\s>/]").containsMatchIn(segment.html)) {
            return false
        }
        val text = PlainTextCodec.htmlToText(segment.html)
            .replace("&nbsp;", " ").replace('\u00A0', ' ')
        return text.isBlank()
    }

    /**
     * Editor working-state form (Phase 1.5 fix 2): exactly one trailing
     * [TextSegment] to hold a caret below a trailing non-text block.
     * Existing trailing empty text segments collapse into that one; a
     * document already ending in a non-empty text segment is returned
     * unchanged. Applied ONLY at the editor boundary — [parse] itself
     * stays the guard's faithful decomposition for non-editor callers.
     */
    fun ensureTrailingTextSegment(segments: List<DocSegment>): List<DocSegment> {
        val trimmed = segments.dropLastWhile { isEmptyTextSegment(it) }
        return when {
            trimmed.isEmpty() -> listOf(TextSegment(""))
            trimmed.last() is TextSegment -> trimmed
            else -> trimmed + TextSegment("")
        }
    }

    /**
     * Storage form (Phase 1.5 fix 2): the caret segment is ephemeral and
     * is never persisted, so repeated open→save cycles cannot accumulate
     * empty paragraphs. Only TRAILING empty text segments are removed —
     * an empty text segment in the middle of a document is left alone.
     *
     * Phase 2.8 (E1) extends the same rule one level down: tap-anywhere
     * caret placement grows the LAST text segment with scratch empty
     * paragraphs, so the final text segment also has its trailing empty
     * paragraphs trimmed (see [trimTrailingEmptyParagraphs]). Empty
     * paragraphs in the middle of a segment — real spacing the user
     * typed into — are untouched, exactly like middle segments.
     */
    fun stripTrailingEmptyTextSegments(segments: List<DocSegment>): List<DocSegment> {
        val dropped = segments.dropLastWhile { isEmptyTextSegment(it) }
        val last = dropped.lastOrNull() as? TextSegment ?: return dropped
        val trimmedHtml = trimTrailingEmptyParagraphs(last.html)
        if (trimmedHtml == last.html) return dropped
        // The trim cannot legitimately empty the segment: a segment
        // whose blocks are ALL empty paragraphs was already dropped
        // above (emptiness there is judged on the whole segment), and
        // a segment holding structure keeps that structure. The blank
        // guard is belt-and-braces for hand-built segment lists.
        if (trimmedHtml.isBlank()) return dropped.dropLast(1)
        return dropped.dropLast(1) + TextSegment(trimmedHtml)
    }

    /** Tags whose empty instances are caret scratch, never content. */
    private val TRIMMABLE_PARAGRAPH_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6")

    /** HTML void elements: no close tag, a chunk by themselves. */
    private val VOID_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )

    /** Embedded structure that makes even a textually blank block user
     *  content (the same test [isEmptyTextSegment] applies). */
    private val STRUCTURE_RE = Regex("(?i)<(img|hr|table|ul|ol|input)[\\s>/]")

    /**
     * Removes trailing empty paragraphs (`<p></p>`, `<p><br></p>`,
     * `<p>&nbsp;</p>`, empty headings) from the END of one text
     * segment's HTML — the scratch padding the tap-anywhere caret
     * (Phase 2.8 E1) appends below the last line. The rich editor's
     * HTML dialect serialises trailing empty paragraphs as bare
     * `<br>` markers (`<p>Hello</p><br><br>` is "Hello" plus empty
     * paragraphs), so a trailing top-level `<br>` is the same scratch
     * and is trimmed too; a `<br>` inside a paragraph or followed by
     * content is untouched. Stops at the first block with visible
     * text or embedded structure, so spacing the user actually typed
     * into (empty paragraphs with content below them) is preserved
     * byte-for-byte, as is everything before it.
     */
    fun trimTrailingEmptyParagraphs(html: String): String {
        if (html.isBlank()) return html
        val chunks = topLevelChunks(html)
        var kept = chunks.size
        while (kept > 0) {
            val range = chunks[kept - 1]
            val chunk = html.substring(range.first, range.last + 1)
            val droppable = chunk.isBlank() || isLineBreakChunk(chunk) ||
                isEmptyParagraphChunk(chunk)
            if (!droppable) break
            kept--
        }
        if (kept == chunks.size) return html
        val cut = if (kept == 0) 0 else chunks[kept - 1].last + 1
        return html.substring(0, cut)
    }

    /** True when [chunk] is exactly one `<br>` element — the rich
     *  editor's serialised form of a trailing empty paragraph. */
    private fun isLineBreakChunk(chunk: String): Boolean {
        val tag = readTag(chunk, 0) ?: return false
        return !tag.closing && tag.name == "br" && tag.end == chunk.length
    }

    /** True when [chunk] is exactly one empty paragraph-level element. */
    private fun isEmptyParagraphChunk(chunk: String): Boolean {
        val tag = readTag(chunk, 0) ?: return false
        if (tag.closing || tag.name !in TRIMMABLE_PARAGRAPH_TAGS) return false
        val close = findClose(chunk, tag.end, tag.name) ?: return false
        if (close.second != chunk.length) return false
        val inner = chunk.substring(tag.end, close.first)
        if (STRUCTURE_RE.containsMatchIn(inner)) return false
        val text = PlainTextCodec.htmlToText(inner)
            .replace("&nbsp;", " ").replace('\u00A0', ' ')
        return text.isBlank()
    }

    /**
     * Splits [html] into its top-level chunks: text runs and whole
     * elements (open tag through matching close, tracking nesting
     * depth generically). Void and self-closing elements are chunks by
     * themselves; a stray `<` that does not open a tag is a one-char
     * chunk. Chunks tile the input exactly, so reassembly is lossless.
     */
    private fun topLevelChunks(html: String): List<IntRange> {
        val chunks = mutableListOf<IntRange>()
        val n = html.length
        var i = 0
        while (i < n) {
            if (html[i] != '<') {
                var j = html.indexOf('<', i)
                if (j < 0) j = n
                chunks += i until j
                i = j
                continue
            }
            val tag = readTag(html, i)
            if (tag == null) {
                chunks += i until i + 1
                i += 1
                continue
            }
            val selfClosing = tag.closing ||
                tag.attrs.trimEnd().endsWith("/") || tag.name in VOID_TAGS
            if (selfClosing) {
                chunks += i until tag.end
                i = tag.end
                continue
            }
            var depth = 1
            var j = tag.end
            var end = n
            while (j < n) {
                val lt = html.indexOf('<', j)
                if (lt < 0) {
                    j = n
                    break
                }
                val inner = readTag(html, lt)
                if (inner == null) {
                    j = lt + 1
                    continue
                }
                val innerSelfClosing = inner.attrs.trimEnd().endsWith("/") ||
                    inner.name in VOID_TAGS
                if (inner.closing) {
                    depth--
                    if (depth == 0) {
                        end = inner.end
                        break
                    }
                } else if (!innerSelfClosing) {
                    depth++
                }
                j = inner.end
            }
            chunks += i until end
            i = end
        }
        return chunks
    }

    private fun serializeTable(table: TableSegment): String = buildString {
        append("<table><tbody>")
        table.rows.forEachIndexed { index, row ->
            append("<tr>")
            val cellTag = if (index == 0 && table.headerRow) "th" else "td"
            row.forEachIndexed { cellIndex, cell ->
                val span = table.cellSpans.getOrNull(index)?.getOrNull(cellIndex)
                    ?: TableCellSpan()
                append('<').append(cellTag)
                if (span.rowSpan > 1) append(" rowspan=\"").append(span.rowSpan).append('"')
                if (span.colSpan > 1) append(" colspan=\"").append(span.colSpan).append('"')
                append('>').append(cell).append("</").append(cellTag).append('>')
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

    /**
     * Phase 6 polish (N5): replaces a task item's label text with
     * [newText] while preserving the item's inline markup. The
     * editor's label field is a plain-text cell (the table-cell
     * pattern), and the old edit path rebuilt the item as escaped
     * plain text — the moment a label was touched, any bold, italic
     * or link inside it was destroyed.
     *
     * The fidelity this guarantees: every element and attribute in
     * [contentHtml] survives byte-identical, and the (escaped) new
     * text is carried by the item's first non-blank text run; any
     * later text runs are emptied (their elements remain). For the
     * shapes task items actually take — one <p> wrapping plain or
     * singly-formatted text — that is exactly "same formatting, new
     * words": <p><strong>Old</strong></p> → <p><strong>New</strong></p>.
     * For mixed markup (<p><strong>Buy</strong> milk</p>) the text
     * consolidates into the first run (<p><strong>Buy oat milk</strong></p>):
     * the structure survives, the formatting coverage of later runs
     * does not — the honest limit of a plain-text label editor.
     */
    fun replaceTaskItemText(contentHtml: String, newText: String): String {
        val trimmed = contentHtml.trim()
        // The canonical item shape is a single wrapping <p>: edit
        // inside it so the paragraph element itself (and any
        // attributes it carries) stays byte-identical.
        if (trimmed.startsWith("<p", ignoreCase = true)) {
            val open = readTag(trimmed, 0)
            val close = open?.let { findClose(trimmed, it.end, "p") }
            if (open != null && close != null && close.second == trimmed.length) {
                val inner = trimmed.substring(open.end, close.first)
                return trimmed.substring(0, open.end) +
                    replaceFragmentText(inner, newText) +
                    trimmed.substring(close.first)
            }
        }
        return replaceFragmentText(trimmed, newText)
    }

    /** The text-run surgery behind [replaceTaskItemText]: tags are
     *  copied verbatim; the first non-blank text run is replaced by
     *  the escaped [newText]; every other text run is dropped. A
     *  fragment with no text run at all gains the text at its end. */
    private fun replaceFragmentText(fragment: String, newText: String): String {
        // Collect the text runs (spans outside any tag).
        val runs = mutableListOf<IntRange>()
        var i = 0
        var runStart = 0
        while (i < fragment.length) {
            val lt = fragment.indexOf('<', i)
            if (lt < 0) break
            val tag = readTag(fragment, lt)
            if (tag == null) {
                i = lt + 1
                continue
            }
            if (lt > runStart) runs += runStart until lt
            i = tag.end
            runStart = i
        }
        if (fragment.length > runStart) runs += runStart until fragment.length

        val carrier = runs.firstOrNull { fragment.substring(it).isNotBlank() }
            ?: runs.firstOrNull()
        if (carrier == null) return fragment + escapeText(newText)

        val out = StringBuilder()
        var cursor = 0
        for (run in runs) {
            out.append(fragment, cursor, run.first)
            if (run === carrier) out.append(escapeText(newText))
            cursor = run.last + 1
        }
        out.append(fragment, cursor, fragment.length)
        return out.toString()
    }

    // ------------------------------------- paragraph ⇄ task conversion

    /**
     * Phase 6 (Editor Refinement #2): true when [html] is exactly one
     * plain top-level paragraph — the only shape "Make task" converts.
     * Headings, lists (the editor's range serialisation wraps a caret
     * paragraph that lives in one), multi-block fragments and bare
     * text runs are all refused: the caret's paragraph must BE a <p>,
     * not live inside some other structure.
     */
    fun isPlainParagraphHtml(html: String): Boolean {
        val trimmed = html.trim()
        if (trimmed.isEmpty()) return false
        val chunks = topLevelChunks(trimmed)
        if (chunks.size != 1) return false
        val tag = readTag(trimmed, 0) ?: return false
        if (tag.closing || tag.name != "p") return false
        val close = findClose(trimmed, tag.end, "p") ?: return false
        return close.second == trimmed.length
    }

    /**
     * The number of leaf paragraphs in a text segment's HTML — the
     * ordinal space the editor's paragraph ranges live in. A
     * paragraph or heading chunk is one leaf; a list chunk
     * contributes one leaf per item; anything else counts once.
     * Used to aim the caret after a conversion merges text segments.
     */
    fun leafParagraphCount(html: String): Int {
        var count = 0
        for (range in topLevelChunks(html)) {
            val chunk = html.substring(range)
            val tag = if (chunk.startsWith("<")) readTag(chunk, 0) else null
            count += when {
                tag == null || tag.closing -> 1
                tag.name == "ul" || tag.name == "ol" ->
                    Regex("(?i)<li[\\s>]").findAll(chunk).count().coerceAtLeast(1)
                else -> 1
            }
        }
        return count
    }

    /**
     * The outcome of a paragraph ⇄ task conversion (Phase 6, Editor
     * Refinement #2): the rebuilt segment list, plus where the
     * converted content landed so the editor can restore focus —
     * [convertedIndex] is the segment holding it, and
     * [convertedOrdinal] is the item's index inside that task list
     * (paragraph → task) or the first converted paragraph's ordinal
     * inside that text segment (task → text). [sourceIndices] runs
     * parallel to [segments]: for each output segment, the index of
     * the INPUT segment it derives from (a split text segment's
     * halves both name the original; a task list that absorbed an
     * item names itself), or -1 for a segment minted by the
     * conversion. The editor uses it to keep live segment ids (and
     * their states) attached to the pieces that survived.
     */
    data class ParagraphConversion(
        val segments: List<DocSegment>,
        val convertedIndex: Int,
        val convertedOrdinal: Int,
        val sourceIndices: List<Int>,
    )

    /**
     * "Make task": converts ONE paragraph of the text segment at
     * [segmentIndex] into a task item. The caller supplies the
     * segment's HTML in three parts — [beforeHtml] and [afterHtml]
     * are everything before/after the caret's paragraph and
     * [paragraphHtml] is the paragraph itself (the editor's own
     * range serialisation supplies all three, so inline marks
     * survive exactly). The paragraph must be a plain top-level <p>
     * ([isPlainParagraphHtml]); anything else returns null.
     *
     * The before/after halves stay as text segments (an empty half
     * is dropped). When a half is empty and the segment on that side
     * is a task list, the item joins THAT list instead of minting a
     * one-item block between the halves; when both sides qualify,
     * the two lists and the item fuse into a single list. The new
     * item starts unchecked.
     */
    fun paragraphToTask(
        segments: List<DocSegment>,
        segmentIndex: Int,
        beforeHtml: String,
        paragraphHtml: String,
        afterHtml: String,
    ): ParagraphConversion? {
        if (segments.getOrNull(segmentIndex) !is TextSegment) return null
        if (!isPlainParagraphHtml(paragraphHtml)) return null
        val item = TaskItem(paragraphHtml.trim(), checked = false)
        val before = beforeHtml.trim()
        val after = afterHtml.trim()
        val prefix = segments.subList(0, segmentIndex)
            .mapIndexed { i, s -> s to i }.toMutableList()
        val suffix = segments.subList(segmentIndex + 1, segments.size)
            .mapIndexed { i, s -> s to (segmentIndex + 1 + i) }.toMutableList()
        val aboveList = prefix.lastOrNull()?.first as? TaskListSegment
        val belowList = suffix.firstOrNull()?.first as? TaskListSegment
        val joinAbove = aboveList != null && before.isEmpty()
        val joinBelow = belowList != null && after.isEmpty()
        val middle = mutableListOf<Pair<DocSegment, Int>>()
        var convertedIndex = -1
        var convertedOrdinal = 0
        when {
            joinAbove && joinBelow -> {
                prefix.removeAt(prefix.lastIndex)
                suffix.removeAt(0)
                convertedOrdinal = aboveList!!.items.size
                middle += (
                    TaskListSegment(aboveList.items + item + belowList!!.items) to
                        (segmentIndex - 1)
                    )
                convertedIndex = prefix.size
            }
            joinAbove -> {
                prefix.removeAt(prefix.lastIndex)
                convertedOrdinal = aboveList!!.items.size
                middle += (TaskListSegment(aboveList.items + item) to (segmentIndex - 1))
                if (after.isNotEmpty()) middle += (TextSegment(after) to segmentIndex)
                convertedIndex = prefix.size
            }
            joinBelow -> {
                suffix.removeAt(0)
                if (before.isNotEmpty()) middle += (TextSegment(before) to segmentIndex)
                convertedIndex = prefix.size + middle.size
                middle += (
                    TaskListSegment(listOf(item) + belowList!!.items) to
                        (segmentIndex + 1)
                    )
                convertedOrdinal = 0
            }
            else -> {
                if (before.isNotEmpty()) middle += (TextSegment(before) to segmentIndex)
                convertedIndex = prefix.size + middle.size
                middle += (TaskListSegment(listOf(item)) to -1)
                if (after.isNotEmpty()) middle += (TextSegment(after) to segmentIndex)
            }
        }
        val out = prefix + middle + suffix
        return ParagraphConversion(
            segments = out.map { it.first },
            convertedIndex = convertedIndex,
            convertedOrdinal = convertedOrdinal,
            sourceIndices = out.map { it.second },
        )
    }

    /**
     * "Make text": converts the task item at ([segmentIndex],
     * [itemIndex]) back into plain paragraph(s) — the exact inverse
     * of [paragraphToTask]. The item's content keeps its block
     * structure (a multi-paragraph item becomes multiple
     * paragraphs); bare inline content gains its <p>.
     *
     * The list splits around the item; each non-empty half stays a
     * task list. The paragraph merges into a directly adjacent text
     * segment when one truly touches it — above only when the item
     * is the list's FIRST (nothing of the list stands between), below
     * only when it is the LAST — so document order is never changed
     * by a merge; otherwise it stands as its own text segment.
     */
    fun taskItemToText(
        segments: List<DocSegment>,
        segmentIndex: Int,
        itemIndex: Int,
    ): ParagraphConversion? {
        val list = segments.getOrNull(segmentIndex) as? TaskListSegment ?: return null
        val item = list.items.getOrNull(itemIndex) ?: return null
        val paragraphHtml = asTextHtml(item.contentHtml)
        val itemsBefore = list.items.subList(0, itemIndex)
        val itemsAfter = list.items.subList(itemIndex + 1, list.items.size)
        val prefix = segments.subList(0, segmentIndex)
            .mapIndexed { i, s -> s to i }.toMutableList()
        val suffix = segments.subList(segmentIndex + 1, segments.size)
            .mapIndexed { i, s -> s to (segmentIndex + 1 + i) }.toMutableList()
        val aboveText = prefix.lastOrNull()?.first as? TextSegment
        val belowText = suffix.firstOrNull()?.first as? TextSegment
        val mergeAbove = aboveText != null && itemsBefore.isEmpty()
        val mergeBelow = belowText != null && itemsAfter.isEmpty()
        val middle = mutableListOf<Pair<DocSegment, Int>>()
        var convertedOrdinal = 0
        if (itemsBefore.isNotEmpty()) {
            middle += (TaskListSegment(itemsBefore) to segmentIndex)
        }
        when {
            mergeAbove && mergeBelow -> {
                prefix.removeAt(prefix.lastIndex)
                suffix.removeAt(0)
                convertedOrdinal = leafParagraphCount(aboveText!!.html)
                middle += (
                    TextSegment(aboveText.html + paragraphHtml + belowText!!.html) to
                        (segmentIndex - 1)
                    )
            }
            mergeAbove -> {
                prefix.removeAt(prefix.lastIndex)
                convertedOrdinal = leafParagraphCount(aboveText!!.html)
                middle += (TextSegment(aboveText.html + paragraphHtml) to (segmentIndex - 1))
            }
            mergeBelow -> {
                suffix.removeAt(0)
                middle += (TextSegment(paragraphHtml + belowText!!.html) to (segmentIndex + 1))
            }
            else -> {
                middle += (TextSegment(paragraphHtml) to -1)
            }
        }
        val convertedIndex = prefix.size + middle.size - 1
        if (itemsAfter.isNotEmpty()) {
            middle += (TaskListSegment(itemsAfter) to segmentIndex)
        }
        val out = prefix + middle + suffix
        return ParagraphConversion(
            segments = out.map { it.first },
            convertedIndex = convertedIndex,
            convertedOrdinal = convertedOrdinal,
            sourceIndices = out.map { it.second },
        )
    }

    /**
     * Task item content as text-segment HTML: existing block
     * structure is kept whole; bare inline content gains its <p>;
     * an empty item becomes one empty paragraph.
     */
    private fun asTextHtml(contentHtml: String): String {
        val content = contentHtml.trim()
        if (content.isEmpty()) return "<p></p>"
        val tag = readTag(content, 0)
        val blockTags = setOf(
            "p", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "div", "blockquote", "pre", "table",
        )
        return if (tag != null && !tag.closing && tag.name in blockTags) {
            content
        } else {
            "<p>$content</p>"
        }
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
                is FileSegment -> segment.fileName
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

    /**
     * Phase 6 polish: classification is STRUCTURAL — it reads the
     * list's and items' OWN attributes only, never text content. The
     * previous heuristic substring-searched the whole inner HTML, so
     * a plain list whose visible text merely mentioned "data-checked"
     * or "taskItem" (a note about HTML, say) was misclassified as a
     * task list and silently rewritten in that dialect on save. A
     * list is a task list when its <ul> carries data-type="taskList"
     * (the 1.x/Tiptap and Nota serialisation), or when one of its
     * item elements carries the taskItem type / a data-checked
     * attribute of its own (dialects that mark items but not the
     * list).
     */
    private fun isTaskList(ulAttrs: String, inner: String): Boolean {
        if (attr(ulAttrs, "data-type").equals("taskList", ignoreCase = true)) return true
        var idx = 0
        while (true) {
            val liStart = inner.indexOf("<li", idx, ignoreCase = true)
            if (liStart < 0) return false
            val liTag = readTag(inner, liStart) ?: return false
            if (liTag.name == "li" &&
                (attr(liTag.attrs, "data-type").equals("taskItem", ignoreCase = true) ||
                    liTag.attrs.contains("data-checked", ignoreCase = true))
            ) {
                return true
            }
            idx = liTag.end
        }
    }

    private fun isChartBlock(attrs: String): Boolean =
        attrs.contains("data-nota-block", ignoreCase = true) ||
            attrs.contains("data-chart", ignoreCase = true) ||
            attrs.contains("nota-chart", ignoreCase = true)

    private fun parseTable(whole: String): TableSegment? {
        val rows = mutableListOf<List<String>>()
        val rowSpans = mutableListOf<List<TableCellSpan>>()
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
            val spans = mutableListOf<TableCellSpan>()
            var allTh = true
            var cIdx = 0
            while (true) {
                val tdAt = rowHtml.indexOf("<td", cIdx, ignoreCase = true)
                val thAt = rowHtml.indexOf("<th", cIdx, ignoreCase = true)
                val cellAt = listOf(tdAt, thAt).filter { it >= 0 }.minOrNull() ?: break
                val cellTag = readTag(rowHtml, cellAt) ?: break
                val cellClose = findClose(rowHtml, cellTag.end, cellTag.name) ?: break
                cells += rowHtml.substring(cellTag.end, cellClose.first).trim()
                spans += TableCellSpan(
                    rowSpan = attr(cellTag.attrs, "rowspan").toIntOrNull()
                        ?.coerceAtLeast(1) ?: 1,
                    colSpan = attr(cellTag.attrs, "colspan").toIntOrNull()
                        ?.coerceAtLeast(1) ?: 1,
                )
                if (cellTag.name != "th") allTh = false
                cIdx = cellClose.second
            }
            if (cells.isNotEmpty()) {
                if (firstRow && allTh) headerRow = true
                rows += cells
                rowSpans += spans
            }
            firstRow = false
            idx = close.second
        }
        return if (rows.isEmpty()) null else TableSegment(rows, headerRow, rowSpans)
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
            // Phase 6 polish: checked state comes from the item
            // element's OWN data-checked attribute only. The old
            // content fallback (the word "checked" plus an <input
            // anywhere in the item body) marked items checked when
            // their TEXT talked about checkboxes.
            val checked = attr(liTag.attrs, "data-checked")
                .equals("true", ignoreCase = true)
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

    // Entity decoding is the full HTML5 set, exactly once,
    // here where raw markup becomes text: "&copy;" parses to
    // the character a browser shows, and "&amp;copy;" stays
    // the literal text "&copy;" — never a double decode.
    private fun unescapeAttr(value: String): String =
        HtmlEntities.decode(value, inAttribute = true)

    private fun unescapeText(value: String): String = HtmlEntities.decode(value)
}
