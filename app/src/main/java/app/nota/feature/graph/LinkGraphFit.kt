package app.nota.feature.graph

/**
 * The link graph screen's auto-fit decisions (Phase 4.4), kept
 * pure so they can be unit-tested without composition.
 *
 * The map refits into the viewport only when there is a reason
 * to: the node set is new or changed, or a changed link
 * structure moved the laid-out bounds outside what the current
 * transform shows. Everything else — a title edit, a topology
 * change that still fits inside the current view — leaves the
 * transform alone, so the map never yanks itself out from under
 * the user's fingers. (Phase 4.4 fix round: the original guard
 * compared only the node-id set, so a topology change that
 * spread the same notes wider never refitted and could clip
 * nodes at the edges.)
 */
object LinkGraphFit {

    /**
     * Whether the screen should (re)fit the map: yes for a node
     * set that has never been fitted or has changed, and yes
     * whenever [boundsExceedViewport] says the current view no
     * longer covers the laid-out map.
     */
    fun shouldFit(
        fittedNodeIds: Set<String>?,
        nodeIds: Set<String>,
        boundsExceedViewport: Boolean,
    ): Boolean =
        nodeIds.isNotEmpty() && (fittedNodeIds != nodeIds || boundsExceedViewport)

    /**
     * Whether the laid-out bounds (minX..maxX, minY..maxY in
     * graph units, expanded by [margin] so node discs count, not
     * just centres) fall outside the viewport under the current
     * transform: a point maps to screen as `point * scale + pan`.
     */
    fun boundsExceedViewport(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        scale: Float,
        panX: Float,
        panY: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        margin: Float,
    ): Boolean {
        val left = (minX - margin) * scale + panX
        val top = (minY - margin) * scale + panY
        val right = (maxX + margin) * scale + panX
        val bottom = (maxY + margin) * scale + panY
        return left < 0f || top < 0f || right > viewportWidth || bottom > viewportHeight
    }
}
