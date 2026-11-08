package app.nota.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 6 (Editor Refinement #1) — the round's constants and small
 * pure gates: the colour palettes must stay 1.x's VERBATIM values
 * (they are the shared HTML format), and the Image-from-URL dialog
 * must accept only absolute http(s) URLs.
 */
class EditorRefinementTest {

    @Test
    fun textColourPaletteIsOneXsVerbatim() {
        // note-editor.tsx TEXT_COLORS, in order (the "Default"
        // clear entry is the menu's own row, not a swatch).
        assertEquals(
            listOf(
                "Ink" to 0xFF1F1B16,
                "Slate" to 0xFF4B5563,
                "Red" to 0xFFB3261E,
                "Amber" to 0xFFB45309,
                "Green" to 0xFF15803D,
                "Blue" to 0xFF1D4ED8,
                "Violet" to 0xFF6D28D9,
            ),
            TEXT_COLOR_SWATCHES,
        )
    }

    @Test
    fun highlightPaletteIsOneXsVerbatim() {
        // note-editor.tsx BG_COLORS, in order (the "None" clear
        // entry is the menu's own row, not a swatch).
        assertEquals(
            listOf(
                "Sand" to 0xFFF4EAD9,
                "Rose" to 0xFFFBE0E0,
                "Peach" to 0xFFFDE4CF,
                "Lemon" to 0xFFFBF1C7,
                "Mint" to 0xFFDCF3E4,
                "Sky" to 0xFFDDE9FB,
                "Lilac" to 0xFFE8E0FB,
            ),
            HIGHLIGHT_COLOR_SWATCHES,
        )
    }

    @Test
    fun remoteImageUrlAcceptsAbsoluteHttpAndHttps() {
        assertEquals(
            "https://example.com/pic.png",
            parseRemoteImageUrl("https://example.com/pic.png"),
        )
        assertEquals(
            "http://example.com/pic.png",
            parseRemoteImageUrl("http://example.com/pic.png"),
        )
        // Surrounding whitespace is trimmed; scheme case is free.
        assertEquals(
            "HTTPS://example.com/pic.png",
            parseRemoteImageUrl("  HTTPS://example.com/pic.png  "),
        )
    }

    @Test
    fun remoteImageUrlRejectsEverythingElse() {
        assertNull(parseRemoteImageUrl(""))
        assertNull(parseRemoteImageUrl("   "))
        assertNull(parseRemoteImageUrl("example.com/pic.png"))
        assertNull(parseRemoteImageUrl("ftp://example.com/pic.png"))
        assertNull(parseRemoteImageUrl("https://"))
        assertNull(parseRemoteImageUrl("http://"))
        assertNull(parseRemoteImageUrl("https://example.com/my pic.png"))
        assertNull(parseRemoteImageUrl("nota-attachment://abc123"))
    }
}
