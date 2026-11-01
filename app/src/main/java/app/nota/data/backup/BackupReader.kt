package app.nota.data.backup

import java.io.File
import java.io.InputStream
import java.io.Reader
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * The backup reader (Phase 2c) — mirrors 1.x `readBackupArchive` /
 * `readBackupZipBytes`, streaming throughout:
 *
 * - The source file is sniffed by its first bytes: `PK` plaintext ZIPs
 *   are read in place; `NOMAENC1` files are stream-decrypted (after the
 *   passphrase unwrap — a wrong passphrase fails HERE, before anything
 *   else happens) into a temp ZIP that is read the same way.
 * - ZIP entries are consumed one at a time through a [ZipInputStream].
 *   Small JSONs are read as text per entry; attachment `<id>.json`
 *   records are parsed by a streaming JSON reader that writes the
 *   payload (decoded data URL bytes, or the envelope string verbatim)
 *   straight into a staging file — payload bytes never sit in memory.
 * - Validation errors mirror 1.x's messages verbatim.
 */
class BackupReader(private val workDir: File) {

    class StagedAttachment(
        val meta: BxAttachmentMeta,
        /** Staging file holding the payload: raw bytes for plaintext,
         *  the envelope string's bytes for encrypted attachments. */
        val stagedFile: File,
    )

    class Handle(
        val manifest: BackupManifest,
        val notes: List<BxNote>,
        val folders: List<BxFolder>,
        val tags: List<BxTag>,
        val reminders: List<BxReminder>,
        val attachments: List<StagedAttachment>,
        val tombstones: List<BxTombstone>,
        val deviceId: String?,
        val encrypted: Boolean,
        private val stagingDir: File,
        private val decryptedZip: File?,
    ) : AutoCloseable {
        var consumed: Boolean = false
        override fun close() {
            stagingDir.deleteRecursively()
            decryptedZip?.delete()
        }
    }

    fun read(source: File, passphrase: String?): Handle {
        val stagingDir = File(workDir, "backup-staging-${System.nanoTime()}")
        stagingDir.mkdirs()
        var decryptedZip: File? = null
        try {
            val encrypted: Boolean
            val zipFile: File
            source.inputStream().buffered().use { probe ->
                val prefix = ByteArray(8)
                var read = 0
                while (read < prefix.size) {
                    val n = probe.read(prefix, read, prefix.size - read)
                    if (n < 0) break
                    read += n
                }
                encrypted = BackupCrypto.isEncryptedPrefix(prefix.copyOf(read))
                if (!encrypted && read >= 2 && !(prefix[0] == 'P'.code.toByte() && prefix[1] == 'K'.code.toByte())) {
                    throw BackupError("That file isn't a readable ZIP archive.")
                }
            }
            if (encrypted) {
                val tmp = File(workDir, "backup-decrypted-${System.nanoTime()}.zip")
                decryptedZip = tmp
                try {
                    source.inputStream().buffered().use { input ->
                        // Skip the 8 magic bytes already identified.
                        input.skip(8)
                        val (bmk, envelope) = BackupCrypto.openEnvelope(input, passphrase)
                        try {
                            tmp.outputStream().buffered().use { out ->
                                BackupCrypto.decryptTo(envelope, bmk, out)
                            }
                        } finally {
                            bmk.fill(0)
                        }
                    }
                } catch (e: Exception) {
                    tmp.delete()
                    decryptedZip = null
                    throw e
                }
                zipFile = tmp
            } else {
                zipFile = source
            }
            return parseZip(zipFile, stagingDir, encrypted, decryptedZip).also {
                decryptedZip = null // ownership moved to the handle
            }
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            decryptedZip?.delete()
            throw e
        }
    }

    // ------------------------------------------------------------ ZIP parse

