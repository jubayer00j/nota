package app.nota.data.backup

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.FolderEntity
import app.nota.data.db.NoteEntity
import app.nota.data.repository.refreshFtsEntry
import app.nota.data.db.NoteLinkEntity
import app.nota.data.db.NoteTagCrossRef
import app.nota.data.db.NotaDatabase
import app.nota.data.db.ReminderEntity
import app.nota.data.db.TagEntity
import app.nota.data.html.NoteDocument
import app.nota.data.templates.TemplateRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Backup orchestration (Phase 2c): export collection, restore apply,
 * and the post-restore rebuilds. The ZIP/NOMAENC1 mechanics live in
 * [BackupWriter], [BackupReader] and [BackupCrypto]; the mapping and
 * merge semantics live here and mirror 1.x `collectBackup` /
 * `applyBackup` / `applyTombstones` with the Nota adaptations documented
 * per step (and in the PR description):
 *
 * - Field mapping Nota⇄1.x: fileName→name, sizeBytes→size,
 *   favourite→favorite, trashedAt→deleted/deletedAt. `wordCount` is
 *   computed from plainText for unlocked notes, 0 for locked (1.x stores
 *   the pre-seal count; the field is display metadata and 1.x recomputes
 *   it on its own saves). A note's `reminderAt` is its earliest ENABLED
 *   reminder's triggerAt, else null.
 * - Nota has no `updatedAt` on tags/attachments/reminders: merge compares
 *   the payload's (updatedAt ?? createdAt) against the local row's
 *   createdAt proxy, and reminders merge insert-if-absent (after the
 *   payload is deduplicated by noteId, newest updatedAt kept).
 * - Tombstones apply in MERGE mode only and are NOT stored (Nota has no
 *   tombstone store until Phase 3).
 * - Settings are never imported and never exported beyond `null`.
 */
