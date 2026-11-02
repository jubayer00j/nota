package app.nota.data.attachments

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** What picking a file should become when the result lands (Phase 3.6, G2). */
enum class PendingInsertMode {
    /** The picked item becomes a document block at the saved caret target. */
    INSERT_AT_CARET,

    /** The picked item is only imported into the note's attachments. */
    IMPORT_ONLY,
}

/** Which picker a pending insert belongs to (Phase 3.6, G2). */
enum class PendingInsertKind { IMAGE, FILE }

/**
 * A durable record of an in-flight "add from the file manager / photo
 * picker" operation (Phase 3.6, G2).
 *
 * Why this exists: the Phase 0.4.1 device recording showed a file
 * picked from the external file manager landing in the note, but the
 * user NOT returning to the note he was editing — a long black
 * transition ended at the Android home screen / the notes list. The
 * mechanism is in MainActivity + the pre-3.6 editor: with App Lock's
 * Lock-after = Immediately (Jubayer's setting), leaving for the
 * picker relocks the app; MainActivity swaps the whole nav shell for
 * the lock screen, so the [androidx.navigation.NavController] — and
 * with it the editor route, the editor composition and every
 * `remember` flag that knew an insert was pending — is destroyed.
 * After unlock a FRESH shell starts at the notes list. Process death
 * in the picker (a 2 GB J7 running a file manager) destroys the same
 * state even harder.
 *
 * This record is staged BEFORE a picker launches and lives in a file
 * under filesDir, so it survives both the lock swap and process
 * death. The nav shell routes back into [noteId] once (see
 * PendingInsertResumer); the editor consumes the record when the
 * picker result arrives and applies the insertion at the saved caret
 * target — [segmentIndex] into the editor's segment list, verified
 * against [segmentAnchor] (the target segment's plain-text prefix),
 * with [caretOffset] the caret's offset inside that segment.
 */
data class PendingInsertRecord(
    val id: String = UUID.randomUUID().toString(),
    val accountId: String,
    val noteId: String,
    val kind: PendingInsertKind,
    val mode: PendingInsertMode,
    val segmentIndex: Int = -1,
    val segmentAnchor: String = "",
    val caretOffset: Int = 0,
    val stagedAtMillis: Long = System.currentTimeMillis(),
) {
    fun toJson(): String = JSONObject()
        .put("id", id)
        .put("accountId", accountId)
        .put("noteId", noteId)
        .put("kind", kind.name)
        .put("mode", mode.name)
        .put("segmentIndex", segmentIndex)
        .put("segmentAnchor", segmentAnchor)
        .put("caretOffset", caretOffset)
        .put("stagedAtMillis", stagedAtMillis)
        .toString()

    companion object {
        fun parse(json: String): PendingInsertRecord? = try {
            val obj = JSONObject(json)
            PendingInsertRecord(
                id = obj.getString("id"),
                accountId = obj.getString("accountId"),
                noteId = obj.getString("noteId"),
                kind = PendingInsertKind.valueOf(obj.getString("kind")),
                mode = PendingInsertMode.valueOf(obj.getString("mode")),
                segmentIndex = obj.optInt("segmentIndex", -1),
                segmentAnchor = obj.optString("segmentAnchor", ""),
                caretOffset = obj.optInt("caretOffset", 0),
                stagedAtMillis = obj.optLong("stagedAtMillis", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * File-backed holder for the one in-flight picker insert (Phase 3.6,
 * G2). Follows the [app.nota.data.backup.PendingRestoreStore] pattern
 * — an app-scoped holder whose state outlives the app-lock gate's
 * composition swap — but persists to disk, because G2's failure mode
 * includes full process death, which an in-memory store cannot span.
 *
 * Writes are synchronous ([stage] / [clear] complete before returning)
 * so a record staged immediately before a picker launch is durable
 * even if the process dies inside the picker. At most one insert is
 * ever in flight (the editor is a single screen), so a single record
 * suffices; staging a new one replaces the old.
 */
class PendingInsertStore(private val dir: File) {

    constructor(context: android.content.Context) : this(context.filesDir)

    private val file = File(dir, FILE_NAME)

    private val _state = MutableStateFlow<PendingInsertRecord?>(readFromDisk())
    val state: StateFlow<PendingInsertRecord?> = _state.asStateFlow()

    fun peek(): PendingInsertRecord? = _state.value

    @Synchronized
    fun stage(record: PendingInsertRecord) {
        writeToDisk(record)
        _state.value = record
    }

    @Synchronized
    fun clear() {
        file.delete()
        _state.value = null
    }

    /** Clears only when the staged record is still [id] — a newer
     *  staged insert is never retired by a stale consumer. */
    @Synchronized
    fun clearIf(id: String) {
        if (_state.value?.id == id) clear()
    }

    private fun readFromDisk(): PendingInsertRecord? = try {
        if (file.isFile) PendingInsertRecord.parse(file.readText()) else null
    } catch (e: Exception) {
        null
    }

    private fun writeToDisk(record: PendingInsertRecord) {
        // Write-then-rename so a process death mid-write can never
        // leave a torn record behind: the reader sees the old record
        // or the new one, never half of either.
        val temp = File(dir, "$FILE_NAME.tmp")
        temp.writeText(record.toJson())
        if (!temp.renameTo(file)) {
            file.writeText(record.toJson())
            temp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "pending_insert.json"
    }
}
