package app.nota.data.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import app.nota.data.db.AttachmentDao
import app.nota.data.db.AttachmentEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Attachment file store (Phase 1c). Binaries live under
 * filesDir/attachments/<noteId>/ (excluded from platform cloud backup —
 * see res/xml/backup_rules.xml); Room's attachments table holds only
 * metadata, and [AttachmentEntity.localPath] is relative to filesDir.
 *
 * Images are downscaled (max [MAX_IMAGE_DIM] px) and re-encoded as JPEG —
 * the 1.x "compress on import" parity behaviour. Generic files are copied
 * byte-for-byte. [reconcile] is the orphan sweep from spec §7: files with
 * no metadata row are deleted, rows whose file vanished are dropped.
 */
class AttachmentStore(
    private val context: Context,
    private val attachmentDao: AttachmentDao,
) {
    /**
     * Serialises imports against [reconcile] (and deletes): an import
     * writes its file BEFORE its Room row exists, so an unsynchronised
     * reconcile snapshot could mistake the in-progress file for an
     * orphan and delete it (Codex F9). All file+row mutations take this.
     */
    private val fileMutex = Mutex()
    private val root: File
        get() = File(context.filesDir, "attachments")

    fun fileFor(attachment: AttachmentEntity): File = File(context.filesDir, attachment.localPath)

    suspend fun importImage(noteId: String, uri: Uri): AttachmentEntity =
        fileMutex.withLock { importImageLocked(noteId, uri) }

    private suspend fun importImageLocked(noteId: String, uri: Uri): AttachmentEntity = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        require(width > 0 && height > 0) { "Not a readable image" }
        // Codex fix G4: the sample must bring BOTH dimensions under the
        // cap (the old loop stopped as soon as the halved height dipped
        // below it and decoded e.g. 4000x3000 full-size).
        val sample = ImageSampling.inSampleSize(width, height, MAX_IMAGE_DIM)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: error("Could not read image")
        // Exact post-decode scale: sampling is power-of-two and decoders
        // may round, so enforce the cap precisely before re-encoding.
        val (fitW, fitH) = ImageSampling.fitWithin(bitmap.width, bitmap.height, MAX_IMAGE_DIM)
        if (fitW != bitmap.width || fitH != bitmap.height) {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, fitW, fitH, true)
            if (scaledBitmap !== bitmap) {
                bitmap.recycle()
                bitmap = scaledBitmap
            }
        }
        val dir = File(root, noteId).apply { mkdirs() }
        val fileName = "${UUID.randomUUID()}.jpg"
        val file = File(dir, fileName)
        file.outputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                "JPEG encode failed"
            }
        }
        val scaledW = bitmap.width
        val scaledH = bitmap.height
        bitmap.recycle()
        val entity = AttachmentEntity(
            id = UUID.randomUUID().toString(),
            noteId = noteId,
            fileName = displayName(uri) ?: fileName,
            mimeType = "image/jpeg",
            sizeBytes = file.length(),
            localPath = "attachments/$noteId/$fileName",
            widthPx = scaledW,
            heightPx = scaledH,
            createdAt = System.currentTimeMillis(),
        )
        attachmentDao.upsert(entity)
        entity
    }

    suspend fun importFile(noteId: String, uri: Uri): AttachmentEntity =
        fileMutex.withLock { importFileLocked(noteId, uri) }

    private suspend fun importFileLocked(noteId: String, uri: Uri): AttachmentEntity = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val originalName = displayName(uri) ?: "file"
        val safeName = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(root, noteId).apply { mkdirs() }
        val storedName = "${UUID.randomUUID()}-$safeName"
        val file = File(dir, storedName)
        resolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not read file")
        val entity = AttachmentEntity(
            id = UUID.randomUUID().toString(),
            noteId = noteId,
            fileName = originalName,
            mimeType = resolver.getType(uri) ?: "application/octet-stream",
            sizeBytes = file.length(),
            localPath = "attachments/$noteId/$storedName",
            createdAt = System.currentTimeMillis(),
        )
        attachmentDao.upsert(entity)
        entity
    }

    suspend fun delete(attachment: AttachmentEntity): Unit = fileMutex.withLock {
        deleteLocked(attachment)
    }

    private suspend fun deleteLocked(attachment: AttachmentEntity) = withContext(Dispatchers.IO) {
        fileFor(attachment).delete()
        attachmentDao.delete(attachment)
    }

    /**
     * Orphan reconciliation (spec §7 parity): removes files no row points
     * at and rows whose file is gone. Returns (filesRemoved, rowsRemoved).
     *
     * [allProvider] is invoked INSIDE [fileMutex] (Codex re-review N3):
     * computing the snapshot before acquiring the lock left a race — an
     * import completing after the snapshot but before the sweep would not
     * be in knownPaths, so its file would be deleted while its Room row
     * remained. Fetching under the same lock that serialises imports
     * closes that window.
     */
    suspend fun reconcile(
        allProvider: suspend () -> List<AttachmentEntity>,
    ): Pair<Int, Int> =
        fileMutex.withLock { reconcileLocked(allProvider()) }

    private suspend fun reconcileLocked(all: List<AttachmentEntity>): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var filesRemoved = 0
        var rowsRemoved = 0
        val knownPaths = all.map { it.localPath }.toSet()
        if (root.exists()) {
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(context.filesDir).path
                if (relative !in knownPaths) {
                    if (file.delete()) filesRemoved++
                }
            }
        }
        for (attachment in all) {
            if (!fileFor(attachment).exists()) {
                attachmentDao.delete(attachment)
                rowsRemoved++
            }
        }
        filesRemoved to rowsRemoved
    }

    private fun displayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else {
                        null
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val MAX_IMAGE_DIM = 2048
        const val JPEG_QUALITY = 85
    }
}
