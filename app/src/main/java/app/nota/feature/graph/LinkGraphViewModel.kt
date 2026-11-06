package app.nota.feature.graph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nota.data.repository.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A [LinkGraphData] with deterministic positions attached (see [LinkGraphLayout]). */
data class PositionedLinkGraph(
    val data: LinkGraphData,
    val positions: Map<String, GraphPoint>,
) {
    val isEmpty: Boolean get() = data.isEmpty

    companion object {
        val EMPTY = PositionedLinkGraph(LinkGraphData.EMPTY, emptyMap())
    }
}

/**
 * Backs the link graph map (Phase 4.4). The repository resolves its
 * DAOs through the DatabaseProvider at call time, so this always
 * reads the CURRENT account's library — an account switch swaps
 * the flows' source underneath, never a captured database.
 *
 * The model build and the force-directed layout both run on the
 * default dispatcher (flowOn upstream of the stateIn), never in
 * composition and never on the main thread: a few hundred notes
 * stay a background recomputation, and the screen just renders the
 * latest settled state.
 */
class LinkGraphViewModel(
    repository: NoteRepository,
) : ViewModel() {

    val graph: StateFlow<PositionedLinkGraph> =
        combine(repository.observeAllNotes(), repository.observeAllLinks()) { notes, links ->
            LinkGraph.build(notes, links)
        }
            .map { data ->
                PositionedLinkGraph(
                    data = data,
                    positions = LinkGraphLayout.layout(
                        nodeIds = data.nodes.map { it.id },
                        edges = data.edges,
                    ),
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PositionedLinkGraph.EMPTY)
}
