package app.nota.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Nota Room schema v1 (Phase 0 design).
 *
 * Compatibility contract (spec §4): a note's body lives in [NoteEntity.contentHtml] as
 * HTML-canonical text — the exact form Noma 1.x stores (Tiptap HTML) and the form the
 * backup ZIP carries. Editors convert HTML <-> document model at the edges only.
 * [NoteEntity.plainText] is a derived, searchable projection kept in sync by the
 * repository; it is never the source of truth.
 */

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** ARGB colour int for the folder cover/chip, null = default. */
    val colorArgb: Int? = null,
    val sortOrder: Int = 0,
    /** Reserved for future nesting; 1.x folders are flat. */
    val parentId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("folderId"),
        Index("updatedAt"),
        Index("dailyDate"),
    ],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val folderId: String? = null,
    val title: String = "",
    /** HTML-canonical note body (spec §3/§4). When [locked] is true this holds the
     *  plaintext-free placeholder and the real body lives in [lockEnvelope]. */
    val contentHtml: String = "",
    /** Derived plain-text projection of [contentHtml] for snippets + FTS. */
    val plainText: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val favourite: Boolean = false,
    val archived: Boolean = false,
    /** Epoch millis when trashed; null = not trashed. */
    val trashedAt: Long? = null,
    /** ARGB colour int for the note card accent, null = default paper. */
    val colorArgb: Int? = null,
    /** "yyyy-MM-dd" for daily notes; null for ordinary notes. */
    val dailyDate: String? = null,
    /** Per-note lock (spec §4.3). Lock crypto is byte-compatible with 1.x:
     *  PBKDF2 + AES-256-GCM; [lockEnvelope] stores the wrapped payload. */
    val locked: Boolean = false,
    val lockEnvelope: String? = null,
    /** Monotonic content hash used by the sync merge (spec §4.4) — set by engines in Phase 3. */
    val contentHash: String = "",
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Int? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "note_tags",
    primaryKeys = ["noteId", "tagId"],
    foreignKeys = [
        ForeignKey(NoteEntity::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class NoteTagCrossRef(
    val noteId: String,
    val tagId: String,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(NoteEntity::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("noteId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    /** File name as packed in the backup ZIP (spec §4.1). */
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Path relative to the app's attachment storage root. */
    val localPath: String,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(NoteEntity::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("noteId"), Index("triggerAt")],
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val triggerAt: Long,
    /** RRULE-style repeat descriptor; null = one-shot. Interpreted in Phase 5. */
    val repeatRule: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long,
)

/** Local-only version history (never synced/backed up, spec §7). */
@Entity(
    tableName = "note_versions",
    foreignKeys = [
        ForeignKey(NoteEntity::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("noteId"), Index("createdAt")],
)
data class NoteVersionEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val title: String,
    val contentHtml: String,
    val createdAt: Long,
)

/**
 * FTS keyword index over note title + plain text. Standalone (not content-linked)
 * so the repository controls sync explicitly after every note write; [noteId] is the
 * join key back to [NoteEntity]. Rowid is auto-generated.
 *
 * Room 2.8 removed the `@Fts5` annotation (verified against room-common 2.8.5 —
 * only Fts3/Fts4 remain); `@Fts4` is the supported FTS entity API and the MATCH
 * query surface Nota uses is identical.
 */
@Fts4
@Entity(tableName = "notes_fts")
data class NoteFtsEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowid: Int = 0,
    val noteId: String,
    val title: String,
    val plainText: String,
)

/** A named, re-runnable search (Phase 1b). Saved from the Search screen. */
@Entity(tableName = "saved_searches")
data class SavedSearchEntity(
    @PrimaryKey val id: String,
    val name: String,
    val query: String,
    val createdAt: Long,
)
