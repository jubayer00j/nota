package app.nota.data.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Charts PR-A: the chart parser/normaliser is a port of Noma 1.x's
 * `parseChartElement` + `validateAndNormalizeChartData` +
 * `migrateV1ToV2` (chart-node.tsx), so these cases pin the 1.x
 * behaviour — bounds, fallbacks, coercions — rather than a new
 * design. Pure JVM: parsing must behave identically here and on
 * device (the reason for the hand-rolled JSON reader).
 */
class ChartDataParserTest {

    private fun v2Div(
        type: String = "bar",
        title: String = "Sales",
        subtitle: String = "",
        categories: String = "[\"Jan\",\"Feb\"]",
        series: String = "[{\"name\":\"A\",\"values\":[1,2]}]",
        options: String? = null,
        version: String = "2",
    ): String {
        val opts = if (options != null) " data-options='$options'" else ""
        return "<div data-type=\"noma-chart\" data-version=\"$version\" " +
            "data-chart-type=\"$type\" data-title=\"$title\" data-subtitle=\"$subtitle\" " +
            "data-categories='$categories' data-series='$series'$opts></div>"
    }

    private fun v1Div(
        type: String = "bar",
        title: String = "Old chart",
        dataChart: String? = "[{\"name\":\"A\",\"value\":3}]",
    ): String {
        val data = if (dataChart != null) " data-chart='$dataChart'" else ""
        return "<div data-type=\"noma-chart\" data-chart-type=\"$type\" " +
            "data-title=\"$title\"$data></div>"
    }

    @Test
    fun v2HappyPath_parsesEveryField() {
        val data = ChartParser.parse(
            v2Div(
                type = "line",
                title = "  Revenue  ",
                subtitle = " 2026 ",
                categories = "[\"Jan\",\"Feb\",\"Mar\"]",
                series = "[" +
                    "{\"id\":\"s1\",\"name\":\"Web\",\"values\":[10,20,30],\"color\":\"#123456\"}," +
                    "{\"id\":\"s2\",\"name\":\"Store\",\"values\":[5,null,15]}" +
                    "]",
                options = "{\"stacked\":true,\"showGrid\":false,\"legendPosition\":\"top\"," +
                    "\"smoothLine\":false,\"innerRadius\":80,\"xAxisLabel\":\"Month\",\"yAxisLabel\":\"BDT\"}",
            ),
        )!!
        assertEquals(ChartType.LINE, data.type)
        assertEquals("Revenue", data.title) // trimmed
        assertEquals("2026", data.subtitle)
        assertEquals(listOf("Jan", "Feb", "Mar"), data.categories)
        assertEquals(2, data.series.size)
        assertEquals("s1", data.series[0].id)
        assertEquals("Web", data.series[0].name)
        assertEquals("#123456", data.series[0].color)
        assertEquals(listOf(10.0, 20.0, 30.0), data.series[0].values)
        // An explicit JSON null stays null (a gap), it is not zeroed.
        assertEquals(listOf(5.0, null, 15.0), data.series[1].values)
        // A missing colour falls back to 1.x's default palette entry.
        assertEquals(ChartParser.ONE_X_DEFAULT_SERIES_COLORS[1], data.series[1].color)
        assertTrue(data.options.stacked)
        assertEquals(false, data.options.showGrid)
        assertEquals(LegendPosition.TOP, data.options.legendPosition)
        assertEquals(false, data.options.smoothLine)
        assertEquals(80.0, data.options.innerRadius, 0.0)
        assertEquals("Month", data.options.xAxisLabel)
        assertEquals("BDT", data.options.yAxisLabel)
    }

    @Test
    fun caps_areEnforced() {
        val longTitle = "T".repeat(150)
        val manyCats = (1..60).joinToString(",") { "\"C$it\"" }
        val manySeries = (1..25).joinToString(",") { "{\"name\":\"S$it\",\"values\":[1]}" }
        val data = ChartParser.parse(
            v2Div(
                title = longTitle,
                categories = "[$manyCats]",
                series = "[$manySeries]",
            ),
        )!!
        assertEquals(100, data.title.length)
        assertEquals(50, data.categories.size)
        assertEquals(20, data.series.size)
        // Every series' values align to the (capped) categories.
        assertTrue(data.series.all { it.values.size == 50 })

        val wideCategory = ChartParser.parse(
            v2Div(categories = "[\"${"x".repeat(60)}\"]", series = "[{\"values\":[1]}]"),
        )!!
        assertEquals(50, wideCategory.categories[0].length)
    }

