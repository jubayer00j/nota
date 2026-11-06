package app.nota.feature.graph

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the link graph auto-fit rule (Phase 4.4 fix
 * round): a changed node set refits, a same-set topology change
 * refits only when the map outgrows the current view, and
 * everything else leaves the user's transform alone.
 */
class LinkGraphFitTest {

    @Test
    fun firstFitHappensWhenNothingWasFittedYet() {
        assertTrue(
            LinkGraphFit.shouldFit(null, setOf("a", "b"), boundsExceedViewport = false),
        )
    }

    @Test
    fun changedNodeSetRefits() {
        assertTrue(
            LinkGraphFit.shouldFit(
                setOf("a", "b"), setOf("a", "b", "c"), boundsExceedViewport = false,
            ),
        )
    }

    @Test
    fun sameNodeSetInsideTheViewDoesNotRefit() {
        // A title edit (same nodes, same layout, view still
        // covering the map) must not yank the user's transform.
        assertFalse(
            LinkGraphFit.shouldFit(setOf("a", "b"), setOf("a", "b"), boundsExceedViewport = false),
        )
    }

    @Test
    fun sameNodeSetSpreadBeyondTheViewRefits() {
        // The Codex finding: same notes, new links, wider layout —
        // the old id-set-only guard never refitted and clipped.
        assertTrue(
            LinkGraphFit.shouldFit(setOf("a", "b"), setOf("a", "b"), boundsExceedViewport = true),
        )
    }

    @Test
    fun emptyGraphNeverFits() {
        assertFalse(LinkGraphFit.shouldFit(null, emptySet(), boundsExceedViewport = true))
    }

    @Test
    fun boundsInsideTheViewportAreCovered() {
        // A -50..50 map at scale 2 with pan (200, 200) lands at
        // 48..352 px once the 26-unit disc margin counts —
        // comfortably inside a 400x400 viewport.
        assertFalse(
            LinkGraphFit.boundsExceedViewport(
                minX = -50f, minY = -50f, maxX = 50f, maxY = 50f,
                scale = 2f, panX = 200f, panY = 200f,
                viewportWidth = 400, viewportHeight = 400, margin = 26f,
            ),
        )
    }

    @Test
    fun boundsClippedByTheViewportExceed() {
        // The same -50..50 map at scale 4 with no pan starts at
        // (-50-26)*4 = -304 px: clipped on the left and top.
        assertTrue(
            LinkGraphFit.boundsExceedViewport(
                minX = -50f, minY = -50f, maxX = 50f, maxY = 50f,
                scale = 4f, panX = 0f, panY = 0f,
                viewportWidth = 400, viewportHeight = 400, margin = 26f,
            ),
        )
    }

    @Test
    fun boundsPannedOffTheRightEdgeExceed() {
        // (50+26)*1 + 380 = 456 px > 400: the right edge is out.
        assertTrue(
            LinkGraphFit.boundsExceedViewport(
                minX = -50f, minY = -50f, maxX = 50f, maxY = 50f,
                scale = 1f, panX = 380f, panY = 200f,
                viewportWidth = 400, viewportHeight = 400, margin = 26f,
            ),
        )
    }
}
