package app.nota.data.ai

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import app.nota.data.db.LibraryStampRow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** One stored chunk: the fingerprint of the exact chunk text
 *  that produced [vector] (per-chunk change detection, G10) and
 *  the vector itself. A stored chunk only ever answers for the
 *  chunk text at its own position in the note whose fingerprint
 *  still matches it. */
data class StoredChunk(
    val fingerprint: String,
    val vector: FloatArray,
)

/** One note's stored vectors: the fingerprint of the exact text
 *  that was chunked (whole-note change detection) and one
 *  [StoredChunk] per chunk, in chunk order. */
data class StoredNoteVectors(
    val fingerprint: String,
    val chunks: List<StoredChunk>,
)

/** A whole account index as persisted: the embedding-space
 *  signature the vectors were written under, their dimension,
 *  and the per-note vectors. */
data class StoredSemanticIndex(
    val signature: String,
    val dims: Int,
    val notes: Map<String, StoredNoteVectors>,
)

/**
 * The semantic index's persistence (Phase 4.2): one binary file
 * per account under a device-local directory (filesDir in
 * production; a temp dir in tests).
 *
 * Why a file of its own, mirroring the AiSettingsStore
 * precedent's properties rather than its mechanism: vectors are
 * derived data that must be LOCAL-ONLY and per-account, dropped
 * on sign-out, and structurally unable to enter a backup —
 * backups are built from Room and the attachment store, and this
 * directory is neither. A preferences DataStore was rejected:
 * it loads its whole file on every read, and megabytes of float
 * vectors would tax every unrelated preference access.
 *
 * The format is deliberately boring (magic, version, then
 * length-prefixed fields) and any unreadable file — corrupt,
 * truncated, from a newer version — reads as "no index", because
 * the index is always rebuildable from the notes + provider.
 * Writes go to a temp file renamed over the target, so a crash
 * mid-write leaves the previous index intact.
 */
class SemanticIndexStore(private val rootDir: File) {

    fun load(accountId: String): StoredSemanticIndex? {
        val file = fileFor(accountId)
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() != VERSION) return null
                val signature = input.readUTF()
                val dims = input.readInt()
                val noteCount = input.readInt()
                if (noteCount < 0 || noteCount > MAX_NOTES) return null
                val notes = LinkedHashMap<String, StoredNoteVectors>(noteCount)
                repeat(noteCount) {
                    val noteId = input.readUTF()
                    val fingerprint = input.readUTF()
                    val chunkCount = input.readInt()
                    if (chunkCount < 0 || chunkCount > MAX_CHUNKS_PER_NOTE) return null
                    val chunks = ArrayList<StoredChunk>(chunkCount)
                    repeat(chunkCount) {
                        val chunkFingerprint = input.readUTF()
                        val size = input.readInt()
                        if (size != dims) return null
                        val vector = FloatArray(size)
                        for (i in 0 until size) vector[i] = input.readFloat()
                        chunks += StoredChunk(chunkFingerprint, vector)
                    }
                    notes[noteId] = StoredNoteVectors(fingerprint, chunks)
                }
                StoredSemanticIndex(signature, dims, notes)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun save(accountId: String, index: StoredSemanticIndex) {
        rootDir.mkdirs()
        val target = fileFor(accountId)
        val temp = File(rootDir, target.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeUTF(index.signature)
            output.writeInt(index.dims)
            output.writeInt(index.notes.size)
            for ((noteId, stored) in index.notes) {
                output.writeUTF(noteId)
                output.writeUTF(stored.fingerprint)
                output.writeInt(stored.chunks.size)
                for (chunk in stored.chunks) {
                    output.writeUTF(chunk.fingerprint)
                    output.writeInt(chunk.vector.size)
                    for (value in chunk.vector) output.writeFloat(value)
                }
            }
        }
        if (!temp.renameTo(target)) {
            // Rename can fail across filesystems on some devices;
            // fall back to a direct copy so the index still lands.
            try {
                temp.copyTo(target, overwrite = true)
            } catch (e: Exception) {
                // A copy that dies mid-write (disk full) leaves a
                // truncated target behind; drop it rather than let
                // a partial file pose as the index — load() reads
                // a missing file as "no index", and the index is
                // always rebuildable (Jules PR #23 P3).
                runCatching { target.delete() }
                throw e
            } finally {
                // Either way the temp file goes: a stale .tmp must
                // not linger until the next save/delete.
                runCatching { temp.delete() }
            }
        }
    }

    /** Drops the account's whole index (sign-out, full rebuild).
     *  The reconcile stamp goes with it: a stamp that outlived
     *  its index would let the stamp gate skip a pass the index
     *  still needs. */
    fun delete(accountId: String) {
        runCatching { fileFor(accountId).delete() }
        runCatching { File(rootDir, fileFor(accountId).name + ".tmp").delete() }
        runCatching { stampFileFor(accountId).delete() }
    }

    /** Performance chapter 2 (F3): the library stamp the last
     *  COMPLETED reconcile pass answered for, persisted next to
     *  the index so the stamp gate survives process death. Any
     *  unreadable or malformed file reads as "no stamp" — the
     *  gate then simply does not fire and a full pass runs,
     *  which is always the safe direction. */
    fun loadStamp(accountId: String): LibraryStampRow? {
        val file = stampFileFor(accountId)
        if (!file.isFile) return null
        return try {
            val parts = file.readText().trim().split(',')
            if (parts.size != 5) return null
            LibraryStampRow(
                noteCount = parts[0].toLong(),
                maxUpdatedAt = parts[1].toLong(),
                sumUpdatedAt = parts[2].toLong(),
                sumTitleLength = parts[3].toLong(),
                sumPlainTextLength = parts[4].toLong(),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun saveStamp(accountId: String, stamp: LibraryStampRow) {
        runCatching {
            rootDir.mkdirs()
            val target = stampFileFor(accountId)
            val temp = File(rootDir, target.name + ".tmp")
            temp.writeText(
                listOf(
                    stamp.noteCount, stamp.maxUpdatedAt, stamp.sumUpdatedAt,
                    stamp.sumTitleLength, stamp.sumPlainTextLength,
                ).joinToString(","),
            )
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                runCatching { temp.delete() }
            }
        }
    }

    fun stampFileFor(accountId: String): File {
        val safe = accountId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(rootDir, "semantic-$safe.stamp")
    }

    fun fileFor(accountId: String): File {
        val safe = accountId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(rootDir, "semantic-$safe.idx")
    }

    private companion object {
        const val MAGIC = 0x4E494458 // "NIDX"

        /** Format 2 (G10): every chunk carries its own text
         *  fingerprint alongside its vector. A version-1 file
         *  has no per-chunk fingerprints to validate against,
         *  so it reads as "no index" under the store's standing
         *  contract and the first reconcile rebuilds it. */
        const val VERSION = 2
        const val MAX_NOTES = 100_000
        const val MAX_CHUNKS_PER_NOTE = 10_000
    }
}
