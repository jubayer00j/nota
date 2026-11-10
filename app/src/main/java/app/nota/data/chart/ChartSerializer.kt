package app.nota.data.chart

import java.math.BigDecimal

/**
 * Writes a [ChartData] back to the stored chart div (Charts PR-B) —
 * the exact counterpart of [ChartParser]: the div Noma 1.x's
 * `renderHTML` emits for the same data, so a chart created or
 * edited in Nota opens in 1.x as the same chart, and vice versa.
 *
 * Two disciplines keep this safe:
 *
 *  - NORMALISE FIRST. [serialize] runs its input through
 *    [normalize] — the parser's own normalisation, fed a JSON tree
 *    rebuilt from the model — so caps, fallbacks, colour
 *    validation and finite-number coercions are IDENTICAL to the
 *    read path by construction, not by a second implementation
 *    that could drift. Anything the chart dialog produces is
 *    therefore valid v2 that 1.x's gates accept.
 *
 *  - 1.x's EMISSION SHAPE. Attribute order, JSON key order
 *    (series: id, name, type, color, values — an absent per-series
 *    type is OMITTED, exactly as `JSON.stringify` omits an
 *    undefined field; options: the nine normalised fields in 1.x's
 *    literal order), compact JSON, JS number formatting, and
 *    attribute escaping identical to NoteDocument's own
 *    `escapeAttr` (& → &amp;, " → &quot;), which is what makes the
 *    value survive NoteDocument's quote-aware tag reader and come
 *    back through the parser's entity decode unchanged.
 *
 * Charts the user never opens in the editor are NEVER routed
 * through here — PR-A's byte-fidelity invariant stands; only a
 * dialog Save re-serialises.
 */
object ChartSerializer {

    /**
     * The normalised form of [data]: what [serialize] emits and
     * what [ChartParser.parse] reads back from the emitted div.
     * Exposed so the round trip is testable without string surgery.
     */
    fun normalize(data: ChartData): ChartData = ChartParser.normalize(data.toJson())

    /** The stored v2 div for [data] (normalised first). */
    fun serialize(data: ChartData): String {
        val d = normalize(data)
        val categoriesJson = d.categories.joinToString(",", "[", "]") { jsonString(it) }
        val seriesJson = d.series.joinToString(",", "[", "]") { s ->
            buildString {
                append('{')
                append("\"id\":").append(jsonString(s.id))
                append(",\"name\":").append(jsonString(s.name))
                // JSON.stringify drops undefined fields: a series
                // with no explicit kind carries NO "type" key.
                if (s.type != null) {
                    append(",\"type\":").append(jsonString(s.type.wire))
                }
                if (s.color != null) {
                    append(",\"color\":").append(jsonString(s.color))
                }
                append(",\"values\":")
                append(s.values.joinToString(",", "[", "]") { v ->
                    v?.let { jsonNumber(it) } ?: "null"
                })
                append('}')
            }
        }
        val o = d.options
        val optionsJson = buildString {
            append('{')
            append("\"stacked\":").append(o.stacked)
            append(",\"showGrid\":").append(o.showGrid)
            append(",\"showLegend\":").append(o.showLegend)
            append(",\"legendPosition\":")
                .append(jsonString(if (o.legendPosition == LegendPosition.TOP) "top" else "bottom"))
            append(",\"smoothLine\":").append(o.smoothLine)
            append(",\"showMarkers\":").append(o.showMarkers)
            append(",\"innerRadius\":").append(jsonNumber(o.innerRadius))
            append(",\"xAxisLabel\":").append(jsonString(o.xAxisLabel))
            append(",\"yAxisLabel\":").append(jsonString(o.yAxisLabel))
            append('}')
        }
        return buildString {
            append("<div data-type=\"noma-chart\" data-version=\"2\"")
            append(" data-chart-type=\"").append(escapeAttr(d.type.wire)).append('"')
            append(" data-title=\"").append(escapeAttr(d.title)).append('"')
            append(" data-subtitle=\"").append(escapeAttr(d.subtitle)).append('"')
            append(" data-categories=\"").append(escapeAttr(categoriesJson)).append('"')
            append(" data-series=\"").append(escapeAttr(seriesJson)).append('"')
            append(" data-options=\"").append(escapeAttr(optionsJson)).append('"')
            append("></div>")
        }
    }

