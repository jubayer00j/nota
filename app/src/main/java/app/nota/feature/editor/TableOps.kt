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
 * same cells the rows do. Cell CONTENT is never touched: inserting
 * adds blank cells, deleting drops exactly the addressed row/column.
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

    /** Insert a blank row BEFORE row index [at] (at == row count appends). */
    fun insertRow(table: TableSegment, at: Int): TableSegment {
        if (at !in 0..table.rows.size) return table
        val cols = table.rows.firstOrNull()?.size ?: 0
        val rows = table.rows.toMutableList().apply { add(at, List(cols) { "" }) }
        // Mirror the pre-existing "+ Row" handler: an empty spans
        // list stays empty; a parallel one gains the same row.
        val spans = if (table.cellSpans.isEmpty()) {
            emptyList()
        } else {
            table.cellSpans.toMutableList().apply { add(at, List(cols) { TableCellSpan() }) }
        }
        return TableSegment(rows, table.headerRow, spans)
    }

    /** Delete row [at]; a no-op on a one-row table or a bad index. */
    fun deleteRow(table: TableSegment, at: Int): TableSegment {
        if (table.rows.size <= 1 || at !in table.rows.indices) return table
        return TableSegment(
            table.rows.filterIndexed { i, _ -> i != at },
            table.headerRow,
            table.cellSpans.filterIndexed { i, _ -> i != at },
        )
    }

    /** Insert a blank column BEFORE column index [at] (at == col count appends). */
    fun insertColumn(table: TableSegment, at: Int): TableSegment {
        val cols = table.rows.firstOrNull()?.size ?: 0
        if (at !in 0..cols) return table
        return TableSegment(
            table.rows.map { row -> row.toMutableList().apply { add(at, "") } },
            table.headerRow,
            table.cellSpans.map { row ->
                row.toMutableList().apply { add(at, TableCellSpan()) }
            },
        )
    }

    /** Delete column [at]; a no-op on a one-column table or a bad index. */
    fun deleteColumn(table: TableSegment, at: Int): TableSegment {
        val cols = table.rows.firstOrNull()?.size ?: 0
        if (cols <= 1 || at !in 0 until cols) return table
        return TableSegment(
            table.rows.map { row -> row.filterIndexed { i, _ -> i != at } },
            table.headerRow,
            table.cellSpans.map { row -> row.filterIndexed { i, _ -> i != at } },
        )
    }
}
