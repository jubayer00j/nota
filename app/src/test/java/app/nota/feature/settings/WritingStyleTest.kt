package app.nota.feature.settings

import androidx.compose.ui.unit.sp
import app.nota.ui.theme.notaEditorBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Writing typography presentation (Phase 6, PR #43), pure JVM —
 * the ThemeSelectionTest pattern: the Settings summary format
 * ("17px · 1.7", 1.x's `summariseWriting`) and the derived
 * editor body style (serif, size in sp, line height = size ×
 * multiplier).
 */
class WritingStyleTest {

    @Test
    fun summary_formatsSizeAndOneDecimalLineHeight() {
        assertEquals("17px · 1.7", writingSummary(17, 1.7f))
        assertEquals("22px · 2.2", writingSummary(22, 2.2f))
        assertEquals("14px · 1.4", writingSummary(14, 1.4f))
        assertEquals("1.4", formatLineHeight(1.4f))
        assertEquals("2.0", formatLineHeight(2.0f))
    }

    @Test
    fun editorBody_derivesSizeAndLineHeight() {
        val style = notaEditorBody(20, 2.0f)
        assertEquals(20.sp, style.fontSize)
        assertEquals(40.sp, style.lineHeight)

        val defaults = notaEditorBody(17, 1.7f)
        assertEquals(17.sp, defaults.fontSize)
        assertEquals((17 * 1.7f).sp, defaults.lineHeight)
    }
}
