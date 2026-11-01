package app.nota.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Nota "pine & paper" palette — the approved design language from the Phase 0
 * mock-ups: deep pine green + warm paper tones. These constants are the single
 * source of colour truth; screens consume them through Material 3 colour roles
 * in [NotaTheme], never directly (dynamic colour may replace them at runtime).
 */
object NotaPalette {
    // Pine scale
    val Pine950 = Color(0xFF0A2A20)
    val Pine900 = Color(0xFF0F3527)
    val Pine800 = Color(0xFF15452F)
    val Pine700 = Color(0xFF1B5E44)
    val Pine600 = Color(0xFF22755A)
    val Pine500 = Color(0xFF2E8B6E)
    val Pine300 = Color(0xFF7FBFA5)
    val Pine200 = Color(0xFFA9D8C3)
    val Pine100 = Color(0xFFCDE9DB)
    val Pine50 = Color(0xFFE7F4EC)

    // Paper scale (warm neutrals)
    val Paper = Color(0xFFFAF5EA)        // app background, light
    val PaperRaised = Color(0xFFFFFDF6)  // cards/surfaces, light
    val PaperVariant = Color(0xFFEFE8D8) // surfaceVariant, light
    val PaperDeep = Color(0xFFE3DAC6)

    // Ink
    val Ink = Color(0xFF1B211D)
    val InkSoft = Color(0xFF454C46)
    val InkFaint = Color(0xFF767C75)

    // Dark surfaces
    val NightBg = Color(0xFF101613)
    val NightSurface = Color(0xFF161D19)
    val NightVariant = Color(0xFF242E28)
    val NightInk = Color(0xFFE3EAE0)
    val NightInkSoft = Color(0xFFB9C4B8)

    // OLED true black
    val OledBg = Color(0xFF000000)
    val OledSurface = Color(0xFF050505)
    val OledVariant = Color(0xFF121212)

    // Warm clay accent (sparing — highlights, FAB secondary actions)
    val Clay = Color(0xFFA9714B)
    val ClaySoft = Color(0xFFFFDCC4)
    val ClayDark = Color(0xFF8A5A36)
}
