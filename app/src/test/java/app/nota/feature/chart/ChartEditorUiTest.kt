package app.nota.feature.chart

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.nota.data.chart.ChartColors
import app.nota.data.chart.ChartData
import app.nota.data.chart.ChartOptions
import app.nota.data.chart.ChartParser
import app.nota.data.chart.ChartSerializer
import app.nota.data.chart.ChartSeries
import app.nota.data.chart.ChartType
import app.nota.data.chart.LegendPosition
import app.nota.ui.theme.NotaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Charts PR-B: the chart editor dialog at the composition level —
 * composed directly, the harness-proven route (the editor's
 * insert-menu popup does not enter the Robolectric semantics
 * forest; the full-screen tap-to-edit flow is covered in
 * EditorReadModeUiTest). Pins: the insert seed renders as the
 * default grid and Save hands back the seeded model (whose
 * serialisation parses back to the same model — the inserted
 * segment IS the seeded chart), title edits flow through, Cancel
 * saves nothing, grid growth keeps values aligned, cell commits
 * coerce like 1.x, and the add buttons disable at the caps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartEditorUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun composeDialog(
        initial: ChartData,
        editing: Boolean = false,
        onSave: (ChartData) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            NotaTheme {
                ChartEditorDialog(
                    initial = initial,
                    editing = editing,
                    onDismiss = onDismiss,
                    onSave = onSave,
                )
            }
        }
    }

    @Test
    fun insertSeed_gridShowsDefaultChart_saveReturnsSeed() {
        var saved: ChartData? = null
        composeDialog(ChartSerializer.defaultChart(), onSave = { saved = it })

        // Data tab is the default: the seeded grid is on screen.
        composeRule.onNodeWithTag(chartCategoryFieldTag(0)).assertTextEquals("Jan")
        composeRule.onNodeWithTag(chartCategoryFieldTag(2)).assertTextEquals("Mar")
        composeRule.onNodeWithTag(chartSeriesNameFieldTag(0)).assertTextEquals("Series 1")
        composeRule.onNodeWithTag(chartValueFieldTag(0, 0)).assertTextEquals("100")
        composeRule.onNodeWithTag(chartValueFieldTag(0, 2)).assertTextEquals("200")

        composeRule.onNodeWithTag(CHART_SAVE_TAG).performClick()
        assertEquals(ChartSerializer.defaultChart(), saved)
        // The inserted segment's stored HTML parses back to the seed.
        assertEquals(
            saved,
            ChartParser.parse(ChartSerializer.serialize(saved!!)),
        )
    }

    @Test
    fun editSeed_titleChangeFlowsToSave() {
        val seed = ChartParser.parse(
            "<div data-type=\"noma-chart\" data-version=\"2\" " +
                "data-chart-type=\"line\" data-title=\"Quarterly Sales\" data-subtitle=\"\" " +
                "data-categories='[\"Jan\",\"Feb\"]' " +
                "data-series='[{\"id\":\"s1\",\"name\":\"Web\",\"values\":[10,20]}]'></div>",
        )!!
        var saved: ChartData? = null
        composeDialog(seed, editing = true, onSave = { saved = it })

        composeRule.onNodeWithTag(CHART_TAB_CHART_TAG).performClick()
        // The field's label merges into its semantics text, so the
        // assertion names both the label and the seeded value.
        composeRule.onNodeWithTag(CHART_TITLE_FIELD_TAG)
            .assertTextEquals("Title", "Quarterly Sales")
        composeRule.onNodeWithTag(CHART_TITLE_FIELD_TAG)
            .performTextReplacement("Annual Sales")
        composeRule.onNodeWithTag(CHART_SAVE_TAG).performClick()
        assertEquals("Annual Sales", saved!!.title)
        assertEquals(ChartType.LINE, saved!!.type)
        assertEquals(listOf(10.0, 20.0), saved!!.series[0].values)
    }

    @Test
    fun cancel_savesNothing() {
        var saved: ChartData? = null
        var dismissed = false
        composeDialog(
            ChartSerializer.defaultChart(),
            editing = true,
            onSave = { saved = it },
            onDismiss = { dismissed = true },
        )
        composeRule.onNodeWithTag(CHART_CANCEL_TAG).performClick()
        assertNull(saved)
        assertTrue(dismissed)
    }

    @Test
    fun addRowAndSeries_growsGridAndModel() {
        var saved: ChartData? = null
        composeDialog(ChartSerializer.defaultChart(), onSave = { saved = it })

        composeRule.onNodeWithTag(CHART_ADD_ROW_TAG).performClick()
        composeRule.onNodeWithTag(chartCategoryFieldTag(3)).assertTextEquals("Item 4")
        composeRule.onNodeWithTag(chartValueFieldTag(0, 3)).assertTextEquals("0")

        composeRule.onNodeWithTag(CHART_ADD_SERIES_TAG).performClick()
        composeRule.onNodeWithTag(chartSeriesNameFieldTag(1)).assertTextEquals("Series 2")

        composeRule.onNodeWithTag(CHART_SAVE_TAG).performClick()
        assertEquals(4, saved!!.categories.size)
        assertEquals(2, saved!!.series.size)
        // The new series wears the Nota palette colour for its
        // index and a zero for every (grown) category.
        assertEquals(ChartColors.NOTA_PALETTE_OKLCH[1], saved!!.series[1].color)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), saved!!.series[1].values)
        assertEquals(listOf(100.0, 150.0, 200.0, 0.0), saved!!.series[0].values)
    }

    @Test
    fun cellCommit_coercesLike1x() {
        var saved: ChartData? = null
        composeDialog(ChartSerializer.defaultChart(), onSave = { saved = it })

        composeRule.onNodeWithTag(chartValueFieldTag(0, 0))
            .performTextReplacement("12.5")
        composeRule.onNodeWithTag(chartValueFieldTag(0, 1))
            .performTextReplacement("abc")
        composeRule.onNodeWithTag(CHART_SAVE_TAG).performClick()
        assertEquals(12.5, saved!!.series[0].values[0]!!, 0.0)
        assertEquals(0.0, saved!!.series[0].values[1]!!, 0.0)
        assertEquals(200.0, saved!!.series[0].values[2]!!, 0.0)
    }

    @Test
    fun caps_disableAddButtons() {
        val maxed = ChartData(
            type = ChartType.BAR,
            title = "Maxed",
            subtitle = "",
            categories = (1..50).map { "C$it" },
            series = (1..20).map { i ->
                ChartSeries(
                    id = "s$i", name = "S$i", type = null,
                    color = "#123456", values = List(50) { 0.0 },
                )
            },
            options = ChartOptions(
                stacked = false, showGrid = true, showLegend = true,
                legendPosition = LegendPosition.BOTTOM, smoothLine = true,
                showMarkers = true, innerRadius = 60.0,
                xAxisLabel = "", yAxisLabel = "",
            ),
        )
        composeDialog(maxed)
        composeRule.onNodeWithTag(CHART_ADD_ROW_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(CHART_ADD_SERIES_TAG).assertIsNotEnabled()
    }

    @Test
    fun dataTabIsDefault_andPreviewRendersTitle() {
        composeDialog(ChartSerializer.defaultChart())
        composeRule.onNodeWithTag(CHART_TAB_DATA_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(CHART_ADD_ROW_TAG).assertIsDisplayed()
    }

    @Test
    fun pasteTsv_keepsCommasInsideCells() {
        // Codex P2 (PR #39): 1.x splits pasted lines on tabs AND
        // commas, truncating a TSV category like "New York, NY"
        // and shifting its values. Nota detects the delimiter:
        // a paste containing tabs splits on tabs only.
        val pasted = applyPasteMatrix(
            ChartSerializer.defaultChart(),
            "City\tSales\nNew York, NY\t12\nBoston\t9",
        )
        assertTrue(pasted != null)
        assertEquals(listOf("New York, NY", "Boston"), pasted!!.categories)
        assertEquals("Sales", pasted.series[0].name)
        assertEquals(listOf(12.0, 9.0), pasted.series[0].values)
    }

    @Test
    fun pasteCsv_honoursQuotedFields() {
        // The other arm: a comma-only paste is CSV, and a quoted
        // field keeps its comma; plain unquoted rows still split.
        val pasted = applyPasteMatrix(
            ChartSerializer.defaultChart(),
            "\"City\",\"Sales\"\n\"New York, NY\",12\nBoston,9",
        )
        assertTrue(pasted != null)
        assertEquals(listOf("New York, NY", "Boston"), pasted!!.categories)
        assertEquals("Sales", pasted.series[0].name)
        assertEquals(listOf(12.0, 9.0), pasted.series[0].values)
    }
}
