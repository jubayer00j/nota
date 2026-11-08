package app.nota.feature.export

import app.nota.data.html.TextSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-note Export #1 — the plain-text converter (1.x's
 * noteToPlainText semantics): readable text, lists with markers
 * and indentation, links shown as text (URL), images as named
 * placeholders — data URLs are never embedded.
 */
class NoteExportTextTest {

    private fun text() = NoteExport.noteToPlainText(
        EXPORT_FIXTURE_TITLE, exportFixture(), exportResolver,
    )

    @Test
    fun titleIsTheFirstLineUnescaped() {
        val out = text()
        assertTrue(out.startsWith("Field notes & <draft>\n\n"))
        assertTrue(out.endsWith("\n"))
    }

    @Test
    fun inlineFormattingFlattensAndLinksShowTheirUrl() {
        val out = text()
        assertTrue(out.contains("Shopping & plans"))
        assertTrue(
            out.contains(
                "Bring bold, italic, struck, x < y and a " +
                    "link (https://example.com/list).",
            ),
        )
        assertTrue(out.contains("See Groceries and the house list."))
    }

    @Test
    fun listsIndentAndKeepTheirNumbers() {
        val out = text()
        assertTrue(out.contains("- milk\n- bread\n  - rye\n  - white"))
        assertTrue(out.contains("3. third\n4. fourth"))
    }

    @Test
    fun tasksTablesAndCode() {
        val out = text()
        assertTrue(out.contains("- [x] Book flights\n- [ ] Pack"))
        assertTrue(out.contains("Item | Qty\nRice | Basmati | 2"))
        assertTrue(out.contains("val x = \"a ``` b\" // <not> a tag"))
    }

    @Test
    fun quoteImagesFileDividerChartAndOpaque() {
        val out = text()
        assertTrue(out.contains("> Measure twice, cut once."))
        assertTrue(out.contains("[Image: Cat]"))
        assertTrue(out.contains("[Image: Gone]"))
        assertTrue(out.contains("[Image: Inline cat]"))
        assertTrue(out.contains("[File: tickets.pdf]"))
        assertTrue(out.contains("\n---\n"))
        assertTrue(out.contains("[Bar chart]"))
        assertTrue(out.contains("Embedded words"))
    }

    @Test
    fun dataUrlsAreNeverEmbedded() {
        assertFalse(text().contains("data:image"))
        assertFalse(text().contains("base64"))
    }

    @Test
    fun linkWithUrlEqualToTextIsNotDoubled() {
        val out = NoteExport.noteToPlainText(
            "T",
            listOf(
                TextSegment(
                    "<p><a href=\"https://example.com\">https://example.com</a></p>",
                ),
            ),
        )
        assertTrue(out.contains("https://example.com"))
        assertFalse(out.contains("(https://example.com)"))
    }

    @Test
    fun emptyDocumentIsJustTheTitle() {
        assertEquals("Solo\n", NoteExport.noteToPlainText("Solo", emptyList()))
    }

    @Test
    fun textBuildNeverConsultsTheResolver() {
        // The editor skips attachment resolution entirely for TEXT
        // exports; that gate is only safe while the text converter
        // never calls the resolver. A resolver that throws on any
        // call must still produce the full, identical output.
        val throwing: AttachmentResolver = { id ->
            throw AssertionError("TEXT export consulted the resolver for $id")
        }
        val artifact = NoteExport.build(
            EXPORT_FIXTURE_TITLE, exportFixture(), ExportFormat.TEXT, throwing,
        )
        assertEquals(text(), artifact.content)
    }
    @Test
    fun entitiesDecodeToCharactersInTextExport() {
        val out = NoteExport.noteToPlainText(
            "T",
            listOf(TextSegment(html = "<p>&copy; 2026 &eacute;t&eacute;</p>")),
            exportResolver,
        )
        assertTrue(out.contains("\u00A9 2026 \u00E9t\u00E9"))
    }

    @Test
    fun escapedEntitiesStayLiteralInTextExport() {
        val out = NoteExport.noteToPlainText(
            "T",
            listOf(TextSegment(html = "<p>&amp;copy;</p>")),
            exportResolver,
        )
        assertTrue(out.contains("&copy;"))
        assertFalse(out.contains("\u00A9"))
    }

    @Test
    fun listItemTextAfterANestedListKeepsItsPosition() {
        val out = NoteExport.noteToPlainText(
            "T",
            listOf(
                TextSegment(
                    html = "<ul><li>before<ul><li>child</li></ul>after</li></ul>",
                ),
            ),
        )
        assertTrue(out.contains("- before\n  - child\n  after"))
    }

    @Test
    fun semicolonlessLegacyReferencesDecodeInText() {
        val out = NoteExport.noteToPlainText(
            "T",
            listOf(
                TextSegment(
                    html = "<p>Fish &copy chips, &notit; today</p>",
                ),
            ),
        )
        assertTrue(out.contains("Fish \u00A9 chips, \u00ACit; today"))
    }

}
