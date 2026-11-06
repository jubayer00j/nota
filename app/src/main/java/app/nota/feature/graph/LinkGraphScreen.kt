package app.nota.feature.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.nota.ui.theme.NotaSpacing
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.koin.androidx.compose.koinViewModel

/** Radius of a node disc, in graph units (see [LinkGraphLayout]). */
private const val NODE_RADIUS_UNITS = 26f

/** How close a tap must land to a node's centre to open it, in graph units. */
private const val NODE_HIT_RADIUS_UNITS = 46f

/** Below this on-screen disc radius the title label is skipped (illegible mush). */
private const val LABEL_MIN_DISC_RADIUS_DP = 8f

/**
 * The link graph map (Phase 4.4): a full-screen, calm visual map of
 * how the current account's notes link to each other through
 * [[backlinks]]. Pan and pinch-zoom move over a deterministic
 * layout (see [LinkGraphLayout] — the same library draws the same
 * map on every visit); tapping a node opens that note. Locked
 * notes appear by title with muted styling, exactly as they do in
 * the notes list; opening one lands on its lock gate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkGraphScreen(
    onBack: () -> Unit = {},
    onOpenNote: (String) -> Unit = {},
    viewModel: LinkGraphViewModel = koinViewModel(),
) {
    val graph by viewModel.graph.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Link graph") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (graph.isEmpty) {
                GraphEmptyState(Modifier.fillMaxSize())
            } else {
                GraphCanvas(
                    graph = graph,
                    onOpenNote = onOpenNote,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** The no-links-yet state: quiet, and it teaches the [[ gesture. */
@Composable
private fun GraphEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(NotaSpacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No links yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Notes link with [[ — type it in a note and pick another " +
                "note to link to. When your notes link to each other, " +
                "their map appears here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = NotaSpacing.sm),
        )
    }
}

@Composable
private fun GraphCanvas(
    graph: PositionedLinkGraph,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val edgeColor = MaterialTheme.colorScheme.outline
    val nodeFill = MaterialTheme.colorScheme.primary
    val lockedFill = MaterialTheme.colorScheme.surfaceContainerHighest
    val lockedStroke = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = labelColor)

    val textMeasurer = rememberTextMeasurer()
    val maxLabelWidthPx = with(density) { 160.dp.toPx() }
    val labelLayouts: Map<String, TextLayoutResult> = remember(graph.data, labelStyle, maxLabelWidthPx) {
        graph.data.nodes.associate { node ->
            node.id to textMeasurer.measure(
                AnnotatedString(ellipsiseTitle(node.title)),
                style = labelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = maxLabelWidthPx.toInt().coerceAtLeast(1)),
            )
        }
    }

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var fittedFor by remember { mutableStateOf<Set<String>?>(null) }
    var coverageCheckedFor by remember { mutableStateOf<Map<String, GraphPoint>?>(null) }

    // Fit the whole map into the viewport when the node set first
    // appears or changes, and whenever a changed link structure
    // spreads the map beyond what the current view covers; after
    // that the transform belongs to the user's fingers. A title
    // edit keeps the same node set and layout (view stays), and a
    // topology change that still fits inside the current view
    // does not yank it either. (LinkGraphFit holds the rule.)
    LaunchedEffect(viewportSize, graph) {
        if (viewportSize.width == 0 || viewportSize.height == 0) return@LaunchedEffect
        val ids = graph.positions.keys
        if (ids.isEmpty()) return@LaunchedEffect
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (point in graph.positions.values) {
            minX = min(minX, point.x)
            minY = min(minY, point.y)
            maxX = max(maxX, point.x)
            maxY = max(maxY, point.y)
        }
        val exceeds = LinkGraphFit.boundsExceedViewport(
            minX = minX, minY = minY, maxX = maxX, maxY = maxY,
            scale = scale, panX = pan.x, panY = pan.y,
            viewportWidth = viewportSize.width,
            viewportHeight = viewportSize.height,
            margin = NODE_RADIUS_UNITS,
        )
        // Coverage is only re-checked when a NEW layout lands
        // (the positioner hands back the same positions instance
        // for an unchanged model): a title-only re-emission must
        // not yank a view the user deliberately panned.
        val layoutChanged = coverageCheckedFor !== graph.positions
        coverageCheckedFor = graph.positions
        if (!LinkGraphFit.shouldFit(fittedFor, ids, exceeds && layoutChanged)) {
            return@LaunchedEffect
        }
        val margin = NODE_RADIUS_UNITS + 70f // disc + label room
        val boundsW = max(maxX - minX + 2f * margin, 1f)
        val boundsH = max(maxY - minY + 2f * margin, 1f)
        val fit = min(
            viewportSize.width / boundsW,
            viewportSize.height / boundsH,
        ).coerceIn(0.05f, 2.5f)
        scale = fit
        pan = Offset(
            viewportSize.width / 2f - (minX + maxX) / 2f * fit,
            viewportSize.height / 2f - (minY + maxY) / 2f * fit,
        )
        fittedFor = ids
    }

    val description = "Link graph: ${graph.data.nodes.size} linked notes, ${graph.data.edges.size} links"
    Canvas(
        modifier = modifier
            .semantics { contentDescription = description }
            .onSizeChanged { viewportSize = it }
            .pointerInput(graph.data) {
                detectMapGestures(
                    onTransform = { centroid, panChange, zoomChange ->
                        val newScale = (scale * zoomChange).coerceIn(0.05f, 6f)
                        pan = centroid - (centroid - pan) * (newScale / scale) + panChange
                        scale = newScale
                    },
                    onTap = { tap ->
                        val gx = (tap.x - pan.x) / scale
                        val gy = (tap.y - pan.y) / scale
                        val minHitPx = with(density) { 24.dp.toPx() }
                        val threshold = max(NODE_HIT_RADIUS_UNITS, minHitPx / scale)
                        var bestId: String? = null
                        var bestDist = Float.MAX_VALUE
                        for ((id, point) in graph.positions) {
                            val dx = point.x - gx
                            val dy = point.y - gy
                            val dist = sqrt(dx * dx + dy * dy)
                            if (dist < bestDist) {
                                bestDist = dist
                                bestId = id
                            }
                        }
                        if (bestId != null && bestDist <= threshold) onOpenNote(bestId)
                    },
                )
            },
    ) {
        fun screen(point: GraphPoint) = Offset(point.x * scale + pan.x, point.y * scale + pan.y)

        val edgeStrokePx = with(density) { 1.5.dp.toPx() }
        for (edge in graph.data.edges) {
            val a = graph.positions[edge.sourceId] ?: continue
            val b = graph.positions[edge.targetId] ?: continue
            drawLine(
                color = edgeColor,
                start = screen(a),
                end = screen(b),
                strokeWidth = edgeStrokePx,
            )
        }

        val radiusPx = NODE_RADIUS_UNITS * scale
        val labelGapPx = with(density) { 4.dp.toPx() }
        val showLabels = radiusPx >= with(density) { LABEL_MIN_DISC_RADIUS_DP.dp.toPx() }
        for (node in graph.data.nodes) {
            val point = graph.positions[node.id] ?: continue
            val centre = screen(point)
            if (node.locked) {
                drawCircle(color = lockedFill, radius = radiusPx, center = centre)
                drawCircle(
                    color = lockedStroke,
                    radius = radiusPx,
                    center = centre,
                    style = Stroke(width = edgeStrokePx),
                )
            } else {
                drawCircle(color = nodeFill, radius = radiusPx, center = centre)
            }
            if (showLabels) {
                val layout = labelLayouts[node.id] ?: continue
                drawText(
                    layout,
                    topLeft = Offset(
                        centre.x - layout.size.width / 2f,
                        centre.y + radiusPx + labelGapPx,
                    ),
                )
            }
        }
    }
}

