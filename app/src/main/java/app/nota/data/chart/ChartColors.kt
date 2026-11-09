package app.nota.data.chart

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Chart colour resolution (Charts PR-A).
 *
 * 1.x persists series colours as CSS strings — its own defaults are
 * `oklch(...)` values, and a user-picked colour can be any CSS
 * colour the web colour input produced. Rendering therefore needs a
 * CSS → Compose [Color] conversion, which lives here: `oklch()`
 * (the persisted norm), plus `#hex` and `rgb()/rgba()` defensively.
 * Anything unparseable resolves to the Nota palette.
 *
 * The palette story mirrors 1.x's `getChartColors`: a series whose
 * persisted colour IS one of 1.x's built-in defaults is recoloured
 * to the ACTIVE theme's palette by index (here, Nota's pine-paper
 * palette), while a genuinely custom colour passes through
 * untouched. Pie/donut slices always cycle the palette by category
 * index, as 1.x's renderer does.
 */
object ChartColors {

    /**
     * Nota's default chart palette — pine-family oklch constants in
     * ONE place (PR-B seeds new series with these same strings, so a
     * chart created in Nota stores colours that read identically
     * back). Order matters: index 0 is the deep pine anchor.
     */
    val NOTA_PALETTE_OKLCH: List<String> = listOf(
        "oklch(0.437 0.095 164)", // deep pine
        "oklch(0.55 0.12 155)", // pine green
        "oklch(0.65 0.11 84)", // warm ochre
        "oklch(0.55 0.09 235)", // slate blue
        "oklch(0.58 0.13 32)", // terracotta clay
        "oklch(0.6 0.08 305)", // muted plum
        "oklch(0.68 0.1 115)", // sage olive
    )

    /** 1.x's LEGACY_DEFAULT_COLORS: the built-in palette strings a
     *  stored chart may carry that mean "no custom colour chosen". */
    val LEGACY_DEFAULT_COLORS: List<String> = ChartParser.ONE_X_DEFAULT_SERIES_COLORS + listOf(
        "oklch(0.6 0.18 150)",
        "oklch(0.65 0.2 40)",
    )

    /** The Nota palette colour for [index] (wrapping), converted. */
    fun notaPaletteColor(index: Int): Color {
        val css = NOTA_PALETTE_OKLCH[((index % NOTA_PALETTE_OKLCH.size) + NOTA_PALETTE_OKLCH.size) %
            NOTA_PALETTE_OKLCH.size]
        // The constants above are valid by construction.
        return parseCssColor(css) ?: Color(0xFF17452F)
    }

    /** Pie/donut slice colour for category [index]: the palette cycle, as in 1.x. */
    fun pieSliceColor(index: Int): Color = notaPaletteColor(index)

    /**
     * The render colour for a series: a persisted 1.x default
     * string is remapped to the Nota palette by series index (1.x's
     * theme-adaptive behaviour); a custom CSS colour is parsed and
     * used as-is; anything unparseable falls back to the palette.
     */
    fun seriesColor(persisted: String?, index: Int): Color {
        val trimmed = persisted?.trim()
        if (trimmed != null && trimmed in LEGACY_DEFAULT_COLORS) return notaPaletteColor(index)
        return parseCssColor(trimmed) ?: notaPaletteColor(index)
    }

    /**
     * Parses a CSS colour string — `oklch()`, `#hex` (3/4/6/8
     * digits), `rgb()`/`rgba()` (comma or space syntax, 0–255 or %
     * components). Returns null for anything else.
     */
    fun parseCssColor(css: String?): Color? {
        val s = css?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        return when {
            s.startsWith("#") -> parseHex(s)
            s.startsWith("oklch(") -> parseOklchBody(s)?.let { (l, c, h, a) -> oklchToColor(l, c, h, a) }
            s.startsWith("rgb(") || s.startsWith("rgba(") -> parseRgb(s)
            else -> null
        }
    }

    /** Parses an `oklch(L C H [/ A])` body → (L, C, hueDegrees, alpha). */
    internal fun parseOklchBody(s: String): Float4? {
        val inner = s.removePrefix("oklch(").substringBefore(')') ?: return null
        val parts = inner.replace('/', ' ').split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val l = parseComponent(parts[0], percentScale = 1.0) ?: return null
        val c = parseComponent(parts[1], percentScale = 0.4) ?: return null
        val h = parseHue(parts[2]) ?: return null
        val a = if (parts.size >= 4) parseAlpha(parts[3]) ?: return null else 1.0
        return Float4(l, c, h, a)
    }

