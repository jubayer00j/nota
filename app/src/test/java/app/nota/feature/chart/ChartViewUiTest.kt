package app.nota.feature.chart

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import app.nota.data.chart.ChartSerializer
import app.nota.ui.theme.NotaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Charts polish pack: ChartView composed directly (the
 * harness-proven route). Pins the tap-for-values loop end to end:
 * the default bar chart (Jan/Feb/Mar, 100/150/200) answers a tap
 * at the canvas centre with Feb's callout, a tap on empty space
 * dismisses it, re-tapping the same bar toggles it off, and a pie
 * chart names the tapped slice with its share.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartViewUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tapCentreBar_showsFebCallout_tapEmptySpaceDismisses() {
        composeRule.setContent {
            NotaTheme { ChartView(ChartSerializer.defaultChart()) }
        }
        val canvas = composeRule.onNodeWithTag(CHART_CANVAS_TAG)
        canvas.assertIsDisplayed()

        // The centre of the canvas is Feb's bar (the middle of
        // three bands, and 150 tall on a 0..200 domain).
        canvas.performTouchInput { click(center) }
        composeRule.onNodeWithTag(CHART_CALLOUT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Feb · Series 1: 150").assertIsDisplayed()

        // The top-left corner is axis-label margin: a miss clears.
        canvas.performTouchInput { click(Offset(2f, 2f)) }
        composeRule.onNodeWithText("Feb · Series 1: 150").assertDoesNotExist()
        composeRule.onNodeWithTag(CHART_CALLOUT_TAG).assertDoesNotExist()
    }

    @Test
    fun tapSameBarAgain_dismissesCallout() {
        composeRule.setContent {
            NotaTheme { ChartView(ChartSerializer.defaultChart()) }
        }
        val canvas = composeRule.onNodeWithTag(CHART_CANVAS_TAG)
        canvas.performTouchInput { click(center) }
        composeRule.onNodeWithText("Feb · Series 1: 150").assertIsDisplayed()
        canvas.performTouchInput { click(center) }
        composeRule.onNodeWithText("Feb · Series 1: 150").assertDoesNotExist()
    }

    @Test
    fun tapPieSlice_showsCalloutWithShare() {
        val pie = ChartSerializer.defaultChart().let {
            ChartSerializer.normalize(
                it.copy(
                    type = app.nota.data.chart.ChartType.PIE,
                    series = it.series.map { s -> s.copy(values = listOf(50.0, 25.0, 25.0)) },
                ),
            )
        }
        composeRule.setContent {
            NotaTheme { ChartView(pie) }
        }
        val canvas = composeRule.onNodeWithTag(CHART_CANVAS_TAG)
        // Straight above the centre is the first slice's territory
        // (slices start at the top; Jan holds half the pie).
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        canvas.performTouchInput {
            click(Offset(bounds.width / 2f, bounds.height * 0.3f))
        }
        composeRule.onNodeWithText("Jan: 50 (50%)").assertIsDisplayed()
    }
}
