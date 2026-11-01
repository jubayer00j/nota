package app.nota.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Nota "pine & paper" palette — the approved design language from the Phase 0
 * mock-ups: deep pine green + warm paper tones. These constants are the single
 * source of colour truth; screens consume them through Material 3 colour roles
 * in [NotaTheme], never directly (dynamic colour may replace them at runtime).
 *
 * Phase 1a: every Material 3 colour role in [Theme.kt] is derived from this
 * palette — including the surface-container family that drives the bottom
 * navigation bar — so no role can fall back to the Material baseline
 * (lavender) scheme on device.
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

    // Paper surface-container family (light) — tonal steps of the paper scale.
    // surfaceContainer is what NavigationBar renders, so it must be paper,
    // never the Material baseline container tone.
    val PaperBright = Color(0xFFFFFEF9)          // surfaceBright / containerLowest, light
    val PaperContainerLow = Color(0xFFF6F0E1)
    val PaperContainer = PaperVariant            // surfaceContainer, light
    val PaperContainerHigh = Color(0xFFEAE1CC)
    val PaperContainerHighest = PaperDeep        // surfaceContainerHighest, light
    val PaperDim = Color(0xFFDDD4BE)             // surfaceDim, light
    val PaperOutlineVariant = Color(0xFFCFC4A9)  // outlineVariant, light

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

    // Night surface-container family (dark) — tonal steps of the night scale.
    val NightBright = Color(0xFF363D38)          // surfaceBright, dark
    val NightContainerLowest = Color(0xFF090C0A)
    val NightContainerLow = Color(0xFF121714)
    val NightContainer = Color(0xFF1C241F)       // surfaceContainer, dark
    val NightContainerHigh = Color(0xFF262E28)
    val NightContainerHighest = Color(0xFF313A34)
    val NightDim = NightBg                       // surfaceDim, dark
    val NightOutlineVariant = Color(0xFF3A443D)  // outlineVariant, dark

    // OLED true black
    val OledBg = Color(0xFF000000)
    val OledSurface = Color(0xFF050505)
    val OledVariant = Color(0xFF121212)

    // OLED surface-container family — true-black steps only.
    val OledBright = Color(0xFF2C2C2C)           // surfaceBright, OLED
    val OledContainerLowest = Color(0xFF000000)
    val OledContainerLow = Color(0xFF050505)
    val OledContainer = Color(0xFF121212)        // surfaceContainer, OLED
    val OledContainerHigh = Color(0xFF1A1A1A)
    val OledContainerHighest = Color(0xFF242424)
    val OledDim = Color(0xFF000000)              // surfaceDim, OLED
    val OledOutlineVariant = Color(0xFF2E2E2E)   // outlineVariant, OLED

    // Warm clay accent (sparing — highlights, FAB secondary actions)
    val Clay = Color(0xFFA9714B)
    val ClaySoft = Color(0xFFFFDCC4)
    val ClayDark = Color(0xFF8A5A36)

    // Error family — warm brick reds that sit with pine & paper (the Material
    // error tones, warmed) with accessible on/container pairings.
    val ErrorLight = Color(0xFFBA1A1A)
    val OnErrorLight = Color(0xFFFFFFFF)
    val ErrorContainerLight = Color(0xFFFFDAD6)
    val OnErrorContainerLight = Color(0xFF93000A)
    val ErrorDark = Color(0xFFFFB4AB)
    val OnErrorDark = Color(0xFF690005)
    val ErrorContainerDark = Color(0xFF93000A)
    val OnErrorContainerDark = Color(0xFFFFDAD6)

    // Scrim (modal barriers) — true black in every scheme.
    val Scrim = Color(0xFF000000)
}
