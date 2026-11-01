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
    background = NotaPalette.Paper,
    onBackground = NotaPalette.Ink,
    surface = NotaPalette.PaperRaised,
    onSurface = NotaPalette.Ink,
    surfaceVariant = NotaPalette.PaperVariant,
    onSurfaceVariant = NotaPalette.InkSoft,
    outline = NotaPalette.InkFaint,
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
    background = NotaPalette.NightBg,
    onBackground = NotaPalette.NightInk,
    surface = NotaPalette.NightSurface,
    onSurface = NotaPalette.NightInk,
    surfaceVariant = NotaPalette.NightVariant,
    onSurfaceVariant = NotaPalette.NightInkSoft,
    outline = NotaPalette.InkFaint,
)

/** True-black OLED variant of the dark scheme (spec §6). */
private val OledColors = DarkColors.copy(
    background = NotaPalette.OledBg,
    surface = NotaPalette.OledSurface,
    surfaceVariant = NotaPalette.OledVariant,
)

/**
 * Nota's theme. Material You dynamic colour is the default on Android 12+
 * (spec §6) with the pine & paper schemes as the fallback and as the static
 * choice; [oledBlack] swaps the dark scheme for true black.
 */
@Composable
fun NotaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    oledBlack: Boolean = false,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        darkTheme && oledBlack -> OledColors
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = NotaTypography,
        content = content,
    )
}