class BackupRepository(
    private val context: Context,
    private val provider: DatabaseProvider,
    private val templateRepository: TemplateRepository,
    private val attachmentStore: AttachmentStore,
) {
    // Phase 3a: the database is the ACTIVE account's, resolved per
    // call through the provider — a backup/restore always targets the
    // library the user is looking at, never a captured one.
    private val db: NotaDatabase get() = provider.current
    private val noteDao get() = db.noteDao()
    private val folderDao get() = db.folderDao()
    private val tagDao get() = db.tagDao()
    private val attachmentDao get() = db.attachmentDao()
    private val reminderDao get() = db.reminderDao()
    private val ftsDao get() = db.noteFtsDao()
    private val linkDao get() = db.noteLinkDao()

    private val reader = BackupReader(cacheDir())

    private fun cacheDir(): File = context.cacheDir

    // ------------------------------------------------------------ export

    /** Collects the library into the writer's export model. */
    suspend fun collectExport(nowMillis: Long = System.currentTimeMillis()): BackupWriter.ExportData {
        val folders = folderDao.getAll()
        val tags = tagDao.getAllTags()
        val notes = noteDao.getAll()
        val crossRefs = mutableMapOf<String, MutableList<String>>()
        // note_tags has no bulk suspend getter; read via the flow's first emission.
        val allRefs = tagDao.observeAllCrossRefs().first()
        for (ref in allRefs) crossRefs.getOrPut(ref.noteId) { mutableListOf() } += ref.tagId

        val reminders = reminderDao.getAll()
        val reminderAtByNote = reminders.filter { it.enabled }
            .groupBy { it.noteId }
            .mapValues { (_, list) -> list.minOf { it.triggerAt } }

        val attachments = attachmentDao.getAll()
        val bxNotes = notes.map { note ->
            BxNote(
                id = note.id,
                title = note.title,
                content = if (note.locked) note.contentHtml else note.contentHtml.toOneXImageScheme(),
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
                folderId = note.folderId,
                tagIds = crossRefs[note.id] ?: emptyList(),
                pinned = note.pinned,
                favorite = note.favourite,
                archived = note.archived,
                deleted = note.trashedAt != null,
                deletedAt = note.trashedAt,
                reminderAt = reminderAtByNote[note.id],
                wordCount = if (note.locked) 0 else wordCount(note.plainText),
                dailyDate = note.dailyDate,
                lockJson = if (note.locked) note.lockRecordJson else null,
                isTemplate = false,
                dailyDefault = null,
            )
        }
        // User templates leave as isTemplate notes (built-ins never do).
        val templateNotes = templateRepository.snapshot().filter { !it.builtIn }.map { template ->
            BxNote(
                id = template.id,
                title = template.title,
                content = template.contentHtml.toOneXImageScheme(),
                createdAt = nowMillis,
                updatedAt = nowMillis,
                folderId = null,
                tagIds = emptyList(),
                pinned = false,
                favorite = false,
                archived = false,
                deleted = false,
                deletedAt = null,
                reminderAt = null,
                wordCount = wordCount(NoteDocument.plainText(NoteDocument.parse(template.contentHtml))),
                dailyDate = null,
                lockJson = null,
                isTemplate = true,
                dailyDefault = null,
            )
        }
        val allNotes = bxNotes + templateNotes
        val bxAttachments = attachments.map { att ->
            BxAttachmentMeta(
                id = att.id,
                noteId = att.noteId,
                name = att.fileName,
                mimeType = att.mimeType,
                size = att.sizeBytes,
                createdAt = att.createdAt,
                updatedAt = null,
                encrypted = att.encrypted,
            )
        }
        val bxFolders = folders.map {
            BxFolder(it.id, it.name, it.parentId, it.createdAt, it.updatedAt)
        }
        val bxTags = tags.map { BxTag(it.id, it.name, it.createdAt, updatedAt = null) }
        val bxReminders = reminders.map {
            BxReminder(
                id = it.id,
                noteId = it.noteId,
                scheduledAt = it.triggerAt,
                status = if (it.enabled) "pending" else "dismissed",
                createdAt = it.createdAt,
                updatedAt = it.createdAt,
            )
        }
        val iso = Instant.ofEpochMilli(nowMillis).toString()
        // Phase 3c: archives carry this device's id (manifest +
        // sync.json, as 1.x's do) and the account's sync tombstones,
        // so a Drive archive doubles as the sync carrier and a plain
        // restore from one still propagates deletions in Merge mode.
        val syncStore = app.nota.data.sync.SyncStore(db)
        return BackupWriter.ExportData(
            manifest = BackupManifest(
                backupFormatVersion = 2,
                // Phase 3a: the manifest now names its owner + library
                // truthfully (1.x parity): a signed-in export carries
                // the account uid as ownerId (1.x readers reject other
                // accounts' archives on the Drive path), a guest export
                // stays ownerless; libraryId is the ACTIVE database
                // name. Guest exports before 3a wrote ownerId=null and
                // the guest libraryId — those archives still read fine
                // (the reader never gates local restores on owner).
                ownerId = provider.accountId.value
                    .takeIf { it != NotaDatabase.GUEST_ACCOUNT },
                libraryId = NotaDatabase.databaseName(provider.accountId.value),
                nomaVersion = "0.4.0",
                createdAt = iso,
                noteCount = allNotes.size,
                folderCount = bxFolders.size,
                tagCount = bxTags.size,
                attachmentCount = bxAttachments.size,
                reminderCount = bxReminders.size,
                deviceId = syncStore.ensureDeviceId(),
            ),
            folders = bxFolders,
            tags = bxTags,
            reminders = bxReminders,
            syncSyncedAtIso = iso,
            notes = allNotes,
            attachments = bxAttachments,
            tombstones = syncStore.tombstones(),
        )
    }

    /**
     * Exports the library to [out]. Plaintext when [passphrase] is null;
     * otherwise the ZIP is built into a cacheDir temp file and sealed
     * into a NOMAENC1 stream. The temp file is always deleted.
     */
    suspend fun exportTo(out: OutputStream, passphrase: String?) = withContext(Dispatchers.IO) {
        val data = collectExport()
        val filesById = attachmentDao.getAll().associate { att -> att.id to attachmentStore.fileFor(att) }
        val writer = BackupWriter { meta -> filesById[meta.id] }
        if (passphrase == null) {
            writer.write(data, out)
            out.flush()
        } else {
            val tmp = File(cacheDir(), "backup-export-${System.nanoTime()}.zip")
            try {
                tmp.outputStream().buffered().use { writer.write(data, it) }
                BackupCrypto.seal(tmp, passphrase, out)
                out.flush()
            } finally {
                tmp.delete()
            }
        }
    }

    suspend fun exportToFile(file: File, passphrase: String?) {
        file.outputStream().buffered().use { exportTo(it, passphrase) }
    }

    /**
     * Keyed export (Phase 3b): seals under a caller-held BMK + header
     * instead of a fresh per-file key — the Drive encryption model
     * (one long-lived BMK wrapped once at setup; the device key makes
     * unattended backups possible). Byte-identical envelope to the
     * passphrase form; only the key's provenance differs.
     */
    suspend fun exportToKeyed(out: OutputStream, bmk: ByteArray, header: BackupCrypto.Header) =
        withContext(Dispatchers.IO) {
            val data = collectExport()
            val filesById = attachmentDao.getAll().associate { att -> att.id to attachmentStore.fileFor(att) }
            val writer = BackupWriter { meta -> filesById[meta.id] }
            val tmp = File(cacheDir(), "backup-export-${System.nanoTime()}.zip")
            try {
                tmp.outputStream().buffered().use { writer.write(data, it) }
                BackupCrypto.seal(tmp, bmk, header, out)
                out.flush()
            } finally {
                tmp.delete()
            }
        }

    suspend fun exportToFileKeyed(file: File, bmk: ByteArray, header: BackupCrypto.Header) {
        file.outputStream().buffered().use { exportToKeyed(it, bmk, header) }
    }

    // ------------------------------------------------------------ restore

    /** Copies a picked source (SAF URI stream) into a cacheDir temp file,
     *  streaming — the reader works on files. Caller deletes the file. */
    fun copyToTempFile(input: InputStream): File {
        val tmp = File(cacheDir(), "backup-import-${System.nanoTime()}.bin")
        input.buffered().use { inp ->
            tmp.outputStream().buffered().use { out -> inp.copyTo(out) }
        }
        return tmp
    }

    /** Reads (and, when encrypted, decrypts) an archive WITHOUT touching
     *  the database. Throws [BackupKeyNeededError] when a passphrase is
     *  required, [BackupUnlockError] when it is wrong. */
    suspend fun readBackup(source: File, passphrase: String?): BackupReader.Handle =
        withContext(Dispatchers.IO) { reader.read(source, passphrase) }

    /** Device-key read (Phase 3b): opens an encrypted archive with a
     *  held BMK (the Drive encryption device key) — no passphrase. */
    /**
     * SHA-256 (hex) of each attachment's on-disk bytes in the ACTIVE
     * library, keyed by attachment id (Phase 3c sync content hashes).
     * A missing file maps to "" — its hash then differs from any real
     * content hash, so sync treats the bytes as changed and heals
     * them from the remote copy on the next cycle.
     */
    suspend fun attachmentContentHashes(data: BackupWriter.ExportData): Map<String, String> {
        val dao = provider.current.attachmentDao()
        val out = LinkedHashMap<String, String>()
        for (meta in data.attachments) {
            val row = dao.getById(meta.id)
            out[meta.id] = if (row != null) {
                val file = attachmentStore.fileFor(row)
                if (file.isFile) app.nota.data.sync.EntityHash.sha256Hex(file) else ""
            } else {
                ""
            }
        }
        return out
    }

    suspend fun readBackupWithBmk(source: File, bmk: ByteArray): BackupReader.Handle =
        withContext(Dispatchers.IO) { reader.readWithBmk(source, bmk) }

    /** read + apply + cleanup; nothing is written before this is called
     *  with a fully-read handle, and apply is only invoked by the caller
     *  after the user picks a mode. */
    suspend fun restore(source: File, passphrase: String?, mode: RestoreMode): MergeCounts {
        val handle = readBackup(source, passphrase)
        try {
            return applyBackup(handle, mode)
        } finally {
            handle.close()
        }
    }

    suspend fun applyBackup(handle: BackupReader.Handle, mode: RestoreMode): MergeCounts =
        withContext(Dispatchers.IO) {
            // ---- Owner gate (Phase 3b): an archive that names a
            // DIFFERENT account as owner is never applied while
            // signed in — 1.x refuses other accounts' archives on
            // the Drive path, and the same rule now guards every
            // apply (local or Drive) before a single row is written.
            // Guest archives (ownerId null) and guest mode (no uid
            // to compare against) are unaffected — the exact 2c
            // behaviour Jubayer's crossover restore proved.
            val ownerId = handle.manifest.ownerId
            val accountId = provider.accountId.value
            if (ownerId != null &&
                accountId != NotaDatabase.GUEST_ACCOUNT &&
                ownerId != accountId
            ) {
                throw BackupError(
                    "This backup belongs to a different account, so it wasn't restored.",
                )
            }

            // ---- Crafted-id filter (PR #55, Argon F-02): a note id
            // from an archive is data, not a trusted path segment —
            // it becomes a directory name under attachments/ and
            // under the editor's session cache. Notes whose id fails
            // isSafeId are refused outright (counted below, never
            // silent); attachments are refused in the plan when
            // either their own id or their note id is unsafe.
            // Folders, tags and reminders carry no filesystem path,
            // so they stay unfiltered (their cross-references are
            // existence-checked below, as before).
            val safeNotes = handle.notes.filter { isSafeId(it.id) }
            val skippedNotes = handle.notes.size - safeNotes.size
            val skippedAttachments = handle.attachments.count {
                !isSafeId(it.meta.id) || !isSafeId(it.meta.noteId)
            }

            // ---- Partition templates out of the note payload.
            val templateNotes = safeNotes.filter { it.isTemplate }
            val payloadNotes = safeNotes.filter { !it.isTemplate }

            // ---- Attachment plan: which staged payloads win a row (the
            // files themselves land after the row transaction, below).
            val existingAttachments = attachmentDao.getAll().associateBy { it.id }
            val existingNotes = noteDao.getAll().associateBy { it.id }
            val payloadNoteIds = payloadNotes.map { it.id }.toSet()
            val survivingNoteIds = when (mode) {
                RestoreMode.REPLACE -> payloadNoteIds
                RestoreMode.MERGE -> existingNotes.keys + payloadNoteIds
            }
            // In merge, attachments only land when their row would win
            // (or is new); in replace, every payload attachment lands.
            data class AttPlan(val staged: BackupReader.StagedAttachment, val wins: Boolean)
            val attPlans = handle.attachments.map { staged ->
                val existing = existingAttachments[staged.meta.id]
                val wins = when (mode) {
                    RestoreMode.REPLACE -> true
                    RestoreMode.MERGE -> existing == null ||
                        (staged.meta.updatedAt ?: staged.meta.createdAt) > existing.createdAt
                }
                // A crafted archive can carry path-traversal noteIds
                // ("../../…"): such attachments are skipped outright —
                // their payload must never be written outside the
                // attachment root (Codex review, P1). PR #55 (Argon
                // F-02) extends the same rule to the attachment's OWN
                // id, which reaches the session-file path.
                AttPlan(
                    staged,
                    wins && staged.meta.noteId in survivingNoteIds &&
                        isSafeId(staged.meta.noteId) && isSafeId(staged.meta.id),
                )
            }

            if (mode == RestoreMode.REPLACE) {
                // Wipe THIS account's attachment files before any new
                // file lands and before rows are cleared, so no orphan
                // survives. Phase 3a: the attachments/ root is shared
                // by every account database on the device — the old
                // wholesale deleteRecursively() would have destroyed
                // OTHER accounts' binaries (whose rows reconcile then
                // drops as file-missing). Only files the active
                // account's rows point at are deleted here; payload
                // files land after the transaction.
                for (att in existingAttachments.values) {
                    attachmentStore.fileFor(att).delete()
                }
            }
            // Attachment FILES are written after the row transaction
            // (below): a failed transaction then leaves no orphan files,
            // and the final reconcile drops any row whose file could not
            // be written — the two sides can never disagree for long.
            val winningAttachments = attPlans.filter { it.wins }.map { it.staged }
            val writtenAttachments = winningAttachments.map { staged ->
                // The entity is computed up front (its file lands later);
                // size/dimensions come from the staged payload.
                attachmentEntityFor(staged)
            }

            var counts = MergeCounts()
            val tagIdRemap = HashMap<String, String>()

            db.withTransaction {
                if (mode == RestoreMode.REPLACE) {
                    linkDao.deleteAll()
                    ftsDao.deleteAll()
                    tagDao.deleteAllCrossRefs()
                    reminderDao.deleteAll()
                    attachmentDao.deleteAll()
                    noteDao.deleteAll()
                    tagDao.deleteAllTags()
                    folderDao.deleteAll()

                    for (folder in handle.folders) folderDao.upsert(folder.toEntity())
                    insertTagsReplace(handle.tags, tagIdRemap)
                    val folderIds = handle.folders.map { it.id }.toSet()
                    for (note in payloadNotes) {
                        noteDao.upsert(note.toEntity(folderIds))
                    }
                    for (note in payloadNotes) {
                        insertCrossRefs(note, tagIdRemap, handle.tags.map { it.id }.toSet())
                    }
                    for (att in writtenAttachments) attachmentDao.upsert(att)
                    val deduped = dedupeReminders(handle.reminders)
                    for (reminder in deduped) {
                        if (reminder.noteId in payloadNoteIds) reminderDao.upsert(reminder.toEntity())
                    }
                    counts = MergeCounts(
                        added = handle.folders.size + handle.tags.size + payloadNotes.size +
                            writtenAttachments.size + deduped.count { it.noteId in payloadNoteIds },
                    )
                } else {
                    // ---- Merge: newest wins per entity, exactly 1.x.
                    for (folder in handle.folders) {
                        val existing = folderDao.getById(folder.id)
                        when {
                            existing == null -> { folderDao.upsert(folder.toEntity()); counts += MergeCounts(added = 1) }
                            folder.updatedAt > existing.updatedAt -> { folderDao.upsert(folder.toEntity()); counts += MergeCounts(updated = 1) }
                        }
                    }
                    val knownTagIds = tagDao.getAllTags().map { it.id }.toMutableSet()
                    for (tag in handle.tags) {
                        val existing = tagDao.getById(tag.id)
                        val byName = if (existing == null) tagDao.getByName(tag.name) else null
                        when {
                            existing == null && byName != null -> tagIdRemap[tag.id] = byName.id
                            existing == null -> {
                                tagDao.upsert(TagEntity(id = tag.id, name = tag.name, createdAt = tag.createdAt))
                                knownTagIds += tag.id
                                counts += MergeCounts(added = 1)
                            }
                            (tag.updatedAt ?: tag.createdAt) > existing.createdAt -> {
                                // Nota tags carry no updatedAt and renames
                                // ride the name: a strictly newer payload
                                // tag replaces the row (name may change).
                                tagDao.upsert(TagEntity(id = tag.id, name = tag.name, createdAt = existing.createdAt))
                                counts += MergeCounts(updated = 1)
                            }
                        }
                    }
                    val folderIds = (folderDao.getAll().map { it.id }).toSet()
                    val allPayloadTagIds = handle.tags.map { it.id }.toSet()
                    for (note in payloadNotes) {
                        val existing = noteDao.getById(note.id)
                        when {
                            existing == null -> {
                                noteDao.upsert(note.toEntity(folderIds))
                                insertCrossRefs(note, tagIdRemap, allPayloadTagIds + knownTagIds)
                                counts += MergeCounts(added = 1)
                            }
                            note.updatedAt > existing.updatedAt -> {
                                noteDao.upsert(note.toEntity(folderIds))
                                tagDao.unlinkAllForNote(note.id)
                                insertCrossRefs(note, tagIdRemap, allPayloadTagIds + knownTagIds)
                                counts += MergeCounts(updated = 1)
                            }
                        }
                    }
                    for (att in writtenAttachments) {
                        val wasExisting = existingAttachments.containsKey(att.id)
                        attachmentDao.upsert(att)
                        counts += if (wasExisting) MergeCounts(updated = 1) else MergeCounts(added = 1)
                    }
                    // Reminders: deduplicated by noteId (newest updatedAt
                    // kept), then insert-if-absent — Nota reminders have
                    // no updatedAt to compare (documented proxy).
                    for (reminder in dedupeReminders(handle.reminders)) {
                        if (reminder.noteId !in survivingNoteIds) continue
                        if (reminderDao.getById(reminder.id) == null) {
                            reminderDao.upsert(reminder.toEntity())
                            counts += MergeCounts(added = 1)
                        }
                    }
                }
            }

            // ---- Tombstones: merge only, NOT stored (Phase 3 store).
            if (mode == RestoreMode.MERGE && handle.tombstones.isNotEmpty()) {
                counts += MergeCounts(deleted = applyTombstones(handle.tombstones))
            }

            // ---- Attachment files land now that their rows are final:
            // plans whose note did not survive (tombstone, FK filter)
            // are skipped, and reconcile below sweeps any stragglers.
            for (staged in winningAttachments) {
                if (noteDao.getById(staged.meta.noteId) == null) continue
                writeAttachmentFile(staged)
            }

            // ---- Templates (separate furniture, not notes).
            if (mode == RestoreMode.REPLACE) templateRepository.clearUserTemplates()
            for (template in templateNotes) {
                templateRepository.importTemplate(
                    id = template.id,
                    name = template.title.ifBlank { "Imported template" },
                    title = template.title,
                    contentHtml = template.content,
                )
                // dailyDefault is dropped: Nota daily notes do not seed
                // from templates yet (documented in the PR).
            }

            rebuildFtsAndLinks()
            // The reconcile's file sweep must spare other accounts'
            // binaries (shared attachments/ root — see AttachmentStore).
            attachmentStore.reconcile(
                allProvider = { attachmentDao.getAll() },
                extraKnownPaths = { provider.allAttachmentLocalPaths() },
            )
            val skippedTotal = skippedNotes + skippedAttachments
            if (skippedTotal > 0) {
                Log.w(
                    TAG,
                    "Restore skipped $skippedTotal crafted-id entities " +
                        "($skippedNotes notes, $skippedAttachments attachments)",
                )
                counts += MergeCounts(skipped = skippedTotal)
            }
            counts
        }

    // ------------------------------------------------------------ pieces

    /** The row for a staged attachment (file copy is [writeAttachmentFile]). */
    private fun attachmentEntityFor(staged: BackupReader.StagedAttachment): AttachmentEntity {
        val meta = staged.meta
        val localPath = "attachments/${meta.noteId}/${storedNameFor(meta)}"
        val (width, height) = if (!meta.encrypted && meta.mimeType.startsWith("image/")) {
            ImageBounds.read(staged.stagedFile)
        } else {
            null to null
        }
        return AttachmentEntity(
            id = meta.id,
            noteId = meta.noteId,
            fileName = meta.name,
            mimeType = meta.mimeType,
            sizeBytes = staged.stagedFile.length(),
            localPath = localPath,
            widthPx = width,
            heightPx = height,
            createdAt = meta.createdAt,
            encrypted = meta.encrypted,
        )
    }

    private fun storedNameFor(meta: BxAttachmentMeta): String = sanitiseStoredName(
        meta.rawEntrySuffix
            ?.removeSuffix(".encrypted")
            ?.takeIf { it.isNotBlank() }
            ?: "${meta.id}-${meta.name}",
    )

    /** Streams one staged payload to its final attachment file. */
    private fun writeAttachmentFile(staged: BackupReader.StagedAttachment) {
        val target = File(
            context.filesDir,
            "attachments/${staged.meta.noteId}/${storedNameFor(staged.meta)}",
        )
        // Defence in depth (Codex P1): the canonical target must stay
        // under the attachment root, whatever the archive claimed.
        val root = File(context.filesDir, "attachments").canonicalFile
        val canonicalTarget = target.canonicalFile
        check(canonicalTarget.path.startsWith(root.path + File.separator)) {
            "Attachment path escapes the attachment root"
        }
        target.parentFile?.mkdirs()
        staged.stagedFile.inputStream().buffered().use { input ->
            target.outputStream().buffered().use { out -> input.copyTo(out) }
        }
    }


    private suspend fun insertTagsReplace(tags: List<BxTag>, remap: MutableMap<String, String>) {
        val seenNames = HashMap<String, String>()
        for (tag in tags) {
            val existingId = seenNames[tag.name]
            if (existingId != null) {
                remap[tag.id] = existingId
                continue
            }
            // A pre-existing local tag was wiped in replace, so names are free.
            tagDao.upsert(TagEntity(id = tag.id, name = tag.name, createdAt = tag.createdAt))
            seenNames[tag.name] = tag.id
        }
    }

    private suspend fun insertCrossRefs(note: BxNote, remap: Map<String, String>, acceptableTagIds: Set<String>) {
        val seen = HashSet<String>()
        for (rawTagId in note.tagIds) {
            val tagId = remap[rawTagId] ?: rawTagId
            // Dangling tagIds (no tags.json row and no local row) are skipped.
            val exists = tagId in acceptableTagIds || tagDao.getById(tagId) != null
            if (!exists) continue
            if (!seen.add(tagId)) continue
            tagDao.link(NoteTagCrossRef(noteId = note.id, tagId = tagId))
        }
    }

    private fun dedupeReminders(reminders: List<BxReminder>): List<BxReminder> {
        val byNote = LinkedHashMap<String, BxReminder>()
        for (reminder in reminders) {
            val existing = byNote[reminder.noteId]
            if (existing == null || reminder.updatedAt > existing.updatedAt) byNote[reminder.noteId] = reminder
        }
        return byNote.values.toList()
    }

    /** 1.x `applyTombstones`: newest marker per entity wins; a marker
     *  deletes only when deletedAt >= the entity's timestamp. */
    private suspend fun applyTombstones(tombstones: List<BxTombstone>): Int {
        val byKey = LinkedHashMap<String, BxTombstone>()
        for (t in tombstones) {
            val key = "${t.entity}:${t.id}"
            val existing = byKey[key]
            if (existing == null || t.deletedAt > existing.deletedAt) byKey[key] = t
        }
        var deleted = 0
        val now = System.currentTimeMillis()
        db.withTransaction {
            for (t in byKey.values) {
                when (t.entity) {
                    "note" -> {
                        val note = noteDao.getById(t.id) ?: continue
                        if (t.deletedAt < note.updatedAt) continue
                        val atts = attachmentDao.getForNote(t.id)
                        val rems = reminderDao.getForNote(t.id)
                        for (att in atts) attachmentStore.fileFor(att).delete()
                        // FK cascades clear attachments/reminders/links/
                        // cross-refs rows with the note row.
                        noteDao.delete(note)
                        ftsDao.deleteForNote(t.id)
                        deleted += 1 + atts.size + rems.size
                    }
                    "folder" -> {
                        val folder = folderDao.getById(t.id) ?: continue
                        if (t.deletedAt < folder.updatedAt) continue
                        for (note in noteDao.getAll().filter { it.folderId == t.id }) {
                            noteDao.upsert(note.copy(folderId = null, updatedAt = now))
                        }
                        folderDao.delete(folder)
                        deleted += 1
                    }
                    "tag" -> {
                        val tag = tagDao.getById(t.id) ?: continue
                        // Nota tags have no updatedAt: createdAt is the proxy.
                        if (t.deletedAt < tag.createdAt) continue
                        tagDao.delete(tag) // cross-refs cascade
                        deleted += 1
                    }
                    "attachment" -> {
                        val att = attachmentDao.getById(t.id) ?: continue
                        if (t.deletedAt < att.createdAt) continue
                        attachmentStore.fileFor(att).delete()
                        attachmentDao.delete(att)
                        deleted += 1
                    }
                    "reminder" -> {
                        val reminder = reminderDao.getById(t.id) ?: continue
                        // Proxy: createdAt stands in for updatedAt.
                        if (t.deletedAt < reminder.createdAt) continue
                        reminderDao.delete(reminder)
                        deleted += 1
                    }
                }
            }
        }
        return deleted
    }

    /** Delegates to the shared [rebuildLibraryIndexes] (also used by
     *  the Phase 3a guest→account copy, which rebuilds the target
     *  library's indexes after its row copy). */
    private suspend fun rebuildFtsAndLinks() = rebuildLibraryIndexes(db)

    companion object {
        private const val TAG = "NotaBackup"

        fun wordCount(plainText: String): Int =
            plainText.split(Regex("\\s+")).count { it.isNotEmpty() }

        fun sanitiseStoredName(name: String): String {
            val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
            // "." / ".." would traverse even after character filtering.
            return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "file" else cleaned
        }

        /** Backup-carried IDs become filesystem path segments (attachment
         *  files live under attachments/<noteId>/): only the ID alphabet
         *  Nota/1.x actually mint (UUIDs, slugs) is accepted. */
        fun isSafeId(id: String): Boolean =
            id.isNotEmpty() && id.length <= 128 && !id.contains("..") &&
                id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }
}

