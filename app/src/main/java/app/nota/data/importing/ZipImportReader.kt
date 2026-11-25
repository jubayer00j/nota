package app.nota.data.importing

import app.nota.data.backup.BackupQuota
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/** Result of a char-bounded text read ([Entry.readTextUpToChars]). */
sealed interface CharBoundedText {
    /** Read whole, within both bounds. */
    data class Ok(val text: String) : CharBoundedText

    /** The decoded text exceeded [maxChars] (raw bytes stayed within
     *  [maxBytes]). */
    object OverChars : CharBoundedText

    /** The raw bytes exceeded [maxBytes]. */
    object OverBytes : CharBoundedText
}

/** A ZIP archive tripped an import quota (too many entries / too large). */
class ImportZipException(message: String) : Exception(message)

/**
 * Char-bounded text read of a plain file — the loose-file counterpart
 * of [ZipImportReader.Entry.readTextUpToChars] (Codex R9-F1): a caller
 * checking an aggregate text budget must not have to materialize a
 * file that budget would reject. Same contract: once the decoded text
 * exceeds [maxChars] the characters are discarded instead of
 * retained, but the stream is read to the end so the byte total is
 * exact — a file past BOTH bounds reports [CharBoundedText.OverBytes],
 * preserving the per-file reason.
 */
internal fun File.readTextUpToChars(maxChars: Long, maxBytes: Long): CharBoundedText {
    var bytesRead = 0L
    val counting = object : FilterInputStream(inputStream().buffered()) {
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
    }
    val out = StringBuilder()
    var overChars = false
    counting.use { stream ->
        val reader = InputStreamReader(stream, Charsets.UTF_8)
        val buf = CharArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            if (!overChars) {
                out.append(buf, 0, n)
                if (out.length > maxChars) overChars = true
            }
        }
    }
    return when {
        bytesRead > maxBytes -> CharBoundedText.OverBytes
        overChars -> CharBoundedText.OverChars
        else -> CharBoundedText.Ok(out.toString())
    }
}

/**
 * Streaming ZIP walker for import archives, enforcing the same ceilings
 * as the backup reader ([BackupQuota]): at most [BackupQuota.MAX_ENTRIES]
 * entries, at most [BackupQuota.MAX_UNCOMPRESSED_BYTES] cumulative
 * UNCOMPRESSED bytes, and a [BackupQuota.MAX_RETAINED_NAME_BYTES] budget
 * for retained entry names.
 *
 * Every byte of every entry is counted — including entries the handler
 * never reads: after the handler returns, the walker drains the rest of
 * the entry THROUGH the counter. An uncounted drain is the zip-bomb
 * hole PR #57 closed in the backup reader; this reader does not
 * reopen it.
 *
 * Entry NAMES are bounded the same way: each walk trips
 * [ImportZipException] once the names it has seen pass
 * [BackupQuota.MAX_RETAINED_NAME_BYTES] (Codex R16-F1). The per-walk
 * seen set and the reader-lifetime duplicate set both hold full entry
 * names, and neither was governed by [retainName]'s budget — a
 * quota-compliant archive of long-named, small-payload entries could
 * retain hundreds of MiB of names as Java strings before any
 * truncation warning. The trip is hard, not silent truncation:
 * stopping tracking past the budget would leave duplicates past it
 * undetected.
 *
 * A quota trip aborts the walk with [ImportZipException] and nothing
 * partial is applied from that archive (same contract as BackupReader).
 * Entry names are used only for matching; parsers derive staging file
 * names from sanitised basenames, never from raw entry paths.
 *
 * [entryNames] is best-effort under the name budget: when the budget
 * runs out, further names are dropped — and [namesTruncated] records
 * it. Parsers that enumerate candidates from [entryNames] (the Keep
 * parser's .json list) must check the flag, or a truncated inventory
 * silently skips notes. Walks that read entry names straight from the
 * archive (the Markdown ZIP path's inventory) are unaffected.
 *
 * A path that occurs more than once in the archive is recorded in
 * [duplicateEntryNames]: the inventory keeps only the first
 * occurrence, but `walk` still visits both, and the parsers stage
 * images keyed by path — the second entry would silently overwrite
 * the first and embed whichever payload appears last. Parsers must
 * not stage an image or attachment under a duplicated path; the
 * reference stays unresolved and counted instead.
 */
class ZipImportReader(private val zipFile: File) {

    private val retainedNames = mutableListOf<String>()
    private val retainedNameSet = HashSet<String>()
    private var retainedNameBytes = 0L
    private var entryCount = 0
    private var totalBytes = 0L

    /** True once any entry name had to be dropped for the retained-name
     *  budget. Sticky for the reader's lifetime (never reset between
     *  walks) — a later walk cannot un-truncate the inventory. */
    var namesTruncated = false
        private set

    /** Paths that occur more than once in the archive (Codex R13-F2).
     *  Sticky for the reader's lifetime, like [namesTruncated]: a path
     *  duplicated in any single walk is a duplicate path in the
     *  archive. The per-walk seen set is what makes this exact — the
     *  reader is walked several times over the same archive (the Keep
     *  parser walks twice), so a name legitimately reappears once per
     *  walk and only a second occurrence WITHIN one walk counts. */
    private val duplicateNames = mutableSetOf<String>()

    /** Every path that occurs more than once in the archive. */
    val duplicateEntryNames: Set<String> get() = duplicateNames

    /** Every entry name seen so far (best-effort under the name budget). */
    val entryNames: List<String> get() = retainedNames

    fun hasEntry(name: String): Boolean = name in retainedNameSet

