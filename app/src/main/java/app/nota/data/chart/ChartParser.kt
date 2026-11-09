package app.nota.data.chart

import app.nota.data.html.HtmlEntities
import kotlin.math.floor

/**
 * Reads a stored chart div into a normalised [ChartData] (Charts
 * PR-A) — a line-by-line Kotlin port of Noma 1.x's chart reading
 * path (chart-node.tsx): `parseChartElement` for the attribute
 * plumbing, `validateAndNormalizeChartData` for the bounds and
 * coercions, `migrateV1ToV2` for legacy charts. Where JS semantics
 * differ from Kotlin's (truthiness, `parseFloat`, `String(...)`),
 * small helpers below reproduce them; the goal is that a chart
 * written by 1.x renders in Nota as the SAME chart 1.x shows.
 *
 * The parse is READ-ONLY. Stored bytes never change: [parse] works
 * on [app.nota.data.html.ChartSegment.rawHtml], which NoteDocument
 * continues to preserve verbatim.
 */
object ChartParser {

    /** 1.x's DEFAULT_SERIES_COLORS (LIGHT_CHART_FALLBACKS): the
     *  concrete colours normalisation assigns when a series carries
     *  no usable colour of its own. */
    val ONE_X_DEFAULT_SERIES_COLORS: List<String> = listOf(
        "oklch(0.578 0.192 260)",
        "oklch(0.51 0.24 293)",
        "oklch(0.66 0.14 255)",
        "oklch(0.72 0.1 280)",
        "oklch(0.62 0.14 320)",
    )

    private val COLOR_ACCEPT = Regex("^oklch\\(|^#[0-9a-f]{3,8}", RegexOption.IGNORE_CASE)

    /**
     * Parses [rawHtml] (a whole chart element, as preserved in a
     * [app.nota.data.html.ChartSegment]) into normalised chart data,
     * or returns null when the element carries NO recognisable Noma
     * chart payload — the caller's cue to keep the honest static
     * placeholder instead of inventing a chart.
     *
     * Recognition mirrors 1.x's own gates, widened only where
     * Nota's block detection is wider: the element is a chart when
     * it declares `data-type="noma-chart"` (1.x's parseHTML rule),
     * or carries a v1 `data-chart` payload, or carries the v2
     * `data-categories` + `data-series` payload pair. A div that
     * merely tripped NoteDocument's substring detection (a bare
     * `data-nota-block`, a lone `data-chart-type`) is NOT a chart
     * here and returns null.
     *
     * A recognised chart ALWAYS yields data: malformed JSON
     * degrades through 1.x's safe-parse fallbacks into the
     * normalised minimal chart, never a crash — exactly what 1.x
     * renders for the same bytes.
     */
    fun parse(rawHtml: String): ChartData? {
        val attrs = extractAttrs(rawHtml) ?: return null
        val categoriesRaw = attrs["data-categories"]
        val seriesRaw = attrs["data-series"]
        // 1.x's gate is attribute PRESENCE (`!== null`), not
        // non-emptiness: an empty-string payload still takes the v2
        // path, where its failed JSON parse substitutes the empty
        // fallback the normaliser then minimalises.
        val hasV2Pair = categoriesRaw != null && seriesRaw != null
        val recognised = attrs["data-type"] == "noma-chart" ||
            attrs.containsKey("data-chart") ||
            hasV2Pair
        if (!recognised) return null

        // parseChartElement: version / type / title / subtitle are
        // plain attributes; everything else is JSON inside them.
        val version = attrs["data-version"]?.let { jsParseInt(it) } ?: 1
        val type = attrs["data-chart-type"] ?: "bar"
        val title = attrs["data-title"] ?: ""
        val subtitle = attrs["data-subtitle"] ?: ""

        if (version == 2 && hasV2Pair) {
            // safeJsonParse semantics: a FAILED parse substitutes
            // the fallback, but a successful parse of the WRONG JSON
            // type passes through as-is — the normaliser's own type
            // checks then route it exactly as 1.x's do (a non-array
            // categories goes to the migration path there).
            return normalize(
                JsonObject(
                    mapOf(
                        "version" to JsonNumber(2.0),
                        "type" to JsonString(type),
                        "title" to JsonString(title),
                        "subtitle" to JsonString(subtitle),
                        "categories" to (MiniJson.parse(categoriesRaw!!) ?: JsonArray(emptyList())),
                        "series" to (MiniJson.parse(seriesRaw!!) ?: JsonArray(emptyList())),
                        "options" to (attrs["data-options"]?.let { MiniJson.parse(it) }
                            ?: JsonObject(emptyMap())),
                    ),
                ),
            )
        }

        // Legacy V1 fallback: the data-chart array (or its absence).
        val legacy = attrs["data-chart"]?.let { MiniJson.parse(it) } ?: JsonArray(emptyList())
        return normalize(
            JsonObject(
                mapOf(
                    "version" to JsonNumber(1.0),
                    "type" to JsonString(type),
                    "title" to JsonString(title),
                    "data" to legacy,
                ),
            ),
        )
    }

