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
 */
object PickedFileStaging {

    val MAX_STAGED_PICK_BYTES: Long = BackupQuota.MAX_DECRYPTED_ZIP_BYTES

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
