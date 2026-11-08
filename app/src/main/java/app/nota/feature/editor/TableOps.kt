package app.nota.feature.editor

import app.nota.data.html.TableCellSpan
import app.nota.data.html.TableSegment

/**
 * Phase 6 (Editor Refinement #1, A3) — pure table construction and
 * structural operations behind the editor's insert dialog and the
 * TableBlock's at-the-selected-cell row/column actions. Before this
 * round the editor could only mint a fixed 2×2 table with no header
 * row and append/drop the LAST row or column; Noma 1.x offers a size
 * dialog (rows ≤ 20, columns ≤ 10, header-row toggle —
 * note-editor.tsx) and insert/delete at the caret, and these
 * functions are the testable core of the same behaviour.
 *
 * The [TableSegment.cellSpans] parallelism discipline is the one the
 * existing append/drop handlers in TableBlock established: cellSpans
 * is either EMPTY (editor-built tables; every span is implicitly 1)
 * or parallels [TableSegment.rows] cell-for-cell. Insertions and
 * deletions therefore mirror rows and spans together — an insert
 * into an empty spans list must stay empty (padding just one row
 * would break the parallel), and a non-empty list gains/loses the
 * same cells the rows do.
 *
 * Column addressing is VISUAL (grid) addressing: a cell with
 * colSpan 2 covers two columns. Deleting one of those columns
 * shrinks the cell's span and KEEPS its content; only a column a
 * span-1 cell sits in removes that cell (Codex PR #34 P2 — the
 * first cut addressed raw cell indices and deleting the left half
 * of a merged header cell destroyed the whole cell). Inserting a
 * column strictly inside a merged cell grows its span instead of
 * splitting it. For span-free tables visual columns ARE the cell
 * indices, so behaviour there is unchanged. Row addressing is by
 * row index as before (a rowspan cell lives in its starting row in
 * this model — the pre-existing discipline).
 */
internal object TableOps {

    /** 1.x's insert-dialog caps (note-editor.tsx: rows 1–20, cols 1–10). */
    const val MAX_ROWS = 20
    const val MAX_COLS = 10

    /** Clamp requested dimensions to 1.x's dialog caps. */
    fun clampDimensions(rows: Int, cols: Int): Pair<Int, Int> =
        rows.coerceIn(1, MAX_ROWS) to cols.coerceIn(1, MAX_COLS)

    /** A fresh blank table of the (clamped) requested shape. */
    fun newTable(rows: Int, cols: Int, headerRow: Boolean): TableSegment {
        val (r, c) = clampDimensions(rows, cols)
        return TableSegment(List(r) { List(c) { "" } }, headerRow)
    }

    /** The colSpan of the cell at ([row], [cellIndex]); 1 when span-free. */
    fun colSpanOf(table: TableSegment, row: Int, cellIndex: Int): Int =
        table.cellSpans.getOrNull(row)?.getOrNull(cellIndex)?.colSpan
            ?.coerceAtLeast(1) ?: 1

    /** The rowSpan of the cell at ([row], [cellIndex]); 1 when span-free. */
    fun rowSpanOf(table: TableSegment, row: Int, cellIndex: Int): Int =
        table.cellSpans.getOrNull(row)?.getOrNull(cellIndex)?.rowSpan
            ?.coerceAtLeast(1) ?: 1

    /** One cell laid on the visual grid by [place]. */
    private class PlacedCell(
        val row: Int,
        val cellIndex: Int,
        val startCol: Int,
        val colSpan: Int,
    )

