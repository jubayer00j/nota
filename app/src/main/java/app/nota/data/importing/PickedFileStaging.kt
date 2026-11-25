package app.nota.data.importing

import app.nota.data.backup.BackupQuota
import java.io.File
import java.io.InputStream

/**
 * Guards for staging files picked through SAF before the import engine
 * ever sees them. The pickers accept any MIME type, so without a guard
 * a huge unsupported file — or a giant compressed ZIP — was copied into
 * cache in full before any type or size check ran (Codex R2).
 *
 * - [isSupportedPick] is the extension gate, checked BEFORE any copy.
 *   The set mirrors what the engine accepts: ZIPs, Markdown/text, and
 *   the image types the Markdown image resolver accepts as fodder.
 *   Anything else is reported as a skipped file, never copied.
 * - [MAX_STAGED_PICK_BYTES] bounds the copy itself. A staged file is
 *   a compressed ZIP (uncompressed content ≤ 512 MiB, so a
 *   stored-entry ZIP can legitimately approach that size compressed)
 *   or a single file the engine caps at 100 MiB — so the cap reuses
 *   [BackupQuota.MAX_DECRYPTED_ZIP_BYTES] (544 MiB), the same headroom
 *   the backup pipeline allows for a decrypted ZIP.
 * - [copyCapped] is the streaming copy that enforces the cap and
 *   deletes the partial target when it trips.
 * - [StagingBudget] bounds a whole multi-file selection (Codex
 *   R6-F2): the per-file cap alone lets several large picks stage
 *   gigabytes into one cache directory before parsing starts.
 *   [stageOne] stages one pick under both caps at once.
 */
object PickedFileStaging {

    val MAX_STAGED_PICK_BYTES: Long = BackupQuota.MAX_DECRYPTED_ZIP_BYTES

    /** Total bytes one multi-file selection may stage — one
     *  per-file cap's worth across all picks. A selection of several
     *  large files is the pathological case this bounds; ordinary
     *  Markdown/text selections are kilobytes. */
    val MAX_TOTAL_STAGED_BYTES: Long = MAX_STAGED_PICK_BYTES

    /** Running total for one multi-file staging pass (Codex R6-F2):
     *  [copyCapped] bounds each file, but several large picks in one
     *  selection could still stage gigabytes into cache. */
    class StagingBudget(private val totalBytes: Long = MAX_TOTAL_STAGED_BYTES) {
        private var usedBytes = 0L

        fun remaining(): Long = (totalBytes - usedBytes).coerceAtLeast(0)

        fun record(bytes: Long) {
            usedBytes += bytes
        }
    }

    sealed interface StagedPick {
        /** Staged, holding [bytes] of the selection budget. */
        data class Ok(val bytes: Long) : StagedPick

        /** The file alone exceeds the per-file cap. */
        object TooBig : StagedPick

        /** The selection budget is exhausted (or this file would
         *  exceed what remains of it). */
        object OverBudget : StagedPick
    }

    /** Stages one picked file under the per-file cap AND the
     *  selection [budget]: copies at most the smaller of the two.
     *  A rejected copy leaves no file behind, and the input is
     *  always closed. */
    fun stageOne(input: InputStream, target: File, budget: StagingBudget): StagedPick {
        val remaining = budget.remaining()
        if (remaining <= 0) {
            input.close()
            return StagedPick.OverBudget
        }
        val cap = minOf(MAX_STAGED_PICK_BYTES, remaining)
        if (!copyCapped(input, target, cap)) {
            // The cap passed was the binding one: the per-file cap
            // when the budget still had room, the budget otherwise.
            return if (remaining < MAX_STAGED_PICK_BYTES) StagedPick.OverBudget else StagedPick.TooBig
        }
        val bytes = target.length()
        budget.record(bytes)
        return StagedPick.Ok(bytes)
    }

    fun isSupportedPick(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".zip") ||
            lower.endsWith(".md") ||
            lower.endsWith(".markdown") ||
            lower.endsWith(".txt") ||
            importMimeFromName(name) != null
    }

    /** Copies [input] into [target], enforcing [maxBytes]. Returns
     *  false — and leaves no file behind — when the stream exceeds
     *  the cap. */
    fun copyCapped(input: InputStream, target: File, maxBytes: Long = MAX_STAGED_PICK_BYTES): Boolean {
        var copied = 0L
        var ok = true
        input.use { ins ->
            target.outputStream().buffered().use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = ins.read(buffer)
                    if (n < 0) break
                    copied += n
                    if (copied > maxBytes) {
                        ok = false
                        break
                    }
                    out.write(buffer, 0, n)
                }
            }
        }
        if (!ok) target.delete()
        return ok
    }
}
