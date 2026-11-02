package app.nota.data.sync

import app.nota.data.backup.BxAttachmentMeta
import app.nota.data.backup.BxFolder
import app.nota.data.backup.BxNote
import app.nota.data.backup.BxReminder
import app.nota.data.backup.BxTag
import app.nota.data.backup.BxTombstone
import java.io.File
import java.security.MessageDigest

/**
 * Per-entity content hashes — the three-way merge's change detector
 * (Phase 3c, mirroring 1.x's design). One canonical string per
 * entity, FNV-1a-64 over its UTF-8 bytes; identical on every device
 * because the inputs are the archive (Bx) models, never local-only
 * state. Attachments fold in a SHA-256 of the payload bytes, so a
 * binary change under the same metadata still registers.
 */
object EntityHash {

    const val ENTITY_NOTE = "note"
    const val ENTITY_FOLDER = "folder"
    const val ENTITY_TAG = "tag"
    const val ENTITY_REMINDER = "reminder"
    const val ENTITY_ATTACHMENT = "attachment"

    fun key(entity: String, id: String): String = "$entity:$id"

    /** 64-bit FNV-1a, hex — small, stable, dependency-free (the same
     *  role 1.x's fnv1a plays; NOT a security primitive). */
    fun fnv1a(text: String): String {
        var hash = -3750763034362895579L // 14695981039346656037 as signed
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 1099511628211L
        }
        return java.lang.Long.toHexString(hash).padStart(16, '0')
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fields(vararg parts: Any?): String =
        parts.joinToString("\u0001") { it?.toString() ?: "\u0000" }

    fun noteHash(note: BxNote): String = fnv1a(
        fields(
            ENTITY_NOTE, note.id, note.title, note.content, note.folderId,
            note.createdAt, note.updatedAt, note.pinned, note.favorite,
            note.archived, note.deleted, note.deletedAt, note.reminderAt,
            note.dailyDate, note.lockJson, note.isTemplate, note.dailyDefault,
            note.tagIds.sorted().joinToString(","),
        ),
    )

    fun folderHash(folder: BxFolder): String = fnv1a(
        fields(ENTITY_FOLDER, folder.id, folder.name, folder.parentId, folder.createdAt, folder.updatedAt),
    )

    fun tagHash(tag: BxTag): String = fnv1a(
        fields(ENTITY_TAG, tag.id, tag.name, tag.createdAt, tag.updatedAt),
    )

    fun reminderHash(reminder: BxReminder): String = fnv1a(
        fields(
            ENTITY_REMINDER, reminder.id, reminder.noteId, reminder.scheduledAt,
            reminder.status, reminder.createdAt, reminder.updatedAt,
        ),
    )

    fun attachmentHash(meta: BxAttachmentMeta, contentSha256: String): String = fnv1a(
        fields(
            ENTITY_ATTACHMENT, meta.id, meta.noteId, meta.name, meta.mimeType,
            meta.size, meta.createdAt, meta.updatedAt, meta.encrypted, contentSha256,
        ),
    )

    /** A single fingerprint of a whole snapshot's hash map +
     *  tombstones — the "did anything change?" check that lets a
     *  sync cycle skip work (and lets a device recognise its own
     *  last push echoing back). */
    fun signature(hashes: Map<String, String>, tombstones: List<BxTombstone>): String {
        val lines = hashes.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" } +
            tombstones.sortedWith(compareBy({ it.entity }, { it.id }))
                .map { "tombstone:${it.entity}:${it.id}:${it.deletedAt}" }
        return fnv1a(lines.joinToString("\n"))
    }
}