    /**
     * Lay every cell on the VISUAL grid: rows in order, each cell
     * into the first columns not already occupied by a cell
     * spanning DOWN from an earlier row. This placement — not a
     * per-row scan — is the authority for every column address
     * (Codex PR #34 re-review P2): with row 0 = [Tall (rowspan 2),
     * B] and row 1 = [C], C sits at visual column 1 because Tall
     * occupies column 0 of row 1; a per-row scan restarts at 0
     * and addresses C as column 0, so an operation aimed at C's
     * column would have hit Tall's.
     */
    private fun place(table: TableSegment): List<PlacedCell> {
        val placed = ArrayList<PlacedCell>()
        // visual column -> first row NOT covered by a descending span
        val occupiedUntilRow = HashMap<Int, Int>()
        for (r in table.rows.indices) {
            var col = 0
            for (c in table.rows[r].indices) {
                while ((occupiedUntilRow[col] ?: 0) > r) col++
                val colSpan = colSpanOf(table, r, c)
                val rowSpan = rowSpanOf(table, r, c)
                placed += PlacedCell(r, c, col, colSpan)
                if (rowSpan > 1) {
                    for (k in col until col + colSpan) {
                        occupiedUntilRow[k] =
                            maxOf(occupiedUntilRow[k] ?: 0, r + rowSpan)
                    }
                }
                col += colSpan
            }
        }
        return placed
    }

    /**
     * The VISUAL column at which the cell at ([row], [cellIndex])
     * starts, per the grid placement (descending rowspans from
     * earlier rows shift it right).
     */
    fun visualColumnOf(table: TableSegment, row: Int, cellIndex: Int): Int =
        place(table).firstOrNull { it.row == row && it.cellIndex == cellIndex }
            ?.startCol
            ?: table.rows.getOrNull(row)
                ?.indices?.sumOf { if (it < cellIndex) colSpanOf(table, row, it) else 0 }
            ?: 0

    /** The table's visual column count, per the grid placement. */
    fun visualColumns(table: TableSegment): Int =
        place(table).maxOfOrNull { it.startCol + it.colSpan } ?: 0

    /** Insert a blank row BEFORE row index [at] (at == row count appends). */
    fun insertRow(table: TableSegment, at: Int): TableSegment {
        if (at !in 0..table.rows.size) return table
        // Cells in rows above whose rowspan CROSSES the insertion
        // boundary grow by one — the new row passes underneath
        // them (Codex PR #34 re-review, the insert twin of the
        // delete-row span fix) — and the blank row is narrower by
        // their combined colspan, since they already cover those
        // visual columns of the new row.
        var coveredCols = 0
        for (r in 0 until minOf(at, table.cellSpans.size)) {
            for (span in table.cellSpans[r]) {
                if (at < r + span.rowSpan) coveredCols += span.colSpan
            }
        }
        val cols = (visualColumns(table) - coveredCols).coerceAtLeast(0)
        val rows = table.rows.toMutableList().apply { add(at, List(cols) { "" }) }
        // Mirror the pre-existing "+ Row" handler: an empty spans
        // list stays empty; a parallel one gains the same row,
        // with crossing rowspans grown.
        val spans = if (table.cellSpans.isEmpty()) {
            emptyList()
        } else {
            table.cellSpans.mapIndexed { r, rowSpans ->
                rowSpans.map { span ->
                    if (r < at && at < r + span.rowSpan) {
                        span.copy(rowSpan = span.rowSpan + 1)
                    } else {
                        span
                    }
                }
            }.toMutableList().apply { add(at, List(cols) { TableCellSpan() }) }
        }
        return TableSegment(rows, table.headerRow, spans)
    }

    /** Delete row [at]; a no-op on a one-row table or a bad index. */
    fun deleteRow(table: TableSegment, at: Int): TableSegment {
        if (table.rows.size <= 1 || at !in table.rows.indices) return table
        return TableSegment(
            table.rows.filterIndexed { i, _ -> i != at },
            table.headerRow,
            // A surviving cell whose rowspan CROSSES the deleted
            // row shrinks by one (Codex PR #34 re-review P2):
            // without this the saved table keeps a cell claiming
            // a span into a row that no longer exists.
            table.cellSpans.mapIndexed { r, rowSpans ->
                rowSpans.map { span ->
                    if (r < at && at < r + span.rowSpan) {
                        span.copy(rowSpan = span.rowSpan - 1)
                    } else {
                        span
                    }
                }
            }.filterIndexed { i, _ -> i != at },
        )
    }

