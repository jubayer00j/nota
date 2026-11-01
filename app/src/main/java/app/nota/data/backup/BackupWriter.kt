package app.nota.data.backup

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The plaintext ZIP writer (Phase 2c) — entry set, entry order, field
 * order and pretty-printing match 1.x `buildBackupZip` exactly:
 * manifest, folders, tags, reminders, settings, sync, notes/<id>.json,
 * then (only when attachments exist) attachments/index.json plus, per
 * attachment, the raw entry and the authoritative `<id>.json` record.
 *
 * Streaming contract: the ZIP goes through a [ZipOutputStream] over the
 * caller's stream; small JSONs are built as strings, but attachment
 * payloads NEVER are — the raw entry is a straight file copy and the
 * `<id>.json` data URL is written as prefix + streaming-Base64(file) +
 * suffix directly into the open zip entry. For an encrypted attachment
 * the file holds the envelope string: the raw entry copies it verbatim
 * (name suffixed `.encrypted`) and the `<id>.json` embeds it as a JSON
 * string (escaping streamed per byte).
 */
class BackupWriter(
    /** Resolves an attachment's on-disk file (raw bytes, or the envelope
     *  text bytes while its note is locked). */
    private val attachmentFile: (BxAttachmentMeta) -> File?,
) {
    data class ExportData(
        val manifest: BackupManifest,
        val folders: List<BxFolder>,
        val tags: List<BxTag>,
        val reminders: List<BxReminder>,
        val syncSyncedAtIso: String,
        val notes: List<BxNote>,
        val attachments: List<BxAttachmentMeta>,
    )

    fun write(data: ExportData, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            writeTextEntry(zip, "manifest.json", JsonPretty.stringify(manifestJson(data.manifest)))
            writeTextEntry(zip, "folders.json", JsonPretty.stringify(jArr(data.folders.map { folderJson(it) })))
            writeTextEntry(zip, "tags.json", JsonPretty.stringify(jArr(data.tags.map { tagJson(it) })))
            writeTextEntry(zip, "reminders.json", JsonPretty.stringify(jArr(data.reminders.map { reminderJson(it) })))
            // Nota decision (see PR description): settings.json is the
            // JSON literal `null` — exactly what 1.x writes when it has
            // no settings row — and settings are never imported. App Lock
            // credentials are device-local by design on both sides.
            writeTextEntry(zip, "settings.json", "null")
            writeTextEntry(
                zip, "sync.json",
                JsonPretty.stringify(
                    jObj(
                        "deviceId" to jStr(data.manifest.deviceId),
                        "syncedAt" to jStr(data.syncSyncedAtIso),
                        "tombstones" to jArr(emptyList()),
                    ),
                ),
            )
            for (note in data.notes) {
                writeTextEntry(zip, "notes/${note.id}.json", JsonPretty.stringify(noteJson(note)))
            }
            if (data.attachments.isNotEmpty()) {
                writeTextEntry(
                    zip, "attachments/index.json",
                    JsonPretty.stringify(jArr(data.attachments.map { attachmentIndexJson(it) })),
                )
                for (attachment in data.attachments) {
                    writeAttachmentEntries(zip, attachment)
                }
            }
        }
    }

    private fun writeAttachmentEntries(zip: ZipOutputStream, attachment: BxAttachmentMeta) {
        val file = attachmentFile(attachment)
        // Raw entry: straight file copy (plaintext) or the envelope
        // string verbatim (encrypted, name + ".encrypted").
        val rawName = "attachments/${attachment.id}-${attachment.name}" +
            if (attachment.encrypted) ".encrypted" else ""
        zip.putNextEntry(ZipEntry(rawName))
        if (file != null && file.exists()) {
            file.inputStream().buffered().use { it.copyTo(zip) }
        }
        zip.closeEntry()

        // The authoritative `<id>.json`: full meta INCLUDING data. The
        // meta head/tail are small strings; the data payload streams.
        zip.putNextEntry(ZipEntry("attachments/${attachment.id}.json"))
        zip.write(attachmentMetaHead(attachment).toByteArray(Charsets.UTF_8))
        if (file != null && file.exists()) {
            if (attachment.encrypted) {
                writeJsonEscapedStream(zip, file)
            } else {
                zip.write(
                    ("data:" + attachment.mimeType + ";base64,").toByteArray(Charsets.UTF_8),
                )
                val encoder = StreamingBase64Encoder(zip)
                file.inputStream().buffered().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        encoder.write(buf, 0, n)
                    }
                }
                encoder.finish()
            }
        } else {
            // Row without a file (reconcile would drop it; the writer
            // still emits a well-formed empty payload rather than
            // producing a corrupt record).
            if (!attachment.encrypted) {
                zip.write(("data:" + attachment.mimeType + ";base64,").toByteArray(Charsets.UTF_8))
            }
        }
        zip.write(attachmentMetaTail(attachment).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** Streams [file] into the open entry as a JSON string body: bytes
     *  are envelope ASCII; `"` and `\` are escaped per byte. */
    private fun writeJsonEscapedStream(zip: ZipOutputStream, file: File) {
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(8192)
            val outBuf = java.io.ByteArrayOutputStream(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                outBuf.reset()
                for (i in 0 until n) {
                    val b = buf[i]
                    if (b == '"'.code.toByte() || b == '\\'.code.toByte()) outBuf.write('\\'.code)
                    outBuf.write(b.toInt())
                }
                zip.write(outBuf.toByteArray())
            }
        }
    }

    /** `{\n  "id": …, …,\n  "createdAt": N,\n  "data": "` — the part of the
     *  `<id>.json` object before the streamed data value. */
    private fun attachmentMetaHead(a: BxAttachmentMeta): String = buildString {
        append("{\n")
        append("  \"id\": \"").append(JsonPretty.escape(a.id)).append("\",\n")
        append("  \"noteId\": \"").append(JsonPretty.escape(a.noteId)).append("\",\n")
        append("  \"name\": \"").append(JsonPretty.escape(a.name)).append("\",\n")
        append("  \"mimeType\": \"").append(JsonPretty.escape(a.mimeType)).append("\",\n")
        append("  \"size\": ").append(a.size).append(",\n")
        append("  \"createdAt\": ").append(a.createdAt).append(",\n")
        if (a.updatedAt != null) append("  \"updatedAt\": ").append(a.updatedAt).append(",\n")
        append("  \"data\": \"")
    }

    private fun attachmentMetaTail(a: BxAttachmentMeta): String =
        if (a.encrypted) "\",\n  \"encrypted\": true\n}" else "\"\n}"

    private fun writeTextEntry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    companion object {
        fun manifestJson(m: BackupManifest): JObj = jObj(
            "backupFormatVersion" to jNum(m.backupFormatVersion),
            "ownerId" to jStr(m.ownerId),
            "libraryId" to jStr(m.libraryId),
            "nomaVersion" to jStr(m.nomaVersion),
            "createdAt" to jStr(m.createdAt),
            "noteCount" to jNum(m.noteCount),
            "folderCount" to jNum(m.folderCount),
            "tagCount" to jNum(m.tagCount),
            "attachmentCount" to jNum(m.attachmentCount),
            "reminderCount" to jNum(m.reminderCount),
            "deviceId" to jStr(m.deviceId),
        )

        fun folderJson(f: BxFolder): JObj = jObj(
            "id" to jStr(f.id),
            "name" to jStr(f.name),
            "parentId" to jStr(f.parentId),
            "createdAt" to jNum(f.createdAt),
            "updatedAt" to jNum(f.updatedAt),
        )

        fun tagJson(t: BxTag): JObj {
            val fields = mutableListOf<Pair<String, JVal>>(
                "id" to jStr(t.id),
                "name" to jStr(t.name),
                "createdAt" to jNum(t.createdAt),
            )
            if (t.updatedAt != null) fields += "updatedAt" to jNum(t.updatedAt)
            return JObj(fields)
        }

        fun reminderJson(r: BxReminder): JObj = jObj(
            "id" to jStr(r.id),
            "noteId" to jStr(r.noteId),
            "scheduledAt" to jNum(r.scheduledAt),
            "status" to jStr(r.status),
            "createdAt" to jNum(r.createdAt),
            "updatedAt" to jNum(r.updatedAt),
        )

        fun noteJson(n: BxNote): JObj {
            val fields = mutableListOf<Pair<String, JVal>>(
                "id" to jStr(n.id),
                "title" to jStr(n.title),
                "content" to jStr(n.content),
                "contentFormat" to jStr("tiptap-html"),
                "createdAt" to jNum(n.createdAt),
                "updatedAt" to jNum(n.updatedAt),
                "folderId" to jStr(n.folderId),
                "tagIds" to jArr(n.tagIds.map { jStr(it) }),
                "pinned" to jBool(n.pinned),
                "favorite" to jBool(n.favorite),
                "archived" to jBool(n.archived),
                "deleted" to jBool(n.deleted),
                "deletedAt" to (n.deletedAt?.let { jNum(it) } ?: JNull),
                "reminderAt" to (n.reminderAt?.let { jNum(it) } ?: JNull),
                "wordCount" to jNum(n.wordCount),
            )
            if (n.dailyDate != null) fields += "dailyDate" to jStr(n.dailyDate)
            if (n.lockJson != null) {
                // The lock record is embedded VERBATIM (already JSON).
                fields += "lock" to RawJson(n.lockJson)
            }
            if (n.isTemplate) {
                fields += "isTemplate" to jBool(true)
                if (n.dailyDefault != null) fields += "dailyDefault" to jBool(n.dailyDefault)
            }
            return JObj(fields)
        }

        fun attachmentIndexJson(a: BxAttachmentMeta): JObj {
            val fields = mutableListOf<Pair<String, JVal>>(
                "id" to jStr(a.id),
                "noteId" to jStr(a.noteId),
                "name" to jStr(a.name),
                "mimeType" to jStr(a.mimeType),
                "size" to jNum(a.size),
                "createdAt" to jNum(a.createdAt),
            )
            if (a.updatedAt != null) fields += "updatedAt" to jNum(a.updatedAt)
            if (a.encrypted) fields += "encrypted" to jBool(true)
            return JObj(fields)
        }
    }
}

/** A pre-rendered JSON fragment embedded as a value (the verbatim lock
 *  record). Rendered by [JsonPretty] as-is. */
data class RawJson(val text: String) : JVal