// ------------------------------------------------------------ mappings

/**
 * Phase 2.5 (F1 companion): 1.x writes inline-image srcs as
 * `noma-attachment://<id>`; Nota's editor resolves
 * `nota-attachment://<id>`. Import normalises the src attribute to
 * Nota's scheme (unlocked notes only — locked envelopes always stay
 * byte-verbatim); export maps it back so a Nota backup restored
 * into 1.x keeps working images.
 */
private fun String.toNotaImageScheme(): String =
    replace("src=\"noma-attachment://", "src=\"nota-attachment://")

private fun String.toOneXImageScheme(): String =
    replace("src=\"nota-attachment://", "src=\"noma-attachment://")

internal fun BxFolder.toEntity(): FolderEntity = FolderEntity(
    id = id, name = name, parentId = parentId, createdAt = createdAt, updatedAt = updatedAt,
)

internal fun BxNote.toEntity(validFolderIds: Set<String>): NoteEntity {
    val locked = lockJson != null
    return NoteEntity(
        id = id,
        folderId = folderId?.takeIf { it in validFolderIds },
        title = title,
        // Locked notes bypass ALL content processing: envelope + lock
        // record are stored verbatim (PR-B invariants). Unlocked
        // bodies get only the image-scheme normalisation above.
        contentHtml = if (locked) content else content.toNotaImageScheme(),
        plainText = if (locked) "" else NoteDocument.plainText(NoteDocument.parse(content)),
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned,
        favourite = favorite,
        archived = archived,
        trashedAt = when {
            deleted -> deletedAt ?: updatedAt
            else -> null
        },
        dailyDate = dailyDate,
        locked = locked,
        lockRecordJson = lockJson,
    )
}

