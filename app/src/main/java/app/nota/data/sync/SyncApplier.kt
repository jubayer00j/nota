package app.nota.data.sync

import android.content.Context
import androidx.room.withTransaction
import app.nota.data.attachments.AttachmentStore
import app.nota.data.backup.BackupReader
import app.nota.data.backup.BackupRepository
import app.nota.data.backup.BackupWriter
import app.nota.data.backup.BxAttachmentMeta
import app.nota.data.backup.ImageBounds
import app.nota.data.backup.rebuildLibraryIndexes
import app.nota.data.backup.toEntity
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NoteTagCrossRef
import app.nota.data.templates.TemplateRepository
import java.io.File

/**
 * Applies a MERGED snapshot to the active database (Phase 3c). The
 * merge ([SyncEngine]) already decided everything; this writes the
 * decision — the sync counterpart of a restore's apply, with three
 * sync-specific rules:
 *
 * - Deletions are bounded by knowledge: a local entity is removed
 *   only when sync KNEW it (it was in the base state or the remote
 *   snapshot) and the merge dropped it. A local row the merge never
 *   saw was created during the sync and is left for the next cycle.
 * - Attachment binaries are written only when their content
 *   actually changed (hash-compared); unchanged attachments keep
 *   their files — and their existing local paths — untouched.
 * - Templates route to the template store, never into Room (the
 *   same partition restores keep).
 */
