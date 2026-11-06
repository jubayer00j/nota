package app.nota.data.capture

import app.nota.data.text.PlainTextCodec
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 5.2 quick capture: the model layer — a faithful port of
 * Noma 1.x's capture pipeline (src/lib/noma/capture.ts +
 * NomaSharePlugin.java), reshaped onto Nota's durable-store seams.
 *
 * Every "door" into Nota — the Android share target, the launcher
 * shortcuts, the Quick Settings tile — normalises into a
 * [CaptureRequest], staged in [PendingCaptureStore] stamped with the
 * account that was active when it arrived, and consumed by the nav
 * shell only while the app is unlocked. A capture therefore never
 * bypasses App Lock and never lands in another account's library.
 */

/** Where a capture came from (1.x's CaptureSource, minus web doors). */
enum class CaptureSource(val wire: String) {
    SHORTCUT("shortcut"),
    TILE("tile"),
    ANDROID_SHARE("android-share"),
    // Phase 5.3: the home-screen widget's capture buttons.
    WIDGET("widget"),
    ;

    companion object {
        fun fromWire(wire: String?): CaptureSource =
            entries.firstOrNull { it.wire == wire } ?: SHORTCUT
    }
}

/** One shared image whose bytes already live in the pending store. */
data class CaptureImageRef(
    val name: String,
    val mimeType: String,
    /** File name inside the owning record's staging directory. */
    val fileName: String,
)

/** A normalised capture request — the shapes 1.x's capture.ts emits. */
sealed interface CaptureRequest {
    val id: String
    val source: CaptureSource

    /** A blank note, opened in the editor (shortcut / tile / widget). */
    data class NewNote(
        override val id: String,
        override val source: CaptureSource,
    ) : CaptureRequest

    /** Today's daily note, created if needed, opened in the editor. */
    data class Today(
        override val id: String,
        override val source: CaptureSource,
    ) : CaptureRequest

    /**
     * Phase 5.4: a voice door was tapped. Unlike the other kinds it
     * carries no content — the transcript does not exist yet. The
     * record stages and waits/retires exactly like every capture
     * (lock-safe, exactly-once); when the shell takes it, it
     * presents the listening sheet instead of calling the
     * deliverer, and the note is created from what was said.
     */
    data class VoiceNote(
        override val id: String,
        override val source: CaptureSource,
    ) : CaptureRequest

    /** A share: title + HTML body, images embedded on delivery. */
    data class Share(
        override val id: String,
        override val source: CaptureSource,
        val title: String,
        /** Body HTML ([PlainTextCodec] paragraphs); images are NOT in
         *  here yet — delivery appends one <img> paragraph per image,
         *  exactly like 1.x's handleShareCapture. */
        val contentHtml: String,
        val images: List<CaptureImageRef>,
    ) : CaptureRequest
}

/**
 * A [CaptureRequest] durably staged for the shell, stamped with the
 * account active when the door fired. Serialised with org.json in
 * the [PendingInsertRecord][app.nota.data.attachments.PendingInsertRecord]
 * style so the store file is a plain, inspectable queue.
 */