    private fun parseZip(
        zipFile: File,
        stagingDir: File,
        encrypted: Boolean,
        decryptedZip: File?,
    ): Handle {
        var manifest: BackupManifest? = null
        var folders: List<BxFolder> = emptyList()
        var tags: List<BxTag> = emptyList()
        var reminders: List<BxReminder> = emptyList()
        val notes = mutableListOf<BxNote>()
        val attachments = mutableListOf<StagedAttachment>()
        var tombstones: List<BxTombstone> = emptyList()
        var deviceId: String? = null

        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    try {
                        when {
                            name == "manifest.json" -> {
                                manifest = parseManifest(readEntryText(zip))
                                deviceId = manifest?.deviceId
                            }
                            name == "folders.json" ->
                                folders = parseFolders(readEntryTextStrict(zip, name))
                            name == "tags.json" ->
                                tags = parseTags(readEntryTextStrict(zip, name))
                            name == "reminders.json" ->
                                reminders = parseReminders(readEntryTextStrict(zip, name))
                            name == "settings.json" -> {
                                // Never imported (device-local credentials
                                // by design); the entry is drained only.
                                zip.readBytes()
                            }
                            name == "sync.json" -> {
                                val parsed = parseSyncTolerant(readEntryText(zip))
                                if (parsed != null) {
                                    tombstones = parsed.first
                                    if (parsed.second != null) deviceId = parsed.second
                                }
                            }
                            name.startsWith("notes/") && name.endsWith(".json") -> {
                                parseNoteOrNull(readEntryText(zip))?.let { notes += it }
                            }
                            name.startsWith("attachments/") && name.endsWith(".json") &&
                                !name.endsWith("index.json") -> {
                                parseAttachmentEntry(zip, stagingDir)?.let { attachments += it }
                            }
                            else -> zip.readBytes() // raw attachment entries etc: drained, map read below
                        }
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: BackupError) {
            throw e
        } catch (e: Exception) {
            throw BackupError("That file isn't a readable ZIP archive.")
        }

        val foundManifest = manifest
            ?: throw BackupError("This archive has no Noma manifest, so it can't be imported.")

        // Raw-entry stored names: a second lightweight pass over entry
        // NAMES only (no content reads) — the `<id>-<name>` suffix is
        // the storedName source for restored attachment files.
        val rawSuffixes = HashMap<String, String>()
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name.startsWith("attachments/") && !name.endsWith(".json")) {
                        val rest = name.removePrefix("attachments/")
                        val dash = rest.indexOf('-')
                        if (dash > 0) rawSuffixes[rest.substring(0, dash)] = rest.substring(dash + 1)
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            // Names pass is best-effort; imports fall back to synthesised names.
        }
        val withSuffixes = attachments.map { staged ->
            val suffix = rawSuffixes[staged.meta.id]
            if (suffix == null) staged
            else StagedAttachment(staged.meta.copy(rawEntrySuffix = suffix), staged.stagedFile)
        }

