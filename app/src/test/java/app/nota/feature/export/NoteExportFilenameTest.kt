package app.nota.feature.export

import org.junit.Assert.assertEquals
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
}
