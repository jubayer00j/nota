package app.nota.data.chart

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Charts PR-A: oklch → sRGB conversion vectors. The expected
 * values were produced by an independent implementation of the
 * OKLCH → OKLab → linear sRGB → gamma pipeline (the standard
 * Björn Ottosson matrices), cross-checked against known CSS
 * reference values (e.g. oklch(0.7 0.15 240) = #26A9F1), so a
 * broken matrix in the Kotlin port cannot agree with them by
 * construction.
 */
class ChartColorsTest {

    private fun assertColor(expected: Triple<Float, Float, Float>, actual: Color, tol: Float = 0.01f) {
        assertEquals("red", expected.first, actual.red, tol)
        assertEquals("green", expected.second, actual.green, tol)
        assertEquals("blue", expected.third, actual.blue, tol)
    }

    @Test
    fun oklch_oneXDefaultPalette_convertsToExpectedSrgb() {
        // 1.x's DEFAULT_SERIES_COLORS, in order.
        assertColor(Triple(0.1686f, 0.4510f, 0.9137f), ChartColors.oklchToColor(0.578, 0.192, 260.0))
        assertColor(Triple(0.4510f, 0.1922f, 0.8745f), ChartColors.oklchToColor(0.51, 0.24, 293.0))
        assertColor(Triple(0.3216f, 0.5804f, 0.9020f), ChartColors.oklchToColor(0.66, 0.14, 255.0))
        assertColor(Triple(0.5961f, 0.6196f, 0.8863f), ChartColors.oklchToColor(0.72, 0.1, 280.0))
        assertColor(Triple(0.6706f, 0.4078f, 0.7294f), ChartColors.oklchToColor(0.62, 0.14, 320.0))
    }

    @Test
    fun oklch_achromaticAndReferencePoints() {
        assertColor(Triple(0.3882f, 0.3882f, 0.3882f), ChartColors.oklchToColor(0.5, 0.0, 0.0))
        assertColor(Triple(1f, 1f, 1f), ChartColors.oklchToColor(1.0, 0.0, 0.0))
        assertColor(Triple(0f, 0f, 0f), ChartColors.oklchToColor(0.0, 0.0, 0.0))
        // The CSS Color 4 worked example hue.
        assertColor(Triple(0.1490f, 0.6627f, 0.9451f), ChartColors.oklchToColor(0.7, 0.15, 240.0))
    }

    @Test
    fun parseCssColor_oklchStringForm() {
        val parsed = ChartColors.parseCssColor("oklch(0.578 0.192 260)")!!
        assertColor(Triple(0.1686f, 0.4510f, 0.9137f), parsed)
        // Percent lightness + alpha slash form.
        val withAlpha = ChartColors.parseCssColor("oklch(55% 0.12 155 / 50%)")!!
        assertEquals(0.5f, withAlpha.alpha, 0.01f)
    }

    @Test
    fun parseCssColor_hexAndRgbForms() {
        assertColor(
            Triple(0f, 98f / 255f, 67f / 255f),
            ChartColors.parseCssColor("#006243")!!,
        )
        assertColor(
            Triple(170f / 255f, 187f / 255f, 204f / 255f),
            ChartColors.parseCssColor("#abc")!!,
        )
        assertColor(
            Triple(37f / 255f, 134f / 255f, 81f / 255f),
            ChartColors.parseCssColor("rgb(37, 134, 81)")!!,
        )
        val rgba = ChartColors.parseCssColor("rgba(186, 89, 71, 0.5)")!!
        assertColor(Triple(186f / 255f, 89f / 255f, 71f / 255f), rgba)
        assertEquals(0.5f, rgba.alpha, 0.01f)
    }

    @Test
    fun parseCssColor_rejectsNonColours() {
        assertNull(ChartColors.parseCssColor("banana"))
        assertNull(ChartColors.parseCssColor("red")) // named colours are out of scope
        assertNull(ChartColors.parseCssColor(""))
        assertNull(ChartColors.parseCssColor(null))
        assertNull(ChartColors.parseCssColor("#12"))
        assertNull(ChartColors.parseCssColor("oklch(0.5)"))
    }

    @Test
    fun seriesColor_legacyDefaultsRemapToNotaPalette() {
        // A persisted 1.x default means "no custom colour" → the
        // active palette by index (here index 0 = deep pine
        // oklch(0.437 0.095 164) = rgb(0, 98, 67)).
        val remapped = ChartColors.seriesColor("oklch(0.578 0.192 260)", 0)
        assertColor(Triple(0f, 0.3843f, 0.2627f), remapped)
        // The extra legacy entries remap too.
        val legacyGreen = ChartColors.seriesColor("oklch(0.6 0.18 150)", 2)
        assertEquals(ChartColors.notaPaletteColor(2), legacyGreen)
    }

    @Test
    fun seriesColor_customColoursPassThrough_andFallbacksUsePalette() {
        val custom = ChartColors.seriesColor("#123456", 3)
        assertColor(Triple(0x12 / 255f, 0x34 / 255f, 0x56 / 255f), custom)
        assertEquals(ChartColors.notaPaletteColor(1), ChartColors.seriesColor(null, 1))
        assertEquals(ChartColors.notaPaletteColor(2), ChartColors.seriesColor("not-a-colour", 2))
        // Index wraps around the 7-entry palette.
        assertEquals(ChartColors.notaPaletteColor(0), ChartColors.notaPaletteColor(7))
        assertEquals(ChartColors.pieSliceColor(1), ChartColors.pieSliceColor(8))
    }

    @Test
    fun notaPalette_entriesAllParse() {
        ChartColors.NOTA_PALETTE_OKLCH.forEachIndexed { i, css ->
            assertEquals("palette[$i]", ChartColors.notaPaletteColor(i), ChartColors.parseCssColor(css))
        }
    }
}
