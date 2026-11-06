package app.nota.feature.graph

import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteLinkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the link graph's visual model (Phase 4.4):
 * [LinkGraph.build] applies the shipped backlink visibility rules
 * to raw note/link rows — trashed notes vanish entirely, locked
 * sources contribute no edges, locked targets stay by title, and
 * only notes participating in a surviving edge become nodes.
 */
class LinkGraphTest {

    private fun note(
        id: String,
        title: String = id,
        trashedAt: Long? = null,
        locked: Boolean = false,
    ) = NoteEntity(
        id = id,
        title = title,
        createdAt = 1L,
        updatedAt = 1L,
        trashedAt = trashedAt,
        locked = locked,
    )

    private fun link(source: String, target: String) = NoteLinkEntity(sourceId = source, targetId = target)

    @Test
    fun singleEdgeMakesBothNotesNodes() {
        val data = LinkGraph.build(
            notes = listOf(note("a", "Alpha"), note("b", "Beta")),
            links = listOf(link("a", "b")),
        )
        assertEquals(listOf("a", "b"), data.nodes.map { it.id })
        assertEquals(listOf(LinkGraphEdge("a", "b")), data.edges)
    }

    @Test
    fun isolatedNotesAreNotNodes() {
        val data = LinkGraph.build(
            notes = listOf(note("a"), note("b"), note("lonely")),
            links = listOf(link("a", "b")),
        )
        assertEquals(setOf("a", "b"), data.nodes.map { it.id }.toSet())
    }

    @Test
    fun trashedSourceEdgeIsDropped() {
        val data = LinkGraph.build(
            notes = listOf(note("a", trashedAt = 5L), note("b")),
            links = listOf(link("a", "b")),
        )
        assertTrue(data.isEmpty)
    }

    @Test
    fun trashedTargetEdgeIsDropped() {
        val data = LinkGraph.build(
            notes = listOf(note("a"), note("b", trashedAt = 5L)),
            links = listOf(link("a", "b")),
        )
        assertTrue(data.isEmpty)
    }

    @Test
    fun lockedSourceEdgeIsDropped() {
        val data = LinkGraph.build(
            notes = listOf(note("a", locked = true), note("b")),
            links = listOf(link("a", "b")),
        )
        assertTrue(data.isEmpty)
    }

    @Test
    fun lockedTargetStaysAsALockedNode() {
        val data = LinkGraph.build(
            notes = listOf(note("a"), note("b", locked = true)),
            links = listOf(link("a", "b")),
        )
        assertEquals(1, data.edges.size)
        val lockedNode = data.nodes.single { it.id == "b" }
        assertTrue(lockedNode.locked)
    }

    @Test
    fun selfEdgeIsDropped() {
        val data = LinkGraph.build(
            notes = listOf(note("a")),
            links = listOf(link("a", "a")),
        )
        assertTrue(data.isEmpty)
    }

    @Test
    fun edgeToAMissingNoteIsDropped() {
        val data = LinkGraph.build(
            notes = listOf(note("a")),
            links = listOf(link("a", "ghost")),
        )
        assertTrue(data.isEmpty)
    }

    @Test
    fun duplicateRowsCollapseIntoOneEdge() {
        val data = LinkGraph.build(
            notes = listOf(note("a"), note("b")),
            links = listOf(link("a", "b"), link("a", "b"), link("b", "a")),
        )
        // A→B collapses; B→A is a different directed edge and stays.
        assertEquals(
            listOf(LinkGraphEdge("a", "b"), LinkGraphEdge("b", "a")),
            data.edges,
        )
    }

    @Test
    fun blankTitleFallsBackToUntitled() {
        val data = LinkGraph.build(
            notes = listOf(note("a", title = ""), note("b")),
            links = listOf(link("a", "b")),
        )
        assertEquals("Untitled note", data.nodes.single { it.id == "a" }.title)
    }

    @Test
    fun nodesAreOrderedByTitleThenId() {
        val data = LinkGraph.build(
            notes = listOf(note("z", "Zulu"), note("m", "mango"), note("a", "Apple")),
            links = listOf(link("z", "m"), link("m", "a")),
        )
        assertEquals(listOf("a", "m", "z"), data.nodes.map { it.id })
    }

    @Test
    fun emptyInputsGiveTheEmptyGraph() {
        assertEquals(LinkGraphData.EMPTY, LinkGraph.build(emptyList(), emptyList()))
        assertEquals(
            LinkGraphData.EMPTY,
            LinkGraph.build(listOf(note("a")), emptyList()),
        )
    }
}
