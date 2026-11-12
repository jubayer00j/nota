package app.nota.data.sync

import app.nota.data.backup.BackupReader
import app.nota.data.backup.BackupWriter
import app.nota.data.backup.BxAttachmentMeta
import app.nota.data.backup.BxFolder
import app.nota.data.backup.BxNote
import app.nota.data.backup.BxReminder
import app.nota.data.backup.BxTag
import app.nota.data.backup.BxTombstone

/** A read archive as a snapshot the merge can consume. */
fun BackupReader.Handle.toExportData(): BackupWriter.ExportData =
    BackupWriter.ExportData(
        manifest = manifest,
        folders = folders,
        tags = tags,
        reminders = reminders,
        syncSyncedAtIso = manifest.createdAt ?: "",
        notes = notes,
        attachments = attachments.map { it.meta },
        tombstones = tombstones,
    )

/**
 * The three-way merge (Phase 3c) — 1.x `sync.ts` semantics over
 * archive snapshots, pure and deterministic so the whole behaviour
 * is unit-testable without Android or network:
 *
 * - Change detection is by per-entity content hash vs the recorded
 *   base (what the archive held at the last sync), never by
 *   timestamps alone; `updatedAt` only picks a winner when both
 *   sides changed the same entity.
 * - Notes changed on both sides: newest wins the original; the
 *   loser is preserved as a "(conflict copy)" note — content is
 *   never silently overwritten. Other entity types: newest wins.
 * - Deletions travel as tombstones (union, newest per entity,
 *   90-day retention): a tombstone kills an entity whose
 *   updatedAt is not newer than the deletion; a newer edit revives
 *   the entity and drops the tombstone.
 * - An entity missing on one side WITHOUT a tombstone is kept
 *   (absence is not deletion — the other side may simply hold an
 *   archive from before the entity existed, e.g. a plain backup).
 * - Settings never appear here at all: snapshots carry none.
 */
object SyncEngine {

    data class Outcome(
        val merged: BackupWriter.ExportData,
        /** Hashes of the merged snapshot — the next cycle's base. */
        val newBaseHashes: Map<String, String>,
        val mergedTombstones: List<BxTombstone>,
        /** Titles of the conflict copies created (user-visible). */
        val conflictTitles: List<String>,
        /** Conflict-clone attachment id → (source attachment id,
         *  source was the remote side). The applier resolves bytes. */
        val conflictAttachmentSources: Map<String, Pair<String, Boolean>>,
        /** Content SHA-256 of every merged attachment (by id) — the
         *  applier compares against local files to decide which
         *  binaries actually need writing. */
        val mergedAttachmentContent: Map<String, String>,
        /** Merged entities whose hash differs from the local /
         *  remote snapshot (pull / push effect sizes). */
        val changedVsLocal: Int,
        val changedVsRemote: Int,
    )

    private class ConflictInfo(
        val copyId: String,
        val loserIsRemote: Boolean,
        val winnerAttachments: Set<String>,
        val loserAttachments: Map<String, BxAttachmentMeta>,
    )