    /**
     * The chart the insert flow seeds (1.x's default chart): a bar
     * chart titled "Chart" over Jan/Feb/Mar with one series,
     * [100, 150, 200]. The series colour is the first NOTA palette
     * colour — Nota seeds its own palette strings, which the
     * renderer draws as-is and 1.x accepts as a custom colour.
     */
    fun defaultChart(): ChartData = ChartData(
        type = ChartType.BAR,
        title = "Chart",
        subtitle = "",
        categories = listOf("Jan", "Feb", "Mar"),
        series = listOf(
            ChartSeries(
                id = "series-1",
                name = "Series 1",
                type = null,
                color = ChartColors.NOTA_PALETTE_OKLCH[0],
                values = listOf(100.0, 150.0, 200.0),
            ),
        ),
        options = ChartOptions(
            stacked = false,
            showGrid = true,
            showLegend = true,
            legendPosition = LegendPosition.BOTTOM,
            smoothLine = true,
            showMarkers = true,
            innerRadius = 60.0,
            xAxisLabel = "",
            yAxisLabel = "",
        ),
    )

    // ---------------------------------------------------------------
    // Model → JSON tree (the normaliser's input)

    private fun ChartData.toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "version" to JsonNumber(2.0),
            "type" to JsonString(type.wire),
            "title" to JsonString(title),
            "subtitle" to JsonString(subtitle),
            "categories" to JsonArray(categories.map { JsonString(it) }),
            "series" to JsonArray(
                series.map { s ->
                    JsonObject(
                        linkedMapOf(
                            "id" to JsonString(s.id),
                            "name" to JsonString(s.name),
                            "type" to (s.type?.let { JsonString(it.wire) } ?: JsonNull),
                            "color" to (s.color?.let { JsonString(it) } ?: JsonNull),
                            "values" to JsonArray(
                                s.values.map { v -> v?.let { JsonNumber(it) } ?: JsonNull },
                            ),
                        ),
                    )
                },
            ),
            "options" to JsonObject(
                linkedMapOf(
                    "stacked" to JsonBool(options.stacked),
                    "showGrid" to JsonBool(options.showGrid),
                    "showLegend" to JsonBool(options.showLegend),
                    "legendPosition" to JsonString(
                        if (options.legendPosition == LegendPosition.TOP) "top" else "bottom",
                    ),
                    "smoothLine" to JsonBool(options.smoothLine),
                    "showMarkers" to JsonBool(options.showMarkers),
                    "innerRadius" to JsonNumber(options.innerRadius),
                    "xAxisLabel" to JsonString(options.xAxisLabel),
                    "yAxisLabel" to JsonString(options.yAxisLabel),
                ),
            ),
        ),
    )

    // ---------------------------------------------------------------
    // JSON emission (JSON.stringify semantics)

    /** A JSON string literal: `"` and `\` escaped, control
     *  characters as the short escapes or \u00XX, everything else
     *  raw — exactly what `JSON.stringify` produces. */
    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') {
                    append("\\u")
                    append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    /**
     * A JSON number the way JS prints one: integral doubles carry
     * no decimal point (100, not 100.0), fractions keep their
     * shortest round-trip digits. Non-finite values must never be
     * emitted — normalisation has already coerced them to 0, and
     * this final guard keeps the promise even for a hand-built
     * model that bypassed it.
     */
    private fun jsonNumber(d: Double): String {
        if (!d.isFinite()) return "0"
        if (d == 0.0) return "0" // JS prints -0 as "0" too
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
    }

    /** NoteDocument's attribute escaping, matched exactly
     *  (& first, then "): the pair its tag reader reverses. */
    private fun escapeAttr(value: String): String = value
        .replace("&", "&amp;").replace("\"", "&quot;")
}