        return Handle(
            manifest = foundManifest,
            notes = notes,
            folders = folders,
            tags = tags,
            reminders = reminders,
            attachments = withSuffixes,
            tombstones = tombstones,
            deviceId = deviceId,
            encrypted = encrypted,
            stagingDir = stagingDir,
            decryptedZip = decryptedZip,
        )
    }

    private fun readEntryText(zip: ZipInputStream): String =
        String(zip.readBytes(), Charsets.UTF_8)

    /** Strict variant for the top-level arrays, mirroring 1.x `readJson`:
     *  a parse failure is the "looks corrupted" error. */
    private fun readEntryTextStrict(zip: ZipInputStream, path: String): String {
        val text = readEntryText(zip)
        try {
            JSONArray(text)
        } catch (e: Exception) {
            throw BackupError("$path is not valid JSON. This backup file looks corrupted.")
        }
        return text
    }

    private fun parseManifest(text: String): BackupManifest {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw BackupError("This archive has no Noma manifest, so it can't be imported.")
        }
        val versionValue = if (obj.has("backupFormatVersion")) obj.opt("backupFormatVersion") else null
        if (versionValue !is Number) {
            throw BackupError("This archive has no Noma manifest, so it can't be imported.")
        }
        val version = versionValue.toInt()
        if (version > 2) {
            throw BackupError(
                "This backup was made by a newer version of Noma (format v$version). " +
                    "Update Noma and try again.",
            )
        }
        return BackupManifest(
            backupFormatVersion = version,
            ownerId = obj.optStringOrNull("ownerId"),
            libraryId = obj.optStringOrNull("libraryId"),
            nomaVersion = obj.optStringOrNull("nomaVersion"),
            createdAt = obj.optStringOrNull("createdAt"),
            noteCount = obj.optInt("noteCount", 0),
            folderCount = obj.optInt("folderCount", 0),
            tagCount = obj.optInt("tagCount", 0),
            attachmentCount = obj.optInt("attachmentCount", 0),
            reminderCount = obj.optInt("reminderCount", 0),
            deviceId = obj.optStringOrNull("deviceId"),
        )
    }

    private fun parseFolders(text: String): List<BxFolder> {
        val array = JSONArray(text)
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            BxFolder(
                id = o.getString("id"),
                name = o.optString("name", ""),
                parentId = o.optStringOrNull("parentId"),
                createdAt = o.optLong("createdAt", 0),
                updatedAt = o.optLong("updatedAt", o.optLong("createdAt", 0)),
            )
        }
    }

    private fun parseTags(text: String): List<BxTag> {
        val array = JSONArray(text)
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            BxTag(
                id = o.getString("id"),
                name = o.optString("name", ""),
                createdAt = o.optLong("createdAt", 0),
                updatedAt = if (o.has("updatedAt") && !o.isNull("updatedAt")) o.optLong("updatedAt") else null,
            )
        }
    }

    private fun parseReminders(text: String): List<BxReminder> {
        val array = JSONArray(text)
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            BxReminder(
                id = o.getString("id"),
                noteId = o.optString("noteId", ""),
                scheduledAt = o.optLong("scheduledAt", 0),
                status = o.optString("status", "pending"),
                createdAt = o.optLong("createdAt", 0),
                updatedAt = o.optLong("updatedAt", o.optLong("createdAt", 0)),
            )
        }
    }

    /** sync.json is additive metadata: malformed content NEVER fails the
     *  import (1.x behaviour). Returns (tombstones, deviceId). */
    private fun parseSyncTolerant(text: String): Pair<List<BxTombstone>, String?>? {
        return try {
            val obj = JSONObject(text)
            val list = mutableListOf<BxTombstone>()
            val array = obj.optJSONArray("tombstones")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val t = array.optJSONObject(i) ?: continue
                    val id = t.optString("id", "")
                    val entity = t.optString("entity", "")
                    if (id.isEmpty() || entity.isEmpty() || !t.has("deletedAt")) continue
                    list += BxTombstone(
                        entity = entity,
                        id = id,
                        deletedAt = t.optLong("deletedAt", 0),
                        deviceId = t.optStringOrNull("deviceId"),
                    )
                }
            }
            list to obj.optStringOrNull("deviceId")
        } catch (e: Exception) {
            null
        }
    }

    /** One note file: unreadable or shape-less notes are SKIPPED, never
     *  fatal (1.x reader tolerance — id + string content required). */
    private fun parseNoteOrNull(text: String): BxNote? {
        return try {
            val o = JSONObject(text)
            val id = o.optString("id", "")
            if (id.isEmpty() || !o.has("content") || o.opt("content") !is String) return null
            val tagIds = mutableListOf<String>()
            val tagArray = o.optJSONArray("tagIds")
            if (tagArray != null) {
                for (i in 0 until tagArray.length()) tagArray.optString(i, "").takeIf { it.isNotEmpty() }?.let { tagIds += it }
            }
            val lockJson = if (o.has("lock") && !o.isNull("lock")) extractRawValue(text, "lock") else null
            BxNote(
                id = id,
                title = o.optString("title", ""),
                content = o.getString("content"),
                createdAt = o.optLong("createdAt", 0),
                updatedAt = o.optLong("updatedAt", 0),
                folderId = o.optStringOrNull("folderId"),
                tagIds = tagIds,
                pinned = o.optBoolean("pinned", false),
                favorite = o.optBoolean("favorite", false),
                archived = o.optBoolean("archived", false),
                deleted = o.optBoolean("deleted", false),
                deletedAt = if (o.has("deletedAt") && !o.isNull("deletedAt")) o.optLong("deletedAt") else null,
                reminderAt = if (o.has("reminderAt") && !o.isNull("reminderAt")) o.optLong("reminderAt") else null,
                wordCount = o.optInt("wordCount", 0),
                dailyDate = o.optStringOrNull("dailyDate"),
                lockJson = lockJson,
                isTemplate = o.optBoolean("isTemplate", false),
                dailyDefault = if (o.has("dailyDefault") && !o.isNull("dailyDefault")) o.optBoolean("dailyDefault") else null,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts the raw JSON substring of a top-level object/array value
     * (the `lock` record) VERBATIM — re-serialising it through org.json
     * would reorder fields and break the byte-parity lock records need.
     * String-aware balanced scan from the value's first `{`.
     */
    private fun extractRawValue(text: String, key: String): String? {
        val keyToken = "\"$key\""
        var searchFrom = 0
        while (true) {
            val keyAt = text.indexOf(keyToken, searchFrom)
            if (keyAt < 0) return null
            var i = keyAt + keyToken.length
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != ':') { searchFrom = keyAt + 1; continue }
            i++
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != '{') return null
            var depth = 0
            var inString = false
            var escaped = false
            val start = i
            while (i < text.length) {
                val c = text[i]
                if (inString) {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') inString = false
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{', '[' -> depth++
                        '}', ']' -> {
                            depth--
                            if (depth == 0) return text.substring(start, i + 1)
                        }
                    }
                }
                i++
            }
            return null
        }
    }

    // ------------------------------------------------- attachment streaming

    /**
     * Parses one `attachments/<id>.json` entry STREAMING: meta scalars
     * are collected field by field; the `data` string is consumed
     * character by character — a leading `{` means an encryption
     * envelope (written verbatim to the staging file), otherwise it is a
     * data URL whose Base64 body is decoded straight to the file.
     * Malformed records are skipped (staging file removed).
     */
    private fun parseAttachmentEntry(zip: ZipInputStream, stagingDir: File): StagedAttachment? {
        val staged = File(stagingDir, "att-${System.nanoTime()}.bin")
        return try {
            val parser = AttachmentJsonParser(zip, staged)
            val meta = parser.parse()
            if (meta == null) {
                staged.delete()
                null
            } else {
                StagedAttachment(meta, staged)
            }
        } catch (e: Exception) {
            staged.delete()
            null
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

/** Streaming parser for a single attachment record object. See
 *  [BackupReader.parseAttachmentEntry] for the contract. */
private class AttachmentJsonParser(input: InputStream, private val stagedFile: File) {
    private val reader: Reader = input.reader(Charsets.UTF_8)
    private var pushback: Int = -2 // -2 = empty

    private fun next(): Int {
        if (pushback != -2) {
            val v = pushback
            pushback = -2
            return v
        }
        return reader.read()
    }

    private fun peek(): Int {
        if (pushback == -2) pushback = reader.read()
        return pushback
    }

    private fun skipWs() {
        while (true) {
            val c = peek()
            if (c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code) next() else return
        }
    }

    private fun expect(c: Char) {
        skipWs()
        val got = next()
        check(got == c.code) { "Expected '$c', got $got" }
    }

    /** Reads a JSON string value (after the opening quote is consumed),
     *  invoking [onChar] per UNESCAPED character instead of building the
     *  string, when a sink is given. Returns the string when no sink. */
    private fun readString(onChar: ((Char) -> Unit)? = null): String {
        expect('"')
        val sb = if (onChar == null) StringBuilder() else null
        while (true) {
            val c = next()
            check(c >= 0) { "Unterminated string" }
            when (c) {
                '"'.code -> return sb?.toString() ?: ""
                '\\'.code -> {
                    val esc = next()
                    val decoded = when (esc) {
                        'n'.code -> '\n'
                        'r'.code -> '\r'
                        't'.code -> '\t'
                        'b'.code -> '\b'
                        'f'.code -> ''
                        '/'.code -> '/'
                        '\\'.code -> '\\'
                        '"'.code -> '"'
                        'u'.code -> {
                            var code = 0
                            repeat(4) {
                                val h = next()
                                check(h >= 0) { "Bad \\u escape" }
                                code = code * 16 + Character.digit(h.toChar(), 16)
                            }
                            code.toChar()
                        }
                        else -> throw IllegalStateException("Bad escape $esc")
                    }
                    if (onChar != null) onChar(decoded) else sb!!.append(decoded)
                }
                else -> if (onChar != null) onChar(c.toChar()) else sb!!.append(c.toChar())
            }
        }
    }

    private fun readScalarToken(): String {
        skipWs()
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0 || c == ','.code || c == '}'.code || c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) break
            sb.append(next().toChar())
        }
        return sb.toString()
    }

    fun parse(): BxAttachmentMeta? {
        val fields = HashMap<String, String>()
        var encryptedField: Boolean? = null
        var payloadIsEnvelope: Boolean? = null
        expect('{')
        skipWs()
        if (peek() == '}'.code) {
            next()
            return null
        }
        while (true) {
            val key = readString()
            expect(':')
            skipWs()
            if (key == "data" && peek() == '"'.code) {
                // Stream the payload into the staging file.
                stagedFile.outputStream().buffered().use { out ->
                    var sink: ((Char) -> Unit)? = null
                    val envelopeBuffer = StringBuilder()
                    val envelopeOut: (Char) -> Unit = { c ->
                        envelopeBuffer.append(c)
                        if (envelopeBuffer.length >= 4096) {
                            out.write(envelopeBuffer.toString().toByteArray(Charsets.UTF_8))
                            envelopeBuffer.setLength(0)
                        }
                    }
                    val dataUrlSink = DataUrlBase64Sink { bytes, off, len -> out.write(bytes, off, len) }
                    var decided = false
                    var probe = StringBuilder()
                    val charSink: (Char) -> Unit = { ch ->
                        if (!decided) {
                            probe.append(ch)
                            when {
                                probe[0] == '{' -> {
                                    decided = true
                                    payloadIsEnvelope = true
                                    val text = probe.toString()
                                    probe = StringBuilder()
                                    envelopeBuffer.append(text)
                                    sink = envelopeOut
                                }
                                probe.length >= 5 -> {
                                    check(probe.toString() == "data:") { "Attachment data is neither envelope nor data URL" }
                                    decided = true
                                    payloadIsEnvelope = false
                                    val text = probe.toString()
                                    probe = StringBuilder()
                                    // The data-URL header chars must go through the sink too.
                                    for (pc in text) dataUrlSink.feed(pc)
                                    sink = { c -> dataUrlSink.feed(c) }
                                }
                                probe[0] != '{' && !"data:".startsWith(probe.toString()) -> {
                                    throw IllegalStateException("Attachment data is neither envelope nor data URL")
                                }
                            }
                        } else {
                            sink?.invoke(ch)
                        }
                    }
                    readString(onChar = charSink)
                    if (envelopeBuffer.isNotEmpty()) {
                        out.write(envelopeBuffer.toString().toByteArray(Charsets.UTF_8))
                        envelopeBuffer.setLength(0)
                    }
                    if (payloadIsEnvelope == false) dataUrlSink.finish()
                    if (payloadIsEnvelope == null) {
                        // Empty data string: no payload — treat as malformed.
                        throw IllegalStateException("Empty attachment data")
                    }
                }
            } else if (peek() == '"'.code) {
                val value = readString()
                if (key == "encrypted") encryptedField = value == "true" else fields[key] = value
            } else {
                val token = readScalarToken()
                if (key == "encrypted") encryptedField = token == "true" else fields[key] = token
            }
            skipWs()
            val sep = next()
            if (sep == '}'.code) break
            check(sep == ','.code) { "Expected ',' or '}' in attachment record" }
        }
        val id = fields["id"]?.takeIf { it.isNotEmpty() } ?: return null
        return BxAttachmentMeta(
            id = id,
            noteId = fields["noteId"] ?: "",
            name = fields["name"] ?: "",
            mimeType = fields["mimeType"] ?: "application/octet-stream",
            size = fields["size"]?.toLongOrNull() ?: 0L,
            createdAt = fields["createdAt"]?.toLongOrNull() ?: 0L,
            updatedAt = fields["updatedAt"]?.toLongOrNull(),
            encrypted = encryptedField ?: (payloadIsEnvelope ?: false),
        )
    }
}
