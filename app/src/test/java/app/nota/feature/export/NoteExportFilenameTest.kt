package app.nota.feature.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-note Export #1 — the filename sanitiser and the artifact
 * builder's naming/MIME contract (1.x's exportFilename rules).
 */
class NoteExportFilenameTest {

    @Test
    fun extensionAndMimePerFormat() {
        val html = NoteExport.build("My note", emptyList(), ExportFormat.HTML)
        assertEquals("My note.html", html.fileName)
        assertEquals("text/html", html.mimeType)
        val md = NoteExport.build("My note", emptyList(), ExportFormat.MARKDOWN)
        assertEquals("My note.md", md.fileName)
        assertEquals("text/markdown", md.mimeType)
        val txt = NoteExport.build("My note", emptyList(), ExportFormat.TEXT)
        assertEquals("My note.txt", txt.fileName)
        assertEquals("text/plain", txt.mimeType)
    }

    @Test
    fun pathHostileCharactersAreStripped() {
        assertEquals(
            "Trip Packing list.html",
            NoteExport.exportFilename("Trip / Packing: list?", ExportFormat.HTML),
        )
        assertEquals(
            "a b c d e f g h i j.txt",
            NoteExport.exportFilename("a/b\\c:d*e?f\"g<h>i|j", ExportFormat.TEXT),
        )
    }

    @Test
    fun controlCharactersBecomeSpaces() {
        assertEquals(
            "a b.md",
            NoteExport.exportFilename("ab", ExportFormat.MARKDOWN),
        )
    }

    @Test
    fun blankAndWhitespaceTitlesFallBack() {
        assertEquals(
            "Untitled note.html",
            NoteExport.exportFilename("", ExportFormat.HTML),
        )
        assertEquals(
            "Untitled note.html",
            NoteExport.exportFilename("   ", ExportFormat.HTML),
        )
        assertEquals(
            "many spaces.txt",
            NoteExport.exportFilename("  many   spaces  ", ExportFormat.TEXT),
        )
    }

    @Test
    fun trailingDotsAreTrimmed() {
        assertEquals(
            "Notes.html",
            NoteExport.exportFilename("Notes...", ExportFormat.HTML),
        )
    }

    @Test
    fun longTitlesAreCappedAtEightyCharacters() {
        val name = NoteExport.exportFilename("a".repeat(120), ExportFormat.HTML)
        assertEquals(80 + ".html".length, name.length)
        assertTrue(name.endsWith(".html"))
    }

    @Test
    fun unicodeTitlesSurvive() {
        assertEquals(
            "ঢাকা নোট.html",
            NoteExport.exportFilename("ঢাকা নোট", ExportFormat.HTML),
        )
    }

    @Test
    fun emojiTitlesAreCappedByBytesToo() {
        // 80 emoji pass the 80-code-point cap but are 320 UTF-8
        // bytes — over the 255-byte file name component limit, so
        // the share cache write would fail. The byte cap cuts on
        // code-point boundaries: 63 emoji (252 bytes) + ".md" is
        // exactly 255.
        val name = NoteExport.exportFilename(
            "😀".repeat(80), ExportFormat.MARKDOWN,
        )
        assertTrue(name.endsWith(".md"))
        assertEquals(255, name.toByteArray(Charsets.UTF_8).size)
        val stem = name.removeSuffix(".md")
        assertEquals(63, stem.codePointCount(0, stem.length))
        // No split surrogates: the name round-trips through UTF-8.
        assertEquals(
            name,
            String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8),
        )
        assertFalse(name.contains("�"))
    }

    @Test
    fun mixedBengaliEmojiTitlesStayWithinByteLimit() {
        val name = NoteExport.exportFilename(
            "ঢাকা " + "😀".repeat(80), ExportFormat.HTML,
        )
        assertTrue(name.endsWith(".html"))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        // The Bengali prefix is at the front, so truncation keeps it.
        assertTrue(name.startsWith("ঢাকা "))
        assertEquals(
            name,
            String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8),
        )
    }

    @Test
    fun byteTruncationReTrimsExposedDots() {
        // 62 emoji (248 bytes) + "." + more emoji: the byte cut
        // lands just after the dot, and the re-trim must remove
        // it so the name does not end "…​..html".
        val name = NoteExport.exportFilename(
            "😀".repeat(62) + "." + "😀".repeat(10), ExportFormat.HTML,
        )
        assertEquals("😀".repeat(62) + ".html", name)
    }
}