    data class Float4(val l: Double, val c: Double, val h: Double, val a: Double)

    private fun parseComponent(token: String, percentScale: Double): Double? =
        if (token.endsWith('%')) {
            token.dropLast(1).toDoubleOrNull()?.div(100.0)?.times(percentScale)
        } else {
            token.toDoubleOrNull()
        }

    private fun parseHue(token: String): Double? = when {
        token.endsWith("deg") -> token.dropLast(3).toDoubleOrNull()
        token.endsWith("grad") -> token.dropLast(4).toDoubleOrNull()?.times(0.9)
        token.endsWith("rad") -> token.dropLast(3).toDoubleOrNull()?.times(180.0 / Math.PI)
        token.endsWith("turn") -> token.dropLast(4).toDoubleOrNull()?.times(360.0)
        else -> token.toDoubleOrNull()
    }

    private fun parseAlpha(token: String): Double? =
        if (token.endsWith('%')) {
            token.dropLast(1).toDoubleOrNull()?.div(100.0)
        } else {
            token.toDoubleOrNull()
        }?.coerceIn(0.0, 1.0)

    /**
     * OKLCH → sRGB via OKLab (Björn Ottosson's matrices), with the
     * linear result gamma-encoded and clamped into gamut.
     */
    fun oklchToColor(l: Double, c: Double, hueDegrees: Double, alpha: Double = 1.0): Color {
        val hRad = Math.toRadians(hueDegrees)
        val a = c * cos(hRad)
        val b = c * sin(hRad)
        val l_ = l + 0.3963377774 * a + 0.2158037573 * b
        val m_ = l - 0.1055613458 * a - 0.0638541728 * b
        val s_ = l - 0.0894841775 * a - 1.2914855480 * b
        val l3 = l_ * l_ * l_
        val m3 = m_ * m_ * m_
        val s3 = s_ * s_ * s_
        val rLin = +4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3
        val gLin = -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3
        val bLin = -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3
        return Color(
            red = gammaEncode(rLin),
            green = gammaEncode(gLin),
            blue = gammaEncode(bLin),
            alpha = alpha.toFloat().coerceIn(0f, 1f),
        )
    }

    private fun gammaEncode(linear: Double): Float {
        val encoded = if (linear <= 0.0031308) {
            12.92 * linear
        } else {
            1.055 * linear.pow(1.0 / 2.4) - 0.055
        }
        return encoded.coerceIn(0.0, 1.0).toFloat()
    }

    private fun parseHex(s: String): Color? {
        val hex = s.drop(1)
        if (hex.isEmpty() || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        fun pair(at: Int): Int = hex.substring(at, at + 2).toInt(16)
        fun single(at: Int): Int = hex[at].digitToInt(16) * 17
        return when (hex.length) {
            3 -> Color(single(0), single(1), single(2))
            4 -> Color(single(0), single(1), single(2), single(3))
            6 -> Color(pair(0), pair(2), pair(4))
            8 -> Color(pair(0), pair(2), pair(4), pair(6))
            else -> null
        }
    }

    private fun parseRgb(s: String): Color? {
        val inner = s.substringAfter('(').substringBefore(')')
        val parts = inner.replace(',', ' ').replace('/', ' ')
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        fun channel(token: String): Int? =
            if (token.endsWith('%')) {
                token.dropLast(1).toDoubleOrNull()?.times(2.55)?.let { kotlin.math.round(it).toInt() }
            } else {
                token.toDoubleOrNull()?.let { kotlin.math.round(it).toInt() }
            }?.coerceIn(0, 255)
        val r = channel(parts[0]) ?: return null
        val g = channel(parts[1]) ?: return null
        val b = channel(parts[2]) ?: return null
        val a = if (parts.size >= 4) {
            parseAlpha(parts[3])?.times(255)?.let { kotlin.math.round(it).toInt() } ?: return null
        } else {
            255
        }
        return Color(r, g, b, a)
    }
}
