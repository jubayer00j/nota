package app.nota.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Ask Aurora (v0.26.0), Part 3: the app draws edge-to-edge, so the
 * system bars' icon appearance has to follow the RESOLVED theme —
 * [WindowInsetsControllerCompat.isAppearanceLightStatusBars] is
 * "the bars sit on a LIGHT background", i.e. dark icons. It is the
 * exact inverse of resolved-dark. `enableEdgeToEdge()` applies an
 * appearance once, from the system state at activity creation; a
 * user-pinned Dark theme on a light-system device (or the reverse)
 * leaves the icons blending into the app background — invisible.
 * This mapping is pure so the polarity is pinned by unit tests
 * (ThemeSelectionTest); [SystemBarAppearance] is the applying seam.
 */
fun lightBarIcons(resolvedDark: Boolean): Boolean = !resolvedDark

/**
 * Applies [lightBarIcons] for the theme currently provided by
 * [NotaTheme] to this window's status AND navigation bars, and
 * re-applies whenever the resolved theme flips. Called once inside
 * MainActivity's themed content; a no-op in edit/preview mode.
 */
@Composable
fun SystemBarAppearance() {
    val resolvedDark = LocalNotaDarkTheme.current
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowInsetsControllerCompat(window, view)
        val light = lightBarIcons(resolvedDark)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }
}
