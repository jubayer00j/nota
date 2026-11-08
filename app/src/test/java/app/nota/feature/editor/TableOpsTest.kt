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
        // Deleting VISUAL column 0 (Codex PR #34 P2): row 1's
        // span-1 cell there ("a") is dropped, but row 0's "Head"
        // spans columns 0–1, so it SHRINKS to span 1 and keeps its
        // content — the cell-index first cut destroyed it here.
        assertEquals(listOf("Head", "Side"), colDeleted.rows[0])
        assertEquals(1, colDeleted.cellSpans[0][0].colSpan)
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

    @Test
    fun mergedCellColumnOpsUseVisualAddressing() {
        // The Codex PR #34 P2 fixture: "Head" merged across visual
        // columns 0–1. Column ops address VISUAL columns, so a
        // merged cell is only ever shrunk or grown — never dropped
        // or split by an operation aimed at part of it.
        val parsed = NoteDocument.parse(
            "<table><tbody>" +
                "<tr><th colspan=\"2\">Head</th><th>Side</th></tr>" +
                "<tr><td>a</td><td>b</td><td>c</td></tr>" +
                "</tbody></table>",
        ).single() as TableSegment

        assertEquals(3, TableOps.visualColumns(parsed))
        assertEquals(0, TableOps.visualColumnOf(parsed, 0, 0))
        assertEquals(2, TableOps.visualColumnOf(parsed, 0, 1))
        assertEquals(2, TableOps.colSpanOf(parsed, 0, 0))
        assertEquals(1, TableOps.colSpanOf(parsed, 0, 1))

        // Deleting the second visual column also only shrinks Head.
        val midDeleted = TableOps.deleteColumn(parsed, 1)
        assertEquals(listOf("Head", "Side"), midDeleted.rows[0])
        assertEquals(1, midDeleted.cellSpans[0][0].colSpan)
        assertEquals(listOf("a", "c"), midDeleted.rows[1])

        // Deleting the last visual column removes exactly the
        // span-1 cells sitting in it and leaves the merged cell.
        val lastDeleted = TableOps.deleteColumn(parsed, 2)
        assertEquals(listOf("Head"), lastDeleted.rows[0])
        assertEquals(2, lastDeleted.cellSpans[0][0].colSpan)
        assertEquals(listOf("a", "b"), lastDeleted.rows[1])

        // Inserting a column INSIDE the merged cell's span grows
        // the span; the plain row gains a real blank cell there.
        val grown = TableOps.insertColumn(parsed, 1)
        assertEquals(listOf("Head", "Side"), grown.rows[0])
        assertEquals(3, grown.cellSpans[0][0].colSpan)
        assertEquals(listOf("a", "", "b", "c"), grown.rows[1])
        assertEquals(grown.rows.map { it.size }, grown.cellSpans.map { it.size })

        // Inserting at the merged cell's left edge inserts BEFORE it.
        val edged = TableOps.insertColumn(parsed, 0)
        assertEquals(listOf("", "Head", "Side"), edged.rows[0])
        assertEquals(listOf("", "a", "b", "c"), edged.rows[1])

        // A row inserted into a merged table spans the full
        // visual width, not the first row's cell count.
        val rowInserted = TableOps.insertRow(parsed, 1)
        assertEquals(3, rowInserted.rows[1].size)
    }

    @Test
    fun rowSpanShrinksWhenACrossedRowIsDeletedAndGrowsWhenOneIsInserted() {
        // Codex PR #34 re-review P2, the vertical twin of the
        // merged-column fix: "Tall" spans rows 0–1. Deleting row 1
        // must shrink it to rowspan 1 — otherwise the saved table
        // keeps a cell claiming a span into a row that no longer
        // exists. Inserting a row at the crossed boundary grows
        // it instead, and the new row is narrower by the covered
        // columns.
        val parsed = NoteDocument.parse(
            "<table><tbody>" +
                "<tr><td rowspan=\"2\">Tall</td><td>B</td></tr>" +
                "<tr><td>C</td></tr>" +
                "<tr><td>D</td><td>E</td></tr>" +
                "</tbody></table>",
        ).single() as TableSegment
        assertEquals(2, parsed.cellSpans[0][0].rowSpan)
        assertEquals(listOf("C"), parsed.rows[1])

        val crossed = TableOps.deleteRow(parsed, 1)
        assertEquals(listOf(listOf("Tall", "B"), listOf("D", "E")), crossed.rows)
        assertEquals(1, crossed.cellSpans[0][0].rowSpan)
        assertFalse(NoteDocument.serialize(listOf(crossed)).contains("rowspan"))
        assertEquals(
            crossed.rows,
            (NoteDocument.parse(NoteDocument.serialize(listOf(crossed)))
                .single() as TableSegment).rows,
        )

        // Deleting a row BELOW the span's reach leaves it alone.
        val below = TableOps.deleteRow(parsed, 2)
        assertEquals(2, below.rows.size)
        assertEquals(2, below.cellSpans[0][0].rowSpan)

        // Inserting at the crossed boundary: the span grows, and
        // the blank row covers only the uncovered visual column.
        val grown = TableOps.insertRow(parsed, 1)
        assertEquals(4, grown.rows.size)
        assertEquals(3, grown.cellSpans[0][0].rowSpan)
        assertEquals(listOf(""), grown.rows[1])
        assertEquals(grown.rows.map { it.size }, grown.cellSpans.map { it.size })

        // Appending never grows a span.
        val appended = TableOps.insertRow(parsed, 3)
        assertEquals(2, appended.cellSpans[0][0].rowSpan)
        assertEquals(2, appended.rows[3].size)
    }

    @Test
    fun columnsAddressThePlacedGridWhenRowspansShiftCells() {
        // Codex PR #34 re-review P2: column addressing must come
        // from the placed grid, not a per-row scan. Here Tall
        // (rowspan 2) occupies visual column 0 of BOTH rows, so
        // row 1's only cell, C, sits at visual column 1 — a
        // per-row scan restarts at 0 and would address C as
        // column 0, aiming operations at Tall's column instead.
        val parsed = NoteDocument.parse(
            "<table><tbody>" +
                "<tr><td rowspan=\"2\">Tall</td><td>B</td></tr>" +
                "<tr><td>C</td></tr>" +
                "</tbody></table>",
        ).single() as TableSegment

        assertEquals(2, TableOps.visualColumns(parsed))
        assertEquals(0, TableOps.visualColumnOf(parsed, 0, 0)) // Tall
        assertEquals(1, TableOps.visualColumnOf(parsed, 0, 1)) // B
        assertEquals(1, TableOps.visualColumnOf(parsed, 1, 0)) // C

        // Deleting C's real column (visual 1) removes C and B;
        // Tall, covering only column 0, is untouched. (Pre-fix,
        // "C is column 0" made this call destroy Tall and C.)
        val deleted = TableOps.deleteColumn(parsed, 1)
        assertEquals(listOf(listOf("Tall"), emptyList<String>()), deleted.rows)
        assertEquals(2, deleted.cellSpans[0][0].rowSpan)

        // Deleting visual column 0 removes Tall — it sits exactly
        // there in both rows — and leaves B and C aligned at 0.
        val deleted0 = TableOps.deleteColumn(parsed, 0)
        assertEquals(listOf(listOf("B"), listOf("C")), deleted0.rows)

        // Inserting at visual 0 puts a blank before every row's
        // first cell; Tall's descent follows it to column 1 and
        // pushes C to column 2 in the re-placed grid.
        val inserted = TableOps.insertColumn(parsed, 0)
        assertEquals(listOf("", "Tall", "B"), inserted.rows[0])
        assertEquals(listOf("", "C"), inserted.rows[1])
        assertEquals(1, TableOps.visualColumnOf(inserted, 0, 1))
        assertEquals(2, TableOps.visualColumnOf(inserted, 1, 1))
    }
}
