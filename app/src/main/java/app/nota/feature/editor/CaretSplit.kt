package app.nota.feature.editor

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * The two halves of a text-bearing segment split at the caret
 * (Phase 3.5, D4). Each side's HTML is the library's own range
 * serialisation (`RichTextState.toHtml(range)`), so inline marks and
 * list structure survive the split exactly as a copy of that range
 * would carry them — the block inserted between the halves never has
 * to flatten or rewrite the surrounding text.
 */
data class CaretSplit(val leftHtml: String, val rightHtml: String)

/**
 * Splits [state]'s content at its caret. A non-collapsed selection
 * is deleted first (the standard insert-over-selection behaviour),
 * leaving the caret where the selection started. Beyond that
 * deletion the state's content is not modified: the caller decides
 * which halves survive and re-seeds states from the returned HTML.
 */
fun splitHtmlAtCaret(state: RichTextState): CaretSplit {
    val text = state.annotatedString.text
    val selection = state.selection
    val start = selection.start.coerceIn(0, text.length)
    val end = selection.end.coerceIn(0, text.length)
    if (start != end) {
        state.removeTextRange(TextRange(start, end))
    }
    val length = state.annotatedString.text.length
    val caret = state.selection.start.coerceIn(0, length)
    return CaretSplit(
        leftHtml = state.toHtml(TextRange(0, caret)),
        rightHtml = state.toHtml(TextRange(caret, length)),
    )
}

/**
 * Phase 6 (Editor Refinement #2): the paragraph holding the caret,
 * located through the annotated string's own paragraph ranges —
 * the library's ground truth for where paragraphs begin and end,
 * so no HTML offset arithmetic is ever guessed. [range] is the
 * paragraph's span of the raw text; [html] is the library's
 * serialisation of exactly that span (a plain paragraph serialises
 * as one `<p>`, a list item as a `<ul>` around its `<li>`, a
 * heading as its `<hN>` — which is how the caller tells them
 * apart). Range ownership: a paragraph's range INCLUDES its
 * trailing separator, so a caret at a paragraph's text end is
 * still inside that paragraph's range, and the caret at the next
 * paragraph's first character is one past it. The match is
 * therefore the first range whose end is strictly past the caret
 * (with the caret at the very end of the text falling to the last
 * range) — probe-verified against the shipped library build by
 * CaretSplitRobolectricTest.
 */
data class CaretParagraph(val range: TextRange, val html: String)

@OptIn(ExperimentalRichTextApi::class)
fun caretParagraph(state: RichTextState): CaretParagraph? {
    val annotated = state.annotatedString
    val text = annotated.text
    if (text.isEmpty()) return null
    val caret = state.selection.start.coerceIn(0, text.length)
    val ranges = annotated.paragraphStyles
    if (ranges.isEmpty()) {
        // No paragraph structure reported: the whole state is the
        // one paragraph there is.
        return CaretParagraph(TextRange(0, text.length), state.toHtml())
    }
    val match = ranges.firstOrNull { caret < it.end } ?: ranges.last()
    val range = TextRange(match.start, match.end)
    return CaretParagraph(range, state.toHtml(range))
}

/**
 * The three HTML parts a paragraph conversion consumes (see
 * [app.nota.data.html.NoteDocument.paragraphToTask]): everything
 * before the caret's paragraph, the paragraph itself, and
 * everything after it — each the library's own range serialisation,
 * so inline marks and structure survive on their own side.
 * Returns null when there is no caret paragraph (empty state).
 */
data class ParagraphParts(val beforeHtml: String, val paragraphHtml: String, val afterHtml: String)

@OptIn(ExperimentalRichTextApi::class)
fun paragraphPartsAtCaret(state: RichTextState): ParagraphParts? {
    val paragraph = caretParagraph(state) ?: return null
    val length = state.annotatedString.text.length
    // An empty range serialises as an empty PARAGRAPH ("<p></p>"),
    // not as nothing — the library emits the paragraph shell for
    // any range it is handed. The before/after parts of a caret in
    // the first/last paragraph are genuinely empty, so empty
    // ranges are answered directly instead of serialised.
    fun htmlOf(range: TextRange): String =
        if (range.start >= range.end) "" else state.toHtml(range)
    return ParagraphParts(
        beforeHtml = htmlOf(TextRange(0, paragraph.range.start)),
        paragraphHtml = paragraph.html,
        afterHtml = htmlOf(TextRange(paragraph.range.end, length)),
    )
}
