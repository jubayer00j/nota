package app.nota.feature.graph

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A laid-out node position in graph space; the origin is the graph's centre. */
data class GraphPoint(val x: Float, val y: Float)

/**
 * Deterministic force-directed layout for the link graph map
 * (Phase 4.4): Fruchterman–Reingold with a fixed iteration cap,
 * run off the main thread by the ViewModel.
 *
 * Determinism is a product requirement — the same library must
 * produce the same map on every visit — so there is no randomness
 * anywhere: initial positions are seeded on a circle in input
 * order (the model hands over nodes in a stable sorted order),
 * every force is a pure function of the current positions, and the
 * cooling schedule is fixed. Identical input ⇒ identical output,
 * bit for bit.
 *
 * Degenerate inputs are handled explicitly: no nodes ⇒ no
 * positions; one node ⇒ the origin; disconnected components repel
 * each other like everything else and settle apart; coincident
 * nodes get a fixed, index-derived separation nudge so no force
 * ever divides by zero. Coordinates are recentred on the centroid
 * before returning, and every returned value is finite.
 */
object LinkGraphLayout {
    /** Edge length the simulation treats as ideal, in graph units. */
    const val IDEAL_EDGE_LENGTH = 220f

    /** Hard cap on simulation steps so a huge library cannot stall the map. */
    const val MAX_ITERATIONS = 300

    /**
     * Budget for the total repulsion work of one layout, in
     * pair-force evaluations (Phase 4.4 fix round). One iteration
     * costs n·(n−1)/2 pair evaluations, so past a few hundred
     * nodes the step count scales down to keep the total under
     * this budget — a 1,000-note map does ~15M pair evaluations
     * instead of ~150M. The cap is a pure function of the node
     * count, so determinism is untouched: same input ⇒ same step
     * count ⇒ same map.
     */
    const val MAX_PAIR_OPERATIONS = 15_000_000

    /**
     * Simulation steps for a graph of [nodeCount] nodes: the
     * classic `100 + n` schedule, capped by [MAX_ITERATIONS] and
     * by the [MAX_PAIR_OPERATIONS] work budget. Identical to the
     * pre-cap schedule for every n up to ~300 nodes, so realistic
     * libraries lay out exactly as they did before the cap.
     */
    fun iterationsFor(nodeCount: Int): Int {
        if (nodeCount < 2) return 0
        val scheduled = min(MAX_ITERATIONS, 100 + nodeCount)
        val pairsPerIteration = nodeCount * (nodeCount - 1) / 2
        val budgeted = MAX_PAIR_OPERATIONS / pairsPerIteration
        return min(scheduled, max(1, budgeted))
    }

    fun layout(nodeIds: List<String>, edges: List<LinkGraphEdge>): Map<String, GraphPoint> {
        val ids = nodeIds.distinct()
        val n = ids.size
        if (n == 0) return emptyMap()
        if (n == 1) return mapOf(ids[0] to GraphPoint(0f, 0f))

        val index = HashMap<String, Int>(n)
        ids.forEachIndexed { i, id -> index[id] = i }
        val edgeIdx = edges.mapNotNull { edge ->
            val a = index[edge.sourceId]
            val b = index[edge.targetId]
            if (a != null && b != null && a != b) a to b else null
        }

        // Seeded placement: evenly on a circle whose radius grows
        // with the node count, so big graphs start spread out.
        val radius = max(IDEAL_EDGE_LENGTH, n * IDEAL_EDGE_LENGTH / (2f * PI.toFloat()))
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val angle = 2.0 * PI * i / n
            xs[i] = (radius * cos(angle)).toFloat()
            ys[i] = (radius * sin(angle)).toFloat()
        }

        val k = IDEAL_EDGE_LENGTH
        val iterations = iterationsFor(n)
        val dispX = FloatArray(n)
        val dispY = FloatArray(n)
        repeat(iterations) { step ->
            dispX.fill(0f)
            dispY.fill(0f)
            // Repulsion between every pair: k²/d along the joining line.
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    var dx = xs[i] - xs[j]
                    var dy = ys[i] - ys[j]
                    var d2 = dx * dx + dy * dy
                    if (d2 < 0.01f) {
                        // Coincident (or near enough): separate along a
                        // fixed index-derived direction instead of
                        // dividing by ~zero.
                        dx = (i - j) * 0.05f
                        dy = ((i + j) % 3 - 1) * 0.05f
                        if (dx == 0f && dy == 0f) dy = 0.05f
                        d2 = dx * dx + dy * dy
                    }
                    val d = sqrt(d2)
                    val force = k * k / d
                    val fx = dx / d * force
                    val fy = dy / d * force
                    dispX[i] += fx
                    dispY[i] += fy
                    dispX[j] -= fx
                    dispY[j] -= fy
                }
            }
            // Attraction along edges: d²/k along the joining line.
            for ((a, b) in edgeIdx) {
                val dx = xs[a] - xs[b]
                val dy = ys[a] - ys[b]
                val d = max(sqrt(dx * dx + dy * dy), 0.01f)
                val force = d * d / k
                val fx = dx / d * force
                val fy = dy / d * force
                dispX[a] -= fx
                dispY[a] -= fy
                dispX[b] += fx
                dispY[b] += fy
            }
            // Apply, capped by the linearly cooling temperature.
            val temperature = radius * (1f - step.toFloat() / iterations) + 0.001f
            for (i in 0 until n) {
                val len = sqrt(dispX[i] * dispX[i] + dispY[i] * dispY[i])
                if (len > 0f) {
                    val capped = min(len, temperature)
                    xs[i] += dispX[i] / len * capped
                    ys[i] += dispY[i] / len * capped
                }
            }
        }

        // Recentring on the centroid keeps the map's middle at the
        // origin regardless of how the forces drifted the whole.
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            cx += xs[i]
            cy += ys[i]
        }
        cx /= n
        cy /= n
        val out = LinkedHashMap<String, GraphPoint>(n)
        for (i in 0 until n) {
            out[ids[i]] = GraphPoint(xs[i] - cx, ys[i] - cy)
        }
        return out
    }
}

/**
 * Memoising seam between the graph model and its layout
 * (Phase 4.4 fix round). The repository flows re-emit whenever
 * anything in the notes/links tables changes — including writes
 * that leave the built graph model identical (an unrelated note
 * edit re-emits the notes flow). Re-running the force simulation
 * for an unchanged model would burn the O(n²) layout for
 * nothing, so the last model and its positions are kept, and an
 * equal model gets the very same positions map back. Because
 * [LinkGraphLayout] is deterministic, skipping can never change
 * the resulting map — only how often it is recomputed.
 *
 * Used from the ViewModel's single flow chain, whose emissions
 * are sequential, so no synchronisation is needed.
 */
class LinkGraphPositioner {
    private var lastData: LinkGraphData? = null
    private var lastPositions: Map<String, GraphPoint> = emptyMap()

    fun positionsFor(data: LinkGraphData): Map<String, GraphPoint> {
        if (data == lastData) return lastPositions
        val positions = LinkGraphLayout.layout(
            nodeIds = data.nodes.map { it.id },
            edges = data.edges,
        )
        lastData = data
        lastPositions = positions
        return positions
    }
}
