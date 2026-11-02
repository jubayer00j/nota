package app.nota.feature.editor

import androidx.compose.ui.text.TextRange
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
