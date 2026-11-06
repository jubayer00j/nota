package app.nota.feature.graph

import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteLinkEntity

/** One node of the link graph: a note taking part in at least one visible edge. */
data class LinkGraphNode(
    val id: String,
    val title: String,
    val locked: Boolean,
)

/** One visible [[backlink]] edge, direction preserved (source → target). */
data class LinkGraphEdge(
    val sourceId: String,
    val targetId: String,
)

/**
 * The visual model of the current library's link graph (Phase 4.4):
 * the nodes and edges the map draws. Built purely from the stored
 * `note_links` edges plus note metadata — no note content is read,
 * so locked notes' bodies are never touched.
 */
data class LinkGraphData(
    val nodes: List<LinkGraphNode>,
    val edges: List<LinkGraphEdge>,
) {
    val isEmpty: Boolean get() = edges.isEmpty()

    companion object {
        val EMPTY = LinkGraphData(emptyList(), emptyList())
    }
}

/**
 * Builds the graph's visual model from raw rows, applying the same
 * visibility rules the shipped backlink surfaces use:
 *
 * - Trashed notes never appear — neither as nodes nor as edge
 *   endpoints (mirrors [app.nota.data.db.NoteLinkDao.observeIncoming],
 *   which joins on `trashedAt IS NULL`).
 * - A locked note contributes no outgoing edges (mirrors the DAO's
 *   locked-source exclusion; `NoteRepository.relink` also clears a
 *   note's edges when it locks, so this is defence in depth for
 *   rows written by any other path, e.g. a sync merge).
 * - A locked note CAN be a target/node, shown by title: titles are
 *   visible metadata everywhere else in the app (notes list, the
 *   "Linked from…" panel's target side), and opening it lands on
 *   its lock gate.
 * - Self-links are dropped (relink never stores them; the map must
 *   not draw a loop line for a stored row that claims one anyway).
 * - Edges pointing at notes that no longer exist are dropped, and
 *   duplicate stored rows collapse into one edge.
 * - Templates are not notes (they live in the template store), so
 *   they can never appear here; archived notes take part like any
 *   other live note, exactly as they do in the panel and picker.
 *
 * Only notes participating in a surviving edge become nodes — an
 * unlinked note is not part of the map. Node order is deterministic
 * (title, then id) so the layout built on top of this model is
 * stable between visits.
 */
object LinkGraph {
    fun build(notes: List<NoteEntity>, links: List<NoteLinkEntity>): LinkGraphData {
        val byId = notes.associateBy { it.id }
        val visible = LinkedHashMap<Pair<String, String>, LinkGraphEdge>()
        for (link in links) {
            val source = byId[link.sourceId] ?: continue
            val target = byId[link.targetId] ?: continue
            if (source.id == target.id) continue
            if (source.trashedAt != null || target.trashedAt != null) continue
            if (source.locked) continue
            visible.putIfAbsent(
                source.id to target.id,
                LinkGraphEdge(sourceId = source.id, targetId = target.id),
            )
        }
        if (visible.isEmpty()) return LinkGraphData.EMPTY

        val participating = LinkedHashSet<String>()
        for (edge in visible.values) {
            participating += edge.sourceId
            participating += edge.targetId
        }
        val nodes = notes.asSequence()
            .filter { it.id in participating }
            .map { note ->
                LinkGraphNode(
                    id = note.id,
                    title = note.title.ifBlank { "Untitled note" },
                    locked = note.locked,
                )
            }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.id }))
            .toList()
        val order = nodes.mapIndexed { index, node -> node.id to index }.toMap()
        val edges = visible.values.sortedWith(
            compareBy({ order[it.sourceId] }, { order[it.targetId] }),
        )
        return LinkGraphData(nodes = nodes, edges = edges)
    }
}
