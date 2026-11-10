package app.nota.feature.chart

import java.math.BigDecimal
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Charts polish pack: the tap-for-values hit model — pure float
 * geometry, deliberately free of Compose types so the whole model
 * is plain-JVM unit-testable.
 *
 * Targets are RECORDED DURING THE DRAW PASS in ChartView, from the
 * same coordinates that were just drawn (never a parallel copy
 * that could drift), and always at their FINAL (progress = 1)
 * geometry, so a tap lands even while the entrance animation is
 * still running. All coordinates are canvas pixels.
 */
internal enum class ChartHitKind { RECT, POINT, SLICE }

internal data class ChartHitTarget(
    val kind: ChartHitKind,
    val categoryIndex: Int,
    val seriesIndex: Int,
    /** The datum this target stands for (a stack segment's own value, not the stack sum). */
    val value: Double,
    /** Pie/donut only: the slice's share of the whole, 0..1. */
    val percent: Double? = null,
    /** RECT geometry. */
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    /** POINT centre / SLICE centre. */
    val cx: Float = 0f,
    val cy: Float = 0f,
    /** SLICE geometry (angles in canvas degrees: 0 = +x axis, growing clockwise). */
    val innerRadius: Float = 0f,
    val outerRadius: Float = 0f,
    /** Normalised to [0, 360). */
    val startAngle: Float = 0f,
    val sweepAngle: Float = 0f,
    /** Where the callout chip anchors. */
    val anchorX: Float = 0f,
    val anchorY: Float = 0f,
)

/**
 * The draw pass's mailbox for hit targets: ChartView's draw
 * functions replace [targets] wholesale each pass; the tap handler
 * reads whatever the latest pass recorded. A plain var on purpose
 * — this is draw-phase scratch, not snapshot state.
 */
internal class ChartHitStore {
    var targets: List<ChartHitTarget> = emptyList()
}

/**
 * Resolves a tap at ([x], [y]) against [targets]. Pie/donut
 * slices are exclusive to their charts; on cartesian/radar charts
 * the nearest POINT within [pointRadiusPx] wins over RECTs (a
 * marker sitting inside a bar's area is the more precise target),
 * and RECTs are tested last-drawn-first so stacked segments and
 * overlaps resolve to the topmost shape. Null = a miss.
 */
internal fun hitTest(
    targets: List<ChartHitTarget>,
    x: Float,
    y: Float,
    pointRadiusPx: Float,
): ChartHitTarget? {
    var hasSlices = false
    for (t in targets) {
        if (t.kind == ChartHitKind.SLICE) {
            hasSlices = true
            break
        }
    }
    if (hasSlices) {
        for (t in targets.asReversed()) {
            if (t.kind == ChartHitKind.SLICE && sliceHit(t, x, y)) return t
        }
        return null
    }
    var best: ChartHitTarget? = null
    var bestDist = Float.MAX_VALUE
    for (t in targets) {
        if (t.kind != ChartHitKind.POINT) continue
        val d = hypot(x - t.cx, y - t.cy)
        if (d <= pointRadiusPx && d < bestDist) {
            best = t
            bestDist = d
        }
    }
    if (best != null) return best
    for (t in targets.asReversed()) {
        if (t.kind == ChartHitKind.RECT &&
            x >= t.left && x < t.right && y >= t.top && y < t.bottom
        ) {
            return t
        }
    }
    return null
}

private fun sliceHit(t: ChartHitTarget, x: Float, y: Float): Boolean {
    if (t.sweepAngle <= 0f) return false
    val dx = x - t.cx
    val dy = y - t.cy
    val r = hypot(dx, dy)
    if (r < t.innerRadius || r > t.outerRadius) return false
    // atan2 in a y-down plane yields exactly the canvas angle
    // convention drawArc uses (0 = +x, clockwise positive).
    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    val rel = ((angle - t.startAngle) % 360f + 360f) % 360f
    return rel < t.sweepAngle
}

/** JS-style value formatting (100, not 100.0) — ChartSerializer's rule. */
internal fun formatChartValue(v: Double): String {
    if (!v.isFinite()) return "0"
    if (v == 0.0) return "0" // JS prints -0 as "0" too
    return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
}
