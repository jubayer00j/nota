package app.nota.data.ai

/**
 * The in-memory semantic-index cache slot (Phase 6.1; generation
 * discipline added in PR #46's Codex fix round). Holds ONE account's
 * loaded index — the indexer switches accounts by replacing the
 * entry — and arbitrates the one race the plain slot had: a cold
 * load reads the index file OUTSIDE any lock (it is the slow step),
 * and while it reads, a reconcile can write a NEWER index through
 * the store path. Installing the stale load unconditionally would
 * clobber the fresh entry, and nothing would re-read the file
 * afterwards — searches would rank against old vectors until the
 * next write.
 *
 * The discipline: [store] bumps a generation as it installs. A
 * cold load captures the generation atomically with its miss (see
 * [Lookup]) and [fill]s after reading disk; the fill installs ONLY
 * if the generation is unchanged. If a store intervened, the fill
 * returns the fresher entry when it belongs to the same account,
 * and otherwise returns the loaded value WITHOUT caching it (the
 * entry is another account's now; the next read for this account
 * loads again).
 *
 * Pure and internally synchronised, so the race matrix is
 * unit-testable without the platform (SemanticIndexCacheSlotTest) —
 * the same pattern as the voice StartGate in PR #45.
 */
internal class SemanticIndexCacheSlot {

    /** The outcome of a [get]: whether the slot held an entry for
     *  the account (whose index may itself be null — an account
     *  with no index file), and the generation at that moment. */
    data class Lookup(
        val hit: Boolean,
        val index: StoredSemanticIndex?,
        val generation: Long,
    )

    private var generation = 0L
    private var entry: Pair<String, StoredSemanticIndex?>? = null

    /** Look up [accountId]: a hit returns its (possibly null)
     *  index; a miss returns the generation a cold load must
     *  present back to [fill]. */
    @Synchronized
    fun get(accountId: String): Lookup {
        val current = entry
        return if (current != null && current.first == accountId) {
            Lookup(hit = true, index = current.second, generation = generation)
        } else {
            Lookup(hit = false, index = null, generation = generation)
        }
    }

    /** Install a freshly stored index (the ONLY mutation path's
     *  cache refresh): bumps the generation, replacing any entry. */
    @Synchronized
    fun store(accountId: String, index: StoredSemanticIndex?) {
        generation++
        entry = accountId to index
    }

    /**
     * Install a cold [loaded] index read from disk, unless a
     * [store] intervened since [generationAtLoadStart]. Returns the
     * index the caller should use: [loaded] when it was installed
     * (or when the current entry belongs to another account), the
     * fresher cached entry when one for the same account exists.
     */
    @Synchronized
    fun fill(
        accountId: String,
        loaded: StoredSemanticIndex?,
        generationAtLoadStart: Long,
    ): StoredSemanticIndex? {
        if (generation == generationAtLoadStart) {
            entry = accountId to loaded
            return loaded
        }
        val current = entry
        return if (current != null && current.first == accountId) {
            current.second
        } else {
            loaded
        }
    }
}
