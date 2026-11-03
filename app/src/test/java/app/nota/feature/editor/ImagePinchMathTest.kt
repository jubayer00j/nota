package app.nota.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 3.6 (G4): the pinch→width mapping. The width follows the
 * span RATIO from the gesture's start, clamped to the document
 * layer's own width range (25%..100% — NoteDocument's constants), so
 * a persisted pinch result round-trips exactly like a corner-drag
 * result always has.
 */
class ImagePinchMathTest {

    @Test
    fun doublingTheSpan_doublesTheWidth() {
        assertEquals(80, ImagePinchMath.widthPercent(40, 100f, 200f))
    }

    @Test
    fun halvingTheSpan_halvesTheWidth() {
        assertEquals(40, ImagePinchMath.widthPercent(80, 200f, 100f))
    }

    @Test
    fun pinchOut_clampsAtFullWidth() {
        assertEquals(100, ImagePinchMath.widthPercent(90, 100f, 150f))
        assertEquals(100, ImagePinchMath.widthPercent(60, 100f, 200f))
    }

    @Test
    fun pinchIn_clampsAtMinimumWidth() {
        assertEquals(25, ImagePinchMath.widthPercent(30, 200f, 100f))
        assertEquals(25, ImagePinchMath.widthPercent(50, 300f, 100f))
    }

    @Test
    fun fractionalRatios_roundToNearestPercent() {
        // 33% * 1.5 = 49.5 → 50.
        assertEquals(50, ImagePinchMath.widthPercent(33, 100f, 150f))
        // 50% * 1.25 = 62.5 → 63 (round half up via roundToInt).
        assertEquals(63, ImagePinchMath.widthPercent(50, 100f, 125f))
    }

    @Test
    fun unchangedSpan_keepsTheWidth() {
        assertEquals(57, ImagePinchMath.widthPercent(57, 123f, 123f))
    }

    @Test
    fun degenerateSpans_leaveTheWidthAlone() {
        // A finger lifting mid-read must never jump the width.
        assertEquals(57, ImagePinchMath.widthPercent(57, 0f, 100f))
        assertEquals(57, ImagePinchMath.widthPercent(57, 100f, 0f))
        assertEquals(57, ImagePinchMath.widthPercent(57, -10f, 100f))
    }
}
