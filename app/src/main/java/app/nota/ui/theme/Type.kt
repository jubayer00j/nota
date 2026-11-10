package app.nota.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.nota.notes.R

/**
 * Nota type system (spec §6): Newsreader serif for reading, Public Sans for UI,
 * bundled Hind Siliguri so Bengali renders identically on every device.
 *
 * Phase 1a: ALL families are built purely from static TTFs bundled in
 * res/font. Phase 0 loaded Newsreader + Public Sans through Google Fonts
 * downloadable fonts (GMS provider), which fails on devices where the
 * Google app is disabled — bundling removes that dependency entirely.
 * The UI family lists Hind Siliguri after Public Sans so Bengali glyphs
 * fall back to the bundled font deterministically.
 */
val NotaSerifFamily: FontFamily = FontFamily(
    Font(R.font.newsreader_regular, FontWeight.Normal),
    Font(R.font.newsreader_medium, FontWeight.Medium),
    Font(R.font.newsreader_semibold, FontWeight.SemiBold),
    Font(R.font.newsreader_italic, FontWeight.Normal, style = FontStyle.Italic),
    Font(R.font.newsreader_italic_medium, FontWeight.Medium, style = FontStyle.Italic),
)

val NotaBengaliFamily = FontFamily(
    Font(R.font.hind_siliguri_regular, FontWeight.Normal),
    Font(R.font.hind_siliguri_medium, FontWeight.Medium),
    Font(R.font.hind_siliguri_semibold, FontWeight.SemiBold),
    Font(R.font.hind_siliguri_bold, FontWeight.Bold),
)

/** UI family: Public Sans first, bundled Hind Siliguri as the Bengali fallback. */
val NotaSansFamily: FontFamily = FontFamily(
    Font(R.font.public_sans_regular, FontWeight.Normal),
    Font(R.font.public_sans_medium, FontWeight.Medium),
    Font(R.font.public_sans_semibold, FontWeight.SemiBold),
    Font(R.font.public_sans_bold, FontWeight.Bold),
    Font(R.font.hind_siliguri_regular, FontWeight.Normal),
    Font(R.font.hind_siliguri_medium, FontWeight.Medium),
    Font(R.font.hind_siliguri_semibold, FontWeight.SemiBold),
    Font(R.font.hind_siliguri_bold, FontWeight.Bold),
)

/**
 * One type scale for the whole app. Reading styles (display/headline/title and
 * the editor body) use the serif with generous line height — reading comfort is
 * the brand (spec §6). UI chrome uses the sans.
 */
val NotaTypography = Typography(
    displayLarge = TextStyle(fontFamily = NotaSerifFamily, fontWeight = FontWeight.Medium, fontSize = 44.sp, lineHeight = 52.sp),
    displayMedium = TextStyle(fontFamily = NotaSerifFamily, fontWeight = FontWeight.Medium, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = NotaSerifFamily, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = NotaSerifFamily, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = NotaSerifFamily, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = NotaSerifFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = NotaSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = NotaSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = NotaSansFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = NotaSansFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = NotaSansFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = NotaSansFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = NotaSansFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = NotaSansFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

/** The editor's reading style: serif, calm line height (spec §6). */
val NotaEditorBody = TextStyle(
    fontFamily = NotaSerifFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 17.sp,
    lineHeight = 28.sp,
)

/**
 * The editor body style built from the Writing preferences
 * (Phase 6, PR #43 — Noma 1.x parity): the user's font size with
 * the line height as a MULTIPLIER of it, exactly how 1.x applies
 * its two Writing sliders to the note body. At the defaults
 * (17 / 1.7) this is the [NotaEditorBody] shape modulo the
 * multiplier's rounding (28.9sp vs the hand-set 28sp).
 */
fun notaEditorBody(fontSizeSp: Int, lineHeightMultiplier: Float): TextStyle = TextStyle(
    fontFamily = NotaSerifFamily,
    fontWeight = FontWeight.Normal,
    fontSize = fontSizeSp.sp,
    lineHeight = (fontSizeSp * lineHeightMultiplier).sp,
)