data class CaptureRecord(
    val id: String = UUID.randomUUID().toString(),
    val accountId: String,
    val request: CaptureRequest,
    val stagedAtMillis: Long = System.currentTimeMillis(),
) {
    fun toJson(): String {
        val obj = JSONObject()
            .put("id", id)
            .put("accountId", accountId)
            .put("stagedAtMillis", stagedAtMillis)
            .put("source", request.source.wire)
        when (val r = request) {
            is CaptureRequest.NewNote -> obj.put("kind", KIND_NEW_NOTE)
            is CaptureRequest.Today -> obj.put("kind", KIND_TODAY)
            is CaptureRequest.VoiceNote -> obj.put("kind", KIND_VOICE_NOTE)
            is CaptureRequest.Share -> {
                obj.put("kind", KIND_SHARE)
                obj.put("title", r.title)
                obj.put("contentHtml", r.contentHtml)
                val images = JSONArray()
                for (image in r.images) {
                    images.put(
                        JSONObject()
                            .put("name", image.name)
                            .put("mimeType", image.mimeType)
                            .put("fileName", image.fileName),
                    )
                }
                obj.put("images", images)
            }
        }
        return obj.toString()
    }

    companion object {
        const val KIND_NEW_NOTE = "new-note"
        const val KIND_TODAY = "today"
        const val KIND_VOICE_NOTE = "voice-note"
        const val KIND_SHARE = "share"

        fun parse(json: String): CaptureRecord? = try {
            val obj = JSONObject(json)
            val id = obj.getString("id")
            val source = CaptureSource.fromWire(obj.optString("source", ""))
            val request: CaptureRequest = when (obj.getString("kind")) {
                KIND_NEW_NOTE -> CaptureRequest.NewNote(id, source)
                KIND_TODAY -> CaptureRequest.Today(id, source)
                KIND_VOICE_NOTE -> CaptureRequest.VoiceNote(id, source)
                KIND_SHARE -> {
                    val images = mutableListOf<CaptureImageRef>()
                    val array = obj.optJSONArray("images") ?: JSONArray()
                    for (i in 0 until array.length()) {
                        val image = array.getJSONObject(i)
                        images += CaptureImageRef(
                            name = image.getString("name"),
                            mimeType = image.getString("mimeType"),
                            fileName = image.getString("fileName"),
                        )
                    }
                    CaptureRequest.Share(
                        id = id,
                        source = source,
                        title = obj.optString("title", ""),
                        contentHtml = obj.optString("contentHtml", ""),
                        images = images,
                    )
                }
                else -> return null
            }
            CaptureRecord(
                id = id,
                accountId = obj.getString("accountId"),
                request = request,
                stagedAtMillis = obj.optLong("stagedAtMillis", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** A shared image as extraction produced it, before normalisation. */
data class RawShareImage(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** File name of the staged copy inside the record's directory. */
    val fileName: String,
)

/** The loose share payload extraction hands to [ShareNormalizer]. */
data class RawSharePayload(
    /** Android EXTRA_SUBJECT — the note title when present (1.x). */
    val subject: String?,
    val text: String?,
    val images: List<RawShareImage>,
)

/**
 * Maps a loose share payload onto a [CaptureRequest] — a line-for-
 * line port of 1.x's `normalizeSharePayload` (capture.ts), including
 * its caps and its degrade rule: a payload with no title, no body and
 * no usable images becomes a plain new-note capture rather than
 * failing. Pure and total; unit-tested without Android.
 */
object ShareNormalizer {

    const val MAX_TITLE_CHARS = 200
    const val MAX_TEXT_CHARS = 100_000
    const val MAX_IMAGE_BYTES = 10L * 1024L * 1024L
    const val MAX_IMAGES = 10

    fun normalize(raw: RawSharePayload, id: String): CaptureRequest {
        val title = (raw.subject?.trim().orEmpty())
            .take(MAX_TITLE_CHARS).trim()

        var text = raw.text ?: ""
        var url = ""

        // A share whose whole text is one bare URL is a link share,
        // not prose (1.x checks this in the plugin AND here; Nota
        // keeps the single implementation here).
        val trimmedText = text.trim()
        if (trimmedText.isNotEmpty() && isBareUrl(trimmedText)) {
            url = trimmedText
            text = ""
        }

        text = text.take(MAX_TEXT_CHARS)

        // Merge the URL into the body exactly once.
        var body = text.trim()
        if (url.isNotEmpty()) {
            if (body.isEmpty()) {
                body = url
            } else if (!body.contains(url)) {
                body = "$body\n\n$url"
            }
        }

        val images = normalizeImages(raw.images)
        val contentHtml = if (body.isNotEmpty()) PlainTextCodec.textToHtml(body) else ""

        if (title.isEmpty() && contentHtml.isEmpty() && images.isEmpty()) {
            return CaptureRequest.NewNote(id, CaptureSource.ANDROID_SHARE)
        }
        return CaptureRequest.Share(
            id = id,
            source = CaptureSource.ANDROID_SHARE,
            title = title,
            contentHtml = contentHtml,
            images = images.map { CaptureImageRef(it.name, it.mimeType, it.fileName) },
        )
    }

    private fun normalizeImages(images: List<RawShareImage>): List<RawShareImage> {
        val kept = mutableListOf<RawShareImage>()
        for (image in images) {
            if (kept.size >= MAX_IMAGES) break
            if (!image.mimeType.startsWith("image/")) continue
            if (image.sizeBytes > MAX_IMAGE_BYTES) continue
            kept += image.copy(
                name = image.name.trim().ifEmpty { "image" },
            )
        }
        return kept
    }

    /** 1.x isBareUrl: a single whitespace-free token that parses as
     *  an http(s) URL. */
    fun isBareUrl(text: String): Boolean {
        if (text.any { it.isWhitespace() }) return false
        val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return false
        return uri.scheme == "http" || uri.scheme == "https"
    }
}
