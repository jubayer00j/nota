package app.nota.data.chart

import app.nota.data.html.ChartSegment
import app.nota.data.html.NoteDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Charts PR-B: the serialiser is the parser's counterpart — it
 * emits EXACTLY the v2 div Noma 1.x's `renderHTML` emits (attribute
 * order, JSON key order, JSON.stringify number/string shapes,
 * NoteDocument's attribute escaping), after running the model
 * through the parser's own normalisation. These cases pin the
 * emission shape byte-for-byte where 1.x pins it, and the
 * round trip parse(serialize(x)) == normalize(x) everywhere else.
 */
class ChartSerializerTest {

    @Test
    fun defaultChart_serialisesToTheExact1xDiv() {
        val expected = "<div data-type=\"noma-chart\" data-version=\"2\" " +
            "data-chart-type=\"bar\" data-title=\"Chart\" data-subtitle=\"\" " +
            "data-categories=\"[&quot;Jan&quot;,&quot;Feb&quot;,&quot;Mar&quot;]\" " +
            "data-series=\"[{&quot;id&quot;:&quot;series-1&quot;,&quot;name&quot;:&quot;Series 1&quot;," +
            "&quot;color&quot;:&quot;oklch(0.437 0.095 164)&quot;,&quot;values&quot;:[100,150,200]}]\" " +
            "data-options=\"{&quot;stacked&quot;:false,&quot;showGrid&quot;:true," +
            "&quot;showLegend&quot;:true,&quot;legendPosition&quot;:&quot;bottom&quot;," +
            "&quot;smoothLine&quot;:true,&quot;showMarkers&quot;:true,&quot;innerRadius&quot;:60," +
            "&quot;xAxisLabel&quot;:&quot;&quot;,&quot;yAxisLabel&quot;:&quot;&quot;}\"></div>"
        assertEquals(expected, ChartSerializer.serialize(ChartSerializer.defaultChart()))
        // The seed is already normalised, so it round-trips to itself.
        assertEquals(
            ChartSerializer.defaultChart(),
            ChartParser.parse(ChartSerializer.serialize(ChartSerializer.defaultChart())),
        )
    }