    fun merge(
        local: BackupWriter.ExportData,
        remote: BackupWriter.ExportData,
        baseHashes: Map<String, String>,
        localAttachmentContent: Map<String, String>,
        remoteAttachmentContent: Map<String, String>,
        nowMillis: Long,
        nowIso: String,
        newId: () -> String,
    ): Outcome {
        // ---- Tombstones: union, newest per entity, 90-day purge.
        val cutoff = nowMillis - SyncStore.RETENTION_MILLIS
        val tombstoneMap = LinkedHashMap<Pair<String, String>, BxTombstone>()
        for (tombstone in local.tombstones + remote.tombstones) {
            val key = tombstone.entity to tombstone.id
            val existing = tombstoneMap[key]
            if (existing == null || tombstone.deletedAt > existing.deletedAt) {
                tombstoneMap[key] = tombstone
            }
        }
        val tombstones = tombstoneMap.values.filter { it.deletedAt >= cutoff }.toMutableList()
        fun tombstoneFor(entity: String, id: String): BxTombstone? =
            tombstones.firstOrNull { it.entity == entity && it.id == id }

        /** The tombstone verdict for an entity version: true = the
         *  deletion is the newest word (entity dies); a version
         *  NEWER than the deletion revives it and consumes the
         *  tombstone. */
        fun killedByTombstone(entity: String, id: String, updatedAt: Long): Boolean {
            val tombstone = tombstoneFor(entity, id) ?: return false
            if (tombstone.deletedAt >= updatedAt) return true
            tombstones.remove(tombstone)
            return false
        }

        // ---- Folders / tags / reminders: newest-wins, no copies.
        val mergedFolders = mergeSimple(
            local.folders, remote.folders, EntityHash.ENTITY_FOLDER,
            { it.id }, { EntityHash.folderHash(it) }, { it.updatedAt },
            baseHashes, ::killedByTombstone,
        )
        val mergedTags = mergeSimple(
            local.tags, remote.tags, EntityHash.ENTITY_TAG,
            { it.id }, { EntityHash.tagHash(it) }, { it.updatedAt ?: it.createdAt },
            baseHashes, ::killedByTombstone,
        )
        val mergedReminders = mergeSimple(
            local.reminders, remote.reminders, EntityHash.ENTITY_REMINDER,
            { it.id }, { EntityHash.reminderHash(it) }, { it.updatedAt },
            baseHashes, ::killedByTombstone,
        )

        // ---- Notes: conflicts become copies.
        val localNotes = local.notes.associateBy { it.id }
        val remoteNotes = remote.notes.associateBy { it.id }
        val mergedNotes = mutableListOf<BxNote>()
        val conflictTitles = mutableListOf<String>()
        val conflictInfos = mutableMapOf<String, ConflictInfo>()
        val killedNoteIds = mutableSetOf<String>()

        for (id in localNotes.keys + remoteNotes.keys) {
            val l = localNotes[id]
            val r = remoteNotes[id]
            // Tombstone first, judged against BOTH versions: if the
            // deletion is newer than every version, the note dies —
            // no conflict copy (the deletion is the latest word).
            val newestVersion = maxOf(l?.updatedAt ?: Long.MIN_VALUE, r?.updatedAt ?: Long.MIN_VALUE)
            if (tombstoneFor(EntityHash.ENTITY_NOTE, id) != null &&
                killedByTombstone(EntityHash.ENTITY_NOTE, id, newestVersion)
            ) {
                killedNoteIds += id
                continue
            }
            if (l != null && r != null) {
                val lh = EntityHash.noteHash(l)
                val rh = EntityHash.noteHash(r)
                val bh = baseHashes[EntityHash.key(EntityHash.ENTITY_NOTE, id)]
                when {
                    lh == rh -> mergedNotes += l
                    bh == lh -> mergedNotes += r
                    bh == rh -> mergedNotes += l
                    else -> {
                        val winner = if (r.updatedAt > l.updatedAt) r else l
                        val loser = if (winner === r) l else r
                        val copyId = newId()
                        val copy = loser.copy(
                            id = copyId,
                            title = loser.title + " (conflict copy)",
                        )
                        mergedNotes += winner
                        mergedNotes += copy
                        conflictTitles += copy.title
                        val loserIsRemote = loser === r
                        val winnerSide = if (loserIsRemote) local else remote
                        val loserSide = if (loserIsRemote) remote else local
                        conflictInfos[id] = ConflictInfo(
                            copyId = copyId,
                            loserIsRemote = loserIsRemote,
                            winnerAttachments = winnerSide.attachments
                                .filter { it.noteId == id }.map { it.id }.toSet(),
                            loserAttachments = loserSide.attachments
                                .filter { it.noteId == id }.associateBy { it.id },
                        )
                    }
                }
            } else {
                val only = l ?: r
                if (only != null) mergedNotes += only
            }
        }

        // ---- Attachments: generic merge + conflict clones + cascade.
        val localAttachments = local.attachments.associateBy { it.id }
        val remoteAttachments = remote.attachments.associateBy { it.id }
        val mergedAttachments = mutableListOf<BxAttachmentMeta>()
        val mergedAttachmentContent = mutableMapOf<String, String>()
        val conflictAttachmentSources = mutableMapOf<String, Pair<String, Boolean>>()
        // Conflict-copy body rewrites (Codex P2): copyId -> (loser
        // attachment id -> clone id), applied to the copies' content
        // before assembly so their inline images resolve to the
        // clones, not the winner's attachments.
        val copyRewrites = mutableMapOf<String, MutableMap<String, String>>()

        fun addMergedAttachment(meta: BxAttachmentMeta, contentSha: String) {
            mergedAttachments += meta
            mergedAttachmentContent[meta.id] = contentSha
        }

        for (id in localAttachments.keys + remoteAttachments.keys) {
            val l = localAttachments[id]
            val r = remoteAttachments[id]
            val any = l ?: r ?: continue
            if (any.noteId in killedNoteIds) continue
            val conflict = conflictInfos[any.noteId]
            if (conflict != null) {
                val inWinner = id in conflict.winnerAttachments
                val loserMeta = conflict.loserAttachments[id]
                if (inWinner) {
                    // The winner's own attachment: normal hash merge,
                    // but WITHOUT the generic loser clone — the losing
                    // bytes belong to the conflict copy (below), never
                    // as an unreferenced extra row on the winner
                    // (Codex P2).
                    addAttachmentGeneric(
                        id, l, r, baseHashes, localAttachmentContent, remoteAttachmentContent,
                        ::killedByTombstone, ::addMergedAttachment,
                        newId, conflictAttachmentSources,
                        allowLoserClone = false,
                    )
                    // The copy ALWAYS gets its own clone of the
                    // loser's version of this attachment — even when
                    // the bytes are identical (Codex P2): the copy's
                    // body must resolve through ITS OWN attachment
                    // rows, so a later deletion of the winner note
                    // (which tombstones its attachments) can never
                    // dangle the copy's inline images. Skipped only
                    // when the merge itself dropped the attachment
                    // (tombstone-killed above).
                    if (loserMeta != null && mergedAttachmentContent.containsKey(id)) {
                        val cloneId = newId()
                        addMergedAttachment(
                            loserMeta.copy(id = cloneId, noteId = conflict.copyId),
                            contentOfSide(
                                loserMeta, conflict.loserIsRemote,
                                localAttachmentContent, remoteAttachmentContent,
                            ),
                        )
                        conflictAttachmentSources[cloneId] = id to conflict.loserIsRemote
                        copyRewrites.getOrPut(conflict.copyId) { mutableMapOf() }[id] = cloneId
                    }
                } else if (loserMeta != null) {
                    // Loser-only attachment: clone under the copy.
                    val cloneId = newId()
                    addMergedAttachment(
                        loserMeta.copy(id = cloneId, noteId = conflict.copyId),
                        contentOfSide(loserMeta, conflict.loserIsRemote, localAttachmentContent, remoteAttachmentContent),
                    )
                    conflictAttachmentSources[cloneId] = id to conflict.loserIsRemote
                    copyRewrites.getOrPut(conflict.copyId) { mutableMapOf() }[id] = cloneId
                }
                continue
            }
            addAttachmentGeneric(
                id, l, r, baseHashes, localAttachmentContent, remoteAttachmentContent,
                ::killedByTombstone, ::addMergedAttachment,
                newId, conflictAttachmentSources,
            )
        }

        // Reminders of killed notes cascade away (FK parity).
        val finalReminders = mergedReminders.filter { it.noteId !in killedNoteIds }

        // ---- Conflict copies: point their inline attachment
        // references at the clones: the archive dialect
        // "attachments/<id>", the editor dialect
        // "nota-attachment://<id>", and the segment attribute
        // forms data-nota-file="<id>" / data-nota-audio="<id>"
        // (quoted, so no partial-id collisions). The attribute
        // forms were the PR #51 Codex P2: an audio (or file)
        // block in a conflict copy kept pointing at the winner
        // note's attachment row while its own clone went
        // unreferenced, so deleting the winner dangled it.
        if (copyRewrites.isNotEmpty()) {
            for (index in mergedNotes.indices) {
                val note = mergedNotes[index]
                val rewrites = copyRewrites[note.id] ?: continue
                var content = note.content
                for ((oldId, cloneId) in rewrites.entries.sortedByDescending { it.key.length }) {
                    content = content
                        .replace("attachments/$oldId", "attachments/$cloneId")
                        .replace("nota-attachment://$oldId", "nota-attachment://$cloneId")
                        .replace("data-nota-file=\"$oldId\"", "data-nota-file=\"$cloneId\"")
                        .replace("data-nota-audio=\"$oldId\"", "data-nota-audio=\"$cloneId\"")
                }
                if (content != note.content) {
                    mergedNotes[index] = note.copy(content = content)
                }
            }
        }

        // ---- Assemble merged snapshot + new base hashes.
        val mergedHashes = mutableMapOf<String, String>()
        for (folder in mergedFolders) {
            mergedHashes[EntityHash.key(EntityHash.ENTITY_FOLDER, folder.id)] = EntityHash.folderHash(folder)
        }
        for (tag in mergedTags) {
            mergedHashes[EntityHash.key(EntityHash.ENTITY_TAG, tag.id)] = EntityHash.tagHash(tag)
        }
        for (reminder in finalReminders) {
            mergedHashes[EntityHash.key(EntityHash.ENTITY_REMINDER, reminder.id)] = EntityHash.reminderHash(reminder)
        }
        for (note in mergedNotes) {
            mergedHashes[EntityHash.key(EntityHash.ENTITY_NOTE, note.id)] = EntityHash.noteHash(note)
        }
        for (attachment in mergedAttachments) {
            mergedHashes[EntityHash.key(EntityHash.ENTITY_ATTACHMENT, attachment.id)] =
                EntityHash.attachmentHash(attachment, mergedAttachmentContent[attachment.id] ?: "")
        }

        val localHashes = snapshotHashes(local, localAttachmentContent)
        val remoteHashes = snapshotHashes(remote, remoteAttachmentContent)
        // Tombstone-set differences count too (Codex P1): a cycle
        // whose ONLY divergence is a tombstone (the entity is already
        // absent on the other side) must still push/pull, or the
        // tombstone never leaves this device and the deletion can
        // resurrect from a stale copy elsewhere.
        fun tombKey(t: BxTombstone): String = "${t.entity}:${t.id}:${t.deletedAt}"
        val mergedTombKeys = tombstones.map { tombKey(it) }.toSet()
        val localTombKeys = local.tombstones.map { tombKey(it) }.toSet()
        val remoteTombKeys = remote.tombstones.map { tombKey(it) }.toSet()
        val changedVsLocal = mergedHashes.count { (k, v) -> localHashes[k] != v } +
            localHashes.keys.count { it !in mergedHashes } +
            (mergedTombKeys union localTombKeys).size - (mergedTombKeys intersect localTombKeys).size
        val changedVsRemote = mergedHashes.count { (k, v) -> remoteHashes[k] != v } +
            remoteHashes.keys.count { it !in mergedHashes } +
            (mergedTombKeys union remoteTombKeys).size - (mergedTombKeys intersect remoteTombKeys).size

        val merged = BackupWriter.ExportData(
            manifest = local.manifest.copy(
                createdAt = nowIso,
                noteCount = mergedNotes.size,
                folderCount = mergedFolders.size,
                tagCount = mergedTags.size,
                attachmentCount = mergedAttachments.size,
                reminderCount = finalReminders.size,
            ),
            folders = mergedFolders,
            tags = mergedTags,
            reminders = finalReminders,
            syncSyncedAtIso = nowIso,
            notes = mergedNotes,
            attachments = mergedAttachments,
            tombstones = tombstones,
        )
        return Outcome(
            merged = merged,
            newBaseHashes = mergedHashes,
            mergedTombstones = tombstones,
            conflictTitles = conflictTitles,
            conflictAttachmentSources = conflictAttachmentSources,
            mergedAttachmentContent = mergedAttachmentContent,
            changedVsLocal = changedVsLocal,
            changedVsRemote = changedVsRemote,
        )
    }

