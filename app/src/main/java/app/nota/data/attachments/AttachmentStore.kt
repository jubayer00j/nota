package app.nota.data.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
        // Codex fix G7: read the EXIF orientation BEFORE decoding so the
        // bitmap can be uprighted before the JPEG re-encode; without this
        // portrait photos (orientation 6/8) import sideways. Sources with
        // no EXIF (screenshots, PNGs, unreadable metadata) default to
        // ORIENTATION_NORMAL and pass through untouched.
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: error("Could not read image")
        bitmap = applyExifOrientation(bitmap, orientation)
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

    /**
     * Runs [block] under the same [fileMutex] that serialises imports,
     * deletes and [reconcile] (Phase 2b, Codex review PR #8). A multi-step
     * transition such as note locking — snapshot the note's attachments,
     * rewrite their files, flip their rows, persist the note — must not
     * interleave with an import: an import whose row lands after the
     * snapshot would otherwise stay plaintext under a freshly locked
     * note, with no later save guaranteed to seal it. Imports hold the
     * mutex end-to-end, so a lock block and an import can never overlap.
     * The mutex is NOT reentrant: inside [block], use the `…Locked`
     * variants below, never the public primitives.
     */
    suspend fun <T> runExclusive(block: suspend () -> T): T = fileMutex.withLock { block() }

    // --- Per-note lock support (Phase 2b) --------------------------------
    // All file mutation for sealing/unsealing goes through these
    // primitives so it stays under the same [fileMutex] discipline as
    // imports/deletes/reconcile (the F9 race): the lock repository never
    // touches attachment files directly.

    /** Reads an attachment's current on-disk bytes (raw payload, or the
     *  envelope text bytes while the owning note is locked). */
    suspend fun readBytes(attachment: AttachmentEntity): ByteArray = fileMutex.withLock {
        readBytesLocked(attachment)
    }

    /** [readBytes] for callers already holding the mutex via [runExclusive]. */
    internal suspend fun readBytesLocked(attachment: AttachmentEntity): ByteArray =
        withContext(Dispatchers.IO) { fileFor(attachment).readBytes() }

    /**
     * Overwrites an attachment's file with [newBytes] and updates its row
     * (size + [AttachmentEntity.encrypted] flag) in the same locked step.
     * Used to seal (envelope in, encrypted=true) and unseal (payload back,
     * encrypted=false) without any window where file and row disagree
     * about the payload form.
     */
    suspend fun rewriteBytes(
        attachment: AttachmentEntity,
        newBytes: ByteArray,
        encrypted: Boolean,
    ): AttachmentEntity = fileMutex.withLock {
        rewriteBytesLocked(attachment, newBytes, encrypted)
    }

    /** [rewriteBytes] for callers already holding the mutex via [runExclusive]. */
    internal suspend fun rewriteBytesLocked(
        attachment: AttachmentEntity,
        newBytes: ByteArray,
        encrypted: Boolean,
    ): AttachmentEntity = withContext(Dispatchers.IO) {
        val file = fileFor(attachment)
        file.parentFile?.mkdirs()
        file.writeBytes(newBytes)
        val updated = attachment.copy(sizeBytes = newBytes.size.toLong(), encrypted = encrypted)
        attachmentDao.upsert(updated)
        updated
    }

    // --- Editor session temp files (Phase 2b) -----------------------------
    // While a locked note is open, its decrypted attachment bytes live
    // ONLY here (cacheDir — never filesDir, never Room), keyed per note,
    // and are wiped when the session ends. The editor resolves them
    // through the same fileFor-style seam it uses for stored files.

    private fun sessionDir(noteId: String): File =
        File(File(context.cacheDir, "nota-session"), noteId)

    fun sessionFile(noteId: String, attachment: AttachmentEntity): File =
        File(sessionDir(noteId), attachment.id + ".bin")

    /** Writes decrypted session bytes for [attachment]; returns the file. */
    fun writeSessionBytes(noteId: String, attachment: AttachmentEntity, bytes: ByteArray): File {
        val file = sessionFile(noteId, attachment)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    /** Deletes every session temp file for [noteId] (session end). */
    fun clearSession(noteId: String) {
        sessionDir(noteId).deleteRecursively()
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

    /**
     * Returns [bitmap] transformed to its EXIF-intended orientation
     * (rotations AND mirror flips), recycling the source when a new
     * bitmap is produced. ORIENTATION_NORMAL / undefined / unknown
     * values return the bitmap unchanged (Codex fix G7).
     */
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.postRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
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
