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

    /**
     * The VISUAL column at which the cell at ([row], [cellIndex])
     * starts — the summed colspans of the cells before it in its row.
     */
    fun visualColumnOf(table: TableSegment, row: Int, cellIndex: Int): Int {
        val cells = table.rows.getOrNull(row) ?: return 0
        var visual = 0
        for (i in 0 until minOf(cellIndex, cells.size)) {
            visual += colSpanOf(table, row, i)
        }
        return visual
    }

    /** The table's visual column count (max over rows of summed colspans). */
    fun visualColumns(table: TableSegment): Int =
        table.rows.indices.maxOfOrNull { row ->
            table.rows[row].indices.sumOf { colSpanOf(table, row, it) }
        } ?: 0

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
     * visual column count appends). A row whose merged cell strictly
     * spans over [at] grows that cell's span by one instead of
     * gaining a cell — the merged cell's content is never split.
     */
    fun insertColumn(table: TableSegment, at: Int): TableSegment {
        if (at !in 0..visualColumns(table)) return table
        val newRows = ArrayList<List<String>>(table.rows.size)
        val newSpans = ArrayList<List<TableCellSpan>>(table.rows.size)
        for (r in table.rows.indices) {
            val row = table.rows[r]
            val spans = table.cellSpans.getOrNull(r)
            var visual = 0
            var insertIndex = row.size // default: append at the row's end
            var grownIndex = -1
            for (i in row.indices) {
                val span = colSpanOf(table, r, i)
                if (at in (visual + 1) until (visual + span)) {
                    grownIndex = i
                    break
                }
                if (visual >= at) {
                    insertIndex = i
                    break
                }
                visual += span
            }
            if (grownIndex >= 0) {
                newRows += row
                if (spans != null) {
                    newSpans += spans.mapIndexed { i, s ->
                        if (i == grownIndex) s.copy(colSpan = s.colSpan + 1) else s
                    }
                }
            } else {
                newRows += row.toMutableList().apply { add(insertIndex, "") }
                if (spans != null) {
                    newSpans += spans.toMutableList().apply {
                        add(insertIndex, TableCellSpan())
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

    /**
     * Delete visual column [at]; a no-op on a one-visual-column
     * table or a bad index. The cell covering [at] in each row is
     * dropped when its colSpan is 1, or kept with its span shrunk
     * by one when merged — merged content is never destroyed by
     * deleting only part of it.
     */
    fun deleteColumn(table: TableSegment, at: Int): TableSegment {
        if (visualColumns(table) <= 1 || at !in 0 until visualColumns(table)) {
            return table
        }
        val newRows = ArrayList<List<String>>(table.rows.size)
        val newSpans = ArrayList<List<TableCellSpan>>(table.rows.size)
        for (r in table.rows.indices) {
            val row = table.rows[r]
            val spans = table.cellSpans.getOrNull(r)
            var visual = 0
            var hitIndex = -1
            var hitSpan = 1
            for (i in row.indices) {
                val span = colSpanOf(table, r, i)
                if (at in visual until (visual + span)) {
                    hitIndex = i
                    hitSpan = span
                    break
                }
                visual += span
            }
            when {
                hitIndex < 0 -> {
                    newRows += row
                    if (spans != null) newSpans += spans
                }
                hitSpan > 1 -> {
                    newRows += row
                    if (spans != null) {
                        newSpans += spans.mapIndexed { i, s ->
                            if (i == hitIndex) s.copy(colSpan = s.colSpan - 1) else s
                        }
                    }
                }
                else -> {
                    newRows += row.filterIndexed { i, _ -> i != hitIndex }
                    if (spans != null) {
                        newSpans += spans.filterIndexed { i, _ -> i != hitIndex }
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
