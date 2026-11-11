package app.nota.domain

import app.nota.data.db.NoteListRow

/** Smart-folder keys (Phase 1b, spec §12 note-list richness). */
enum class SmartFolderKey(val label: String) {
    UNTAGGED("Untagged"),
    LAST_7_DAYS("Last 7 days"),
    HAS_ATTACHMENTS("Has attachments"),
    FAVOURITES("Favourites"),
}

/**
 * Pure smart-folder predicates over (note, tagIds, attachmentCount, now).
 * ALL predicates exclude locked, trashed and archived notes — a smart
 * folder is a view over the active library, never a back door into
 * locked or discarded content.
 */
object SmartFolders {
    const val SEVEN_DAYS_MS: Long = 7L * 24 * 60 * 60 * 1000

    fun matches(
        key: SmartFolderKey,
        note: NoteListRow,
        tagIds: Set<String>,
        attachmentCount: Int,
        now: Long,
    ): Boolean {
        if (note.locked || note.trashedAt != null || note.archived) return false
        return when (key) {
            SmartFolderKey.UNTAGGED -> tagIds.isEmpty()
            SmartFolderKey.LAST_7_DAYS -> note.updatedAt >= now - SEVEN_DAYS_MS
            SmartFolderKey.HAS_ATTACHMENTS -> attachmentCount > 0
            SmartFolderKey.FAVOURITES -> note.favourite
        }
    }
}
