package app.nota.feature.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.nota.data.chart.ChartColors
import app.nota.data.chart.ChartData
import app.nota.data.chart.ChartOptions
import app.nota.data.chart.ChartSeries
import app.nota.data.chart.ChartType
import app.nota.data.chart.LegendPosition
import app.nota.data.chart.SeriesKind
import app.nota.ui.theme.NotaSpacing
import java.math.BigDecimal
import kotlin.math.roundToInt

/**
 * Charts PR-B: the chart editor dialog — the Compose counterpart of
 * Noma 1.x's `ChartEditorDialog`, in Nota's pine & paper dress.
 *
 * A live [ChartView] preview sits on top; three tabs mirror 1.x —
 * **Data** (the spreadsheet grid + paste import), **Chart** (title,
 * subtitle, type), **Options** (per-series colour/kind, toggles,
 * legend position, donut radius, axis labels). Everything is
 * in-dialog state until Save: [onSave] receives the edited model
 * and the caller serialises it ([app.nota.data.chart.ChartSerializer]
 * normalises on the way out, so caps and fallbacks match 1.x
 * exactly); Cancel touches nothing.
 *
 * Grid discipline follows 1.x's handlers: a cell commits through
 * the parseFloat coercion (invalid or blank → 0, an untouched gap
 * stays a gap), add/remove row and series keep every series'
 * values index-aligned to the categories, and the add buttons
 * disable at the 50-category / 20-series caps the normaliser
 * enforces.
 */

// Test tags (the dialog is composed directly in UI tests; the
// editor's insert-menu popup does not enter the Robolectric
// semantics forest — the PR #36/#38 harness pattern).
const val CHART_BLOCK_EDIT_TAG = "chartBlockEdit"
const val CHART_TITLE_FIELD_TAG = "chartTitleField"
const val CHART_SAVE_TAG = "chartSaveButton"
const val CHART_CANCEL_TAG = "chartCancelButton"
const val CHART_ADD_ROW_TAG = "chartAddRowButton"
const val CHART_ADD_SERIES_TAG = "chartAddSeriesButton"
const val CHART_PASTE_TAG = "chartPasteDataButton"
const val CHART_TAB_DATA_TAG = "chartTabData"
const val CHART_TAB_CHART_TAG = "chartTabChart"
const val CHART_TAB_OPTIONS_TAG = "chartTabOptions"

fun chartCategoryFieldTag(index: Int): String = "chartCategoryField-$index"
fun chartSeriesNameFieldTag(index: Int): String = "chartSeriesNameField-$index"
fun chartValueFieldTag(series: Int, category: Int): String = "chartValueField-$series-$category"

private const val MAX_CATEGORIES = 50
private const val MAX_SERIES = 20