internal fun BxReminder.toEntity(): ReminderEntity = ReminderEntity(
    id = id,
    noteId = noteId,
    triggerAt = scheduledAt,
    repeatRule = null,
    enabled = status == "pending",
    createdAt = createdAt,
)

/**
 * Full-library FTS + backlink rebuild for [db]. Clearing note_links
 * first is required: NoteRepository.backfillLinksIfNeeded only runs
 * when the table is empty, and a merge/copy can leave edges pointing
 * at notes whose titles just changed — a full clear + re-extraction
 * keeps every edge truthful. Locked notes get title-only FTS rows and
 * no edges (the per-note-lock invariants). Shared by restore
 * ([BackupRepository.applyBackup]) and the Phase 3a guest→account
 * copy ([app.nota.data.auth.GuestLibraryTransfer]).
 */
suspend fun rebuildLibraryIndexes(db: NotaDatabase) {
    val noteDao = db.noteDao()
    val ftsDao = db.noteFtsDao()
    val linkDao = db.noteLinkDao()
    val allNotes = noteDao.getAll()
    db.withTransaction {
        ftsDao.deleteAll()
        linkDao.deleteAll()
        for (note in allNotes) {
            if (note.trashedAt != null) continue
            // Search Quality Pass: rows come from the one shared
            // builder (FtsSync) so a restore also indexes tag and
            // folder names, under the same lock discipline.
            db.refreshFtsEntry(note)
        }
        for (note in allNotes) {
            if (note.locked || note.trashedAt != null) continue
            val titles = NoteDocument.backlinkTitles(note.plainText)
            for (title in titles) {
                val target = noteDao.getByTitle(title) ?: continue
                if (target.id == note.id || target.locked) continue
                linkDao.insertAll(listOf(NoteLinkEntity(sourceId = note.id, targetId = target.id)))
            }
        }
    }
}