    @Test
    fun blankCategoriesAndNames_fallBackPositionally() {
        val data = ChartParser.parse(
            v2Div(
                categories = "[\"\", \"  \", \"Mar\"]",
                series = "[{\"values\":[1,2,3]}, {\"name\":\"  \",\"values\":[4,5,6]}]",
            ),
        )!!
        assertEquals(listOf("Item 1", "Item 2", "Mar"), data.categories)
        assertEquals("Series 1", data.series[0].name)
        assertEquals("Series 2", data.series[1].name)
        // Deterministic id fallback (1.x uses a timestamp; rendering
        // never reads ids, and a stable id keeps tests honest).
        assertEquals("series-auto-0", data.series[0].id)

        val emptyTitle = ChartParser.parse(v2Div(title = "   "))!!
        assertEquals("Chart", emptyTitle.title)
    }

    @Test
    fun unknownType_becomesBar_andSeriesKindIsWhitelisted() {
        val data = ChartParser.parse(
            v2Div(
                type = "scatter",
                series = "[{\"name\":\"A\",\"type\":\"line\",\"values\":[1,2]}," +
                    "{\"name\":\"B\",\"type\":\"pie\",\"values\":[3,4]}]",
            ),
        )!!
        assertEquals(ChartType.BAR, data.type)

        val composed = ChartParser.parse(
            v2Div(
                type = "composed",
                series = "[{\"name\":\"A\",\"type\":\"line\",\"values\":[1,2]}," +
                    "{\"name\":\"B\",\"type\":\"pie\",\"values\":[3,4]}," +
                    "{\"name\":\"C\",\"values\":[5,6]}]",
            ),
        )!!
        assertEquals(ChartType.COMPOSED, composed.type)
        assertEquals(SeriesKind.LINE, composed.series[0].type)
        assertNull(composed.series[1].type) // "pie" is not a series kind
        assertNull(composed.series[2].type)
    }

    @Test
    fun malformedJson_degradesLikeSafeJsonParse_neverCrashes() {
        // Unparseable categories JSON → the [] fallback → the v2
        // normaliser's minimal chart (categories ["Item 1"], the
        // parsed series re-aligned to it).
        val data = ChartParser.parse(
            v2Div(
                categories = "not json at all",
                series = "[{\"name\":\"A\",\"values\":[7,8,9]}]",
            ),
        )!!
        assertEquals(listOf("Item 1"), data.categories)
        assertEquals(listOf(7.0), data.series[0].values)

        // Wrong-typed categories JSON passes 1.x's safe parse
        // through, fails its Array check, and routes to the
        // MIGRATION defaults — Jan/Feb/Mar + 100/150/200.
        val migrated = ChartParser.parse(v2Div(categories = "5"))!!
        assertEquals(listOf("Jan", "Feb", "Mar"), migrated.categories)
        assertEquals(listOf(100.0, 150.0, 200.0), migrated.series[0].values)

        // Broken series JSON → the synthesised zero series.
        val noSeries = ChartParser.parse(v2Div(series = "[{\"name\": broken"))!!
        assertEquals(1, noSeries.series.size)
        assertEquals("Series 1", noSeries.series[0].name)
        assertEquals(listOf(0.0, 0.0), noSeries.series[0].values)
    }

    @Test
    fun emptyV2Payload_normalisesToMinimalChart() {
        val data = ChartParser.parse(v2Div(categories = "[]", series = "[]"))!!
        assertEquals(listOf("Item 1"), data.categories)
        assertEquals(1, data.series.size)
        assertEquals(listOf(0.0), data.series[0].values)
    }

    @Test
    fun valueCoercion_matchesParseFloatSemantics() {
        val data = ChartParser.parse(
            v2Div(
                categories = "[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\",\"G\"]",
                series = "[{\"name\":\"S\",\"values\":[null,\"12.5\",\"abc\",true,1e999,42]}]",
            ),
        )!!
        // 7th value missing entirely → parseFloat(undefined) → 0.
        assertEquals(
            listOf(null, 12.5, 0.0, 0.0, 0.0, 42.0, 0.0),
            data.series[0].values,
        )

        // Extra values beyond the category count are dropped.
        val extra = ChartParser.parse(
            v2Div(
                categories = "[\"A\"]",
                series = "[{\"name\":\"S\",\"values\":[1,2,3]}]",
            ),
        )!!
        assertEquals(listOf(1.0), extra.series[0].values)
    }

