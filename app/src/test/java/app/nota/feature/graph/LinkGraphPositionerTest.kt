package app.nota.feature.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the layout memoisation seam (Phase 4.4 fix
 * round): an unchanged graph model must not trigger a second
 * force-directed layout, and a changed model must lay out afresh
 * with exactly the positions a direct layout call produces.
 */
class LinkGraphPositionerTest {

    private val nodeA = LinkGraphNode(id = "a", title = "A", locked = false)
    private val nodeB = LinkGraphNode(id = "b", title = "B", locked = false)
    private val nodeC = LinkGraphNode(id = "c", title = "C", locked = false)

    @Test
    fun unchangedModelSkipsTheLayout() {
        val positioner = LinkGraphPositioner()
        val data = LinkGraphData(
            nodes = listOf(nodeA, nodeB),
            edges = listOf(LinkGraphEdge("a", "b")),
        )
        val first = positioner.positionsFor(data)
        // An equal-but-fresh model instance (a re-emission that
        // rebuilt the same graph) gets the very same map back —
        // reference equality proves no second layout ran.
        val rebuilt = LinkGraphData(
            nodes = listOf(nodeA.copy(), nodeB.copy()),
            edges = listOf(LinkGraphEdge("a", "b")),
        )
        assertEquals(data, rebuilt)
        assertSame(first, positioner.positionsFor(rebuilt))
    }

    @Test
    fun changedModelLaysOutAgain() {
        val positioner = LinkGraphPositioner()
        val two = LinkGraphData(
            nodes = listOf(nodeA, nodeB),
            edges = listOf(LinkGraphEdge("a", "b")),
        )
        val three = LinkGraphData(
            nodes = listOf(nodeA, nodeB, nodeC),
            edges = listOf(LinkGraphEdge("a", "b"), LinkGraphEdge("b", "c")),
        )
        val first = positioner.positionsFor(two)
        val second = positioner.positionsFor(three)
        assertNotSame(first, second)
        assertEquals(
            LinkGraphLayout.layout(listOf("a", "b", "c"), three.edges),
            second,
        )
    }

    @Test
    fun emptyModelPositionsAreEmptyAndMemoised() {
        val positioner = LinkGraphPositioner()
        val first = positioner.positionsFor(LinkGraphData.EMPTY)
        assertTrue(first.isEmpty())
        assertSame(first, positioner.positionsFor(LinkGraphData.EMPTY))
    }
}