    @Test
    fun attributeOrder_matchesRenderHtml() {
        val html = ChartSerializer.serialize(ChartSerializer.defaultChart())
        val order = listOf(
            "data-type=", "data-version=", "data-chart-type=", "data-title=",
            "data-subtitle=", "data-categories=", "data-series=", "data-options=",
        ).map { html.indexOf(it) }
        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    @Test
    fun seriesTypeKey_omittedWhenAbsent_presentWhenSet() {
        // JSON.stringify drops undefined fields: no "type" key for
        // a plain series, a real one for a composed series.
        val plain = ChartSerializer.serialize(ChartSerializer.defaultChart())
        assertFalse(plain.contains("&quot;type&quot;"))
        val composed = ChartSerializer.defaultChart().copy(
            type = ChartType.COMPOSED,
            series = listOf(
                ChartSeries(
                    id = "s1", name = "A", type = SeriesKind.LINE,
                    color = "#123456", values = listOf(1.0, 2.0, 3.0),
                ),
            ),
        )
        val html = ChartSerializer.serialize(composed)
        assertTrue(html.contains("&quot;type&quot;:&quot;line&quot;"))
        assertEquals(SeriesKind.LINE, ChartParser.parse(html)!!.series[0].type)
    }

    @Test
    fun numberFormatting_isJsStyle() {
        val data = ChartSerializer.defaultChart().copy(
            series = listOf(
                ChartSeries(
                    id = "s1", name = "A", type = null, color = "#123456",
                    values = listOf(100.0, 1.5, 0.25),
                ),
                ChartSeries(
                    id = "s2", name = "B", type = null, color = "#123456",
                    values = listOf(-3.0, 2.50, 0.0),
                ),
            ),
        )
        val html = ChartSerializer.serialize(data)
        assertTrue(html.contains("&quot;values&quot;:[100,1.5,0.25]"))
        assertTrue(html.contains("&quot;values&quot;:[-3,2.5,0]"))
    }

    @Test
    fun jsonEscaping_quotesBackslashAndControlsRoundTrip() {
        val data = ChartSerializer.defaultChart().copy(
            title = "He said \"hi\" \\ twice",
            categories = listOf("A\"B", "C\\D", "Line\nBreak"),
            series = listOf(
                ChartSeries(
                    id = "s1", name = "Tab\tName", type = null,
                    color = "#123456", values = listOf(1.0, 2.0, 3.0),
                ),
            ),
        )
        val html = ChartSerializer.serialize(data)
        // The title rides in an ATTRIBUTE: &quot; for its quotes,
        // the backslash raw (JSON escaping applies to the JSON
        // payloads, not to attribute text).
        assertTrue(html.contains("data-title=\"He said &quot;hi&quot; \\ twice\""))
        // The category JSON payload carries real JSON escapes.
        assertTrue(html.contains("A\\&quot;B"))
        val parsed = ChartParser.parse(html)!!
        assertEquals(ChartSerializer.normalize(data), parsed)
        assertEquals("He said \"hi\" \\ twice", parsed.title)
        assertEquals(listOf("A\"B", "C\\D", "Line\nBreak"), parsed.categories)
        assertEquals("Tab\tName", parsed.series[0].name)
    }

    @Test
    fun attributeEscaping_matchesNoteDocument() {
        // NoteDocument escapes & and " in attribute values (and
        // nothing else); the div must come back through BOTH the
        // chart parser and the document parser with the title
        // intact, angle brackets included.
        val data = ChartSerializer.defaultChart().copy(title = "Fish & \"Chips\" <deluxe>")
        val html = ChartSerializer.serialize(data)
        assertTrue(html.contains("data-title=\"Fish &amp; &quot;Chips&quot; <deluxe>\""))
        assertEquals("Fish & \"Chips\" <deluxe>", ChartParser.parse(html)!!.title)
        val segments = NoteDocument.parse(html)
        val chart = segments.filterIsInstance<ChartSegment>().single()
        assertEquals("Fish & \"Chips\" <deluxe>", ChartParser.parse(chart.rawHtml)!!.title)
    }

    @Test
    fun roundTrip_everyChartType() {
        for (type in ChartType.entries) {
            val data = ChartData(
                type = type,
                title = "Round trip",
                subtitle = "sub",
                categories = listOf("Q1", "Q2"),
                series = listOf(
                    ChartSeries(
                        id = "s1", name = "Alpha",
                        type = if (type == ChartType.COMPOSED) SeriesKind.AREA else null,
                        color = "#0a5c2e", values = listOf(3.5, null),
                    ),
                    ChartSeries(
                        id = "s2", name = "Beta", type = null,
                        color = "oklch(0.55 0.12 155)", values = listOf(7.0, 8.25),
                    ),
                ),
                options = ChartOptions(
                    stacked = true, showGrid = false, showLegend = true,
                    legendPosition = LegendPosition.TOP, smoothLine = false,
                    showMarkers = false, innerRadius = 45.5,
                    xAxisLabel = "When", yAxisLabel = "How much",
                ),
            )
            assertEquals(
                "round trip for $type",
                ChartSerializer.normalize(data),
                ChartParser.parse(ChartSerializer.serialize(data)),
            )
        }
    }

    @Test
    fun serialiseOfParsed1xDiv_passes1xGates() {
        // A 1.x-authored div (JSON in single-quoted attributes, as
        // the parser tests model it) re-serialises to a div whose
        // own parse is unchanged and which carries every attribute
        // 1.x's parseHTML gate looks for.
        val oneX = "<div data-type=\"noma-chart\" data-version=\"2\" " +
            "data-chart-type=\"donut\" data-title=\"Share\" data-subtitle=\"\" " +
            "data-categories='[\"A\",\"B\"]' " +
            "data-series='[{\"id\":\"s1\",\"name\":\"A\",\"values\":[3,4]}]' " +
            "data-options='{\"innerRadius\":90}'></div>"
        val parsed = ChartParser.parse(oneX)!!
        val out = ChartSerializer.serialize(parsed)
        assertTrue(out.startsWith("<div data-type=\"noma-chart\" data-version=\"2\""))
        assertTrue(out.contains("data-categories="))
        assertTrue(out.contains("data-series="))
        assertTrue(out.contains("data-options="))
        assertEquals(parsed, ChartParser.parse(out))
    }

    @Test
    fun normalisation_capsCoercionsAndColourValidation() {
        val data = ChartData(
            type = ChartType.DONUT,
            title = "T".repeat(150),
            subtitle = "",
            categories = (1..60).map { "C$it" },
            series = (1..25).map { i ->
                ChartSeries(
                    id = "s$i", name = "S$i", type = null,
                    color = "not-a-colour",
                    values = listOf(Double.POSITIVE_INFINITY, Double.NaN) + List(58) { 1.0 },
                )
            },
            options = ChartOptions(
                stacked = false, showGrid = true, showLegend = true,
                legendPosition = LegendPosition.BOTTOM, smoothLine = true,
                showMarkers = true, innerRadius = 999.0,
                xAxisLabel = "", yAxisLabel = "",
            ),
        )
        val html = ChartSerializer.serialize(data)
        assertFalse(html.contains("Infinity"))
        assertFalse(html.contains("NaN"))
        assertFalse(html.contains("not-a-colour"))
        val parsed = ChartParser.parse(html)!!
        assertEquals(100, parsed.title.length)
        assertEquals(50, parsed.categories.size)
        assertEquals(20, parsed.series.size)
        assertEquals(120.0, parsed.options.innerRadius, 0.0)
        // Non-finite values coerced to 0; the rest survive, aligned.
        assertEquals(0.0, parsed.series[0].values[0]!!, 0.0)
        assertEquals(0.0, parsed.series[0].values[1]!!, 0.0)
        assertEquals(1.0, parsed.series[0].values[2]!!, 0.0)
        assertTrue(parsed.series.all { it.values.size == 50 })
        // The invalid colour was replaced by 1.x's default for the index.
        assertEquals(ChartParser.ONE_X_DEFAULT_SERIES_COLORS[0], parsed.series[0].color)
    }

    @Test
    fun legacyV1_serialisesAsV2() {
        val v1 = "<div data-type=\"noma-chart\" data-chart-type=\"bar\" " +
            "data-title=\"Old chart\" data-chart='[{\"name\":\"A\",\"value\":3}," +
            "{\"name\":\"B\",\"value\":7}]'></div>"
        val migrated = ChartParser.parse(v1)!!
        val out = ChartSerializer.serialize(migrated)
        assertTrue(out.contains("data-version=\"2\""))
        val reparsed = ChartParser.parse(out)!!
        assertEquals(migrated, reparsed)
        assertEquals(listOf("A", "B"), reparsed.categories)
        assertEquals(listOf(3.0, 7.0), reparsed.series[0].values)
    }
}
