package app.nota.feature.chart

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.nota.data.chart.ChartColors
import app.nota.data.chart.ChartData
import app.nota.data.chart.ChartType
import app.nota.data.chart.LegendPosition
import app.nota.data.chart.SeriesKind
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders a normalised [ChartData] (Charts PR-A) — the Compose
 * counterpart of Noma 1.x's Recharts `ChartRenderer`, drawn on a
 * plain Canvas with no chart dependency: grouped/stacked bars,
 * lines (straight or Catmull-Rom smoothed, null = gap), translucent
 * filled areas, pie, donut, radar polygons, and composed charts
 * whose series each draw by their own kind. Title/subtitle sit
 * above in Nota typography; the legend follows the persisted
 * position and lists series — or, for pie/donut, the categories
 * whose colours the slices actually use.
 *
 * Geometry follows 1.x where it is visible: bars get 4dp rounded
 * outer corners unless stacked, line stroke 2.5dp with 3.5dp
 * markers, radar fill at 50%, pie labels for slices over 5%, and
 * the donut hole at min(innerRadius, 45) / 65 of the outer radius
 * — 1.x's Recharts radii. The y-axis title is drawn small at the
 * plot's top-left instead of rotated (a documented simplification;
 * rotated canvas text tested poorly on the J7's narrow cards).
 *
 * Charts polish pack (PR #40): the canvas plays a one-shot
 * entrance animation (bars grow, lines/areas reveal left-to-right,
 * pie slices sweep in order, radar radii grow), area fills are a
 * vertical gradient, grouped bars and stacks can carry value
 * labels, and — when [interactive] — a tap on a bar, point or
 * slice raises a callout chip with that datum's exact value.
 * None of this touches the stored data: it is purely how the
 * chart draws and responds.
 */

/** Test tag for the chart canvas (the tap target). */
const val CHART_CANVAS_TAG = "chartCanvas"

/** Test tag for the tap-for-values callout chip. */
const val CHART_CALLOUT_TAG = "chartCallout"

@Composable
fun ChartView(
    data: ChartData,
    modifier: Modifier = Modifier,
    animateEntrance: Boolean = true,
    interactive: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = data.title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (data.subtitle.isNotEmpty()) {
            Text(
                text = data.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val pieLike = data.type == ChartType.PIE || data.type == ChartType.DONUT
        // 1.x honours showLegend as persisted — including for a
        // single-series chart, whose one legend entry names it.
        val showLegend = data.options.showLegend
        val entries = legendEntries(data)
        if (showLegend && data.options.legendPosition == LegendPosition.TOP) {
            ChartLegend(entries)
        }
        ChartCanvas(
            data = data,
            animateEntrance = animateEntrance,
            interactive = interactive,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(top = 4.dp),
        )
        if (showLegend && data.options.legendPosition == LegendPosition.BOTTOM) {
            ChartLegend(entries)
        }
    }
}

/** Legend entries: series for cartesian/radar; categories for pie/donut. */
private fun legendEntries(data: ChartData): List<Pair<String, Color>> =
    if (data.type == ChartType.PIE || data.type == ChartType.DONUT) {
        data.categories.mapIndexed { i, name -> name to ChartColors.pieSliceColor(i) }
    } else {
        data.series.mapIndexed { i, s -> s.name to ChartColors.seriesColor(s.color, i) }
    }

@Composable
private fun ChartLegend(entries: List<Pair<String, Color>>) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        entries.forEach { (name, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(color, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ChartCanvas(
    data: ChartData,
    modifier: Modifier,
    animateEntrance: Boolean,
    interactive: Boolean,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    // Entrance animation: a one-shot 0→1 progress. The editor
    // dialog's live preview opts out (it re-renders per keystroke;
    // replaying on every edit would be nauseating).
    val anim = remember { Animatable(if (animateEntrance) 0f else 1f) }
    LaunchedEffect(animateEntrance) {
        if (animateEntrance && anim.value < 1f) {
            anim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            )
        }
    }

    // Tap-for-values: hit targets recorded by the draw pass at
    // final geometry; the current selection drives the callout.
    val hitStore = remember { ChartHitStore() }
    var selection by remember(data) { mutableStateOf<ChartHitTarget?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier.onSizeChanged { canvasSize = it }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .testTag(CHART_CANVAS_TAG)
                .then(
                    if (interactive) {
                        Modifier.pointerInput(data) {
                            detectTapGestures { offset ->
                                val hit = hitTest(
                                    hitStore.targets,
                                    offset.x,
                                    offset.y,
                                    28.dp.toPx(),
                                )
                                val current = selection
                                // Re-tapping the selected target (or
                                // tapping empty space) dismisses;
                                // tapping another target moves the
                                // callout to it.
                                selection = when {
                                    hit == null -> null
                                    current != null &&
                                        current.kind == hit.kind &&
                                        current.categoryIndex == hit.categoryIndex &&
                                        current.seriesIndex == hit.seriesIndex -> null
                                    else -> hit
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            val targets = mutableListOf<ChartHitTarget>()
            if (data.categories.isEmpty() || data.series.isEmpty()) {
                drawCenteredNote(textMeasurer, labelStyle, axisColor, "No chart data")
                hitStore.targets = targets
                return@Canvas
            }
            val progress = anim.value
            when (data.type) {
                ChartType.PIE -> drawPieChart(
                    data, donut = false, textMeasurer, labelStyle, gridColor, axisColor,
                    progress, targets,
                )
                ChartType.DONUT -> drawPieChart(
                    data, donut = true, textMeasurer, labelStyle, gridColor, axisColor,
                    progress, targets,
                )
                ChartType.RADAR -> drawRadarChart(
                    data, textMeasurer, labelStyle, axisColor, gridColor, progress, targets,
                )
                else -> drawCartesianChart(
                    data, textMeasurer, labelStyle, axisColor, gridColor, progress, targets,
                )
            }
            hitStore.targets = targets
        }

        val selected = selection
        if (interactive && selected != null) {
            var calloutSize by remember { mutableStateOf(IntSize.Zero) }
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .onSizeChanged { calloutSize = it }
                    .offset {
                        val maxX = (canvasSize.width - calloutSize.width)
                            .coerceAtLeast(0).toFloat()
                        val maxY = (canvasSize.height - calloutSize.height)
                            .coerceAtLeast(0).toFloat()
                        val x = (selected.anchorX - calloutSize.width / 2f)
                            .coerceIn(0f, maxX)
                        // Above the anchor when it fits, below it
                        // when the anchor hugs the top edge.
                        val above = selected.anchorY - calloutSize.height - 8.dp.toPx()
                        val y = if (above >= 0f) {
                            above
                        } else {
                            (selected.anchorY + 12.dp.toPx()).coerceAtMost(maxY)
                        }
                        IntOffset(x.roundToInt(), y.roundToInt())
                    }
                    .testTag(CHART_CALLOUT_TAG),
            ) {
                Text(
                    text = calloutText(data, selected),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** The callout's words for a hit: "Feb · Series 1: 150", pie "Feb: 150 (33%)". */
private fun calloutText(data: ChartData, hit: ChartHitTarget): String {
    val category = data.categories.getOrNull(hit.categoryIndex) ?: ""
    val percent = hit.percent
    return if (percent != null) {
        "$category: ${formatChartValue(hit.value)} (${(percent * 100).roundToInt()}%)"
    } else {
        val series = data.series.getOrNull(hit.seriesIndex)?.name ?: ""
        "$category · $series: ${formatChartValue(hit.value)}"
    }
}

private fun DrawScope.drawCenteredNote(
    measurer: TextMeasurer,
    style: TextStyle,
    color: Color,
    text: String,
) {
    val layout = measurer.measure(
        AnnotatedString(text),
        style = style,
        maxLines = 1,
        constraints = Constraints(maxWidth = size.width.toInt().coerceAtLeast(1)),
    )
    drawText(
        layout,
        color = color,
        topLeft = Offset(
            (size.width - layout.size.width) / 2f,
            (size.height - layout.size.height) / 2f,
        ),
    )
}

// ---------------------------------------------------------------------
// Cartesian family: bar / line / area / composed

private fun DrawScope.drawCartesianChart(
    data: ChartData,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    axisColor: Color,
    gridColor: Color,
    progress: Float,
    targets: MutableList<ChartHitTarget>,
) {
    val cats = data.categories
    val n = cats.size
    val leftPad = 36.dp.toPx()
    val rightPad = 8.dp.toPx()
    val topPad = if (data.options.yAxisLabel.isNotEmpty()) 15.dp.toPx() else 6.dp.toPx()
    val bottomPad = 18.dp.toPx() + if (data.options.xAxisLabel.isNotEmpty()) 14.dp.toPx() else 0f
    val plotL = leftPad
    val plotT = topPad
    val plotR = size.width - rightPad
    val plotB = size.height - bottomPad
    val plotW = (plotR - plotL).coerceAtLeast(1f)
    val plotH = (plotB - plotT).coerceAtLeast(1f)
    val bandW = plotW / n
    fun centerX(ci: Int): Float = plotL + bandW * (ci + 0.5f)

    // Each series' drawing kind; COMPOSED falls back to 1.x's
    // positional default (even index bar, odd index line).
    val kinds: List<SeriesKind> = data.series.mapIndexed { idx, s ->
        when (data.type) {
            ChartType.BAR -> SeriesKind.BAR
            ChartType.LINE -> SeriesKind.LINE
            ChartType.AREA -> SeriesKind.AREA
            else -> s.type ?: if (idx % 2 == 1) SeriesKind.LINE else SeriesKind.BAR
        }
    }
    val stacked = data.options.stacked
    val stackActive = stacked && kinds.any { it != SeriesKind.LINE }
    val hasBar = kinds.any { it == SeriesKind.BAR }

    // Value domain. Stack participants contribute their per-category
    // signed sums; everything else its own extremes. Bars (and any
    // stack) pin the domain to include zero, as cartesian axes do
    // in 1.x; pure line/area charts get a 5% breathing pad instead.
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    fun consider(v: Double) {
        if (v < lo) lo = v
        if (v > hi) hi = v
    }
    if (stackActive) {
        for (ci in 0 until n) {
            var pos = 0.0
            var neg = 0.0
            data.series.forEachIndexed { si, s ->
                if (kinds[si] == SeriesKind.LINE) return@forEachIndexed
                val v = s.values.getOrNull(ci) ?: 0.0
                if (v >= 0) pos = satAdd(pos, v) else neg = satAdd(neg, v)
            }
            consider(pos)
            consider(neg)
        }
    }
    data.series.forEachIndexed { si, s ->
        if (stackActive && kinds[si] != SeriesKind.LINE) return@forEachIndexed
        s.values.forEach { v -> if (v != null) consider(v) }
    }
    if (hasBar || stackActive) consider(0.0)
    if (!lo.isFinite() || !hi.isFinite()) {
        lo = 0.0
        hi = 1.0
    } else if (!hasBar && !stackActive) {
        val pad = (hi - lo) * 0.05
        if (pad > 0) {
            lo -= pad
            hi += pad
        }
    }
    val scale = niceScale(lo, hi)
    // Project in the scale's UNIT domain: subtracting a raw 1e308
    // value from the clamped bound overflows to Infinity in raw
    // space, which would throw the point off the chart entirely.
    fun yOf(v: Double): Float =
        (plotB - ((v / scale.unit - scale.loUnit) / (scale.hiUnit - scale.loUnit)) * plotH).toFloat()

    // Gridlines + value labels.
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
    for (tick in scale.ticks) {
        val y = yOf(tick)
        if (data.options.showGrid) {
            drawLine(
                color = gridColor,
                start = Offset(plotL, y),
                end = Offset(plotR, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dash,
            )
        }
        val layout = measurer.measure(
            AnnotatedString(formatTick(tick, scale.step)),
            style = labelStyle,
            maxLines = 1,
            constraints = Constraints(maxWidth = (leftPad - 6.dp.toPx()).toInt().coerceAtLeast(1)),
        )
        drawText(
            layout,
            color = axisColor,
            topLeft = Offset(plotL - 6.dp.toPx() - layout.size.width, y - layout.size.height / 2f),
        )
    }
    if (data.options.showGrid) {
        for (ci in 0 until n) {
            val x = centerX(ci)
            drawLine(
                color = gridColor.copy(alpha = 0.55f),
                start = Offset(x, plotT),
                end = Offset(x, plotB),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dash,
            )
        }
    }
    // Baseline: the zero line when in range, else the plot floor.
    val baseY = if (0.0 in scale.lo..scale.hi) yOf(0.0) else plotB
    drawLine(
        color = axisColor.copy(alpha = 0.55f),
        start = Offset(plotL, baseY),
        end = Offset(plotR, baseY),
        strokeWidth = 1.dp.toPx(),
    )

    // Category labels — stride-skipped when crowded, first and last
    // always kept (Recharts' preserveStartEnd in spirit).
    val maxLabelWidth = 64.dp.toPx()
    val fitCount = (plotW / 52.dp.toPx()).toInt().coerceAtLeast(1)
    val stride = ceil(n.toDouble() / fitCount).toInt().coerceAtLeast(1)
    for (ci in 0 until n) {
        if (ci % stride != 0 && ci != n - 1) continue
        val layout = measurer.measure(
            AnnotatedString(cats[ci]),
            style = labelStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = maxLabelWidth.toInt()),
        )
        drawText(
            layout,
            color = axisColor,
            topLeft = Offset(centerX(ci) - layout.size.width / 2f, plotB + 4.dp.toPx()),
        )
    }
    // Axis titles.
    if (data.options.xAxisLabel.isNotEmpty()) {
        val layout = measurer.measure(
            AnnotatedString(data.options.xAxisLabel),
            style = labelStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = plotW.toInt().coerceAtLeast(1)),
        )
        drawText(
            layout,
            color = axisColor,
            topLeft = Offset(plotL + (plotW - layout.size.width) / 2f, size.height - layout.size.height),
        )
    }
    if (data.options.yAxisLabel.isNotEmpty()) {
        val layout = measurer.measure(
            AnnotatedString(data.options.yAxisLabel),
            style = labelStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = plotW.toInt().coerceAtLeast(1)),
        )
        drawText(layout, color = axisColor, topLeft = Offset(plotL, 0f))
    }

    // Grouped bars first (pure BAR / the bar members of COMPOSED
    // when not stacking); stacked participants and lines/areas then
    // draw in series order, sharing stack accumulators exactly as
    // 1.x's shared stackId does.
    if (!stackActive) {
        val barIdx = kinds.indices.filter { kinds[it] == SeriesKind.BAR }
        if (barIdx.isNotEmpty()) {
            val groupW = bandW * 0.72f
            val barW = groupW / barIdx.size
            // Polish pack: value labels above grouped bars when
            // the chart is sparse enough to stay readable (total
            // bar count = categories × bar series ≤ 12).
            val showBarLabels = n * barIdx.size <= 12
            barIdx.forEachIndexed { order, si ->
                val s = data.series[si]
                val color = ChartColors.seriesColor(s.color, si)
                for (ci in 0 until n) {
                    val v = s.values.getOrNull(ci) ?: continue
                    if (v == 0.0) continue
                    val x = plotL + bandW * ci + (bandW - groupW) / 2f + barW * order + barW * 0.06f
                    val yTop = yOf(max(v, 0.0))
                    val yBot = yOf(min(v, 0.0))
                    targets += ChartHitTarget(
                        kind = ChartHitKind.RECT,
                        categoryIndex = ci,
                        seriesIndex = si,
                        value = v,
                        left = x,
                        top = yTop,
                        right = x + barW * 0.88f,
                        bottom = yBot,
                        anchorX = x + barW * 0.44f,
                        anchorY = if (v > 0) yTop else yBot,
                    )
                    // Entrance: the bar grows from the baseline —
                    // its full height scaled by the progress (down
                    // from the zero line for negative values).
                    val fullH = yBot - yTop
                    drawBar(
                        x,
                        if (v > 0) yBot - fullH * progress else yTop,
                        barW * 0.88f, fullH * progress,
                        color, 4.dp.toPx(), roundTop = v > 0,
                    )
                    if (showBarLabels) {
                        drawValueLabel(
                            measurer, labelStyle, axisColor,
                            text = formatChartValue(v),
                            centerX = x + barW * 0.44f,
                            edgeY = if (v > 0) {
                                yBot - fullH * progress
                            } else {
                                yTop + fullH * progress
                            },
                            above = v > 0,
                            maxWidth = bandW,
                        )
                    }
                }
            }
        }
    }
    val posAcc = DoubleArray(n)
    val negAcc = DoubleArray(n)
    data.series.forEachIndexed { si, s ->
        val color = ChartColors.seriesColor(s.color, si)
        when {
            kinds[si] == SeriesKind.BAR && stackActive -> {
                val groupW = bandW * 0.6f
                val x0 = plotL + (bandW - groupW) / 2f
                for (ci in 0 until n) {
                    val v = s.values.getOrNull(ci) ?: 0.0
                    if (v == 0.0) continue
                    val from: Double
                    val to: Double
                    if (v >= 0) {
                        from = posAcc[ci]
                        to = satAdd(posAcc[ci], v)
                        posAcc[ci] = to
                    } else {
                        from = negAcc[ci]
                        to = satAdd(negAcc[ci], v)
                        negAcc[ci] = to
                    }
                    val yTop = yOf(max(from, to))
                    val yBot = yOf(min(from, to))
                    val segX = x0 + bandW * ci + groupW * 0.05f
                    targets += ChartHitTarget(
                        kind = ChartHitKind.RECT,
                        categoryIndex = ci,
                        seriesIndex = si,
                        value = v,
                        left = segX,
                        top = yTop,
                        right = segX + groupW * 0.9f,
                        bottom = yBot,
                        anchorX = segX + groupW * 0.45f,
                        anchorY = if (v > 0) yTop else yBot,
                    )
                    // Entrance: cumulative stack heights scale by
                    // the progress, so the whole stack grows from
                    // the baseline as one column.
                    val animFrom = from * progress
                    val animTo = to * progress
                    val animTop = yOf(max(animFrom, animTo))
                    val animBot = yOf(min(animFrom, animTo))
                    drawRect(
                        color = color,
                        topLeft = Offset(segX, animTop),
                        size = Size(groupW * 0.9f, animBot - animTop),
                    )
                }
            }
            kinds[si] == SeriesKind.AREA && stackActive -> {
                // Null stays a GAP, as in the unstacked area path
                // (and 1.x, where Recharts does not connect nulls):
                // the series contributes nothing at that category —
                // later series stack on the base without it — and
                // its fill breaks into contiguous runs.
                val tops = arrayOfNulls<Offset>(n)
                val bottoms = arrayOfNulls<Offset>(n)
                for (ci in 0 until n) {
                    val v = s.values.getOrNull(ci) ?: continue
                    val base = if (v >= 0) posAcc[ci] else negAcc[ci]
                    tops[ci] = Offset(centerX(ci), yOf(satAdd(base, v)))
                    bottoms[ci] = Offset(centerX(ci), yOf(base))
                    targets += ChartHitTarget(
                        kind = ChartHitKind.POINT,
                        categoryIndex = ci,
                        seriesIndex = si,
                        value = v,
                        cx = tops[ci]!!.x,
                        cy = tops[ci]!!.y,
                        anchorX = tops[ci]!!.x,
                        anchorY = tops[ci]!!.y,
                    )
                    if (v >= 0) posAcc[ci] = satAdd(posAcc[ci], v) else negAcc[ci] = satAdd(negAcc[ci], v)
                }
                var ci = 0
                while (ci < n) {
                    if (tops[ci] == null) {
                        ci++
                        continue
                    }
                    var end = ci
                    while (end + 1 < n && tops[end + 1] != null) end++
                    if (end > ci) {
                        val fill = Path().apply {
                            for (k in ci..end) {
                                val p = tops[k]!!
                                if (k == ci) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                            }
                            for (k in end downTo ci) {
                                val p = bottoms[k]!!
                                lineTo(p.x, p.y)
                            }
                            close()
                        }
                        val strokePath = Path().apply {
                            for (k in ci..end) {
                                val p = tops[k]!!
                                if (k == ci) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                            }
                        }
                        withRevealClip(plotL, plotW, progress) {
                            drawPath(fill, areaGradient(color, tops, bottoms, ci, end))
                            drawPath(
                                strokePath,
                                color,
                                style = Stroke(
                                    width = 2.5.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }
                    }
                    ci = end + 1
                }
            }
            kinds[si] == SeriesKind.AREA -> {
                val pts = (0 until n).map { ci ->
                    s.values.getOrNull(ci)?.let { Offset(centerX(ci), yOf(it)) }
                }
                pts.forEachIndexed { ci, p ->
                    if (p != null) {
                        targets += ChartHitTarget(
                            kind = ChartHitKind.POINT,
                            categoryIndex = ci,
                            seriesIndex = si,
                            value = s.values[ci]!!,
                            cx = p.x,
                            cy = p.y,
                            anchorX = p.x,
                            anchorY = p.y,
                        )
                    }
                }
                withRevealClip(plotL, plotW, progress) {
                    drawAreaRuns(pts, color, plotB, smooth = data.options.smoothLine)
                }
            }
            kinds[si] == SeriesKind.LINE -> {
                val pts = (0 until n).map { ci ->
                    s.values.getOrNull(ci)?.let { Offset(centerX(ci), yOf(it)) }
                }
                pts.forEachIndexed { ci, p ->
                    if (p != null) {
                        targets += ChartHitTarget(
                            kind = ChartHitKind.POINT,
                            categoryIndex = ci,
                            seriesIndex = si,
                            value = s.values[ci]!!,
                            cx = p.x,
                            cy = p.y,
                            anchorX = p.x,
                            anchorY = p.y,
                        )
                    }
                }
                withRevealClip(plotL, plotW, progress) {
                    drawLineRuns(
                        pts = pts,
                        color = color,
                        smooth = data.options.smoothLine,
                        markers = data.options.showMarkers,
                        strokeW = 2.5.dp.toPx(),
                        markerR = 3.5.dp.toPx(),
                    )
                }
            }
            else -> Unit // grouped bars were drawn above
        }
    }

    // Polish pack: stack TOTAL labels — the positive total above
    // each stack, the negative total below it — when the chart is
    // sparse enough to stay readable (≤ 12 categories). The
    // accumulators now hold the final totals; the labels ride the
    // animated stack tops.
    if (stackActive && hasBar && n <= 12) {
        for (ci in 0 until n) {
            val center = plotL + bandW * ci + bandW / 2f
            val pos = posAcc[ci]
            if (pos > 0.0) {
                drawValueLabel(
                    measurer, labelStyle, axisColor,
                    text = formatChartValue(pos),
                    centerX = center,
                    edgeY = yOf(pos * progress),
                    above = true,
                    maxWidth = bandW,
                )
            }
            val neg = negAcc[ci]
            if (neg < 0.0) {
                drawValueLabel(
                    measurer, labelStyle, axisColor,
                    text = formatChartValue(neg),
                    centerX = center,
                    edgeY = yOf(neg * progress),
                    above = false,
                    maxWidth = bandW,
                )
            }
        }
    }
}

/**
 * The entrance reveal for line/area drawing: the series draws
 * normally inside a clip that grows from the plot's left edge to
 * its full width with [progress]. At progress 1 the draw is
 * unclipped, so nothing at the plot's edge is ever shaved.
 */
private fun DrawScope.withRevealClip(
    plotL: Float,
    plotW: Float,
    progress: Float,
    block: DrawScope.() -> Unit,
) {
    if (progress >= 1f) {
        block()
    } else {
        clipRect(
            left = plotL,
            top = 0f,
            right = plotL + plotW * progress,
            bottom = size.height,
        ) { block() }
    }
}

/** The polish-pack area fill: series colour at 45% alpha at the run's top fading to 6% at its base. */
private fun areaGradient(
    color: Color,
    tops: Array<Offset?>,
    bottoms: Array<Offset?>,
    from: Int,
    to: Int,
): Brush {
    var topY = Float.MAX_VALUE
    var baseY = Float.MIN_VALUE
    for (k in from..to) {
        tops[k]?.let { if (it.y < topY) topY = it.y }
        bottoms[k]?.let { if (it.y > baseY) baseY = it.y }
    }
    if (topY == Float.MAX_VALUE) {
        topY = 0f
        baseY = 1f
    }
    return Brush.verticalGradient(
        colors = listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0.06f)),
        startY = topY,
        endY = max(baseY, topY + 1f),
    )
}

/**
 * A bar/stack value label in labelSmall, centred on [centerX] and
 * sitting just outside the bar's value edge ([edgeY], the
 * ANIMATED edge, so the label rides the growing bar). Clamped
 * inside the canvas so the tallest bar's label stays visible.
 */
private fun DrawScope.drawValueLabel(
    measurer: TextMeasurer,
    style: TextStyle,
    color: Color,
    text: String,
    centerX: Float,
    edgeY: Float,
    above: Boolean,
    maxWidth: Float,
) {
    val layout = measurer.measure(
        AnnotatedString(text),
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(1)),
    )
    val gap = 2.dp.toPx()
    val y = if (above) {
        (edgeY - layout.size.height - gap).coerceAtLeast(0f)
    } else {
        (edgeY + gap).coerceAtMost((size.height - layout.size.height).coerceAtLeast(0f))
    }
    val x = (centerX - layout.size.width / 2f)
        .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
    drawText(layout, color = color, topLeft = Offset(x, y))
}

/** A bar with only its outer (value-side) corners rounded, like 1.x's radius [4,4,0,0]. */
private fun DrawScope.drawBar(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    color: Color,
    radius: Float,
    roundTop: Boolean,
) {
    if (h <= 0f || w <= 0f) return
    val r = min(radius, min(w / 2f, h))
    val path = Path().apply {
        if (roundTop) {
            moveTo(x, y + h)
            lineTo(x, y + r)
            quadraticTo(x, y, x + r, y)
            lineTo(x + w - r, y)
            quadraticTo(x + w, y, x + w, y + r)
            lineTo(x + w, y + h)
            close()
        } else {
            moveTo(x, y)
            lineTo(x + w, y)
            lineTo(x + w, y + h - r)
            quadraticTo(x + w, y + h, x + w - r, y + h)
            lineTo(x + r, y + h)
            quadraticTo(x, y + h, x, y + h - r)
            close()
        }
    }
    drawPath(path, color)
}

private fun linePath(points: List<Offset>, smooth: Boolean): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path
    if (!smooth) {
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        return path
    }
    // Catmull-Rom → cubic Bézier, the standard "monotone-ish" smooth.
    for (i in 0 until points.size - 1) {
        val p0 = points[max(i - 1, 0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[min(i + 2, points.size - 1)]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    return path
}

/** Contiguous non-null runs of a point list (null = the gap rule). */
private fun runsOf(pts: List<Offset?>): List<List<Offset>> {
    val runs = mutableListOf<List<Offset>>()
    var current = mutableListOf<Offset>()
    fun flush() {
        if (current.isNotEmpty()) {
            runs += current
            current = mutableListOf()
        }
    }
    pts.forEach { p -> if (p == null) flush() else current += p }
    flush()
    return runs
}

private fun DrawScope.drawLineRuns(
    pts: List<Offset?>,
    color: Color,
    smooth: Boolean,
    markers: Boolean,
    strokeW: Float,
    markerR: Float,
) {
    for (run in runsOf(pts)) {
        if (run.size > 1) {
            drawPath(
                linePath(run, smooth),
                color,
                style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
    if (markers) {
        pts.forEach { p ->
            if (p != null) {
                drawCircle(color, markerR, p)
            }
        }
    }
}

private fun DrawScope.drawAreaRuns(
    pts: List<Offset?>,
    color: Color,
    baseY: Float,
    smooth: Boolean,
) {
    for (run in runsOf(pts)) {
        if (run.size < 2) continue
        val top = linePath(run, smooth)
        val fill = Path().apply {
            addPath(top)
            lineTo(run.last().x, baseY)
            lineTo(run.first().x, baseY)
            close()
        }
        // Polish pack: gradient fill — 45% alpha at the line
        // fading to 6% at the baseline (was a flat 40%).
        val topY = run.minOf { it.y }
        drawPath(
            fill,
            Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0.06f)),
                startY = topY,
                endY = max(baseY, topY + 1f),
            ),
        )
        drawPath(
            top,
            color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

// ---------------------------------------------------------------------
// Pie / donut

private fun DrawScope.drawPieChart(
    data: ChartData,
    donut: Boolean,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    ringColor: Color,
    noteColor: Color,
    progress: Float,
    targets: MutableList<ChartHitTarget>,
) {
    val series = data.series.first()
    val values = data.categories.indices.map { ci ->
        (series.values.getOrNull(ci) ?: 0.0).coerceAtLeast(0.0)
    }
    // Scale by the peak before summing: [1e308, 1e308] sums to
    // Infinity unscaled, which would zero every slice's share and
    // render real data as an empty chart. Proportions are
    // identical either way.
    val peak = values.maxOrNull() ?: 0.0
    val scaled = if (peak > 0.0) values.map { it / peak } else values
    val total = scaled.sum()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = min(size.width, size.height) / 2f - 6.dp.toPx()
    if (radius <= 0f) return
    if (total <= 0.0) {
        drawArc(
            color = ringColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = 10.dp.toPx()),
        )
        drawCenteredNote(measurer, labelStyle, noteColor, "No chart data")
        return
    }
    val innerR = if (donut) {
        radius * (min(data.options.innerRadius, 45.0) / 65.0).toFloat()
    } else {
        0f
    }
    var start = -90f
    // Entrance: a sweep budget of 360° × progress, consumed slice
    // by slice in order (pacman-style) — earlier slices complete
    // before later ones begin.
    var remaining = 360f * progress
    scaled.forEachIndexed { i, v ->
        val sweep = (v / total * 360.0).toFloat()
        if (sweep > 0f) {
            val frac = v / total
            // Hit target at FINAL geometry, whatever the
            // animation's current frame shows.
            val targetMid = Math.toRadians((start + sweep / 2f).toDouble())
            val targetLabelR = if (donut) (innerR + radius) / 2f else radius * 0.66f
            targets += ChartHitTarget(
                kind = ChartHitKind.SLICE,
                categoryIndex = i,
                seriesIndex = 0,
                value = values[i],
                percent = frac,
                cx = cx,
                cy = cy,
                innerRadius = innerR,
                outerRadius = radius,
                startAngle = ((start % 360f) + 360f) % 360f,
                sweepAngle = sweep,
                anchorX = cx + (targetLabelR * cos(targetMid)).toFloat(),
                anchorY = cy + (targetLabelR * sin(targetMid)).toFloat(),
            )
            val drawnSweep = min(sweep, remaining)
            remaining = (remaining - drawnSweep).coerceAtLeast(0f)
            if (drawnSweep > 0f) {
                val color = ChartColors.pieSliceColor(i)
                if (donut) {
                    val mid = (innerR + radius) / 2f
                    drawArc(
                        color = color,
                        startAngle = start,
                        sweepAngle = drawnSweep,
                        useCenter = false,
                        topLeft = Offset(cx - mid, cy - mid),
                        size = Size(mid * 2, mid * 2),
                        style = Stroke(width = radius - innerR),
                    )
                } else {
                    drawArc(
                        color = color,
                        startAngle = start,
                        sweepAngle = drawnSweep,
                        useCenter = true,
                        topLeft = Offset(cx - radius, cy - radius),
                        size = Size(radius * 2, radius * 2),
                    )
                }
                // The % label appears once its slice has fully
                // swept in (its mid-angle is meaningless mid-sweep).
                if (drawnSweep >= sweep && frac > 0.05) {
                    val midAngle = Math.toRadians((start + sweep / 2f).toDouble())
                    val labelR = if (donut) (innerR + radius) / 2f else radius * 0.66f
                    val lx = cx + (labelR * cos(midAngle)).toFloat()
                    val ly = cy + (labelR * sin(midAngle)).toFloat()
                    val layout = measurer.measure(
                        AnnotatedString("${(frac * 100).roundToInt()}%"),
                        style = labelStyle,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = radius.toInt().coerceAtLeast(1)),
                    )
                    drawText(
                        layout,
                        color = Color.White,
                        topLeft = Offset(lx - layout.size.width / 2f, ly - layout.size.height / 2f),
                    )
                }
            }
        }
        start += sweep
    }
}

// ---------------------------------------------------------------------
// Radar

private fun DrawScope.drawRadarChart(
    data: ChartData,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    axisColor: Color,
    gridColor: Color,
    progress: Float,
    targets: MutableList<ChartHitTarget>,
) {
    val n = data.categories.size
    var maxV = 0.0
    data.series.forEach { s ->
        s.values.forEach { v -> if (v != null && v > maxV) maxV = v }
    }
    if (maxV <= 0.0) maxV = 1.0
    val labelPad = 30.dp.toPx()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = (min(size.width, size.height) / 2f - labelPad).coerceAtLeast(10f)
    fun vertex(ci: Int, frac: Float): Offset {
        val ang = Math.toRadians(-90.0 + 360.0 * ci / n)
        return Offset(
            cx + (radius * frac * cos(ang)).toFloat(),
            cy + (radius * frac * sin(ang)).toFloat(),
        )
    }
    for (f in listOf(0.25f, 0.5f, 0.75f, 1f)) {
        val ring = Path().apply {
            for (ci in 0 until n) {
                val p = vertex(ci, f)
                if (ci == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(ring, gridColor, style = Stroke(width = 1.dp.toPx()))
    }
    for (ci in 0 until n) {
        drawLine(gridColor, Offset(cx, cy), vertex(ci, 1f), strokeWidth = 1.dp.toPx())
    }
    data.series.forEachIndexed { si, s ->
        val color = ChartColors.seriesColor(s.color, si)
        val fracs = (0 until n).map { ci ->
            ((s.values.getOrNull(ci) ?: 0.0).coerceAtLeast(0.0) / maxV).toFloat()
        }
        fracs.forEachIndexed { ci, frac ->
            val p = vertex(ci, frac)
            targets += ChartHitTarget(
                kind = ChartHitKind.POINT,
                categoryIndex = ci,
                seriesIndex = si,
                value = s.values.getOrNull(ci) ?: 0.0,
                cx = p.x,
                cy = p.y,
                anchorX = p.x,
                anchorY = p.y,
            )
        }
        // Entrance: the polygon's radii scale with the progress.
        val pts = fracs.mapIndexed { ci, frac -> vertex(ci, frac * progress) }
        val polygon = Path().apply {
            pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
        drawPath(polygon, color.copy(alpha = 0.5f))
        drawPath(
            polygon,
            color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        pts.forEach { p -> drawCircle(color, 2.5.dp.toPx(), p) }
    }
    for (ci in 0 until n) {
        val anchor = vertex(ci, 1.16f)
        val layout = measurer.measure(
            AnnotatedString(data.categories[ci]),
            style = labelStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = (labelPad * 2.6f).toInt().coerceAtLeast(1)),
        )
        val lx = (anchor.x - layout.size.width / 2f)
            .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
        val ly = (anchor.y - layout.size.height / 2f)
            .coerceIn(0f, (size.height - layout.size.height).coerceAtLeast(0f))
        drawText(layout, color = axisColor, topLeft = Offset(lx, ly))
    }
}

// ---------------------------------------------------------------------
// Axis scaling

/**
 * Saturating add for stack accumulation: two finite 1e308 values
 * sum to Infinity, which would poison the axis domain and every
 * projected coordinate. Saturating at ±MAX/2 keeps the chart
 * finite and usable; proportions at that magnitude are beyond
 * meaning anyway.
 */
private fun satAdd(a: Double, b: Double): Double =
    (a + b).coerceIn(-Double.MAX_VALUE / 2, Double.MAX_VALUE / 2)

private data class ChartScale(
    val lo: Double,
    val hi: Double,
    val step: Double,
    val ticks: List<Double>,
    /** Unit-domain bounds + the unit itself, for overflow-free projection. */
    val loUnit: Double,
    val hiUnit: Double,
    val unit: Double,
)

/** Classic 1/2/5/10 nice-number axis scale over [lo0, hi0]. */
private fun niceScale(lo0: Double, hi0: Double, targetTicks: Int = 4): ChartScale {
    // All arithmetic runs on the UNIT domain (the inputs divided
    // by their own magnitude): raw-space maths on extreme stored
    // values overflows at every step — the span (±1e308), the
    // nice-rounding expansion, the tick back-scaling, even the
    // projection subtraction. On the unit domain the bounds sit
    // in [-1, 1] and nothing can overflow; raw tick values are
    // coerced back inside Double range for labelling only, while
    // projection uses the exact unit bounds.
    var lo = if (lo0.isNaN()) 0.0 else lo0
    var hi = if (hi0.isNaN()) 0.0 else hi0
    if (lo == Double.NEGATIVE_INFINITY) lo = -Double.MAX_VALUE
    if (hi == Double.POSITIVE_INFINITY) hi = Double.MAX_VALUE
    if (hi < lo) {
        val t = lo
        lo = hi
        hi = t
    }
    val magnitude = max(abs(lo), abs(hi))
    val unit = if (magnitude > 0.0) magnitude else 1.0
    var uLo = lo / unit
    var uHi = hi / unit
    if (uHi - uLo <= 1e-12) {
        // Zero (or relatively zero) span: widen symmetrically in
        // unit space so constant data sits mid-plot.
        val mid = (uLo + uHi) / 2
        val half = max((uHi - uLo) / 2, 0.005 * max(1.0, abs(mid)))
        uLo = mid - half
        uHi = mid + half
    }
    val step0 = (uHi - uLo) / targetTicks
    if (!(step0 > 0.0) || !step0.isFinite()) {
        // Defensive: no finer grid exists — fall back to the
        // widened unit bounds themselves.
        val ticks = listOf(uLo * unit, uHi * unit)
        return ChartScale(ticks.first(), ticks.last(), (uHi - uLo) * unit, ticks, uLo, uHi, unit)
    }
    val mag = 10.0.pow(floor(log10(step0)))
    val norm = step0 / mag
    val uStep = when {
        norm < 1.5 -> 1.0
        norm < 3.0 -> 2.0
        norm < 7.0 -> 5.0
        else -> 10.0
    } * mag
    val uNiceLo = floor(uLo / uStep) * uStep
    val uCount = ceil((uHi - uNiceLo) / uStep).toInt().coerceIn(1, 12)
    val uTicks = (0..uCount).map { uNiceLo + it * uStep }
    val ticks = uTicks.map { (it * unit).coerceIn(-Double.MAX_VALUE, Double.MAX_VALUE) }
    return ChartScale(
        lo = ticks.first(),
        hi = ticks.last(),
        step = (uStep * unit).coerceIn(1e-300, Double.MAX_VALUE),
        ticks = ticks,
        loUnit = uNiceLo,
        hiUnit = uNiceLo + uCount * uStep,
        unit = unit,
    )
}

private fun formatTick(value: Double, step: Double): String {
    if (value == 0.0) return "0"
    val decimals = max(0, -floor(log10(step)).toInt()).coerceAtMost(6)
    var s = String.format(java.util.Locale.US, "%.${decimals}f", value)
    if (s.contains('.')) {
        s = s.trimEnd('0').trimEnd('.')
    }
    return if (s == "-0" || s.isEmpty()) "0" else s
}
