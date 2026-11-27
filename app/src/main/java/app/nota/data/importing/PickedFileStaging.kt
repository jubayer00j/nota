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
 * - The per-file cap is type-specific (Codex R40-F1):
 *   [maxStagedBytesFor] gives a ZIP the full [MAX_STAGED_PICK_BYTES]
 *   (544 MiB — a stored-entry ZIP can legitimately approach 512 MiB
 *   compressed, so it needs that headroom), while a Markdown/text
 *   file or image is capped at the engine's single-file limit
 *   ([ImportCaps.MAX_IMPORT_FILE_BYTES], 100 MiB). Before this fix
 *   every pick got the ZIP-sized cap, so a 100–544 MiB text or image
 *   pick was copied into cache in full and only then rejected by the
 *   engine — hundreds of MiB staged for nothing on a low-storage
 *   device. The cap is decided from the pick's name before any copy.
 * - [copyCapped] is the streaming copy that enforces the cap and
 *   deletes the partial target when it trips — or when the read
 *   itself fails (Codex R51-F1): a provider that throws mid-stream
 *   used to leave the partial file in cache, uncharged to the
 *   selection budget.
 * - [StagingBudget] bounds a whole multi-file selection (Codex
 *   R6-F2): the per-file cap alone lets several large picks stage
 *   gigabytes into one cache directory before parsing starts.
 *   [stageOne] stages one pick under both caps at once. The budget's
 *   total stays one ZIP cap's worth (544 MiB) — it bounds total
 *   cache use per selection, and a mixed selection can legitimately
 *   hold a 544 MiB ZIP; the per-file fix already removes the
 *   pathological single-file waste.
 */
object PickedFileStaging {

    val MAX_STAGED_PICK_BYTES: Long = BackupQuota.MAX_DECRYPTED_ZIP_BYTES

    /** Per-file staging cap for one pick, by type (Codex R40-F1):
     *  a ZIP keeps the full 544 MiB headroom; a Markdown/text file or
     *  image can never exceed the engine's 100 MiB single-file cap, so
     *  it is refused at 100 MiB instead of being copied in full and
     *  rejected afterwards. Decided from the pick's name before any
     *  copy — callers pass the display name they already checked
     *  with [isSupportedPick]. */
    fun maxStagedBytesFor(name: String): Long =
        if (name.lowercase().endsWith(".zip")) MAX_STAGED_PICK_BYTES
        else ImportCaps.MAX_IMPORT_FILE_BYTES

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
     *  The per-file cap is type-specific ([maxStagedBytesFor]) —
     *  a ZIP gets the 544 MiB headroom, anything else 100 MiB —
     *  so a huge text or image pick is refused after copying at
     *  most 100 MiB instead of being staged in full first.
     *  A rejected copy leaves no file behind, and the input is
     *  always closed. */
    fun stageOne(input: InputStream, target: File, budget: StagingBudget, name: String): StagedPick {
        val remaining = budget.remaining()
        if (remaining <= 0) {
            input.close()
            return StagedPick.OverBudget
        }
        val perFileCap = maxStagedBytesFor(name)
        val cap = minOf(perFileCap, remaining)
        if (!copyCapped(input, target, cap)) {
            // The cap passed was the binding one: the per-file cap
            // when the budget still had room, the budget otherwise.
            return if (remaining < perFileCap) StagedPick.OverBudget else StagedPick.TooBig
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
     *  the cap. A read or write failure also leaves no file behind:
     *  the exception still propagates (the callers record the pick
     *  as skipped), but the partial target is deleted first — a
     *  failing pick must never leave up-to-the-cap bytes in cache,
     *  uncharged to the selection budget (Codex R51-F1). */
    fun copyCapped(input: InputStream, target: File, maxBytes: Long = MAX_STAGED_PICK_BYTES): Boolean {
        var copied = 0L
        var ok = true
        try {
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
        } catch (e: Exception) {
            // The exception still propagates to the caller — delete
            // first, so no partial file survives a failed copy.
            // CancellationException is an Exception too: deleting
            // then rethrowing keeps cancellation semantics intact.
            target.delete()
            throw e
        }
        if (!ok) target.delete()
        return ok
    }
}

