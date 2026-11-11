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
    /**
     * Phase 2b: the per-note lock record JSON (1.x `NoteLock` shape,
     * stored verbatim — see NoteLockRecord). Null when the note is not
     * locked. While locked, [contentHtml] holds the AES-256-GCM envelope
     * and [plainText] is empty. [lockEnvelope] above stays UNUSED: it was
     * a Phase 0 placeholder and is neither repurposed nor dropped.
     */
    val lockRecordJson: String? = null,
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
    /** Phase 2b: true when the file on disk holds a per-note-lock
     *  AES-256-GCM envelope (of the attachment's data URL) instead of the
     *  raw payload bytes. */
    val encrypted: Boolean = false,
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
    /**
     * Phase 5.1: how the reminder alerts — "notification" (AlarmManager +
     * notification, the default) or "phone-alarm" (handed to the device
     * Clock app at set time; never armed locally). Values are the
     * app.nota.data.reminders.ReminderAlertType constants, mirroring 1.x's
     * alertType. Device-local by design: the backup crossover (BxReminder)
     * carries no alertType, so a restored reminder comes back as a
     * notification reminder (the shipped Phase 2c contract).
     *
     * The Phase 6 extras tone fields below follow the SAME
     * device-local discipline: a tone lives on THIS device (a
     * system ringtone URI or a user-picked file), so they are
     * per-reminder, are never written into the backup wire
     * format or a sync payload, and a restored reminder always
     * comes back with the system default tone and vibrate on.
     * [alarmToneMode] is one of the
     * app.nota.data.reminders.AlarmToneMode values: "system"
     * (the Clock app's default — no ringtone extra is sent),
     * "system-picker" (chosen from the system ringtone picker)
     * or "custom" (a picked audio file). [alarmToneUri] and
     * [alarmToneName] are only meaningful for the latter two.
     * The tone fields apply to phone-alarm reminders only; the
     * notification path ignores them.
     */
    val alertType: String = "notification",
    val alarmToneMode: String = "system",
    val alarmToneUri: String? = null,
    val alarmToneName: String? = null,
    val alarmVibrate: Boolean = true,
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
 * FTS keyword index over note title + plain text + tag names +
 * folder name. Standalone (not content-linked) so the repository
 * controls sync explicitly after every note write; [noteId] is
 * the join key back to [NoteEntity]. Rowid is auto-generated.
 *
 * Room 2.8 removed the `@Fts5` annotation (verified against room-common 2.8.5 —
 * only Fts3/Fts4 remain); `@Fts4` is the supported FTS entity API and the MATCH
 * query surface Nota uses is identical.
 *
 * [tagsText] / [folderName] (Search Quality Pass, v0.27.0,
 * schema v8) denormalise the note's tag names and folder name
 * into the index so both are searchable with 1.x's weights —
 * a MATCH with no column qualifier searches every column, so
 * the query surface is unchanged. The lock discipline carries
 * over from [plainText]: a locked note's body column stays
 * EMPTY (the sealed text is never indexed), while its title,
 * tag names and folder name stay searchable, exactly as 1.x
 * exposes them.
 */
@Fts4
@Entity(tableName = "notes_fts")
data class NoteFtsEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowid: Int = 0,
    val noteId: String,
    val title: String,
    val plainText: String,
    val tagsText: String = "",
    val folderName: String = "",
)

/** A named, re-runnable search (Phase 1b). Saved from the Search screen. */
@Entity(tableName = "saved_searches")
data class SavedSearchEntity(
    @PrimaryKey val id: String,
    val name: String,
    val query: String,
    val createdAt: Long,
)

/**
 * A resolved [[backlink]] edge (Phase 1c): [sourceId]'s body references the
 * note [targetId] by title. Edges are recomputed by the repository on every
 * note write from the note's plain text, so they can never go stale; title
 * renames simply re-resolve on the source's next save. Deleting either note
 * cascades its edges away.
 */
@Entity(
    tableName = "note_links",
    primaryKeys = ["sourceId", "targetId"],
    foreignKeys = [
        ForeignKey(NoteEntity::class, ["id"], ["sourceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(NoteEntity::class, ["id"], ["targetId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("targetId")],
)
data class NoteLinkEntity(
    val sourceId: String,
    val targetId: String,
)

/**
 * A sync tombstone (Phase 3c, schema v5): the record that an entity
 * was deleted on some device, retained 90 days so the deletion can
 * propagate to every device sharing the Drive archive before the
 * record itself is purged (1.x sync semantics). Written by the
 * repositories' hard-delete paths and carried inside the archive's
 * sync.json; stored per account because the table lives in the
 * per-account database.
 */
@Entity(tableName = "tombstones", primaryKeys = ["entity", "id"])
data class TombstoneEntity(
    /** "note" | "folder" | "tag" | "reminder" | "attachment". */
    val entity: String,
    val id: String,
    val deletedAt: Long,
    /** The device that made the deletion (diagnostics + parity with
     *  the archive shape); informational only. */
    val deviceId: String?,
)

/**
 * Key-value sync bookkeeping (Phase 3c, schema v5), per account
 * because it lives in the per-account database: the device id, the
 * last-sync state (remote fingerprint + per-entity base hashes the
 * three-way merge diffs against). Never leaves the device except
 * the device id, which rides inside the archive manifest/sync.json
 * exactly as 1.x's does.
 */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
