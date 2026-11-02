package app.nota.data.sync

import app.nota.data.backup.BxTombstone
import app.nota.data.db.NotaDatabase
import app.nota.data.db.SyncMetaEntity
import app.nota.data.db.TombstoneEntity
import java.util.UUID

/**
 * The per-account sync bookkeeping (Phase 3c) over the database's
 * sync tables: the device id, the three-way merge's base state, and
 * the tombstone set. One store per database instance — construct it
 * from whichever [NotaDatabase] is in play (the active account's, in
 * production).
 */
class SyncStore(private val db: NotaDatabase) {

    /** The merge base: what the archive looked like the last time
     *  this device synced (or pushed), as remote fingerprint +
     *  per-entity content hashes ("entity:id" → hash). */
    data class BaseState(
        val lastSyncAt: Long = 0L,
        val lastRemoteModifiedTime: String? = null,
        val lastRemoteChecksum: String? = null,
        val hashes: Map<String, String> = emptyMap(),
    )

    private val dao get() = db.syncDao()

    /** This device's stable id for the account (generated once,
     *  then read back forever). Rides in the archive manifest and
     *  tombstones, as 1.x's deviceId does. */
    suspend fun ensureDeviceId(): String {
        dao.getMetaValue(KEY_DEVICE_ID)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        dao.putMeta(SyncMetaEntity(KEY_DEVICE_ID, fresh))
        return fresh
    }

    suspend fun loadBaseState(): BaseState = BaseState(
        lastSyncAt = dao.getMetaValue(KEY_LAST_SYNC_AT)?.toLongOrNull() ?: 0L,
        lastRemoteModifiedTime = dao.getMetaValue(KEY_LAST_REMOTE_MODIFIED),
        lastRemoteChecksum = dao.getMetaValue(KEY_LAST_REMOTE_CHECKSUM),
        hashes = parseHashes(dao.getMetaValue(KEY_BASE_HASHES)),
    )

    suspend fun saveBaseState(state: BaseState) {
        dao.putMeta(SyncMetaEntity(KEY_LAST_SYNC_AT, state.lastSyncAt.toString()))
        dao.putMeta(SyncMetaEntity(KEY_LAST_REMOTE_MODIFIED, state.lastRemoteModifiedTime ?: ""))
        dao.putMeta(SyncMetaEntity(KEY_LAST_REMOTE_CHECKSUM, state.lastRemoteChecksum ?: ""))
        dao.putMeta(SyncMetaEntity(KEY_BASE_HASHES, renderHashes(state.hashes)))
    }

    suspend fun tombstones(): List<BxTombstone> =
        dao.getTombstones().map { BxTombstone(it.entity, it.id, it.deletedAt, it.deviceId) }

    /** Records a local hard deletion (the repositories' delete
     *  hooks call this inside their own transaction). */
    suspend fun recordTombstone(entity: String, id: String, deletedAt: Long) {
        dao.upsertTombstone(
            TombstoneEntity(entity, id, deletedAt, ensureDeviceId()),
        )
    }

    /** Replaces the stored set with a merged one (sync apply). */
    suspend fun replaceTombstones(tombstones: List<BxTombstone>) {
        dao.deleteAllTombstones()
        for (tombstone in tombstones) {
            dao.upsertTombstone(
                TombstoneEntity(tombstone.entity, tombstone.id, tombstone.deletedAt, tombstone.deviceId),
            )
        }
    }

    /** 90-day retention (1.x): older tombstones are purged at sync
     *  time — by then every live device has had its chance to see
     *  the deletion. */
    suspend fun purgeOldTombstones(nowMillis: Long) {
        dao.purgeTombstonesOlderThan(nowMillis - RETENTION_MILLIS)
    }

    companion object {
        const val RETENTION_MILLIS: Long = 90L * 24 * 3_600_000

        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LAST_REMOTE_MODIFIED = "last_remote_modified"
        private const val KEY_LAST_REMOTE_CHECKSUM = "last_remote_checksum"
        private const val KEY_BASE_HASHES = "base_hashes"

        /** "entity:id=hash" lines, sorted — a stable, JSON-free
         *  encoding for a string map in a key-value table. */
        fun renderHashes(hashes: Map<String, String>): String =
            hashes.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }

        fun parseHashes(rendered: String?): Map<String, String> {
            if (rendered.isNullOrEmpty()) return emptyMap()
            return rendered.split("\n").mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
            }.toMap()
        }
    }
}