    /**
     * Insert a blank column BEFORE visual column [at] (at == the
     * visual column count appends). Addressing is by the grid
     * placement ([place]): a cell strictly spanning over [at]
     * grows its colSpan by one instead of gaining a sibling — the
     * merged cell's content is never split — and every other row
     * gains a blank cell at the position its placement dictates
     * (which, in a row shifted by a descending rowspan, is not
     * necessarily cell index [at]).
     */
    fun insertColumn(table: TableSegment, at: Int): TableSegment {
        val placed = place(table)
        if (at !in 0..(placed.maxOfOrNull { it.startCol + it.colSpan } ?: 0)) {
            return table
        }
        val grown = placed
            .filter { at in (it.startCol + 1) until (it.startCol + it.colSpan) }
            .map { it.row to it.cellIndex }
            .toSet()
        val newRows = ArrayList<List<String>>(table.rows.size)
        val newSpans = ArrayList<List<TableCellSpan>>(table.rows.size)
        for (r in table.rows.indices) {
            val row = table.rows[r]
            val spans = table.cellSpans.getOrNull(r)
            if (placed.any { it.row == r && (r to it.cellIndex) in grown }) {
                // A cell of this row spans over the insertion
                // point: it grows; the row gains no cell.
                newRows += row
                if (spans != null) {
                    newSpans += spans.mapIndexed { i, s ->
                        if ((r to i) in grown) s.copy(colSpan = s.colSpan + 1) else s
                    }
                }
                continue
            }
            val insertIndex = placed
                .filter { it.row == r && it.startCol >= at }
                .minOfOrNull { it.cellIndex }
                ?: row.size
            newRows += row.toMutableList().apply { add(insertIndex, "") }
            if (spans != null) {
                newSpans += spans.toMutableList().apply {
                    add(insertIndex, TableCellSpan())
                }
            }
        }
        return TableSegment(
            newRows,
            table.headerRow,
            if (table.cellSpans.isEmpty()) emptyList() else newSpans,
        )
    }

    /**
     * Delete visual column [at]; a no-op on a one-visual-column
     * table or a bad index. Addressing is by the grid placement
     * ([place]): the placed cell covering [at] is dropped when
     * its colSpan is 1, or kept with its span shrunk by one when
     * merged — merged content is never destroyed by deleting only
     * part of it, and a cell shifted by a descending rowspan is
     * addressed by where it actually sits.
     */
    fun deleteColumn(table: TableSegment, at: Int): TableSegment {
        val placed = place(table)
        val visualCols = placed.maxOfOrNull { it.startCol + it.colSpan } ?: 0
        if (visualCols <= 1 || at !in 0 until visualCols) return table
        val removed = HashSet<Pair<Int, Int>>()
        val shrunk = HashSet<Pair<Int, Int>>()
        for (cell in placed) {
            if (at in cell.startCol until (cell.startCol + cell.colSpan)) {
                if (cell.colSpan > 1) {
                    shrunk += cell.row to cell.cellIndex
                } else {
                    removed += cell.row to cell.cellIndex
                }
            }
        }
        val newRows = ArrayList<List<String>>(table.rows.size)
        val newSpans = ArrayList<List<TableCellSpan>>(table.rows.size)
        for (r in table.rows.indices) {
            newRows += table.rows[r].filterIndexed { i, _ -> (r to i) !in removed }
            val spans = table.cellSpans.getOrNull(r)
            if (spans != null) {
                newSpans += spans.mapIndexedNotNull { i, s ->
                    when {
                        (r to i) in removed -> null
                        (r to i) in shrunk -> s.copy(colSpan = s.colSpan - 1)
                        else -> s
                    }
                }
            }
        }
        return TableSegment(
            newRows,
            table.headerRow,
            if (table.cellSpans.isEmpty()) emptyList() else newSpans,
        )
    }
}
