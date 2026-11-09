package app.nota.data.chart

/**
 * The normalised chart model (Charts PR-A) — a faithful Kotlin port
 * of Noma 1.x's `AdvancedChartData` (chart-node.tsx). Instances are
 * produced ONLY by [ChartParser], which applies 1.x's
 * `validateAndNormalizeChartData` bounds, so every [ChartData] in
 * hand satisfies: title ≤ 100 chars and never blank, categories
 * 1..50 entries, series 1..20 entries, and each series' [values]
 * aligned by index to [categories] (missing → null or 0 per 1.x's
 * coercion rules).
 *
 * This model is a READ-TIME projection of the stored chart div. It
 * is never serialised back in PR-A: the stored bytes stay exactly
 * what [app.nota.data.html.ChartSegment.rawHtml] preserves.
 */

/** The seven chart kinds 1.x persists in `data-chart-type`. */
enum class ChartType(val wire: String) {
    BAR("bar"),
    LINE("line"),
    AREA("area"),
    PIE("pie"),
    DONUT("donut"),
    RADAR("radar"),
    COMPOSED("composed"),
    ;

    companion object {
        /** 1.x's VALID_TYPES check: anything unrecognised is a bar chart. */
        fun fromWire(raw: String?): ChartType = entries.firstOrNull { it.wire == raw } ?: BAR
    }
}

/** Per-series drawing kind inside a COMPOSED chart (`series[].type`). */
enum class SeriesKind(val wire: String) {
    BAR("bar"),
    LINE("line"),
    AREA("area"),
    ;

    companion object {
        fun fromWire(raw: String?): SeriesKind? = entries.firstOrNull { it.wire == raw }
    }
}

enum class LegendPosition {
    TOP,
    BOTTOM,
}

data class ChartSeries(
    val id: String,
    val name: String,
    /** Only meaningful for COMPOSED charts; null = 1.x's positional default. */
    val type: SeriesKind?,
    /**
     * The persisted CSS colour string (usually `oklch(...)`), AFTER
     * 1.x normalisation — a missing/invalid persisted colour has
     * already been replaced by one of 1.x's default palette strings,
     * exactly as `validateAndNormalizeChartData` does. Resolve to a
     * Compose [androidx.compose.ui.graphics.Color] via [ChartColors].
     */
    val color: String?,
    /** One entry per category, index-aligned; null = no value (a gap in line/area). */
    val values: List<Double?>,
)

data class ChartOptions(
    val stacked: Boolean,
    val showGrid: Boolean,
    val showLegend: Boolean,
    val legendPosition: LegendPosition,
    val smoothLine: Boolean,
    val showMarkers: Boolean,
    /** Donut hole size in 1.x's units (10..120, default 60); see ChartView for the geometry mapping. */
    val innerRadius: Double,
    val xAxisLabel: String,
    val yAxisLabel: String,
)

data class ChartData(
    val type: ChartType,
    val title: String,
    val subtitle: String,
    val categories: List<String>,
    val series: List<ChartSeries>,
    val options: ChartOptions,
)