    // ------------------------------------------------------------ helpers

    private fun contentOfSide(
        meta: BxAttachmentMeta,
        isRemote: Boolean,
        localContent: Map<String, String>,
        remoteContent: Map<String, String>,
    ): String = (if (isRemote) remoteContent else localContent)[meta.id] ?: ""

    private fun <T> mergeSimple(
        localList: List<T>,
        remoteList: List<T>,
        entity: String,
        idOfFn: (T) -> String,
        hashOf: (T) -> String,
        updatedOf: (T) -> Long,
        baseHashes: Map<String, String>,
        killedByTombstone: (String, String, Long) -> Boolean,
    ): List<T> {
        val localById = localList.associateBy(idOfFn)
        val remoteById = remoteList.associateBy(idOfFn)
        val result = mutableListOf<T>()
        for (id in localById.keys + remoteById.keys) {
            val l = localById[id]
            val r = remoteById[id]
            val winner: T? = if (l != null && r != null) {
                val lh = hashOf(l)
                val rh = hashOf(r)
                val bh = baseHashes[EntityHash.key(entity, id)]
                when {
                    lh == rh -> l
                    bh == lh -> r
                    bh == rh -> l
                    else -> if (updatedOf(r) > updatedOf(l)) r else l
                }
            } else {
                l ?: r
            }
            if (winner == null) continue
            if (killedByTombstone(entity, id, updatedOf(winner))) continue
            result += winner
        }
        return result
    }