class SyncApplier(
    private val context: Context,
    private val provider: DatabaseProvider,
    private val attachmentStore: AttachmentStore,
    private val templateRepository: TemplateRepository,
) {
    /**
     * Outcome-shaped entry point for the Drive coordinator: derives
     * the deletion knowledge (local ∪ remote ids per entity — a row
     * created locally DURING the cycle is in neither and is therefore
     * protected) and forwards to [apply].
     */
    suspend fun applyMerged(
        merged: BackupWriter.ExportData,
        local: BackupWriter.ExportData,
        remote: BackupWriter.ExportData,
        outcome: SyncEngine.Outcome,
        remoteHandle: BackupReader.Handle?,
        localAttachmentContent: Map<String, String>,
    ) {
        val known = mutableMapOf<String, Set<String>>()
        known[EntityHash.ENTITY_NOTE] =
            (local.notes.map { it.id } + remote.notes.map { it.id }).toSet()
        known[EntityHash.ENTITY_FOLDER] =
            (local.folders.map { it.id } + remote.folders.map { it.id }).toSet()
        known[EntityHash.ENTITY_TAG] =
            (local.tags.map { it.id } + remote.tags.map { it.id }).toSet()
        known[EntityHash.ENTITY_REMINDER] =
            (local.reminders.map { it.id } + remote.reminders.map { it.id }).toSet()
        known[EntityHash.ENTITY_ATTACHMENT] =
            (local.attachments.map { it.id } + remote.attachments.map { it.id }).toSet()
        apply(
            merged = merged,
            remoteHandle = remoteHandle,
            conflictAttachmentSources = outcome.conflictAttachmentSources,
            knownEntityIds = known,
            mergedAttachmentContent = outcome.mergedAttachmentContent,
            localAttachmentContent = localAttachmentContent,
        )
    }

    suspend fun apply(
        merged: BackupWriter.ExportData,
        remoteHandle: BackupReader.Handle?,
        conflictAttachmentSources: Map<String, Pair<String, Boolean>>,
        knownEntityIds: Map<String, Set<String>>,
        mergedAttachmentContent: Map<String, String>,
        localAttachmentContent: Map<String, String>,
    ) {
        val db = provider.current
        val noteDao = db.noteDao()
        val folderDao = db.folderDao()
        val tagDao = db.tagDao()
        val attachmentDao = db.attachmentDao()
        val reminderDao = db.reminderDao()

        val templateNotes = merged.notes.filter { it.isTemplate }
        val regularNotes = merged.notes.filter { !it.isTemplate }
        val mergedNoteIds = regularNotes.map { it.id }.toSet()
        val validFolderIds = merged.folders.map { it.id }.toSet()
        val stagedById = remoteHandle?.attachments?.associateBy { it.meta.id } ?: emptyMap()

        // ---- Attachment plan (files land before rows commit, like
        // a restore; a failed byte copy aborts the whole apply).
        data class PlannedAttachment(val row: AttachmentEntity, val bytesFrom: File?)
        val planned = mutableListOf<PlannedAttachment>()
        for (meta in merged.attachments) {
            if (meta.noteId !in mergedNoteIds) continue
            val existing = attachmentDao.getById(meta.id)
            val cloneSource = conflictAttachmentSources[meta.id]
            val bytesFrom: File? = when {
                cloneSource != null -> {
                    val (sourceId, sourceIsRemote) = cloneSource
                    if (sourceIsRemote) {
                        stagedById[sourceId]?.stagedFile
                    } else {
                        attachmentDao.getById(sourceId)?.let { attachmentStore.fileFor(it) }
                    } ?: stagedById[meta.id]?.stagedFile
                }
                existing == null -> stagedById[meta.id]?.stagedFile
                localAttachmentContent[meta.id] != mergedAttachmentContent[meta.id] ->
                    stagedById[meta.id]?.stagedFile
                else -> null // unchanged: keep the local file as-is
            }
            val localPath = existing?.localPath
                ?: "attachments/${meta.noteId}/${storedNameFor(meta)}"
            val row = AttachmentEntity(
                id = meta.id,
                noteId = meta.noteId,
                fileName = meta.name,
                mimeType = meta.mimeType,
                sizeBytes = bytesFrom?.length() ?: existing?.sizeBytes ?: meta.size,
                localPath = localPath,
                widthPx = existing?.widthPx,
                heightPx = existing?.heightPx,
                createdAt = meta.createdAt,
                encrypted = meta.encrypted,
            )
            planned += PlannedAttachment(row, bytesFrom)
        }
        // Write binaries whose content changed (or are new/clones).
        for (plan in planned) {
            val from = plan.bytesFrom ?: continue
            val target = File(context.filesDir, plan.row.localPath)
            val root = File(context.filesDir, "attachments").canonicalFile
            check(target.canonicalFile.path.startsWith(root.path + File.separator)) {
                "Attachment path escapes the attachment root"
            }
            target.parentFile?.mkdirs()
            from.inputStream().buffered().use { input ->
                target.outputStream().buffered().use { out -> input.copyTo(out) }
            }
            // Image bounds for fresh unencrypted images (display parity
            // with the restore path).
            if (!plan.row.encrypted && plan.row.mimeType.startsWith("image/") &&
                plan.row.widthPx == null
            ) {
                val (w, h) = ImageBounds.read(target)
                val index = planned.indexOf(plan)
                planned[index] = PlannedAttachment(plan.row.copy(widthPx = w, heightPx = h), from)
            }
        }

        db.withTransaction {
            for (folder in merged.folders) folderDao.upsert(folder.toEntity())
            for (tag in merged.tags) {
                tagDao.upsert(
                    app.nota.data.db.TagEntity(id = tag.id, name = tag.name, createdAt = tag.createdAt),
                )
            }
            val acceptableTagIds = merged.tags.map { it.id }.toSet() +
                tagDao.getAllTags().map { it.id }
            for (note in regularNotes) {
                noteDao.upsert(note.toEntity(validFolderIds))
                tagDao.unlinkAllForNote(note.id)
                val seen = HashSet<String>()
                for (tagId in note.tagIds) {
                    if (tagId in acceptableTagIds && seen.add(tagId)) {
                        tagDao.link(NoteTagCrossRef(noteId = note.id, tagId = tagId))
                    }
                }
            }
            for (reminder in merged.reminders) {
                if (reminder.noteId in mergedNoteIds) {
                    val incoming = reminder.toEntity()
                    val existing = reminderDao.getById(reminder.id)
                    // Device-local fields (Phase 5.1 alertType, PR #45
                    // tone quartet) are NOT in the sync wire format —
                    // toEntity() defaults them. A background sync must
                    // not silently unpick choices the user made on
                    // THIS device, so an existing row's local fields
                    // carry over the merge (Codex PR #45 round 1).
                    reminderDao.upsert(
                        if (existing == null) {
                            incoming
                        } else {
                            incoming.copy(
                                alertType = existing.alertType,
                                alarmToneMode = existing.alarmToneMode,
                                alarmToneUri = existing.alarmToneUri,
                                alarmToneName = existing.alarmToneName,
                                alarmVibrate = existing.alarmVibrate,
                                // PR #53: the repeat rule is
                                // device-local on the same terms — a
                                // merge never strips a series this
                                // device is running.
                                repeatRule = existing.repeatRule,
                            )
                        },
                    )
                }
            }
            for (plan in planned) attachmentDao.upsert(plan.row)

            // ---- Bounded deletions (see class doc).
            deleteKnown(
                EntityHash.ENTITY_NOTE,
                noteDao.getAll().map { it.id },
                mergedNoteIds,
                knownEntityIds,
            ) { noteDao.delete(noteDao.getById(it)!!) }
            deleteKnown(
                EntityHash.ENTITY_FOLDER,
                folderDao.getAll().map { it.id },
                merged.folders.map { it.id }.toSet(),
                knownEntityIds,
            ) { folderDao.delete(folderDao.getById(it)!!) }
            val localTagIds = tagDao.getAllTags().map { it.id }
            deleteKnown(
                EntityHash.ENTITY_TAG,
                localTagIds,
                merged.tags.map { it.id }.toSet(),
                knownEntityIds,
            ) { tagId ->
                tagDao.getById(tagId)?.let { tagDao.delete(it) }
            }
            deleteKnown(
                EntityHash.ENTITY_REMINDER,
                reminderDao.getAll().map { it.id },
                merged.reminders.map { it.id }.toSet(),
                knownEntityIds,
            ) { reminderDao.delete(reminderDao.getById(it)!!) }
            deleteKnown(
                EntityHash.ENTITY_ATTACHMENT,
                attachmentDao.getAll().map { it.id },
                planned.map { it.row.id }.toSet(),
                knownEntityIds,
            ) { attachmentDao.delete(attachmentDao.getById(it)!!) }

            SyncStore(db).replaceTombstones(merged.tombstones)
        }

        // ---- Templates: the separate partition, as in restores.
        val knownNoteIds = knownEntityIds[EntityHash.ENTITY_NOTE] ?: emptySet()
        val mergedTemplateIds = templateNotes.map { it.id }.toSet()
        for (template in templateNotes) {
            templateRepository.importTemplate(
                id = template.id,
                name = template.title.ifBlank { "Imported template" },
                title = template.title,
                contentHtml = template.content,
            )
        }
        for (knownId in knownNoteIds) {
            // A template the merge dropped (deleted on another
            // device) leaves the template store too; regular notes
            // were handled in the transaction above.
            if (knownId !in mergedNoteIds && knownId !in mergedTemplateIds) {
                templateRepository.delete(knownId)
            }
        }

        rebuildLibraryIndexes(db)
        attachmentStore.reconcile(
            allProvider = { attachmentDao.getAll() },
            extraKnownPaths = { provider.allAttachmentLocalPaths() },
        )
    }

    private suspend fun deleteKnown(
        entity: String,
        localIds: List<String>,
        mergedIds: Set<String>,
        knownEntityIds: Map<String, Set<String>>,
        delete: suspend (String) -> Unit,
    ) {
        val known = knownEntityIds[entity] ?: return
        for (id in localIds) {
            if (id in known && id !in mergedIds) delete(id)
        }
    }

    private fun storedNameFor(meta: BxAttachmentMeta): String =
        BackupRepository.sanitiseStoredName(
            meta.rawEntrySuffix
                ?.removeSuffix(".encrypted")
                ?.takeIf { it.isNotBlank() }
                ?: "${meta.id}-${meta.name}",
        )
}