    inner class Entry internal constructor(
        val name: String,
        /** ZIP entry timestamp (millis); 0/-1 when the archive has none. */
        val time: Long,
        private val stream: InputStream,
    ) {
        /** Reads the whole entry; null when it exceeds [capBytes]. The
         *  remainder is still drained (and counted) by the walker. */
        fun readBytesUpTo(capBytes: Long): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = 0L
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                read += n
                if (read <= capBytes) out.write(buffer, 0, n)
            }
            return if (read <= capBytes) out.toByteArray() else null
        }

        fun readTextUpTo(capBytes: Long): String? =
            readBytesUpTo(capBytes)?.toString(Charsets.UTF_8)

        /**
         * Reads the entry as UTF-8 text, retaining at most [maxChars]
         * decoded characters — a caller checking an aggregate text
         * budget must not have to materialize an entry that budget
         * would reject (Codex R7-F3: `readTextUpTo` buffers up to
         * 100 MiB first). The read still streams the whole entry:
         * once the decoded text exceeds [maxChars] the characters
         * are discarded instead of retained, but the stream is read
         * to the end so the byte total is exact — an entry past BOTH
         * bounds reports [CharBoundedText.OverBytes], preserving the
         * per-file reason for a >100 MiB entry (the char bound trips
         * first for text, since 16 M chars are at most ~48 MiB).
         * The walker drains whatever is left either way, so the
         * archive quota still counts every byte.
         */
        fun readTextUpToChars(maxChars: Long, maxBytes: Long): CharBoundedText {
            val bytesBefore = totalBytes
            val reader = InputStreamReader(stream, Charsets.UTF_8)
            val out = StringBuilder()
            val buf = CharArray(DEFAULT_BUFFER_SIZE)
            var overChars = false
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                if (!overChars) {
                    out.append(buf, 0, n)
                    if (out.length > maxChars) overChars = true
                }
                // Past the char bound the decoded characters are
                // discarded — but the stream is still read to the
                // end, so the byte total below is exact.
            }
            val bytesRead = totalBytes - bytesBefore
            return when {
                bytesRead > maxBytes -> CharBoundedText.OverBytes
                overChars -> CharBoundedText.OverChars
                else -> CharBoundedText.Ok(out.toString())
            }
        }

        /** Streams the entry into [target]; false (and no file left
         *  behind) when it exceeds [capBytes]. */
        fun copyToUpTo(target: File, capBytes: Long): Boolean {
            var written = 0L
            var ok = true
            target.outputStream().buffered().use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    written += n
                    if (written <= capBytes) out.write(buffer, 0, n) else ok = false
                }
            }
            if (!ok) target.delete()
            return ok
        }

        internal fun drainRemaining() {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (stream.read(buffer) >= 0) {
                // Counting happens inside the stream; draining is the point.
            }
        }
    }

    private inner class CountingStream(source: InputStream) : FilterInputStream(source) {
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) addBytes(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) addBytes(n.toLong())
            return n
        }

        private fun addBytes(n: Long) {
            totalBytes += n
            if (totalBytes > BackupQuota.MAX_UNCOMPRESSED_BYTES) {
                throw ImportZipException("That ZIP is too large to import safely.")
            }
        }
    }

    /** One streaming pass over the archive. [handler] may read some,
     *  all, or none of each entry; the walker counts every byte either
     *  way. May be called more than once (the Keep parser walks twice:
     *  notes first, then the attachments they reference). */
    fun walk(handler: (Entry) -> Unit) {
        // Quotas are per pass: the Keep parser walks twice (notes, then
        // the attachments they reference) and each pass is its own read.
        entryCount = 0
        totalBytes = 0L
        // Duplicate detection is per walk, not per reader lifetime:
        // the same name legitimately appears once in every walk, so
        // only a second occurrence within THIS walk is a duplicate
        // path in the archive (Codex R13-F2).
        val namesThisWalk = HashSet<String>()
        // Per-walk name bytes are bounded by the same 8 MiB budget as
        // the retained-name inventory (Codex R16-F1): namesThisWalk
        // and duplicateNames both hold full entry names, and without
        // this the walk retains every name it sees — a quota-compliant
        // archive of long-named, small-payload entries (names up to
        // 64 KiB each) could hold hundreds of MiB of Java strings
        // (UTF-16, ~2x bytes) before any truncation warning. The trip
        // is hard: silently stopping tracking past the budget would
        // leave duplicates past it undetected.
        var walkNameBytes = 0L
        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            while (true) {
                val ze = zin.nextEntry ?: break
                entryCount++
                if (entryCount > BackupQuota.MAX_ENTRIES) {
                    throw ImportZipException("That ZIP has too many entries to import safely.")
                }
                walkNameBytes += ze.name.toByteArray(Charsets.UTF_8).size.toLong()
                if (walkNameBytes > BackupQuota.MAX_RETAINED_NAME_BYTES) {
                    throw ImportZipException("That ZIP has too many or too long entry names to import safely.")
                }
                if (!namesThisWalk.add(ze.name)) duplicateNames += ze.name
                retainName(ze.name)
                val entry = Entry(ze.name, ze.time, CountingStream(zin))
                handler(entry)
                entry.drainRemaining()
                zin.closeEntry()
            }
        }
    }

    private fun retainName(name: String) {
        if (name in retainedNameSet) return // already listed
        val bytes = name.toByteArray(Charsets.UTF_8).size.toLong()
        if (retainedNameBytes + bytes > BackupQuota.MAX_RETAINED_NAME_BYTES) {
            namesTruncated = true
            return
        }
        retainedNameSet += name
        retainedNames += name
        retainedNameBytes += bytes
    }
}

