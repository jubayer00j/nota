package app.nota.data.capture

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 5.2: turns door + share intents (forwarded by MainActivity)
 * into staged [CaptureRecord]s in [PendingCaptureStore].
 *
 * Shares are the delicate half. The sender's URI grant does not
 * survive — not a relock, not process death — so image bytes are
 * copied into the pending store PROMPTLY, on a background executor
 * (1.x reads its share streams off the bridge thread for the same
 * reason), with 1.x's caps enforced before and during the read
 * (max 10 images, max 10 MB each). Nothing here creates a note or an
 * attachment: staging is files only, and delivery happens in the
 * shell after unlock.
 *
 * A guard drops a re-delivery of the SAME Intent object (the
 * framework re-dispatches the launch intent), so one share can never
 * be staged twice — 1.x's `lastEnqueuedIntent` rule, verbatim. Two
 * genuine shares are always distinct Intent objects, so identity can
 * never eat a real second share.
 */
class CaptureIntake(
    context: Context,
    private val store: PendingCaptureStore,
) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialises staging so records land in the store in arrival order. */
    private val stageMutex = Mutex()

    @Volatile
    private var lastShareIntent: Intent? = null

    /** Door captures carry no payload — they stage synchronously. */
    fun stageDoor(door: String, source: CaptureSource, accountId: String) {
        val id = UUID.randomUUID().toString()
        val request: CaptureRequest = when (door) {
            CaptureIntents.DOOR_TODAY -> CaptureRequest.Today(id, source)
            else -> CaptureRequest.NewNote(id, source)
        }
        store.stage(
            CaptureRecord(id = id, accountId = accountId, request = request),
        )
    }

    /** Stages a share intent: parse is cheap (extras only); the
     *  image copy runs on the IO scope and the record lands in the
     *  store when the copy completes. A malformed share drops
     *  quietly rather than failing the app (1.x's rule). */
    fun stageShare(intent: Intent, accountId: String) {
        if (intent === lastShareIntent) return
        lastShareIntent = intent
        val parsed = ShareIntents.parse(intent)
        val intentType = intent.type
        scope.launch {
            runCatching { stageMutex.withLock { extractAndStage(parsed, intentType, accountId) } }
        }
    }

    private fun extractAndStage(parsed: ParsedShare, intentType: String?, accountId: String) {
        val recordId = UUID.randomUUID().toString()
        val dir = store.stagingDir(recordId)
        val resolver = appContext.contentResolver
        val rawImages = mutableListOf<RawShareImage>()
        parsed.streamUris
            .take(ShareNormalizer.MAX_IMAGES)
            .forEachIndexed { index, uri ->
                copySharedImage(resolver, intentType, uri, dir, index)
                    ?.let { rawImages += it }
            }
        val request = ShareNormalizer.normalize(
            RawSharePayload(
                subject = parsed.subject,
                text = parsed.text,
                images = rawImages,
            ),
            id = recordId,
        )
        store.stage(
            CaptureRecord(id = recordId, accountId = accountId, request = request),
        )
    }

    /** Copies one shared image into [dir], or returns null when the
     *  stream is unusable — 1.x's readImage rules. */
    private fun copySharedImage(
        resolver: ContentResolver,
        intentType: String?,
        uri: Uri,
        dir: File,
        index: Int,
    ): RawShareImage? {
        var mimeType = resolver.getType(uri)
        if (mimeType == null && intentType != null && intentType.startsWith("image/") &&
            intentType != "image/*"
        ) {
            mimeType = intentType
        }
        if (mimeType == null || !mimeType.startsWith("image/")) {
            // Some senders report no type, or a generic one like
            // application/octet-stream: trust the display name's
            // extension when it names a known image type.
            mimeType = when (
                queryDisplayName(resolver, uri)
                    ?.substringAfterLast('.', "")
                    ?.lowercase()
            ) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "heic" -> "image/heic"
                "heif" -> "image/heif"
                "bmp" -> "image/bmp"
                else -> null
            }
        }
        if (mimeType == null || !mimeType.startsWith("image/")) return null

        // Declared-size pre-check, then the streaming cap enforces
        // it for senders that lie or don't declare (1.x does both).
        if (queryLongColumn(resolver, uri, OpenableColumns.SIZE) >
            ShareNormalizer.MAX_IMAGE_BYTES
        ) {
            return null
        }

        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val file = File(dir, "img-$index.$extension")
        val size = try {
            val input = resolver.openInputStream(uri) ?: return null
            input.use { stream ->
                file.outputStream().use { out ->
                    val chunk = ByteArray(8192)
                    var total = 0L
                    var read: Int
                    while (stream.read(chunk).also { read = it } != -1) {
                        total += read
                        if (total > ShareNormalizer.MAX_IMAGE_BYTES) {
                            file.delete()
                            return null
                        }
                        out.write(chunk, 0, read)
                    }
                    total
                }
            }
        } catch (e: Exception) {
            file.delete()
            return null
        }
        return RawShareImage(
            name = queryDisplayName(resolver, uri) ?: "shared-image.$extension",
            mimeType = mimeType,
            sizeBytes = size,
            fileName = file.name,
        )
    }

    private fun queryLongColumn(resolver: ContentResolver, uri: Uri, column: String): Long {
        return try {
            resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(column)
                    if (index >= 0 && !cursor.isNull(index)) {
                        return cursor.getLong(index)
                    }
                }
                -1L
            } ?: -1L
        } catch (e: Exception) {
            // Size is a pre-check only; the streaming cap enforces too.
            -1L
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                    null
                }
        } catch (e: Exception) {
            null
        }
    }
}