@Composable
fun ChartEditorDialog(
    initial: ChartData,
    editing: Boolean,
    onDismiss: () -> Unit,
    onSave: (ChartData) -> Unit,
) {
    var data by remember { mutableStateOf(initial) }
    var tab by remember { mutableIntStateOf(0) }
    // Per-cell text, seeded from the values: a field shows what the
    // user typed, not the coerced number re-rendered (typing "1."
    // must not collapse to "1"), and an untouched null stays a gap
    // (seeded blank, coerced only when the cell is edited).
    var cellTexts by remember { mutableStateOf(seedCellTexts(initial)) }
    var pasteNote by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    fun replaceData(next: ChartData, reseedCells: Boolean = false) {
        data = next
        if (reseedCells) cellTexts = seedCellTexts(next)
    }

    fun updateOptions(patch: (ChartOptions) -> ChartOptions) {
        data = data.copy(options = patch(data.options))
    }

    fun renameCategory(index: Int, name: String) {
        replaceData(
            data.copy(categories = data.categories.mapIndexed { i, c -> if (i == index) name else c }),
        )
    }

    fun addCategory() {
        if (data.categories.size >= MAX_CATEGORIES) return
        replaceData(
            data.copy(
                categories = data.categories + "Item ${data.categories.size + 1}",
                series = data.series.map { it.copy(values = it.values + 0.0) },
            ),
            reseedCells = true,
        )
    }

    fun removeCategory(index: Int) {
        if (data.categories.size <= 1) return
        replaceData(
            data.copy(
                categories = data.categories.filterIndexed { i, _ -> i != index },
                series = data.series.map {
                    it.copy(values = it.values.filterIndexed { i, _ -> i != index })
                },
            ),
            reseedCells = true,
        )
    }

    fun renameSeries(index: Int, name: String) {
        replaceData(
            data.copy(series = data.series.mapIndexed { i, s -> if (i == index) s.copy(name = name) else s }),
        )
    }

    fun addSeries() {
        if (data.series.size >= MAX_SERIES) return
        val index = data.series.size
        var id = "series-${index + 1}"
        var suffix = 2
        while (data.series.any { it.id == id }) {
            id = "series-${index + 1}-${suffix++}"
        }
        replaceData(
            data.copy(
                series = data.series + ChartSeries(
                    id = id,
                    name = "Series ${index + 1}",
                    type = null,
                    color = ChartColors.NOTA_PALETTE_OKLCH[index % ChartColors.NOTA_PALETTE_OKLCH.size],
                    values = List(data.categories.size) { 0.0 },
                ),
            ),
            reseedCells = true,
        )
    }

    fun removeSeries(index: Int) {
        if (data.series.size <= 1) return
        replaceData(
            data.copy(series = data.series.filterIndexed { i, _ -> i != index }),
            reseedCells = true,
        )
    }

    fun commitCell(seriesIndex: Int, categoryIndex: Int, text: String) {
        cellTexts = cellTexts + ((seriesIndex to categoryIndex) to text)
        val value = coerceCellText(text)
        replaceData(
            data.copy(
                series = data.series.mapIndexed { i, s ->
                    if (i != seriesIndex) {
                        s
                    } else {
                        s.copy(
                            values = s.values.mapIndexed { c, v ->
                                if (c == categoryIndex) value else v
                            },
                        )
                    }
                },
            ),
        )
    }

    fun pasteFromClipboard() {
        val text = clipboard.getText()?.text
        if (text.isNullOrBlank()) {
            pasteNote = "Clipboard is empty — nothing pasted."
            return
        }
        val pasted = applyPasteMatrix(data, text)
        if (pasted == null) {
            pasteNote = "Nothing usable in the clipboard — nothing pasted."
        } else {
            replaceData(pasted, reseedCells = true)
            pasteNote = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.96f).heightIn(max = 760.dp),
        ) {
            Column(Modifier.padding(NotaSpacing.md)) {
                Text(
                    if (editing) "Edit chart" else "Insert chart",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(NotaSpacing.sm))
                Column(
                    Modifier.weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Live preview of the in-dialog state.
                    ChartView(data)
                    Spacer(Modifier.height(NotaSpacing.sm))
                    TabRow(selectedTabIndex = tab) {
                        Tab(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            text = { Text("Data") },
                            modifier = Modifier.testTag(CHART_TAB_DATA_TAG),
                        )
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            text = { Text("Chart") },
                            modifier = Modifier.testTag(CHART_TAB_CHART_TAG),
                        )
                        Tab(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            text = { Text("Options") },
                            modifier = Modifier.testTag(CHART_TAB_OPTIONS_TAG),
                        )
                    }
                    Spacer(Modifier.height(NotaSpacing.sm))
                    when (tab) {
                        0 -> ChartDataTab(
                            data = data,
                            cellTexts = cellTexts,
                            pasteNote = pasteNote,
                            onPaste = { pasteFromClipboard() },
                            onAddCategory = { addCategory() },
                            onAddSeries = { addSeries() },
                            onRenameCategory = { i, v -> renameCategory(i, v) },
                            onRemoveCategory = { removeCategory(it) },
                            onRenameSeries = { i, v -> renameSeries(i, v) },
                            onRemoveSeries = { removeSeries(it) },
                            onCellChange = { s, c, v -> commitCell(s, c, v) },
                        )
                        1 -> ChartChartTab(
                            data = data,
                            onTitleChange = { data = data.copy(title = it.take(100)) },
                            onSubtitleChange = { data = data.copy(subtitle = it.take(100)) },
                            onTypeChange = { data = data.copy(type = it) },
                        )
                        else -> ChartOptionsTab(
                            data = data,
                            onSeriesColor = { index, color ->
                                replaceData(
                                    data.copy(
                                        series = data.series.mapIndexed { i, s ->
                                            if (i == index) s.copy(color = color) else s
                                        },
                                    ),
                                )
                            },
                            onSeriesKind = { index, kind ->
                                replaceData(
                                    data.copy(
                                        series = data.series.mapIndexed { i, s ->
                                            if (i == index) s.copy(type = kind) else s
                                        },
                                    ),
                                )
                            },
                            onOptionsChange = { updateOptions(it) },
                        )
                    }
                }
                Spacer(Modifier.height(NotaSpacing.sm))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag(CHART_CANCEL_TAG),
                    ) { Text("Cancel") }
                    Button(
                        onClick = { onSave(data) },
                        modifier = Modifier.testTag(CHART_SAVE_TAG),
                    ) { Text(if (editing) "Save" else "Insert") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------
// Data tab

@Composable
private fun ChartDataTab(
    data: ChartData,
    cellTexts: Map<Pair<Int, Int>, String>,
    pasteNote: String?,
    onPaste: () -> Unit,
    onAddCategory: () -> Unit,
    onAddSeries: () -> Unit,
    onRenameCategory: (Int, String) -> Unit,
    onRemoveCategory: (Int) -> Unit,
    onRenameSeries: (Int, String) -> Unit,
    onRemoveSeries: (Int) -> Unit,
    onCellChange: (Int, Int, String) -> Unit,
) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
            TextButton(
                onClick = onPaste,
                modifier = Modifier.testTag(CHART_PASTE_TAG),
            ) { Text("Paste data") }
            TextButton(
                onClick = onAddCategory,
                enabled = data.categories.size < MAX_CATEGORIES,
                modifier = Modifier.testTag(CHART_ADD_ROW_TAG),
            ) { Text("Add row") }
            TextButton(
                onClick = onAddSeries,
                enabled = data.series.size < MAX_SERIES,
                modifier = Modifier.testTag(CHART_ADD_SERIES_TAG),
            ) { Text("Add series") }
        }
        if (pasteNote != null) {
            Text(
                pasteNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(NotaSpacing.xs))
        }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            // Header: series names (+ remove-series).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(116.dp)) {
                    Text(
                        "Category",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                data.series.forEachIndexed { sIdx, series ->
                    Row(
                        Modifier.width(148.dp).padding(start = NotaSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = series.name,
                            onValueChange = { onRenameSeries(sIdx, it) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                                .testTag(chartSeriesNameFieldTag(sIdx)),
                        )
                        if (data.series.size > 1) {
                            IconButton(onClick = { onRemoveSeries(sIdx) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove series",
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(48.dp))
            }
            Spacer(Modifier.height(NotaSpacing.xs))
            // Rows: category name, one value cell per series, remove.
            data.categories.forEachIndexed { cIdx, category ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { onRenameCategory(cIdx, it) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(116.dp)
                            .testTag(chartCategoryFieldTag(cIdx)),
                    )
                    data.series.forEachIndexed { sIdx, _ ->
                        OutlinedTextField(
                            value = cellTexts[sIdx to cIdx] ?: "",
                            onValueChange = { onCellChange(sIdx, cIdx, it) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                            ),
                            modifier = Modifier.width(148.dp)
                                .padding(start = NotaSpacing.sm)
                                .testTag(chartValueFieldTag(sIdx, cIdx)),
                        )
                    }
                    if (data.categories.size > 1) {
                        IconButton(onClick = { onRemoveCategory(cIdx) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove row")
                        }
                    } else {
                        Spacer(Modifier.width(48.dp))
                    }
                }
                Spacer(Modifier.height(NotaSpacing.xs))
            }
        }
    }
}

// ---------------------------------------------------------------
// Chart tab

@Composable
private fun ChartChartTab(
    data: ChartData,
    onTitleChange: (String) -> Unit,
    onSubtitleChange: (String) -> Unit,
    onTypeChange: (ChartType) -> Unit,
) {
    Column {
        OutlinedTextField(
            value = data.title,
            onValueChange = onTitleChange,
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(CHART_TITLE_FIELD_TAG),
        )
        Spacer(Modifier.height(NotaSpacing.sm))
        OutlinedTextField(
            value = data.subtitle,
            onValueChange = onSubtitleChange,
            label = { Text("Subtitle") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(NotaSpacing.md))
        Text("Chart type", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(NotaSpacing.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
            ChartType.entries.forEach { type ->
                FilterChip(
                    selected = data.type == type,
                    onClick = { onTypeChange(type) },
                    label = { Text(chartTypeLabel(type)) },
                )
            }
        }
    }
}

private fun chartTypeLabel(type: ChartType): String = when (type) {
    ChartType.BAR -> "Bar"
    ChartType.LINE -> "Line"
    ChartType.AREA -> "Area"
    ChartType.PIE -> "Pie"
    ChartType.DONUT -> "Donut"
    ChartType.RADAR -> "Radar"
    ChartType.COMPOSED -> "Mixed"
}

// ---------------------------------------------------------------
// Options tab

@Composable
private fun ChartOptionsTab(
    data: ChartData,
    onSeriesColor: (Int, String) -> Unit,
    onSeriesKind: (Int, SeriesKind) -> Unit,
    onOptionsChange: ((ChartOptions) -> ChartOptions) -> Unit,
) {
    val options = data.options
    Column {
        data.series.forEachIndexed { sIdx, series ->
            Text(
                series.name,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(NotaSpacing.xs))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm),
            ) {
                val current = series.color
                if (current != null && current !in ChartColors.NOTA_PALETTE_OKLCH) {
                    // A colour the chart already carries (a custom
                    // pick from 1.x, or one of 1.x's own defaults):
                    // shown selected and KEPT unless the user picks
                    // a palette swatch.
                    ColourSwatch(
                        color = ChartColors.parseCssColor(current)
                            ?: MaterialTheme.colorScheme.surfaceVariant,
                        selected = true,
                        onClick = {},
                    )
                    Text(
                        "Custom",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ChartColors.NOTA_PALETTE_OKLCH.forEachIndexed { pIdx, css ->
                    ColourSwatch(
                        color = ChartColors.notaPaletteColor(pIdx),
                        selected = current == css,
                        onClick = { onSeriesColor(sIdx, css) },
                    )
                }
            }
            if (data.type == ChartType.COMPOSED) {
                Spacer(Modifier.height(NotaSpacing.xs))
                // The renderer's positional default (even = bar,
                // odd = line) shows as the selected kind until the
                // user pins one explicitly.
                val effective = series.type
                    ?: if (sIdx % 2 == 0) SeriesKind.BAR else SeriesKind.LINE
                Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                    SeriesKind.entries.forEach { kind ->
                        FilterChip(
                            selected = effective == kind,
                            onClick = { onSeriesKind(sIdx, kind) },
                            label = {
                                Text(
                                    when (kind) {
                                        SeriesKind.BAR -> "Bar"
                                        SeriesKind.LINE -> "Line"
                                        SeriesKind.AREA -> "Area"
                                    },
                                )
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(NotaSpacing.sm))
        }

        OptionSwitch(
            label = "Stacked",
            checked = options.stacked,
            onCheckedChange = { v -> onOptionsChange { it.copy(stacked = v) } },
        )
        OptionSwitch(
            label = "Grid",
            checked = options.showGrid,
            onCheckedChange = { v -> onOptionsChange { it.copy(showGrid = v) } },
        )
        OptionSwitch(
            label = "Legend",
            checked = options.showLegend,
            onCheckedChange = { v -> onOptionsChange { it.copy(showLegend = v) } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
            FilterChip(
                selected = options.legendPosition == LegendPosition.TOP,
                onClick = { onOptionsChange { it.copy(legendPosition = LegendPosition.TOP) } },
                enabled = options.showLegend,
                label = { Text("Legend top") },
            )
            FilterChip(
                selected = options.legendPosition == LegendPosition.BOTTOM,
                onClick = { onOptionsChange { it.copy(legendPosition = LegendPosition.BOTTOM) } },
                enabled = options.showLegend,
                label = { Text("Legend bottom") },
            )
        }
        OptionSwitch(
            label = "Smooth lines",
            checked = options.smoothLine,
            onCheckedChange = { v -> onOptionsChange { it.copy(smoothLine = v) } },
        )
        OptionSwitch(
            label = "Markers",
            checked = options.showMarkers,
            onCheckedChange = { v -> onOptionsChange { it.copy(showMarkers = v) } },
        )
        if (data.type == ChartType.DONUT) {
            Spacer(Modifier.height(NotaSpacing.sm))
            Text(
                "Inner radius: ${options.innerRadius.roundToInt()}",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = options.innerRadius.toFloat(),
                onValueChange = { v ->
                    onOptionsChange { it.copy(innerRadius = v.toDouble()) }
                },
                valueRange = 10f..120f,
            )
        }
        Spacer(Modifier.height(NotaSpacing.sm))
        OutlinedTextField(
            value = options.xAxisLabel,
            onValueChange = { v -> onOptionsChange { it.copy(xAxisLabel = v.take(50)) } },
            label = { Text("X axis label") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(NotaSpacing.sm))
        OutlinedTextField(
            value = options.yAxisLabel,
            onValueChange = { v -> onOptionsChange { it.copy(yAxisLabel = v.take(50)) } },
            label = { Text("Y axis label") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OptionSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ColourSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (selected) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.onSurface,
                        CircleShape,
                    )
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    )
}

// ---------------------------------------------------------------
// Grid state helpers

/** Seeds the per-cell text from the values: a value prints the JS
 *  way (100, not 100.0); a null gap seeds blank. */
private fun seedCellTexts(data: ChartData): Map<Pair<Int, Int>, String> {
    val out = HashMap<Pair<Int, Int>, String>()
    data.series.forEachIndexed { s, series ->
        series.values.forEachIndexed { c, v ->
            out[s to c] = formatCellValue(v)
        }
    }
    return out
}

private fun formatCellValue(v: Double?): String = when {
    v == null -> ""
    !v.isFinite() || v == 0.0 -> "0"
    else -> BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
}

/** 1.x's cell commit coercion: `parseFloat(text)`, NaN → 0. A
 *  non-finite result (1e999) also lands on 0 — the model the
 *  dialog holds must stay finite for the preview and the
 *  serialiser (1.x itself defers that to its normaliser; the
 *  value is identical after save either way). */
private fun coerceCellText(text: String): Double {
    val parsed = parseFloatPrefix(text.trim()) ?: return 0.0
    return if (parsed.isFinite()) parsed else 0.0
}

/** JS `parseFloat`: the longest valid numeric prefix, or null
 *  (NaN) when there is none. */
private fun parseFloatPrefix(s: String): Double? {
    var i = 0
    while (i < s.length && (s[i].isWhitespace() || s[i] == '\uFEFF')) i++
    val start = i
    if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
    if (s.startsWith("Infinity", i)) {
        return if (s[start] == '-') Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
    }
    var digits = 0
    while (i < s.length && s[i] in '0'..'9') {
        i++
        digits++
    }
    if (i < s.length && s[i] == '.') {
        i++
        while (i < s.length && s[i] in '0'..'9') {
            i++
            digits++
        }
    }
    if (digits == 0) return null
    if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
        var j = i + 1
        if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
        var expDigits = 0
        while (j < s.length && s[j] in '0'..'9') {
            j++
            expDigits++
        }
        if (expDigits > 0) i = j
    }
    return s.substring(start, i).toDoubleOrNull()
}

/**
 * 1.x's paste import (`handlePasteData`): TSV/CSV text becomes
 * the grid. With more than one row, the first row names the
 * series (its first cell is the corner) and the first column
 * names the categories; a single row is one anonymous series.
 * Colours are the Nota palette by index (the dialog's seeding
 * rule); kinds alternate bar/line as 1.x's do. The 50/20 caps are
 * applied here so the grid never holds more than the serialiser
 * would keep. Returns null when the text holds no usable rows.
 */
private fun applyPasteMatrix(current: ChartData, text: String): ChartData? {
    val lines = text.trim().split("\n").map { line ->
        line.split('\t', ',').map { it.trim() }
    }
    if (lines.isEmpty()) return null
    val hasHeader = lines.size > 1
    val seriesNames = if (hasHeader) {
        lines[0].drop(1).ifEmpty { listOf("Series 1") }
    } else {
        listOf("Series 1")
    }
    val categories = mutableListOf<String>()
    val seriesValues = seriesNames.map { mutableListOf<Double>() }
    val startRow = if (hasHeader) 1 else 0
    for (r in startRow until lines.size) {
        val row = lines[r]
        if (row.isEmpty()) continue
        categories += row[0].ifEmpty { "Item $r" }
        seriesNames.indices.forEach { s ->
            seriesValues[s] += coerceCellText(row.getOrNull(s + 1) ?: "0")
        }
    }
    if (categories.isEmpty()) return null
    val cappedCategories = categories.take(MAX_CATEGORIES)
    val newSeries = seriesNames.take(MAX_SERIES).mapIndexed { sIdx, name ->
        var id = "series-paste-${sIdx + 1}"
        var suffix = 2
        while (current.series.any { it.id == id }) {
            id = "series-paste-${sIdx + 1}-${suffix++}"
        }
        ChartSeries(
            id = id,
            name = name.ifEmpty { "Series ${sIdx + 1}" },
            type = if (sIdx % 2 == 1) SeriesKind.LINE else SeriesKind.BAR,
            color = ChartColors.NOTA_PALETTE_OKLCH[sIdx % ChartColors.NOTA_PALETTE_OKLCH.size],
            values = seriesValues[sIdx].take(MAX_CATEGORIES),
        )
    }
    return current.copy(categories = cappedCategories, series = newSeries)
}
