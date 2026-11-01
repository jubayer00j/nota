package app.nota.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Phase 1a: EVERY Material 3 colour role is specified explicitly in all
 * three schemes below, derived from [NotaPalette]. Phase 0 left roles such
 * as surfaceContainer (which NavigationBar renders), surfaceDim/Bright,
 * the inverse family, outlineVariant, scrim and the error family to the
 * Material baseline defaults — which is why the bottom bar rendered
 * baseline lavender on device instead of paper.
 */
private val LightColors = lightColorScheme(
    primary = NotaPalette.Pine700,
    onPrimary = Color.White,
    primaryContainer = NotaPalette.Pine100,
    onPrimaryContainer = NotaPalette.Pine900,
    secondary = NotaPalette.Pine600,
    onSecondary = Color.White,
    secondaryContainer = NotaPalette.Pine50,
    onSecondaryContainer = NotaPalette.Pine900,
    tertiary = NotaPalette.Clay,
    onTertiary = Color.White,
    tertiaryContainer = NotaPalette.ClaySoft,
    onTertiaryContainer = NotaPalette.ClayDark,
    error = NotaPalette.ErrorLight,
    onError = NotaPalette.OnErrorLight,
    errorContainer = NotaPalette.ErrorContainerLight,
    onErrorContainer = NotaPalette.OnErrorContainerLight,
    background = NotaPalette.Paper,
    onBackground = NotaPalette.Ink,
    surface = NotaPalette.PaperRaised,
    onSurface = NotaPalette.Ink,
    surfaceVariant = NotaPalette.PaperVariant,
    onSurfaceVariant = NotaPalette.InkSoft,
    outline = NotaPalette.InkFaint,
    outlineVariant = NotaPalette.PaperOutlineVariant,
    scrim = NotaPalette.Scrim,
    inverseSurface = NotaPalette.Ink,
    inverseOnSurface = NotaPalette.Paper,
    inversePrimary = NotaPalette.Pine200,
    surfaceDim = NotaPalette.PaperDim,
    surfaceBright = NotaPalette.PaperBright,
    surfaceContainerLowest = NotaPalette.PaperBright,
    surfaceContainerLow = NotaPalette.PaperContainerLow,
    surfaceContainer = NotaPalette.PaperContainer,
    surfaceContainerHigh = NotaPalette.PaperContainerHigh,
    surfaceContainerHighest = NotaPalette.PaperContainerHighest,
    surfaceTint = NotaPalette.Pine700,
)

private val DarkColors = darkColorScheme(
    primary = NotaPalette.Pine200,
    onPrimary = NotaPalette.Pine950,
    primaryContainer = NotaPalette.Pine800,
    onPrimaryContainer = NotaPalette.Pine100,
    secondary = NotaPalette.Pine300,
    onSecondary = NotaPalette.Pine950,
    secondaryContainer = NotaPalette.Pine900,
    onSecondaryContainer = NotaPalette.Pine100,
    tertiary = NotaPalette.ClaySoft,
    onTertiary = NotaPalette.ClayDark,
    tertiaryContainer = NotaPalette.ClayDark,
    onTertiaryContainer = NotaPalette.ClaySoft,
    error = NotaPalette.ErrorDark,
    onError = NotaPalette.OnErrorDark,
    errorContainer = NotaPalette.ErrorContainerDark,
    onErrorContainer = NotaPalette.OnErrorContainerDark,
    background = NotaPalette.NightBg,
    onBackground = NotaPalette.NightInk,
    surface = NotaPalette.NightSurface,
    onSurface = NotaPalette.NightInk,
    surfaceVariant = NotaPalette.NightVariant,
    onSurfaceVariant = NotaPalette.NightInkSoft,
    outline = NotaPalette.InkFaint,
    outlineVariant = NotaPalette.NightOutlineVariant,
    scrim = NotaPalette.Scrim,
    inverseSurface = NotaPalette.NightInk,
    inverseOnSurface = NotaPalette.NightBg,
    inversePrimary = NotaPalette.Pine700,
    surfaceDim = NotaPalette.NightDim,
    surfaceBright = NotaPalette.NightBright,
    surfaceContainerLowest = NotaPalette.NightContainerLowest,
    surfaceContainerLow = NotaPalette.NightContainerLow,
    surfaceContainer = NotaPalette.NightContainer,
    surfaceContainerHigh = NotaPalette.NightContainerHigh,
    surfaceContainerHighest = NotaPalette.NightContainerHighest,
    surfaceTint = NotaPalette.Pine200,
)