    /**
     * `validateAndNormalizeChartData`, ported. [input] is the
     * assembled attribute object (see [parse]); anything that is
     * not a v2-shaped object routes to [migrate] first, as in 1.x.
     */
    fun normalize(input: JsonObject?): ChartData {
        if (input == null) return fallbackChart()
        val version = input["version"]
        if (version !is JsonNumber || version.value != 2.0 || input["categories"] !is JsonArray) {
            return migrate(input)
        }

        val type = ChartType.fromWire(jsString(input["type"]))
        val title = (jsString(input["title"])?.trim() ?: "").take(100).ifEmpty { "Chart" }
        val subtitle = (jsString(input["subtitle"])?.trim() ?: "").take(100)

        val rawCategories = (input["categories"] as? JsonArray)?.items ?: emptyList()
        val categories = rawCategories.take(50).mapIndexed { i, c ->
            val s = jsString(c)
            if (s != null && s.trim().isNotEmpty()) s.trim().take(50) else "Item ${i + 1}"
        }.toMutableList()
        if (categories.isEmpty()) categories += "Item 1"

        val rawSeries = (input["series"] as? JsonArray)?.items ?: emptyList()
        val series = rawSeries.take(20).mapIndexed { sIdx, s ->
            val obj = s as? JsonObject
            val id = jsString(obj?.get("id"))?.trim()?.takeIf { it.isNotEmpty() }?.take(50)
                ?: "series-auto-$sIdx"
            val name = jsString(obj?.get("name"))?.trim()?.takeIf { it.isNotEmpty() }?.take(50)
                ?: "Series ${sIdx + 1}"
            val seriesType = SeriesKind.fromWire(jsString(obj?.get("type")))
            val rawColor = jsString(obj?.get("color"))?.trim()
            val color = if (rawColor != null && COLOR_ACCEPT.containsMatchIn(rawColor)) {
                rawColor
            } else {
                ONE_X_DEFAULT_SERIES_COLORS[sIdx % ONE_X_DEFAULT_SERIES_COLORS.size]
            }
            val rawVals = (obj?.get("values") as? JsonArray)?.items ?: emptyList()
            val values = categories.indices.map { cIdx -> coerceValue(rawVals.getOrNull(cIdx)) }
            ChartSeries(id, name, seriesType, color, values)
        }.toMutableList()
        if (series.isEmpty()) {
            series += ChartSeries(
                id = "series-1",
                name = "Series 1",
                type = null,
                color = ONE_X_DEFAULT_SERIES_COLORS[0],
                values = categories.map { 0.0 },
            )
        }

        val rawOpts = input["options"] as? JsonObject
        val options = ChartOptions(
            stacked = jsTruthy(rawOpts?.get("stacked")),
            showGrid = notExactlyFalse(rawOpts?.get("showGrid")),
            showLegend = notExactlyFalse(rawOpts?.get("showLegend")),
            legendPosition = if (jsString(rawOpts?.get("legendPosition")) == "top") {
                LegendPosition.TOP
            } else {
                LegendPosition.BOTTOM
            },
            smoothLine = notExactlyFalse(rawOpts?.get("smoothLine")),
            showMarkers = notExactlyFalse(rawOpts?.get("showMarkers")),
            innerRadius = (rawOpts?.get("innerRadius") as? JsonNumber)?.value
                ?.takeIf { it.isFinite() }?.coerceIn(10.0, 120.0) ?: 60.0,
            xAxisLabel = jsString(rawOpts?.get("xAxisLabel"))?.trim()?.take(50) ?: "",
            yAxisLabel = jsString(rawOpts?.get("yAxisLabel"))?.trim()?.take(50) ?: "",
        )

        return ChartData(type, title, subtitle, categories, series, options)
    }