/**
 * Pan / pinch-zoom / tap for the map, detected in ONE gesture
 * stream (Phase 4.4 fix round). Two chained pointerInput blocks —
 * one transform detector, one tap detector — could both see the
 * same gesture: a pinch ending with a finger lifting off near a
 * node registered as a tap and opened a note nobody meant to
 * open. Here a gesture that crosses the touch slop (the same
 * rule detectTransformGestures uses) is a transform for good and
 * can never also fire as a tap; only a clean press-and-release
 * that never leaves the slop taps.
 */
private suspend fun PointerInputScope.detectMapGestures(
    onTransform: (centroid: Offset, panChange: Offset, zoomChange: Float) -> Unit,
    onTap: (Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var pastTouchSlop = false
        var zoomAccumulator = 1f
        var panAccumulator = Offset.Zero
        var upPosition: Offset? = null
        var cancelled = false
        do {
            val event = awaitPointerEvent()
            cancelled = event.changes.any { it.isConsumed }
            if (!cancelled) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (!pastTouchSlop) {
                    zoomAccumulator *= zoomChange
                    panAccumulator += panChange
                    val centroidSize = event.calculateCentroidSize(useTouchSlop = false)
                    val zoomMotion = abs(1f - zoomAccumulator) * centroidSize
                    val panMotion = panAccumulator.getDistance()
                    if (zoomMotion > viewConfiguration.touchSlop ||
                        panMotion > viewConfiguration.touchSlop
                    ) {
                        pastTouchSlop = true
                    }
                }
                if (pastTouchSlop) {
                    val centroid = event.calculateCentroid(useTouchSlop = false)
                    if (zoomChange != 1f || panChange != Offset.Zero) {
                        onTransform(centroid, panChange, zoomChange)
                    }
                    for (change in event.changes) {
                        if (change.positionChanged()) change.consume()
                    }
                }
                // Where the tracked finger lifts is the tap point
                // if this gesture never became a transform.
                for (change in event.changes) {
                    if (change.id == down.id && !change.pressed) {
                        upPosition = change.position
                    }
                }
            }
        } while (!cancelled && event.changes.any { it.pressed })
        if (!pastTouchSlop) upPosition?.let(onTap)
    }
}

/** Hard character cap so label measuring stays bounded at any library size. */
private fun ellipsiseTitle(title: String): String =
    if (title.length <= 24) title else title.take(23).trimEnd() + "…"
