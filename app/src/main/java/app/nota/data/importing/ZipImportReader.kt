package app.nota.data.importing

import app.nota.data.backup.BackupQuota
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A ZIP archive tripped an import quota (too many entries / too large). */
class ImportZipException(message: String) : Exception(message)

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
        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            while (true) {
                val ze = zin.nextEntry ?: break
                entryCount++
                if (entryCount > BackupQuota.MAX_ENTRIES) {
                    throw ImportZipException("That ZIP has too many entries to import safely.")
                }
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