    /** `migrateV1ToV2`, ported: a v1 `{name, value}` list becomes a
     *  single-series v2 chart titled after the legacy title. */
    fun migrate(input: JsonObject?): ChartData {
        if (input != null) {
            val version = input["version"]
            if (version is JsonNumber && version.value == 2.0 && input["categories"] is JsonArray) {
                return normalize(input)
            }
        }
        val legacyData = (input?.get("data") as? JsonArray)?.items ?: emptyList()
        val categories = legacyData.map { legacyCategoryName(it) }
        val values = legacyData.map { el ->
            // JS: typeof d["value"] === "number" ? d["value"] : 0 —
            // a non-object entry reads as undefined in this port
            // (1.x would throw on a null entry; degrading to 0 is
            // the safe superset).
            ((el as? JsonObject)?.get("value") as? JsonNumber)?.value ?: 0.0
        }
        val titleString = jsString(input?.get("title"))
        return ChartData(
            type = ChartType.fromWire(jsString(input?.get("type"))),
            title = titleString?.takeIf { it.isNotEmpty() } ?: "Chart",
            subtitle = "",
            categories = categories.ifEmpty { listOf("Jan", "Feb", "Mar") },
            series = listOf(
                ChartSeries(
                    id = "series-1",
                    name = titleString?.takeIf { it.isNotEmpty() } ?: "Series 1",
                    type = null,
                    color = ONE_X_DEFAULT_SERIES_COLORS[0],
                    values = values.ifEmpty { listOf(100.0, 150.0, 200.0) },
                ),
            ),
            options = defaultOptions(),
        )
    }

    /** 1.x's literal fallback chart (non-object input). */
    fun fallbackChart(): ChartData = ChartData(
        type = ChartType.BAR,
        title = "Chart",
        subtitle = "",
        categories = listOf("Jan", "Feb", "Mar"),
        series = listOf(
            ChartSeries(
                id = "series-1",
                name = "Series 1",
                type = null,
                color = ONE_X_DEFAULT_SERIES_COLORS[0],
                values = listOf(100.0, 150.0, 200.0),
            ),
        ),
        options = defaultOptions(),
    )

    private fun defaultOptions(): ChartOptions = ChartOptions(
        stacked = false,
        showGrid = true,
        showLegend = true,
        legendPosition = LegendPosition.BOTTOM,
        smoothLine = true,
        showMarkers = true,
        innerRadius = 60.0,
        xAxisLabel = "",
        yAxisLabel = "",
    )

    // ---------------------------------------------------------------
    // Attribute plumbing (parseChartElement's getAttribute reads)

    /**
     * The opening tag's attributes of the element [rawHtml] holds,
     * name → entity-decoded value, first occurrence winning — the
     * same reads NoteDocument's private `attr` performs (it is not
     * visible outside NoteDocument, so the quote-aware scan is
     * reproduced here in miniature).
     */
    private fun extractAttrs(rawHtml: String): Map<String, String>? {
        val lt = rawHtml.indexOf('<')
        if (lt < 0) return null
        var i = lt + 1
        val nameStart = i
        while (i < rawHtml.length && (rawHtml[i].isLetterOrDigit() || rawHtml[i] == '-')) i++
        if (i == nameStart) return null
        val out = LinkedHashMap<String, String>()
        while (i < rawHtml.length) {
            while (i < rawHtml.length && (rawHtml[i].isWhitespace() || rawHtml[i] == '/')) i++
            if (i >= rawHtml.length || rawHtml[i] == '>') break
            val attrStart = i
            while (i < rawHtml.length && !rawHtml[i].isWhitespace() &&
                rawHtml[i] != '=' && rawHtml[i] != '>' && rawHtml[i] != '/'
            ) {
                i++
            }
            val name = rawHtml.substring(attrStart, i).lowercase()
            while (i < rawHtml.length && rawHtml[i].isWhitespace()) i++
            var value = ""
            if (i < rawHtml.length && rawHtml[i] == '=') {
                i++
                while (i < rawHtml.length && rawHtml[i].isWhitespace()) i++
                if (i < rawHtml.length && (rawHtml[i] == '"' || rawHtml[i] == '\'')) {
                    val quote = rawHtml[i]
                    i++
                    val valueStart = i
                    while (i < rawHtml.length && rawHtml[i] != quote) i++
                    value = rawHtml.substring(valueStart, i)
                    if (i < rawHtml.length) i++ // closing quote
                } else {
                    val valueStart = i
                    while (i < rawHtml.length && !rawHtml[i].isWhitespace() && rawHtml[i] != '>') i++
                    value = rawHtml.substring(valueStart, i)
                }
            }
            if (name.isNotEmpty() && name !in out) {
                out[name] = HtmlEntities.decode(value, inAttribute = true)
            }
        }
        return out
    }