    @Test
    fun options_jsTruthinessAndDefaults() {
        // JS Boolean("false") is TRUE; only the literal false turns
        // the show* options off (x !== false).
        val data = ChartParser.parse(
            v2Div(
                options = "{\"stacked\":\"false\",\"showGrid\":0,\"showLegend\":false," +
                    "\"legendPosition\":\"left\",\"innerRadius\":500}",
            ),
        )!!
        assertTrue(data.options.stacked)
        assertTrue(data.options.showGrid)
        assertEquals(false, data.options.showLegend)
        assertEquals(LegendPosition.BOTTOM, data.options.legendPosition)
        assertEquals(120.0, data.options.innerRadius, 0.0) // clamped

        val defaults = ChartParser.parse(v2Div())!!
        assertEquals(false, defaults.options.stacked)
        assertTrue(defaults.options.showGrid)
        assertTrue(defaults.options.showLegend)
        assertTrue(defaults.options.smoothLine)
        assertTrue(defaults.options.showMarkers)
        assertEquals(LegendPosition.BOTTOM, defaults.options.legendPosition)
        assertEquals(60.0, defaults.options.innerRadius, 0.0)
    }

    @Test
    fun colourHandling_keepsValidCss_replacesTheRest() {
        val data = ChartParser.parse(
            v2Div(
                series = "[" +
                    "{\"name\":\"A\",\"values\":[1,2],\"color\":\"red\"}," +
                    "{\"name\":\"B\",\"values\":[1,2],\"color\":\"#abc\"}," +
                    "{\"name\":\"C\",\"values\":[1,2],\"color\":\"oklch(0.7 0.1 40)\"}," +
                    "{\"name\":\"D\",\"values\":[1,2],\"color\":\"  #A1B2C3  \"}" +
                    "]",
            ),
        )!!
        // "red" is not a shape 1.x's validator accepts → default.
        assertEquals(ChartParser.ONE_X_DEFAULT_SERIES_COLORS[0], data.series[0].color)
        assertEquals("#abc", data.series[1].color)
        assertEquals("oklch(0.7 0.1 40)", data.series[2].color)
        assertEquals("#A1B2C3", data.series[3].color) // trimmed
    }

    @Test
    fun htmlEscapedJsonAttributes_parseAfterUnescaping() {
        val raw = "<div data-type=\"noma-chart\" data-version=\"2\" data-chart-type=\"bar\" " +
            "data-title=\"T\" data-categories=\"[&quot;Jan&quot;,&quot;Feb&quot;]\" " +
            "data-series=\"[{&quot;name&quot;:&quot;A&quot;,&quot;values&quot;:[3,4]}]\"></div>"
        val data = ChartParser.parse(raw)!!
        assertEquals(listOf("Jan", "Feb"), data.categories)
        assertEquals(listOf(3.0, 4.0), data.series[0].values)

        val entityTitle = ChartParser.parse(
            v2Div(title = "Q1 &amp; Q2"),
        )!!
        assertEquals("Q1 & Q2", entityTitle.title)
    }

    @Test
    fun v1Migration_basicShape() {
        val data = ChartParser.parse(
            v1Div(
                type = "pie",
                title = "Share",
                dataChart = "[{\"name\":\"A\",\"value\":3},{\"name\":\"B\",\"value\":4}]",
            ),
        )!!
        assertEquals(ChartType.PIE, data.type)
        assertEquals("Share", data.title)
        assertEquals(listOf("A", "B"), data.categories)
        assertEquals(1, data.series.size)
        // The legacy series is named after the chart title.
        assertEquals("Share", data.series[0].name)
        assertEquals(listOf(3.0, 4.0), data.series[0].values)
        assertEquals(ChartParser.ONE_X_DEFAULT_SERIES_COLORS[0], data.series[0].color)
    }

    @Test
    fun v1Migration_emptyData_usesSampleDefaults() {
        val data = ChartParser.parse(v1Div(dataChart = "[]"))!!
        assertEquals(listOf("Jan", "Feb", "Mar"), data.categories)
        assertEquals(listOf(100.0, 150.0, 200.0), data.series[0].values)

        // No data-chart attribute at all degrades the same way.
        val absent = ChartParser.parse(v1Div(dataChart = null))!!
        assertEquals(listOf("Jan", "Feb", "Mar"), absent.categories)
    }