    private fun addAttachmentGeneric(
        id: String,
        l: BxAttachmentMeta?,
        r: BxAttachmentMeta?,
        baseHashes: Map<String, String>,
        localContent: Map<String, String>,
        remoteContent: Map<String, String>,
        killedByTombstone: (String, String, Long) -> Boolean,
        add: (BxAttachmentMeta, String) -> Unit,
        newId: () -> String,
        conflictSources: MutableMap<String, Pair<String, Boolean>>,
        // False when the caller handles the losing bytes itself
        // (note-conflict path: they belong to the conflict copy, not
        // as an unreferenced clone on the winner note — Codex P2).
        allowLoserClone: Boolean = true,
    ) {
        val winner: BxAttachmentMeta
        val winnerContent: String
        // A both-sides content change clones the losing bytes under a
        // fresh id on the same note (the note itself is not in
        // conflict — only the bytes diverged). (meta, content, isRemote)
        var loserClone: Triple<BxAttachmentMeta, String, Boolean>? = null
        if (l != null && r != null) {
            val lh = EntityHash.attachmentHash(l, localContent[id] ?: "")
            val rh = EntityHash.attachmentHash(r, remoteContent[id] ?: "")
            val bh = baseHashes[EntityHash.key(EntityHash.ENTITY_ATTACHMENT, id)]
            when {
                lh == rh -> { winner = l; winnerContent = localContent[id] ?: "" }
                bh == lh -> { winner = r; winnerContent = remoteContent[id] ?: "" }
                bh == rh -> { winner = l; winnerContent = localContent[id] ?: "" }
                else -> {
                    val remoteWins = (r.updatedAt ?: r.createdAt) > (l.updatedAt ?: l.createdAt)
                    if (remoteWins) {
                        winner = r; winnerContent = remoteContent[id] ?: ""
                    } else {
                        winner = l; winnerContent = localContent[id] ?: ""
                    }
                    val loserContent =
                        if (remoteWins) localContent[id] ?: "" else remoteContent[id] ?: ""
                    if (allowLoserClone && loserContent != winnerContent) {
                        loserClone = Triple(
                            if (remoteWins) l else r,
                            loserContent,
                            !remoteWins,
                        )
                    }
                }
            }
        } else if (l != null) {
            winner = l; winnerContent = localContent[id] ?: ""
        } else if (r != null) {
            winner = r; winnerContent = remoteContent[id] ?: ""
        } else {
            return
        }
        if (killedByTombstone(EntityHash.ENTITY_ATTACHMENT, id, winner.updatedAt ?: winner.createdAt)) return
        add(winner, winnerContent)
        loserClone?.let { (loserMeta, content, loserIsRemote) ->
            val cloneId = newId()
            add(loserMeta.copy(id = cloneId), content)
            conflictSources[cloneId] = id to loserIsRemote
        }
    }

    /** Hashes of a full snapshot (attachment content supplied). */
    fun snapshotHashes(
        data: BackupWriter.ExportData,
        attachmentContent: Map<String, String>,
    ): Map<String, String> {
        val hashes = mutableMapOf<String, String>()
        for (folder in data.folders) {
            hashes[EntityHash.key(EntityHash.ENTITY_FOLDER, folder.id)] = EntityHash.folderHash(folder)
        }
        for (tag in data.tags) {
            hashes[EntityHash.key(EntityHash.ENTITY_TAG, tag.id)] = EntityHash.tagHash(tag)
        }
        for (reminder in data.reminders) {
            hashes[EntityHash.key(EntityHash.ENTITY_REMINDER, reminder.id)] = EntityHash.reminderHash(reminder)
        }
        for (note in data.notes) {
            hashes[EntityHash.key(EntityHash.ENTITY_NOTE, note.id)] = EntityHash.noteHash(note)
        }
        for (attachment in data.attachments) {
            hashes[EntityHash.key(EntityHash.ENTITY_ATTACHMENT, attachment.id)] =
                EntityHash.attachmentHash(attachment, attachmentContent[attachment.id] ?: "")
        }
        return hashes
    }
}