    // ---------------------------------------------------------------
    // JS semantics helpers

    private fun jsString(v: JsonValue?): String? = (v as? JsonString)?.value

    /** JS truthiness for `Boolean(x)`: only false, 0, NaN, "", null
     *  and undefined are falsy; every object/array is truthy. */
    private fun jsTruthy(v: JsonValue?): Boolean = when (v) {
        null, JsonNull -> false
        is JsonBool -> v.value
        is JsonNumber -> v.value != 0.0 && !v.value.isNaN()
        is JsonString -> v.value.isNotEmpty()
        is JsonArray, is JsonObject -> true
    }

    /** 1.x's `x !== false` option reads: ONLY the boolean false turns these off. */
    private fun notExactlyFalse(v: JsonValue?): Boolean = !(v is JsonBool && !v.value)

    /**
     * 1.x's per-value coercion (the categories-aligned values map):
     * an explicit null stays null; a finite number stays; everything
     * else goes through `parseFloat(String(v))` and a non-finite
     * result becomes 0.
     */
    private fun coerceValue(v: JsonValue?): Double? = when (v) {
        is JsonNull -> null
        is JsonNumber -> if (v.value.isFinite()) v.value else 0.0
        null -> 0.0 // parseFloat("undefined") is NaN → 0
        else -> {
            val parsed = jsParseFloat(jsToString(v))
            if (parsed.isFinite()) parsed else 0.0
        }
    }

    /** JS `String(v)` for the JSON value shapes that can occur here. */
    private fun jsToString(v: JsonValue?): String = when (v) {
        null -> "undefined"
        JsonNull -> "null"
        is JsonString -> v.value
        is JsonBool -> if (v.value) "true" else "false"
        is JsonNumber -> jsNumberToString(v.value)
        is JsonArray -> v.items.joinToString(",") { el ->
            if (el is JsonNull) "" else jsToString(el)
        }
        is JsonObject -> "[object Object]"
    }

    private fun jsNumberToString(d: Double): String = when {
        d.isNaN() -> "NaN"
        d == Double.POSITIVE_INFINITY -> "Infinity"
        d == Double.NEGATIVE_INFINITY -> "-Infinity"
        d == floor(d) && kotlin.math.abs(d) < 1e15 -> d.toLong().toString()
        else -> d.toString()
    }

    /** JS `parseFloat`: longest valid numeric prefix after leading
     *  whitespace, or NaN when there is none. */
    private fun jsParseFloat(s: String): Double {
        var i = 0
        while (i < s.length && (s[i].isWhitespace() || s[i] == '\uFEFF')) i++
        val start = i
        if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
        if (s.startsWith("Infinity", i)) {
            return if (s[start] == '-') Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        }
        var digits = 0
        while (i < s.length && s[i].isDigit()) {
            i++
            digits++
        }
        if (i < s.length && s[i] == '.') {
            i++
            while (i < s.length && s[i].isDigit()) {
                i++
                digits++
            }
        }
        if (digits == 0) return Double.NaN
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            var j = i + 1
            if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
            var expDigits = 0
            while (j < s.length && s[j].isDigit()) {
                j++
                expDigits++
            }
            if (expDigits > 0) i = j
        }
        return s.substring(start, i).toDoubleOrNull() ?: Double.NaN
    }

    /** JS `parseInt(x, 10)`: leading integer prefix, or null (NaN). */
    private fun jsParseInt(s: String): Int? {
        var i = 0
        while (i < s.length && s[i].isWhitespace()) i++
        val start = i
        if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
        var digits = 0
        while (i < s.length && s[i].isDigit()) {
            i++
            digits++
        }
        if (digits == 0) return null
        return s.substring(start, i).toIntOrNull()
    }

    /** JS `String(d["name"] || "Item")` from the v1 migration. */
    private fun legacyCategoryName(el: JsonValue?): String {
        val name = (el as? JsonObject)?.get("name")
        return when (name) {
            null, JsonNull -> "Item"
            is JsonString -> name.value.ifEmpty { "Item" }
            is JsonBool -> if (name.value) "true" else "Item"
            is JsonNumber -> {
                if (name.value == 0.0 || name.value.isNaN()) {
                    "Item"
                } else {
                    jsNumberToString(name.value)
                }
            }
            is JsonArray -> jsToString(name) // arrays are truthy, even empty
            is JsonObject -> "[object Object]"
        }
    }
}
