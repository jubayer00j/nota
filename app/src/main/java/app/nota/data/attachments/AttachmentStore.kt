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
    private val root: File
        get() = File(context.filesDir, "attachments")

    fun fileFor(attachment: AttachmentEntity): File = File(context.filesDir, attachment.localPath)

    suspend fun importImage(noteId: String, uri: Uri): AttachmentEntity = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        require(width > 0 && height > 0) { "Not a readable image" }
        var sample = 1
        while (width / (sample * 2) >= MAX_IMAGE_DIM || height / (sample * 2) >= MAX_IMAGE_DIM) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: error("Could not read image")
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

    suspend fun importFile(noteId: String, uri: Uri): AttachmentEntity = withContext(Dispatchers.IO) {
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

    suspend fun delete(attachment: AttachmentEntity) = withContext(Dispatchers.IO) {
        fileFor(attachment).delete()
        attachmentDao.delete(attachment)
    }

    /**
     * Orphan reconciliation (spec §7 parity): removes files no row points
     * at and rows whose file is gone. Returns (filesRemoved, rowsRemoved).
     */
    suspend fun reconcile(all: List<AttachmentEntity>): Pair<Int, Int> = withContext(Dispatchers.IO) {
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