    @Test
    fun v1Migration_entryCoercions() {
        val data = ChartParser.parse(
            v1Div(
                title = "",
                dataChart = "[" +
                    "{\"value\":2}," + // no name → "Item"
                    "{\"name\":\"\",\"value\":\"x\"}," + // blank name, non-number value
                    "{\"name\":5,\"value\":1.5}" + // numeric name stringifies
                    "]",
            ),
        )!!
        assertEquals(listOf("Item", "Item", "5"), data.categories)
        assertEquals(listOf(2.0, 0.0, 1.5), data.series[0].values)
        // Blank legacy title: "Chart" / "Series 1" fallbacks.
        assertEquals("Chart", data.title)
        assertEquals("Series 1", data.series[0].name)

        // The migration does NOT trim the title (the v2 path does).
        val padded = ChartParser.parse(v1Div(title = "  Padded  "))!!
        assertEquals("  Padded  ", padded.title)
    }

    @Test
    fun recognitionGate_rejectsNonChartBlocks() {
        // A div that only tripped NoteDocument's wider substring
        // detection carries no chart payload → null, so the UI
        // keeps the static label instead of inventing data.
        assertNull(ChartParser.parse("<div data-nota-block=\"chart\"><p>hi</p></div>"))
        assertNull(ChartParser.parse("<div data-chart-type=\"bar\" data-x=\"nota-chart\"></div>"))
        assertNull(ChartParser.parse("<p>no tags at all</p>"))
        assertNull(ChartParser.parse(""))

        // data-type alone IS 1.x's own marker → recognised, and the
        // v1 degradation supplies the sample chart 1.x would show.
        val typed = ChartParser.parse("<div data-type=\"noma-chart\"></div>")!!
        assertEquals(listOf("Jan", "Feb", "Mar"), typed.categories)
    }

    @Test
    fun versionTwoWithoutPayloadPair_readsAsV1() {
        // data-version="2" but the categories/series pair is
        // incomplete → parseChartElement takes the legacy path.
        val raw = "<div data-type=\"noma-chart\" data-version=\"2\" data-chart-type=\"bar\" " +
            "data-title=\"Legacy\" data-categories='[\"A\"]' " +
            "data-chart='[{\"name\":\"A\",\"value\":9}]'></div>"
        val data = ChartParser.parse(raw)!!
        assertEquals(listOf("A"), data.categories)
        assertEquals(listOf(9.0), data.series[0].values)
    }

    @Test
    fun emptyStringV2Payload_normalisesToMinimalChart() {
        // 1.x's v2 gate is attribute PRESENCE: an empty-string
        // payload still takes the v2 path, its failed JSON parse
        // substitutes the empty fallback, and the normaliser
        // minimalises to one category and a zero series — NOT the
        // v1 migration sample chart.
        val raw = "<div data-type=\"noma-chart\" data-version=\"2\" data-chart-type=\"bar\" " +
            "data-title=\"Empty\" data-categories=\"\" data-series=\"\"></div>"
        val data = ChartParser.parse(raw)!!
        assertEquals("Empty", data.title)
        assertEquals(listOf("Item 1"), data.categories)
        assertEquals(1, data.series.size)
        assertEquals(listOf(0.0), data.series[0].values)
    }

    @Test
    fun nonAsciiDigitNumber_degradesInsteadOfCrashing() {
        // JSON numbers are ASCII-only: a value like [1\u0662]
        // (Arabic-Indic digit) is a syntax error, so the payload
        // degrades to the fallback series — the parse must never
        // throw (it runs while a note is being opened).
        val raw = v2Div(
            categories = "[\"A\"]",
            series = "[{\"name\":\"S\",\"values\":[1\u0662]}]",
        )
        val data = ChartParser.parse(raw)!!
        assertEquals("Series 1", data.series[0].name)
        assertEquals(listOf(0.0), data.series[0].values)
    }

    @Test
    fun deeplyNestedJson_degradesInsteadOfCrashing() {
        // A crafted payload nested thousands deep must hit the
        // JSON depth bound and degrade to the fallback series,
        // never a stack overflow that makes the note unopenable.
        val deep = "[".repeat(5_000) + "]".repeat(5_000)
        val raw = "<div data-type=\"noma-chart\" data-version=\"2\" data-chart-type=\"bar\" " +
            "data-categories='[\"A\"]' data-series='$deep'></div>"
        val data = ChartParser.parse(raw)!!
        assertEquals(listOf("A"), data.categories)
        assertEquals("Series 1", data.series[0].name)
        assertEquals(listOf(0.0), data.series[0].values)
    }
}
