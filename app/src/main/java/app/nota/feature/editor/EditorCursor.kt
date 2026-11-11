package app.nota.feature.editor

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.ui.graphics.Color
import app.nota.ui.theme.NotaPalette

/**
 * Ask Aurora (v0.26.0), Part 2: the rich editor's caret and selection
 * ink. The richeditor library's foundation editor defaults its caret
 * brush to SOLID BLACK (BasicRichTextEditor's `cursorBrush` default)
 * and Nota never overrode it, so on the dark schemes the caret
 * blended into the background and was nearly impossible to spot.
 * The choice keys off the RESOLVED theme (LocalNotaDarkTheme), not
 * the system state: dark → the bright aurora mint accent; light →
 * pine, matching the Material fields' primary-coloured caret. Pure
 * so the mapping is pinned by unit tests (EditorCursorTest); the
 * editor compositions wrap the colour in a SolidColor brush.
 */
fun editorCursorColor(resolvedDark: Boolean): Color =
    if (resolvedDark) NotaPalette.AuroraMint else NotaPalette.Pine700

/** Selection handle + highlight aligned with the caret accent. */
fun editorSelectionColors(resolvedDark: Boolean): TextSelectionColors {
    val accent = editorCursorColor(resolvedDark)
    return TextSelectionColors(
        handleColor = accent,
        backgroundColor = accent.copy(alpha = 0.35f),
    )
}
