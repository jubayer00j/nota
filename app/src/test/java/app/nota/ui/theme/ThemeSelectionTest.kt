package app.nota.ui.theme

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1.5 fix 5 regression tests: the OLED switch persisted only a
 * Boolean while scheme selection also required the *system* dark theme,
 * so OLED could never engage from inside the app. [selectScheme] is the
 * pure form of that selection; these tests pin the fixed behaviour.
 */
class ThemeSelectionTest {

    @Test
    fun oledSelectedByFlagAlone_evenWhenSystemIsLight() {
        assertEquals(
            NotaScheme.Oled,
            selectScheme(
                darkTheme = false,
                oledBlack = true,
                dynamicColor = false,
                sdkInt = 35,
            ),
        )
    }

    @Test
    fun oledWinsOverDynamicColour() {
        assertEquals(
            NotaScheme.Oled,
            selectScheme(
                darkTheme = true,
                oledBlack = true,
                dynamicColor = true,
                sdkInt = 35,
            ),
        )
    }

    @Test
    fun systemDarkWithoutOledIsDark() {
        assertEquals(
            NotaScheme.Dark,
            selectScheme(
                darkTheme = true,
                oledBlack = false,
                dynamicColor = false,
                sdkInt = 35,
            ),
        )
    }

    @Test
    fun systemLightWithoutOledIsLight() {
        assertEquals(
            NotaScheme.Light,
            selectScheme(
                darkTheme = false,
                oledBlack = false,
                dynamicColor = false,
                sdkInt = 35,
            ),
        )
    }

    // ---- PR #45: the theme choice → darkTheme mapping -------------

    @Test
    fun themeMode_pinnedLightWinsOverSystemDark() {
        assertFalse(resolveDarkTheme("light", systemInDarkTheme = true))
        assertFalse(resolveDarkTheme("light", systemInDarkTheme = false))
    }

    @Test
    fun themeMode_pinnedDarkWinsOverSystemLight() {
        assertTrue(resolveDarkTheme("dark", systemInDarkTheme = false))
        assertTrue(resolveDarkTheme("dark", systemInDarkTheme = true))
    }

    @Test
    fun themeMode_systemPassesTheSystemStateThrough() {
        assertTrue(resolveDarkTheme("system", systemInDarkTheme = true))
        assertFalse(resolveDarkTheme("system", systemInDarkTheme = false))
    }

    @Test
    fun themeMode_unknownDegradesToSystem() {
        assertTrue(resolveDarkTheme("sepia", systemInDarkTheme = true))
        assertFalse(resolveDarkTheme("sepia", systemInDarkTheme = false))
    }

    @Test
    fun themeMode_labelsMatchTheSettingsWording() {
        assertEquals("System", themeModeLabel("system"))
        assertEquals("Light", themeModeLabel("light"))
        assertEquals("Dark", themeModeLabel("dark"))
        assertEquals("System", themeModeLabel("sepia"))
    }

    // Ask Aurora (v0.26.0), Part 3: the system bars sit on the app
    // background edge-to-edge, so "light bars" (dark icons) is the
    // exact inverse of the resolved theme being dark — for BOTH the
    // status bar and the navigation bar (SystemBarAppearance).
    @Test
    fun lightBarIcons_isTheInverseOfResolvedDark() {
        assertTrue(lightBarIcons(resolvedDark = false))
        assertFalse(lightBarIcons(resolvedDark = true))
    }

    @Test
    fun dynamicColourNeedsApi31() {
        val s = Build.VERSION_CODES.S
        assertEquals(
            NotaScheme.DynamicDark,
            selectScheme(darkTheme = true, oledBlack = false, dynamicColor = true, sdkInt = s),
        )
        assertEquals(
            NotaScheme.Dark,
            selectScheme(darkTheme = true, oledBlack = false, dynamicColor = true, sdkInt = s - 1),
        )
    }
}
