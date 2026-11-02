package app.nota.feature.editor

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * Phase 2.8 (E1) — tap-anywhere caret placement.
 *
 * Jubayer's ask: place the cursor anywhere in the editor space and
 * write there, without hammering space/Enter to manufacture room.
 * The below-content tap area (Phase 1.5 fix 2) used to drop the caret
 * at the very end of the document no matter where in the empty area
 * he tapped; now the tap's visual row is honoured: the document grows
 * with empty paragraphs up to the tapped row and the caret lands in
 * the last of them.
 *
 * Storage discipline: the padding is scratch. If he types, the empty
 * paragraphs above his text are real spacing and persist; if he
 * leaves without typing they are trailing empties in the last text
 * segment, which the storage form has always stripped — at segment
 * level since Phase 1.5 and at paragraph level since this phase
 * ([app.nota.data.html.NoteDocument.trimTrailingEmptyParagraphs]).
 */

/** Hard cap on the empty paragraphs a single tap may append. */
internal const val MAX_TAP_GROW_PARAGRAPHS = 50

/**
 * Visual rows below the last rendered line for a tap [tapYInTailPx]
 * into the below-content area. [gapAboveTailPx] is the vertical space
 * between the last text line and the top of the tap area (the list's
 * item spacing), so a tap right at the top of the area still counts
 * as row 0 — the end of the text itself (Phase 1.5 behaviour).
 */
internal fun tapRowsBelowContent(
    tapYInTailPx: Float,
    gapAboveTailPx: Float,
    lineHeightPx: Float,
): Int {
    if (lineHeightPx <= 0f || tapYInTailPx < 0f) return 0
    return ((tapYInTailPx + gapAboveTailPx) / lineHeightPx).toInt().coerceAtLeast(0)
}

/**
 * Places the caret [rowsBelow] visual rows below the last line of
 * this state's text, appending empty paragraphs at the end as
 * needed. Row 0 is the end of the text itself — the caret goes to
 * the very end and nothing is appended.
 *
 * Because the caret always lands in the LAST paragraph of the state,
 * the number of paragraphs to append equals the number of rows
 * (capped at [MAX_TAP_GROW_PARAGRAPHS]).
 *
 * Returns false — without mutating the text — when the document ends
 * inside a list: appended newlines there would mint phantom list
 * items, not paragraphs. The caller falls back to growing a separate
 * padding segment below the list instead.
 */
@OptIn(ExperimentalRichTextApi::class)
internal fun RichTextState.placeCaretBelowContent(rowsBelow: Int): Boolean {
    val text = annotatedString.text
    if (rowsBelow <= 0) {
        selection = TextRange(text.length)
        return true
    }
    // Probe the trailing paragraph's type before mutating anything:
    // the selection setter refreshes the list flags synchronously.
    selection = TextRange(text.length)
    if (isUnorderedList || isOrderedList) return false
    val grow = rowsBelow.coerceAtMost(MAX_TAP_GROW_PARAGRAPHS)
    addTextAtIndex(text.length, "\n".repeat(grow))
    selection = TextRange(annotatedString.text.length)
    return true
}

/**
 * HTML for a padding segment of [paragraphs] empty paragraphs — the
 * fallback growth for a document ending in a list, where the padding
 * must live in its own text segment below the list (Phase 2.8 E1).
 */
internal fun paddingSegmentHtml(paragraphs: Int): String =
    "<p></p>".repeat(paragraphs.coerceIn(1, MAX_TAP_GROW_PARAGRAPHS))
