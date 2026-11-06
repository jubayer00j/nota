package app.nota.feature.graph

import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the link graph layout (Phase 4.4): the map
 * must be deterministic (same library ⇒ same map), finite for
 * every input, and sane on degenerate graphs.
 */
class LinkGraphLayoutTest {

    private fun edge(a: String, b: String) = LinkGraphEdge(a, b)

    private fun dist(p: GraphPoint, q: GraphPoint): Float = hypot(p.x - q.x, p.y - q.y)

    @Test
    fun emptyGraphHasNoPositions() {
        assertTrue(LinkGraphLayout.layout(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun singleNodeSitsAtTheOrigin() {
        val positions = LinkGraphLayout.layout(listOf("a"), emptyList())
        assertEquals(GraphPoint(0f, 0f), positions["a"])
    }

    @Test
    fun twoConnectedNodesSettleApartAroundTheOrigin() {
        val positions = LinkGraphLayout.layout(listOf("a", "b"), listOf(edge("a", "b")))
        val a = positions.getValue("a")
        val b = positions.getValue("b")
        val d = dist(a, b)
        assertTrue("nodes must separate (was $d)", d > 50f)
        assertTrue("nodes must not fly away (was $d)", d < 800f)
        // Recentring puts the centroid on the origin.
        assertEquals(0f, (a.x + b.x) / 2f, 0.01f)
        assertEquals(0f, (a.y + b.y) / 2f, 0.01f)
    }

    @Test
    fun sameInputProducesIdenticalOutput() {
        val ids = (1..14).map { "n$it" }
        val edges = buildList {
            for (i in ids.indices) add(edge(ids[i], ids[(i + 1) % ids.size]))
            add(edge("n1", "n7"))
            add(edge("n3", "n10"))
            add(edge("n5", "n12"))
        }
        val first = LinkGraphLayout.layout(ids, edges)
        val second = LinkGraphLayout.layout(ids, edges)
        assertEquals(first, second)
        // And the ring actually spread out (no coincident collapse).
        val distinct = first.values.toSet()
        assertEquals(ids.size, distinct.size)
    }

    @Test
    fun disconnectedComponentsKeepTheirPartnersClosest() {
        val ids = listOf("a1", "a2", "b1", "b2")
        val edges = listOf(edge("a1", "a2"), edge("b1", "b2"))
        val p = LinkGraphLayout.layout(ids, edges)
        assertEquals(4, p.size)
        for (point in p.values) {
            assertTrue(point.x.isFinite() && point.y.isFinite())
        }
        assertTrue(dist(p.getValue("a1"), p.getValue("a2")) < dist(p.getValue("a1"), p.getValue("b1")))
        assertTrue(dist(p.getValue("a1"), p.getValue("a2")) < dist(p.getValue("a1"), p.getValue("b2")))
        assertTrue(dist(p.getValue("b1"), p.getValue("b2")) < dist(p.getValue("b1"), p.getValue("a1")))
        assertTrue(dist(p.getValue("b1"), p.getValue("b2")) < dist(p.getValue("b1"), p.getValue("a2")))
    }

    @Test
    fun hubSettlesNearTheCentreOfItsSpokes() {
        val spokes = (1..8).map { "s$it" }
        val ids = listOf("hub") + spokes
        val edges = spokes.map { edge("hub", it) }
        val p = LinkGraphLayout.layout(ids, edges)
        val hub = p.getValue("hub")
        val hubFromOrigin = hypot(hub.x, hub.y)
        val meanSpokeFromOrigin = spokes.map { hypot(p.getValue(it).x, p.getValue(it).y) }.average()
        assertTrue(
            "hub $hubFromOrigin should sit well inside the spoke ring ($meanSpokeFromOrigin)",
            hubFromOrigin < meanSpokeFromOrigin * 0.75,
        )
    }

    @Test
    fun selfAndUnknownEdgesDoNotChangeTheLayout() {
        val ids = listOf("a", "b")
        val plain = LinkGraphLayout.layout(ids, listOf(edge("a", "b")))
        val noisy = LinkGraphLayout.layout(
            ids,
            listOf(edge("a", "b"), edge("a", "a"), edge("a", "ghost"), edge("ghost", "b")),
        )
        assertEquals(plain, noisy)
    }

    @Test
    fun largeGraphStaysFinite() {
        val ids = (1..400).map { "n$it" }
        val edges = (1 until 400).map { edge("n$it", "n${it + 1}") } +
            listOf(edge("n1", "n200"), edge("n50", "n300"), edge("n400", "n1"))
        val p = LinkGraphLayout.layout(ids, edges)
        assertEquals(400, p.size)
        for (point in p.values) {
            assertTrue(point.x.isFinite() && point.y.isFinite())
        }
        // The chain spread out rather than collapsing to a point.
        val spread = sqrt(
            p.values.maxOf { it.x } - p.values.minOf { it.x } +
                (p.values.maxOf { it.y } - p.values.minOf { it.y }),
        )
        assertTrue(spread > 0f)
    }

    @Test
    fun iterationScheduleMatchesThePreCapScheduleForRealisticSizes() {
        // The work cap (Phase 4.4 fix round) must not move a
        // single realistic map: for every size a real library
        // plausibly reaches, the step count is exactly the
        // classic min(300, 100 + n).
        for (n in 2..300) {
            assertEquals(
                "n=$n",
                min(LinkGraphLayout.MAX_ITERATIONS, 100 + n),
                LinkGraphLayout.iterationsFor(n),
            )
        }
    }

    @Test
    fun layoutWorkStaysUnderThePairOperationBudget() {
        for (n in listOf(301, 400, 1_000, 5_000)) {
            val pairsPerIteration = n.toLong() * (n - 1) / 2
            val iterations = LinkGraphLayout.iterationsFor(n)
            val total = pairsPerIteration * iterations
            assertTrue(
                "n=$n work $total exceeds the budget",
                total <= LinkGraphLayout.MAX_PAIR_OPERATIONS,
            )
            assertTrue("n=$n must still simulate", iterations >= 1)
        }
    }

    @Test
    fun thousandNodeGraphStaysFiniteAndDeterministic() {
        val ids = (1..1_000).map { "n$it" }
        val edges = (1 until 1_000).map { edge("n$it", "n${it + 1}") } +
            listOf(edge("n1", "n500"), edge("n250", "n750"), edge("n1000", "n1"))
        val first = LinkGraphLayout.layout(ids, edges)
        val second = LinkGraphLayout.layout(ids, edges)
        assertEquals(1_000, first.size)
        assertEquals(first, second)
        for (point in first.values) {
            assertTrue(point.x.isFinite() && point.y.isFinite())
        }
    }
}
