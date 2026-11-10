package app.nota.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

/**
 * Phase 6 (Editor Refinement #3): the [[ link-suggestion popup's
 * support logic — the trigger derivation, the per-field caret
 * geometry capture, and the popup positioning math. The popup
 * composable itself lives in RichNoteEditorScreen.kt next to the
 * screen state it reads.
 */
object LinkSuggestions {

    /** An in-progress [[ trigger: the index of the "[[" in the
     *  text + the query typed after it. */
    data class Trigger(val start: Int, val query: String)

    private val TRIGGER_REGEX = Regex("""\[\[([^\[\]\n]*)$""")

    /**
     * The trigger active at [caret] in [text], or null — the same
     * derivation the old above-toolbar chip strip used (an
     * unclosed "[[" running up to the caret, the query being
     * everything after it with no bracket or newline in between),
     * generalised from "the active text segment" to whichever
     * rich state is being edited.
     */
    fun triggerAt(text: String, caret: Int): Trigger? {
        val end = caret.coerceIn(0, text.length)
        val match = TRIGGER_REGEX.find(text.substring(0, end)) ?: return null
        return Trigger(match.range.first, match.groupValues[1])
    }
}

/**
 * One rich editor field's caret geometry, captured from the
 * library editor's own callbacks: the latest [TextLayoutResult]
 * (its onTextLayout parameter) and the field's position in the
 * window (onGloballyPositioned on the editor's modifier — placed
 * after any padding, so the origin is the text's origin). Held
 * per field in a screen-owned map keyed like the state maps
 * (segment id, or the table cell's "blockId-row-col" key).
 *
 * The values are snapshot state: the suggestion popup reads them
 * during composition and re-derives its anchor as the caret
 * moves, and the position provider reads them again at layout
 * time, so the popup tracks typing and scrolling.
 */
internal class EditorFieldGeometry {
    var layout: TextLayoutResult? by mutableStateOf(null)
    var windowPosition: Offset by mutableStateOf(Offset.Zero)
    var size: IntSize by mutableStateOf(IntSize.Zero)

    /** The caret rect in WINDOW coordinates, or null before the
     *  field's first layout result lands. */
    fun caretRectInWindow(caret: Int): IntRect? {
        val layout = layout ?: return null
        val coerced = caret.coerceIn(0, layout.layoutInput.text.text.length)
        val rect = layout.getCursorRect(coerced)
        return IntRect(
            left = (windowPosition.x + rect.left).roundToInt(),
            top = (windowPosition.y + rect.top).roundToInt(),
            right = (windowPosition.x + rect.right).roundToInt(),
            bottom = (windowPosition.y + rect.bottom).roundToInt(),
        )
    }
}

/** The geometry entry for [key], created on first use. */
internal fun MutableMap<String, EditorFieldGeometry>.geometryFor(
    key: String,
): EditorFieldGeometry = getOrPut(key) { EditorFieldGeometry() }

/**
 * Where the suggestion popup goes, in window coordinates: below
 * the caret line when it fits, flipped above when the space
 * below is insufficient, x clamped to [marginPx] screen margins.
 * Pure Int math — unit-tested without a composition.
 */
fun linkPopupOffset(
    anchor: IntRect,
    windowSize: IntSize,
    popupSize: IntSize,
    marginPx: Int,
    gapPx: Int,
): IntOffset {
    val maxX = (windowSize.width - marginPx - popupSize.width)
        .coerceAtLeast(marginPx)
    val x = anchor.left.coerceIn(marginPx, maxX)
    val below = anchor.bottom + gapPx
    val y = if (below + popupSize.height <= windowSize.height - marginPx) {
        below
    } else {
        (anchor.top - gapPx - popupSize.height).coerceAtLeast(marginPx)
    }
    return IntOffset(x, y)
}

/**
 * The popup's position provider: ignores the Popup's own anchor
 * bounds (its composition site spans the screen) and positions
 * from the live caret anchor instead, re-read on every layout
 * pass so the popup follows the caret as typing continues.
 */
internal class LinkPopupPositionProvider(
    private val anchorProvider: () -> IntRect,
    private val marginPx: Int,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = linkPopupOffset(
        anchor = anchorProvider(),
        windowSize = windowSize,
        popupSize = popupContentSize,
        marginPx = marginPx,
        gapPx = gapPx,
    )
}
