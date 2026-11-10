package app.nota.feature.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Charts polish pack: the pure tap-for-values hit model. The
 * targets here are hand-built at the same final geometry the
 * draw pass records; these tests pin the resolution rules —
 * rect containment, nearest-point within the touch radius,
 * points beating rects, stack segments resolving individually,
 * and pie slice angle maths (wrap-around, donut hole).
 */
class ChartHitModelTest {

    private fun rect(
        left: Float, top: Float, right: Float, bottom: Float,
        category: Int = 0, series: Int = 0, value: Double = 1.0,
    ) = ChartHitTarget(
        kind = ChartHitKind.RECT,
        categoryIndex = category,
        seriesIndex = series,
        value = value,
        left = left, top = top, right = right, bottom = bottom,
        anchorX = (left + right) / 2f, anchorY = top,
    )

    private fun point(
        x: Float, y: Float,
        category: Int = 0, series: Int = 0, value: Double = 1.0,
    ) = ChartHitTarget(
        kind = ChartHitKind.POINT,
        categoryIndex = category,
        seriesIndex = series,
        value = value,
        cx = x, cy = y, anchorX = x, anchorY = y,
    )

    private fun slice(
        start: Float, sweep: Float,
        inner: Float = 0f, outer: Float = 80f,
        category: Int = 0, value: Double = 1.0,
    ) = ChartHitTarget(
        kind = ChartHitKind.SLICE,
        categoryIndex = category,
        seriesIndex = 0,
        value = value,
        cx = 100f, cy = 100f,
        innerRadius = inner, outerRadius = outer,
        startAngle = start, sweepAngle = sweep,
    )

    private fun polar(angleDeg: Double, r: Double): Pair<Float, Float> {
        val rad = Math.toRadians(angleDeg)
        return (100.0 + r * cos(rad)).toFloat() to (100.0 + r * sin(rad)).toFloat()
    }

    @Test
    fun rectHit_insideHits_outsideMisses() {
        val targets = listOf(rect(10f, 10f, 50f, 60f, value = 42.0))
        assertEquals(42.0, hitTest(targets, 30f, 30f, 28f)!!.value, 0.0)
        assertNull(hitTest(targets, 5f, 30f, 28f))
        assertNull(hitTest(targets, 30f, 70f, 28f))
        // The right/bottom edges are exclusive (adjacent bars own them).
        assertNull(hitTest(targets, 50f, 30f, 28f))
        assertNull(hitTest(targets, 30f, 60f, 28f))
    }

    @Test
    fun stackedSegments_resolveIndividually() {
        // Two segments of one stack column: lower [50,100], upper [0,50].
        val targets = listOf(
            rect(0f, 50f, 100f, 100f, series = 0, value = 30.0),
            rect(0f, 0f, 100f, 50f, series = 1, value = 70.0),
        )
        assertEquals(0, hitTest(targets, 50f, 75f, 28f)!!.seriesIndex)
        assertEquals(1, hitTest(targets, 50f, 25f, 28f)!!.seriesIndex)
    }

    @Test
    fun nearestPoint_withinRadiusWins_beyondMisses() {
        val targets = listOf(
            point(100f, 100f, category = 0, value = 1.0),
            point(200f, 100f, category = 1, value = 2.0),
        )
        assertEquals(0, hitTest(targets, 118f, 104f, 28f)!!.categoryIndex)
        assertEquals(1, hitTest(targets, 186f, 100f, 28f)!!.categoryIndex)
        // Midway is 50px from both — outside the 28px touch radius.
        assertNull(hitTest(targets, 150f, 100f, 28f))
    }

    @Test
    fun point_insideBarRect_beatsTheRect() {
        val targets = listOf(
            rect(0f, 0f, 300f, 300f, series = 0, value = 5.0),
            point(100f, 100f, series = 1, value = 9.0),
        )
        val hit = hitTest(targets, 104f, 100f, 28f)
        assertEquals(ChartHitKind.POINT, hit!!.kind)
        assertEquals(9.0, hit.value, 0.0)
        // Away from the point, the same tap lands on the bar.
        assertEquals(ChartHitKind.RECT, hitTest(targets, 250f, 250f, 28f)!!.kind)
    }

    @Test
    fun pieSlice_angleHitAndMiss() {
        // First slice: from straight up (270°) sweeping 90° to the +x axis.
        val targets = listOf(slice(start = 270f, sweep = 90f, value = 50.0))
        val (hx, hy) = polar(-45.0, 60.0) // mid-slice direction
        assertEquals(50.0, hitTest(targets, hx, hy, 28f)!!.value, 0.0)
        val (mx, my) = polar(45.0, 60.0) // the opposite quadrant
        assertNull(hitTest(targets, mx, my, 28f))
        // Beyond the outer radius, same angle: a miss.
        val (ox, oy) = polar(-45.0, 120.0)
        assertNull(hitTest(targets, ox, oy, 28f))
    }

    @Test
    fun pieSlice_wraparoundAcrossZero() {
        // A slice starting at 350° sweeping 30° covers 350..360 and 0..20.
        val targets = listOf(slice(start = 350f, sweep = 30f, value = 7.0))
        val (hx, hy) = polar(10.0, 60.0)
        assertEquals(7.0, hitTest(targets, hx, hy, 28f)!!.value, 0.0)
        val (mx, my) = polar(30.0, 60.0) // just past the slice's end
        assertNull(hitTest(targets, mx, my, 28f))
    }

    @Test
    fun donutHole_misses_ringHits() {
        val targets = listOf(slice(start = 270f, sweep = 90f, inner = 40f, value = 3.0))
        // The centre is inside the hole, not the slice.
        assertNull(hitTest(targets, 100f, 100f, 28f))
        val (hx, hy) = polar(-45.0, 60.0) // within the ring band
        assertTrue(hitTest(targets, hx, hy, 28f) != null)
        val (ix, iy) = polar(-45.0, 20.0) // inside the hole radius
        assertNull(hitTest(targets, ix, iy, 28f))
    }

    @Test
    fun slices_ignoreRectsAndPoints() {
        // A chart is pie-like or not: with slices present, only
        // slices resolve (a stray rect cannot swallow a pie tap).
        val targets = listOf(
            rect(0f, 0f, 200f, 200f, value = 99.0),
            slice(start = 270f, sweep = 90f, value = 50.0),
        )
        val (mx, my) = polar(135.0, 60.0) // inside the rect, outside the slice
        assertNull(hitTest(targets, mx, my, 28f))
    }

    @Test
    fun formatChartValue_jsStyle() {
        assertEquals("100", formatChartValue(100.0))
        assertEquals("1.5", formatChartValue(1.5))
        assertEquals("12.25", formatChartValue(12.25))
        assertEquals("0", formatChartValue(0.0))
        assertEquals("0", formatChartValue(-0.0))
        assertEquals("0", formatChartValue(Double.NaN))
        assertEquals("-50", formatChartValue(-50.0))
    }
}
