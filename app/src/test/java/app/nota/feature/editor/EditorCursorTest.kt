package app.nota.feature.editor

import androidx.compose.ui.graphics.Color
import app.nota.ui.theme.NotaPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Ask Aurora (v0.26.0), Part 2: the rich editor's caret used to
 * be the richeditor library's default SOLID BLACK brush, which
 * blended into the dark schemes. The mapping from the resolved
 * theme to the caret/selection ink is pure — these tests pin it
 * so the caret can never silently fall back to black-on-dark.
 */
class EditorCursorTest {

    @Test
    fun darkThemeGetsTheBrightAuroraMintCaret() {
        assertEquals(NotaPalette.AuroraMint, editorCursorColor(resolvedDark = true))
    }

    @Test
    fun lightThemeGetsThePineCaret() {
        assertEquals(NotaPalette.Pine700, editorCursorColor(resolvedDark = false))
    }

    @Test
    fun neitherCaretIsTheLibraryDefaultBlack() {
        assertNotEquals(Color.Black, editorCursorColor(resolvedDark = true))
        assertNotEquals(Color.Black, editorCursorColor(resolvedDark = false))
    }

    @Test
    fun theTwoCaretsDiffer() {
        assertNotEquals(
            editorCursorColor(resolvedDark = true),
            editorCursorColor(resolvedDark = false),
        )
    }

    @Test
    fun selectionInkFollowsTheCaretAccent() {
        val dark = editorSelectionColors(resolvedDark = true)
        assertEquals(NotaPalette.AuroraMint, dark.handleColor)
        assertEquals(
            NotaPalette.AuroraMint.copy(alpha = 0.35f),
            dark.backgroundColor,
        )
        val light = editorSelectionColors(resolvedDark = false)
        assertEquals(NotaPalette.Pine700, light.handleColor)
        assertEquals(
            NotaPalette.Pine700.copy(alpha = 0.35f),
            light.backgroundColor,
        )
    }
}
