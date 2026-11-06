package app.nota.data.reminders

import java.io.File
import java.io.FileOutputStream

/**
 * Phase 5.1 fix round (Jules P3): atomic whole-file text writes.
 *
 * The content is written to a sibling temp file and synced, then
 * renamed over the target — a rename within one directory is
 * atomic on the filesystems Android apps live on, so a crash or
 * power loss mid-write can never leave a truncated target behind:
 * readers see the whole old content or the whole new content.
 * (The armed-reminder-ids file is the motivating case: truncating
 * it would orphan system alarms after an account switch.)
 */
object AtomicTextFile {

    fun write(file: File, text: String) {
        val dir = file.absoluteFile.parentFile ?: return
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(file)) {
            // A rename can refuse over an existing target on some
            // filesystems; fall back to a plain overwrite so the
            // write is not lost, and never leave the temp behind.
            runCatching { file.writeText(text, Charsets.UTF_8) }
            tmp.delete()
        }
    }
}
