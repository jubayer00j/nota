package app.nota.data.backup

/**
 * Backup models (Phase 2c) — the 1.x backup shapes (shipped `backup.ts` /
 * `types.ts`), used for BOTH directions so the writer and reader can never
 * drift apart. Field names here are Kotlin-side; the exact wire names,
 * field order and pretty-printing live in [BackupWriter]'s serialisers.
 */

class BackupError(message: String) : Exception(message)

/** Mirror of 1.x `BackupUnlockError`: ONE clean typed failure when a
 *  passphrase cannot open a NOMAENC1 archive. Raised BEFORE any database
 *  write, always. */
class BackupUnlockError(message: String) : Exception(message)

/** Mirror of 1.x `BackupKeyNeededError`: the archive is encrypted and no
 *  passphrase was supplied yet (a prompt, not a failure). */
class BackupKeyNeededError(message: String) : Exception(message)

enum class RestoreMode { MERGE, REPLACE }

data class MergeCounts(
    val added: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    /** Entities the restore refused outright (PR #55, Argon F-02:
     *  crafted ids that fail [BackupRepository.isSafeId]). Counted,
     *  never silent — the restore result surfaces them. */
    val skipped: Int = 0,
) {
    operator fun plus(other: MergeCounts): MergeCounts =
        MergeCounts(
            added + other.added,
            updated + other.updated,
            deleted + other.deleted,
            skipped + other.skipped,
        )
}

data class BackupManifest(
    val backupFormatVersion: Int,
    val ownerId: String?,
    val libraryId: String?,
    val nomaVersion: String?,
    val createdAt: String?,
    val noteCount: Int,
    val folderCount: Int,
    val tagCount: Int,
    val attachmentCount: Int,
    val reminderCount: Int,
    val deviceId: String?,
)

data class BxFolder(val id: String, val name: String, val parentId: String?, val createdAt: Long, val updatedAt: Long)

data class BxTag(val id: String, val name: String, val createdAt: Long, val updatedAt: Long?)

data class BxReminder(
    val id: String,
    val noteId: String,
    val scheduledAt: Long,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class BxTombstone(val entity: String, val id: String, val deletedAt: Long, val deviceId: String?)

/** One note as carried in the archive. [lockJson] is the 1.x lock record
 *  VERBATIM (raw JSON substring from the note file) when locked. */
data class BxNote(
    val id: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val folderId: String?,
    val tagIds: List<String>,
    val pinned: Boolean,
    val favorite: Boolean,
    val archived: Boolean,
    val deleted: Boolean,
    val deletedAt: Long?,
    val reminderAt: Long?,
    val wordCount: Int,
    val dailyDate: String?,
    val lockJson: String?,
    val isTemplate: Boolean,
    val dailyDefault: Boolean?,
)

data class BxAttachmentMeta(
    val id: String,
    val noteId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,
    val updatedAt: Long?,
    val encrypted: Boolean,
    /** Raw-entry file name suffix (`<name>` or `<name>.encrypted`) when the
     *  archive carried the raw entry — the storedName source on import. */
    val rawEntrySuffix: String? = null,
)
