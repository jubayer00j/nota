package app.nota.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import app.nota.notes.R

/**
 * Nota type system (spec §6): Newsreader serif for reading, Public Sans for UI,
 * bundled Hind Siliguri so Bengali renders identically on every device.
 *
 * Newsreader + Public Sans load through Google Fonts downloadable fonts;
 * Hind Siliguri is bundled in res/font. The UI family lists Hind Siliguri after
 * Public Sans so Bengali glyphs fall back to the bundled font deterministically.
 */
private val gmsFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private fun googleFontFamily(name: String, vararg weights: FontWeight): FontFamily =
    FontFamily(
        weights.map { w ->
            Font(googleFont = GoogleFont(name), fontProvider = gmsFontProvider, weight = w)
        },
    )

val NotaSerifFamily: FontFamily = FontFamily(
    Font(googleFont = GoogleFont("Newsreader"), fontProvider = gmsFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Newsreader"), fontProvider = gmsFontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Newsreader"), fontProvider = gmsFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Newsreader"), fontProvider = gmsFontProvider, weight = FontWeight.Normal, style = FontStyle.Italic),
)

val NotaBengaliFamily = FontFamily(
    Font(R.font.hind_siliguri_regular, FontWeight.Normal),
    Font(R.font.hind_siliguri_medium, FontWeight.Medium),
    Font(R.font.hind_siliguri_semibold, FontWeight.SemiBold),
    Font(R.font.hind_siliguri_bold, FontWeight.Bold),
)

/** UI family: Public Sans first, bundled Hind Siliguri as the Bengali fallback. */
val NotaSansFamily: FontFamily = FontFamily(
    Font(googleFont = GoogleFont("Public Sans"), fontProvider = gmsFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Public Sans"), fontProvider = gmsFontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Public Sans"), fontProvider = gmsFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Public Sans"), fontProvider = gmsFontProvider, weight = FontWeight.Bold),
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
