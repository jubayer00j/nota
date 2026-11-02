package app.nota.feature.editor

import app.nota.data.html.NoteDocument
import kotlin.math.roundToInt

/**
 * The pinch-to-resize mapping for image blocks (Phase 3.6, G4).
 *
 * Jubayer's correction to the original 3.6 proposal: NO fixed size
 * presets — resizing an image should work like the gallery, two
 * fingers spreading or pinching. The gesture measures the span
 * between the two pointers; the width follows the span RATIO from
 * wherever the gesture started, so a pinch that doubles the span
 * doubles the width. The result persists through the document's
 * existing `width="N%"` attribute ([app.nota.data.html.ImageSegment.widthPercent]),
 * clamped to the same range the document layer itself enforces, so
 * backups and 1.x compatibility are untouched.
 */
object ImagePinchMath {

    /**
     * The width percent for a pinch that started at [startPercent]
     * with the pointers [startSpanPx] apart and has moved to
     * [currentSpanPx]. A degenerate span (a finger lifting mid-read)
     * leaves the width unchanged rather than jumping.
     */
    fun widthPercent(startPercent: Int, startSpanPx: Float, currentSpanPx: Float): Int {
        if (startSpanPx <= 0f || currentSpanPx <= 0f) return startPercent
        val scaled = startPercent * (currentSpanPx / startSpanPx)
        return scaled.roundToInt().coerceIn(
            NoteDocument.IMAGE_MIN_WIDTH_PERCENT,
            NoteDocument.IMAGE_MAX_WIDTH_PERCENT,
        )
    }
}
