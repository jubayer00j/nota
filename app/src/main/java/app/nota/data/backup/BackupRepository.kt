package app.nota.data.backup

import android.content.Context
import androidx.room.withTransaction
import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.FolderEntity
import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteFtsEntity
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
    private val db: NotaDatabase,
    private val templateRepository: TemplateRepository,
    private val attachmentStore: AttachmentStore,
) {
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
                content = note.contentHtml,
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
                content = template.contentHtml,
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
        return BackupWriter.ExportData(
            manifest = BackupManifest(
                backupFormatVersion = 2,
                ownerId = null,
                libraryId = NotaDatabase.databaseName(NotaDatabase.GUEST_ACCOUNT),
                nomaVersion = "0.3.0",
                createdAt = iso,
                noteCount = allNotes.size,
                folderCount = bxFolders.size,
                tagCount = bxTags.size,
                attachmentCount = bxAttachments.size,
                reminderCount = bxReminders.size,
                deviceId = null,
            ),
            folders = bxFolders,
            tags = bxTags,
            reminders = bxReminders,
            syncSyncedAtIso = iso,
            notes = allNotes,
            attachments = bxAttachments,
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
            // ---- Partition templates out of the note payload.
            val templateNotes = handle.notes.filter { it.isTemplate }
            val payloadNotes = handle.notes.filter { !it.isTemplate }

            // ---- Attachment file plan (files land BEFORE rows, so a row
            // never points at a file that was not written).
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
                AttPlan(staged, wins && staged.meta.noteId in survivingNoteIds)
            }

            if (mode == RestoreMode.REPLACE) {
                // Wipe ALL attachment files before any new file lands and
                // before rows are cleared, so no orphan survives.
                File(context.filesDir, "attachments").deleteRecursively()
            }
            val writtenAttachments = mutableListOf<AttachmentEntity>()
            for (plan in attPlans) {
                if (!plan.wins) continue
                writtenAttachments += writeAttachmentFile(plan.staged)
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
            println("DIAG filesBefore=" + File(context.filesDir, "attachments").walkTopDown().filter { it.isFile }.map { it.relativeTo(context.filesDir).path }.toList())
            println("DIAG rowsBefore=" + attachmentDao.getAll().map { it.id + "@" + it.localPath })
            val rec = attachmentStore.reconcile { attachmentDao.getAll() }
            println("DIAG reconcile=" + rec)
            println("DIAG filesAfter=" + File(context.filesDir, "attachments").walkTopDown().filter { it.isFile }.map { it.relativeTo(context.filesDir).path }.toList())
            counts
        }

    // ------------------------------------------------------------ pieces

    private fun writeAttachmentFile(staged: BackupReader.StagedAttachment): AttachmentEntity {
        val meta = staged.meta
        val storedName = sanitiseStoredName(
            meta.rawEntrySuffix
                ?.removeSuffix(".encrypted")
                ?.takeIf { it.isNotBlank() }
                ?: "${meta.id}-${meta.name}",
        )
        val localPath = "attachments/${meta.noteId}/$storedName"
        val target = File(context.filesDir, localPath)
        target.parentFile?.mkdirs()
        staged.stagedFile.inputStream().buffered().use { input ->
            target.outputStream().buffered().use { out -> input.copyTo(out) }
        }
        val size = target.length()
        val (width, height) = if (!meta.encrypted && meta.mimeType.startsWith("image/")) {
            ImageBounds.read(staged.stagedFile)
        } else {
            null to null
        }
        println("DIAG writeDone id=" + meta.id + " targetExists=" + target.exists() + " stagedExists=" + staged.stagedFile.exists())
        return AttachmentEntity(
            id = meta.id,
            noteId = meta.noteId,
            fileName = meta.name,
            mimeType = meta.mimeType,
            sizeBytes = size,
            localPath = localPath,
            widthPx = width,
            heightPx = height,
            createdAt = meta.createdAt,
            encrypted = meta.encrypted,
        )
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

    /** Full-library FTS + backlink rebuild after a restore. Clearing
     *  note_links first is required: NoteRepository.backfillLinksIfNeeded
     *  only runs when the table is empty, and a merge can leave edges
     *  pointing at notes whose titles the payload just changed — a full
     *  clear + re-extraction keeps every edge truthful. Locked notes get
     *  title-only FTS rows and no edges (the PR-B invariants). */
    private suspend fun rebuildFtsAndLinks() {
        val allNotes = noteDao.getAll()
        db.withTransaction {
            ftsDao.deleteAll()
            linkDao.deleteAll()
            for (note in allNotes) {
                if (note.trashedAt != null) continue
                ftsDao.insert(
                    NoteFtsEntity(
                        noteId = note.id,
                        title = note.title,
                        plainText = if (note.locked) "" else note.plainText,
                    ),
                )
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

    companion object {
        fun wordCount(plainText: String): Int =
            plainText.split(Regex("\\s+")).count { it.isNotEmpty() }

        fun sanitiseStoredName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "file" }
    }
}

// ------------------------------------------------------------ mappings

private fun BxFolder.toEntity(): FolderEntity = FolderEntity(
    id = id, name = name, parentId = parentId, createdAt = createdAt, updatedAt = updatedAt,
)

private fun BxNote.toEntity(validFolderIds: Set<String>): NoteEntity {
    val locked = lockJson != null
    return NoteEntity(
        id = id,
        folderId = folderId?.takeIf { it in validFolderIds },
        title = title,
        // Locked notes bypass ALL content processing: envelope + lock
        // record are stored verbatim (PR-B invariants).
        contentHtml = content,
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

private fun BxReminder.toEntity(): ReminderEntity = ReminderEntity(
    id = id,
    noteId = noteId,
    triggerAt = scheduledAt,
    repeatRule = null,
    enabled = status == "pending",
    createdAt = createdAt,
)
