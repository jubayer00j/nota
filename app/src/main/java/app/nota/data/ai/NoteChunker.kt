package app.nota.data.ai

/**
 * Note chunking for semantic indexing (Phase 4.2). A note's
 * embedding text is its title followed by its plain-text body,
 * split into passages of roughly [TARGET_CHARS] so a long note
 * contributes several vectors and search can score the BEST
 * passage instead of diluting the whole note into one vector
 * (the Nota refinement over 1.x's one-vector-per-note, whose
 * 6,000-char head cap silently dropped long notes' tails).
 *
 * Paragraph-aware: the text is first split into blocks at line
 * breaks (Nota's plain-text projection puts each block-level
 * element on its own line) and blocks are packed greedily up to
 * the target. A single block longer than [MAX_CHUNK_CHARS] is
 * hard-split at word boundaries, consecutive pieces sharing an
 * [OVERLAP_CHARS] tail/head so a phrase straddling a cut still
 * lands whole in one chunk. The whole note is capped at
 * [MAX_TEXT_CHARS] so one huge note cannot dominate an indexing
 * run; the cap keeps the head, like 1.x.
 *
 * Pure and deterministic: same input, same chunks, no clock, no
 * randomness — the indexer's change detection hashes the source
 * text and trusts chunk boundaries to be stable.
 */
object NoteChunker {

    const val TARGET_CHARS = 500
    const val MAX_CHUNK_CHARS = 600
    const val OVERLAP_CHARS = 60

    /** The word-start adjustment in [splitLongBlock] is only
     *  taken when at least this much shared tail survives it —
     *  a space in the last few characters before the cut would
     *  otherwise move the next piece's start to (or past) the
     *  cut itself and silently erase the overlap (Jules PR #23
     *  P3: a space at cut - 1 produced next == cut, i.e. zero
     *  shared characters between consecutive pieces). */
    const val MIN_OVERLAP_CHARS = 20
    const val MAX_TEXT_CHARS = 12_000

    /** The exact text a note is embedded as: title, blank line,
     *  body — capped. Also the fingerprint input, so what is
     *  hashed is exactly what was chunked. */
    fun embeddedText(title: String, plainText: String): String {
        val combined = (title.trim() + "\n\n" + plainText).trim()
        return if (combined.length > MAX_TEXT_CHARS) {
            combined.substring(0, MAX_TEXT_CHARS)
        } else {
            combined
        }
    }

    /** Splits a note into embedding passages; empty for a note
     *  with no title and no body text. */
    fun chunk(title: String, plainText: String): List<String> {
        val text = embeddedText(title, plainText)
        if (text.isBlank()) return emptyList()
        val blocks = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                chunks += current.toString()
                current.clear()
            }
        }
        for (block in blocks) {
            if (block.length > MAX_CHUNK_CHARS) {
                flush()
                splitLongBlock(block, chunks)
                continue
            }
            if (current.isNotEmpty() &&
                current.length + 1 + block.length > TARGET_CHARS
            ) {
                flush()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(block)
        }
        flush()
        return chunks
    }

    /** Hard-splits one over-long block at word boundaries with a
     *  shared overlap tail between consecutive pieces. */
    private fun splitLongBlock(block: String, out: MutableList<String>) {
        var rest = block
        while (rest.length > MAX_CHUNK_CHARS) {
            var cut = rest.lastIndexOf(' ', MAX_CHUNK_CHARS)
            if (cut < MAX_CHUNK_CHARS / 2) cut = MAX_CHUNK_CHARS
            val piece = rest.substring(0, cut).trim()
            if (piece.isNotEmpty()) out += piece
            // The next piece re-starts OVERLAP_CHARS back (moved
            // forward to a word start) so the cut phrase survives
            // whole in at least one piece. The move is only taken
            // when MIN_OVERLAP_CHARS of shared tail survive it —
            // next must stay strictly behind the cut.
            var next = (cut - OVERLAP_CHARS).coerceAtLeast(0)
            val space = rest.indexOf(' ', next)
            if (space in next until cut && cut - (space + 1) >= MIN_OVERLAP_CHARS) {
                next = space + 1
            }
            rest = rest.substring(next).trimStart()
            // Progress guarantee: a pathological no-space input
            // must still advance.
            if (rest.length >= block.length) break
        }
        if (rest.isNotEmpty()) out += rest
    }
}