/**
 * True-black OLED variant of the dark scheme (spec §6): backgrounds,
 * surfaces and containers are the true-black family; all other roles are
 * listed explicitly with the same values as the dark scheme.
 */
private val OledColors = darkColorScheme(
    primary = NotaPalette.Pine200,
    onPrimary = NotaPalette.Pine950,
    primaryContainer = NotaPalette.Pine800,
    onPrimaryContainer = NotaPalette.Pine100,
    secondary = NotaPalette.Pine300,
    onSecondary = NotaPalette.Pine950,
    secondaryContainer = NotaPalette.Pine900,
    onSecondaryContainer = NotaPalette.Pine100,
    tertiary = NotaPalette.ClaySoft,
    onTertiary = NotaPalette.ClayDark,
    tertiaryContainer = NotaPalette.ClayDark,
    onTertiaryContainer = NotaPalette.ClaySoft,
    error = NotaPalette.ErrorDark,
    onError = NotaPalette.OnErrorDark,
    errorContainer = NotaPalette.ErrorContainerDark,
    onErrorContainer = NotaPalette.OnErrorContainerDark,
    background = NotaPalette.OledBg,
    onBackground = NotaPalette.NightInk,
    surface = NotaPalette.OledSurface,
    onSurface = NotaPalette.NightInk,
    surfaceVariant = NotaPalette.OledVariant,
    onSurfaceVariant = NotaPalette.NightInkSoft,
    outline = NotaPalette.InkFaint,
    outlineVariant = NotaPalette.OledOutlineVariant,
    scrim = NotaPalette.Scrim,
    inverseSurface = NotaPalette.NightInk,
    inverseOnSurface = NotaPalette.NightBg,
    inversePrimary = NotaPalette.Pine700,
    surfaceDim = NotaPalette.OledDim,
    surfaceBright = NotaPalette.OledBright,
    surfaceContainerLowest = NotaPalette.OledContainerLowest,
    surfaceContainerLow = NotaPalette.OledContainerLow,
    surfaceContainer = NotaPalette.OledContainer,
    surfaceContainerHigh = NotaPalette.OledContainerHigh,
    surfaceContainerHighest = NotaPalette.OledContainerHighest,
    surfaceTint = NotaPalette.Pine200,
)

/**
 * Nota's theme.
 *
 * Phase 1a theme-default decision: the static pine & paper schemes are the
 * DEFAULT ([dynamicColor] defaults to false). Spec §6 originally made
 * Material You dynamic colour the default, but on device dynamic colour is
 * exactly what made the UI not match the approved mock-ups, so dynamic
 * colour is now an opt-in Settings toggle ("Material You dynamic colour",
 * persisted in DataStore). OLED true black overrides everything when on.
 */

/** Which colour scheme [NotaTheme] will use — a plain value for testability. */
enum class NotaScheme { Light, Dark, Oled, DynamicLight, DynamicDark }

/**
 * Pure scheme selection. Phase 1.5 fix 5: OLED used to be gated on
 * `darkTheme && oledBlack`, but darkTheme comes only from the system —
 * the app has no in-app dark-mode control — so flipping the OLED switch
 * on a light-mode device could never engage the true-black scheme.
 * OLED is itself a dark scheme, so the persisted flag alone selects it.
 */
fun selectScheme(
    darkTheme: Boolean,
    oledBlack: Boolean,
    dynamicColor: Boolean,
    sdkInt: Int,
): NotaScheme = when {
    oledBlack -> NotaScheme.Oled
    dynamicColor && sdkInt >= Build.VERSION_CODES.S ->
        if (darkTheme) NotaScheme.DynamicDark else NotaScheme.DynamicLight
    darkTheme -> NotaScheme.Dark
    else -> NotaScheme.Light
}

@Composable
fun NotaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    oledBlack: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when (
        selectScheme(darkTheme, oledBlack, dynamicColor, Build.VERSION.SDK_INT)
    ) {
        NotaScheme.Oled -> OledColors
        NotaScheme.DynamicDark -> dynamicDarkColorScheme(context)
        NotaScheme.DynamicLight -> dynamicLightColorScheme(context)
        NotaScheme.Dark -> DarkColors
        NotaScheme.Light -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = NotaTypography,
        content = content,
    )
}
