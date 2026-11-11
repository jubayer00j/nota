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

    // ------------------------------------------------------ PR #51: audio

    @Test
    fun audioDurationFormat_isMinutesAndPaddedSeconds() {
        assertEquals("0:00", formatAudioDuration(0))
        assertEquals("0:00", formatAudioDuration(999))
        assertEquals("0:09", formatAudioDuration(9_000))
        assertEquals("1:05", formatAudioDuration(65_000))
        assertEquals("10:00", formatAudioDuration(600_000))
        // Minutes are unbounded (a long recording is 75:20, not 1:15:20).
        assertEquals("75:20", formatAudioDuration(4_520_000))
        // A negative input (a stale clock read) clamps to zero.
        assertEquals("0:00", formatAudioDuration(-5_000))
    }

    @Test
    fun recordingTimer_accruesOnlyWhileRecording() {
        val timer = RecordingTimer()
        assertEquals(RecordingTimer.State.IDLE, timer.state)
        assertEquals(0L, timer.elapsedAt(1_000))

        timer.start(1_000)
        assertEquals(RecordingTimer.State.RECORDING, timer.state)
        assertEquals(2_500L, timer.elapsedAt(3_500))

        // Pausing banks the live segment; time stops accruing.
        timer.pause(3_500)
        assertEquals(RecordingTimer.State.PAUSED, timer.state)
        assertEquals(2_500L, timer.elapsedAt(9_000))

        // Resuming starts a new segment on top of the bank.
        timer.resume(10_000)
        assertEquals(4_000L, timer.elapsedAt(11_500))

        // Stop reports the final elapsed — the duration the saved
        // segment carries — and resets to IDLE.
        assertEquals(4_500L, timer.stop(12_000))
        assertEquals(RecordingTimer.State.IDLE, timer.state)
        assertEquals(0L, timer.elapsedAt(20_000))
    }

    @Test
    fun recordingTimer_ignoresOutOfPhaseTransitions() {
        val timer = RecordingTimer()
        timer.pause(1_000)
        timer.resume(1_000)
        assertEquals(RecordingTimer.State.IDLE, timer.state)
        timer.start(2_000)
        timer.start(3_000) // a second start must not reset the clock
        assertEquals(1_500L, timer.elapsedAt(3_500))
        timer.resume(3_600) // not paused: no-op
        assertEquals(1_600L, timer.elapsedAt(3_600))
        timer.cancel()
        assertEquals(RecordingTimer.State.IDLE, timer.state)
        assertEquals(0L, timer.elapsedAt(9_999))
    }
}
