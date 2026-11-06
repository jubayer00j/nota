package app.nota.data.capture

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * The durable queue of staged quick captures (Phase 5.2).
 *
 * Follows [app.nota.data.attachments.PendingInsertStore]'s pattern —
 * a file under filesDir, write-then-rename, synchronous writes — but
 * holds a FIFO QUEUE rather than a single record: 1.x's share bridge
 * queues shares (several can arrive before the app is unlocked) and
 * drops the oldest past a cap of 25; a single slot would silently
 * lose all but the last share. The queue index is
 * `pending_captures/index.json`; each share's copied image bytes live
 * in `pending_captures/<recordId>/` beside it.
 *
 * A record is stamped with the account active when its door fired;
 * the nav shell delivers it only against that account's library and
 * retires records from other accounts (deleting their staged files),
 * so a capture can never leak across accounts.
 *
 * The staging directory is excluded from platform backup (see
 * res/xml/backup_rules.xml): it holds not-yet-delivered user content
 * and is transient by definition — a restored backup must never
 * resurrect captures into a different library.
 */
class PendingCaptureStore(private val rootDir: File) {

    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    private val indexFile = File(rootDir, INDEX_NAME)

    private val _records = MutableStateFlow<List<CaptureRecord>>(emptyList())
    val records: StateFlow<List<CaptureRecord>> = _records.asStateFlow()

    init {
        rootDir.mkdirs()
        val loaded = readFromDisk()
        _records.value = loaded
        sweepOrphanStaging(loaded)
    }

    fun head(): CaptureRecord? = _records.value.firstOrNull()

    /** Appends [record] to the queue, dropping (and un-staging) the
     *  oldest records past [MAX_PENDING] — 1.x's queue cap. */
    @Synchronized
    fun stage(record: CaptureRecord) {
        var next = _records.value + record
        while (next.size > MAX_PENDING) {
            val dropped = next.first()
            next = next.drop(1)
            deleteStaging(dropped)
        }
        writeToDisk(next)
        _records.value = next
    }

    /** Removes [id] from the queue AND deletes its staged files.
     *  Used for retirements (cross-account) and cap drops. */
    @Synchronized
    fun remove(id: String) {
        val record = _records.value.firstOrNull { it.id == id } ?: return
        val next = _records.value.filterNot { it.id == id }
        writeToDisk(next)
        _records.value = next
        deleteStaging(record)
    }

    /**
     * Removes [id] from the queue but LEAVES its staged files: the
     * delivery path takes the record, reads the staged images while
     * creating the note, and calls [deleteStaging] when done. (The
     * shell consumes BEFORE handling — 1.x's rule — so a capture can
     * never be delivered twice.)
     */
    @Synchronized
    fun take(id: String): CaptureRecord? {
        val record = _records.value.firstOrNull { it.id == id } ?: return null
        val next = _records.value.filterNot { it.id == id }
        writeToDisk(next)
        _records.value = next
        return record
    }

    /** The directory a record's staged image bytes live in (created
     *  on demand — intake copies into it BEFORE staging the record). */
    fun stagingDir(recordId: String): File =
        File(rootDir, recordId).apply { mkdirs() }

    fun stagedFile(record: CaptureRecord, image: CaptureImageRef): File =
        File(File(rootDir, record.id), image.fileName)

    /** Deletes a record's staged image directory, if any. */
    fun deleteStaging(record: CaptureRecord) {
        File(rootDir, record.id).deleteRecursively()
    }

    /** Deletes staged files for [recordId] without a record (an
     *  intake copy that failed before its record was staged). */
    fun deleteStaging(recordId: String) {
        File(rootDir, recordId).deleteRecursively()
    }

    private fun sweepOrphanStaging(live: List<CaptureRecord>) {
        val liveIds = live.map { it.id }.toSet()
        rootDir.listFiles()?.forEach { entry ->
            if (entry.isDirectory && entry.name !in liveIds) {
                entry.deleteRecursively()
            }
        }
    }

    private fun readFromDisk(): List<CaptureRecord> = try {
        if (!indexFile.isFile) {
            emptyList()
        } else {
            val array = JSONArray(indexFile.readText())
            val parsed = mutableListOf<CaptureRecord>()
            for (i in 0 until array.length()) {
                CaptureRecord.parse(array.getJSONObject(i).toString())?.let { parsed += it }
            }
            parsed
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeToDisk(records: List<CaptureRecord>) {
        rootDir.mkdirs()
        val array = JSONArray()
        for (record in records) {
            array.put(org.json.JSONObject(record.toJson()))
        }
        // Write-then-rename (PendingInsertStore discipline): a death
        // mid-write leaves the old queue or the new one, never a torn
        // half of either.
        val temp = File(rootDir, "$INDEX_NAME.tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(indexFile)) {
            indexFile.writeText(array.toString())
            temp.delete()
        }
    }

    companion object {
        const val DIR_NAME = "pending_captures"
        const val INDEX_NAME = "index.json"

        /** 1.x NomaSharePlugin MAX_PENDING_SHARES. */
        const val MAX_PENDING = 25
    }
}
