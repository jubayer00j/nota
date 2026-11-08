package app.nota.feature.editor

import app.nota.data.html.NoteDocument
import app.nota.data.html.TableSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 (Editor Refinement #1, A3) — the table insert dialog's
 * construction and the at-the-selected-cell operations: dimensions
 * and caps from 1.x's dialog, header rows in 1.x's serialised form,
 * and insert/delete at a position keeping every other cell's
 * content — and the cellSpans parallelism — exactly aligned.
 */
class TableOpsTest {

    private fun labelled(rows: Int, cols: Int, headerRow: Boolean = false): TableSegment =
        TableSegment(
            List(rows) { r -> List(cols) { c -> "r${r}c$c" } },
            headerRow,
        )

    @Test
    fun newTableBuildsTheRequestedShape() {
        val table = TableOps.newTable(3, 4, headerRow = true)
        assertEquals(3, table.rows.size)
        assertTrue(table.rows.all { it.size == 4 })
        assertTrue(table.rows.flatten().all { it.isEmpty() })
        assertTrue(table.headerRow)
        // Editor-built tables carry no span list (all spans are 1).
        assertTrue(table.cellSpans.isEmpty())
    }

    @Test
    fun newTableClampsToOneXsCaps() {
        val oversized = TableOps.newTable(25, 99, headerRow = false)
        assertEquals(TableOps.MAX_ROWS, oversized.rows.size)
        assertTrue(oversized.rows.all { it.size == TableOps.MAX_COLS })
        val undersized = TableOps.newTable(0, -3, headerRow = false)
        assertEquals(1, undersized.rows.size)
        assertEquals(1, undersized.rows[0].size)
    }

    @Test
    fun headerRowSerialisesAsThAndRoundTripsByteIdentically() {
        // 1.x's form: <table><tbody><tr><th>… — the header row is
        // row 0 as <th> cells. A dialog-built table must serialise
        // to it and re-parse to the same rows + header flag, with
        // the second serialisation byte-identical.
        val table = TableSegment(
            listOf(listOf("Subject", "Score"), listOf("Physics", "91")),
            headerRow = true,
        )
        val html = NoteDocument.serialize(listOf(table))
        assertTrue(html.contains("<table><tbody><tr><th>Subject</th><th>Score</th></tr>"))
        val parsed = NoteDocument.parse(html).single() as TableSegment
        assertEquals(table.rows, parsed.rows)
        assertTrue(parsed.headerRow)
        assertEquals(html, NoteDocument.serialize(listOf(parsed)))

        val headerless = NoteDocument.parse(
            NoteDocument.serialize(listOf(TableOps.newTable(2, 2, headerRow = false))),
        ).single() as TableSegment
        assertFalse(headerless.headerRow)
        assertEquals(2, headerless.rows.size)
    }

    @Test
    fun insertRowAtMiddleKeepsContentsAndOrder() {
        val table = labelled(3, 2)
        val inserted = TableOps.insertRow(table, 1)
        assertEquals(4, inserted.rows.size)
        assertEquals(listOf("r0c0", "r0c1"), inserted.rows[0])
        assertEquals(listOf("", ""), inserted.rows[1])
        assertEquals(listOf("r1c0", "r1c1"), inserted.rows[2])
        assertEquals(listOf("r2c0", "r2c1"), inserted.rows[3])
        // Appending through the same door (at == row count).
        val appended = TableOps.insertRow(table, 3)
        assertEquals(listOf("", ""), appended.rows[3])
        // Out-of-range is a no-op.
        assertEquals(table, TableOps.insertRow(table, 5))
    }

    @Test
    fun deleteRowAtMiddleDropsExactlyThatRow() {
        val table = labelled(3, 2)
        val deleted = TableOps.deleteRow(table, 1)
        assertEquals(
            listOf(listOf("r0c0", "r0c1"), listOf("r2c0", "r2c1")),
            deleted.rows,
        )
        // A one-row table cannot lose its last row.
        val single = labelled(1, 2)
        assertEquals(single, TableOps.deleteRow(single, 0))
    }

    @Test
    fun insertAndDeleteColumnAtMiddleKeepContentsAligned() {
        val table = labelled(2, 3)
        val inserted = TableOps.insertColumn(table, 1)
        assertEquals(listOf("r0c0", "", "r0c1", "r0c2"), inserted.rows[0])
        assertEquals(listOf("r1c0", "", "r1c1", "r1c2"), inserted.rows[1])
        val deleted = TableOps.deleteColumn(table, 2)
        assertEquals(listOf("r0c0", "r0c1"), deleted.rows[0])
        assertEquals(listOf("r1c0", "r1c1"), deleted.rows[1])
        // A one-column table cannot lose its last column.
        val single = labelled(2, 1)
        assertEquals(single, TableOps.deleteColumn(single, 0))
    }

    @Test
    fun operationsKeepCellSpansParallelOnParsedTables() {
        // A table parsed from HTML carries a real spans list
        // (here with a colspan); structural ops must keep spans
        // parallel to rows cell-for-cell or merged cells would
        // drift against their content.
        val parsed = NoteDocument.parse(
            "<table><tbody>" +
                "<tr><th colspan=\"2\">Head</th><th>Side</th></tr>" +
                "<tr><td>a</td><td>b</td><td>c</td></tr>" +
                "</tbody></table>",
        ).single() as TableSegment
        assertEquals(2, parsed.cellSpans.size)
        assertEquals(2, parsed.cellSpans[0][0].colSpan)

        val rowInserted = TableOps.insertRow(parsed, 1)
        assertEquals(3, rowInserted.rows.size)
        assertEquals(rowInserted.rows.map { it.size }, rowInserted.cellSpans.map { it.size })

        val colDeleted = TableOps.deleteColumn(parsed, 0)
        assertEquals(colDeleted.rows.map { it.size }, colDeleted.cellSpans.map { it.size })
        // Row 0 held two header cells ("Head" spanning 2, "Side");
        // dropping column 0 removes exactly "Head".
        assertEquals(listOf("Side"), colDeleted.rows[0])
        assertEquals(listOf("b", "c"), colDeleted.rows[1])

        val rowDeleted = TableOps.deleteRow(parsed, 0)
        assertEquals(1, rowDeleted.rows.size)
        assertEquals(rowDeleted.rows.map { it.size }, rowDeleted.cellSpans.map { it.size })

        // And the results still serialise/parse to themselves.
        val html = NoteDocument.serialize(listOf(colDeleted))
        val reparsed = NoteDocument.parse(html).single() as TableSegment
        assertEquals(colDeleted.rows, reparsed.rows)
        assertEquals(html, NoteDocument.serialize(listOf(reparsed)))
    }
}
