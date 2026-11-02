package app.nota.data.drive

import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** One Drive file's metadata, epoch-millis normalised. */
data class DriveFileMeta(
    val id: String,
    val name: String,
    val createdTimeMillis: Long,
    val modifiedTimeMillis: Long,
    val md5Checksum: String?,
    val sizeBytes: Long,
)

/**
 * The Drive operations the coordinator needs — an interface so the
 * orchestration (backup/restore/sync) is unit-testable against a fake
 * without network or Google services (the same shape as 1.x's
 * `drive.ts`, which is also where the canonical-file rules come from).
 */
interface DriveGateway {
    /** Finds the shared "Noma Backups" folder (drive.file can only
     *  see app-created folders, so a hit is always ours), creating it
     *  when absent. Returns the folder id. */
    suspend fun findOrCreateFolder(token: String): String

    /**
     * The canonical backup file inside [folderId], or null. listFiles
     * can surface DUPLICATES of the canonical name (two devices racing
     * a first backup); 1.x's deterministic rule — oldest createdTime
     * wins, id breaks ties — picks the one every client converges on.
     * Pure read: this never deletes (no destructive surprise).
     */
    suspend fun findCanonicalBackup(token: String, folderId: String): DriveFileMeta?

    /** Fresh metadata for one file, or null when it no longer exists. */
    suspend fun getMeta(token: String, fileId: String): DriveFileMeta?

    /** Streams a file's bytes to [out] (alt=media). */
    suspend fun download(token: String, fileId: String, out: File)

    /** Creates (existingId null, multipart with the folder parent) or
     *  overwrites (media PATCH) the canonical file; returns its meta. */
    suspend fun upload(token: String, folderId: String, existingId: String?, file: File): DriveFileMeta
}

/**
 * Drive REST v3 over OkHttp. Every call carries the short-lived token
 * [DriveAuth] brokered; nothing is persisted. Folder/file names are
 * 1.x's exactly — "Noma Backups" / "Noma Backup.zip" — so Nota and
 * Noma share one cloud archive (the crossover contract, spec §4.1).
 */
class DriveClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val apiBase: String = API_BASE,
    private val uploadBase: String = UPLOAD_BASE,
) : DriveGateway {

    override suspend fun findOrCreateFolder(token: String): String {
        val query = "name = '$FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' " +
            "and trashed = false"
        val files = listFiles(token, query)
        if (files.isNotEmpty()) return files.first().id
        val metadata = JSONObject()
            .put("name", FOLDER_NAME)
            .put("mimeType", "application/vnd.google-apps.folder")
        val request = Request.Builder()
            .url("$apiBase/files?fields=id")
            .header("Authorization", "Bearer $token")
            .post(metadata.toString().toRequestBody(JSON_MEDIA))
            .build()
        val json = executeJson(request)
        return json.optString("id").ifEmpty {
            throw DriveError("Google Drive didn't create the backup folder.")
        }
    }

    override suspend fun findCanonicalBackup(token: String, folderId: String): DriveFileMeta? {
        val query = "name = '$BACKUP_FILE_NAME' and '$folderId' in parents and trashed = false"
        val matches = listFiles(token, query)
        return canonicalOf(matches)
    }

    override suspend fun getMeta(token: String, fileId: String): DriveFileMeta? {
        val request = Request.Builder()
            .url("$apiBase/files/$fileId?fields=$META_FIELDS")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val json = executeJson(request, allowNotFound = true) ?: return null
        return parseMeta(json)
    }

    override suspend fun download(token: String, fileId: String, out: File): Unit =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$apiBase/files/$fileId?alt=media")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw DriveError(driveFailure(response.code))
                val body = response.body ?: throw DriveError("Google Drive sent an empty backup.")
                out.outputStream().buffered().use { sink ->
                    body.byteStream().copyTo(sink)
                }
            }
        }

    override suspend fun upload(
        token: String,
        folderId: String,
        existingId: String?,
        file: File,
    ): DriveFileMeta = withContext(Dispatchers.IO) {
        val request = if (existingId == null) {
            val metadata = JSONObject()
                .put("name", BACKUP_FILE_NAME)
                .put("parents", JSONArray().put(folderId))
            val body = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(
                    Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                    metadata.toString().toRequestBody(JSON_MEDIA),
                )
                .addPart(file.asRequestBody(ZIP_MEDIA))
                .build()
            Request.Builder()
                .url("$uploadBase/files?uploadType=multipart&fields=$META_FIELDS")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
        } else {
            Request.Builder()
                .url("$uploadBase/files/$existingId?uploadType=media&fields=$META_FIELDS")
                .header("Authorization", "Bearer $token")
                .patch(file.asRequestBody(ZIP_MEDIA))
                .build()
        }
        parseMeta(executeJson(request))
    }

    // ------------------------------------------------------------- internals

    private suspend fun listFiles(token: String, query: String): List<DriveFileMeta> {
        val url = "$apiBase/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files($META_FIELDS)")
            .addQueryParameter("spaces", "drive")
            .build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token").get().build()
        val json = executeJson(request)
        val files = json.optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).map { parseMeta(files.getJSONObject(it)) }
    }

    private suspend fun executeJson(request: Request, allowNotFound: Boolean = false): JSONObject? =
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (response.code == 404 && allowNotFound) return@withContext null
                if (!response.isSuccessful) throw DriveError(driveFailure(response.code))
                val text = response.body?.string()
                    ?: throw DriveError("Google Drive sent an empty reply.")
                try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw DriveError("Google Drive sent an unreadable reply.")
                }
            }
        }

    private fun driveFailure(code: Int): String = when (code) {
        401, 403 -> "Drive access was refused — reconnect Google Drive and try again."
        404 -> "That Drive file no longer exists."
        429 -> "Google Drive is rate-limiting right now — try again in a bit."
        in 500..599 -> "Google Drive is having trouble right now — try again later."
        else -> "Google Drive request failed (HTTP $code)."
    }

    private fun parseMeta(json: JSONObject): DriveFileMeta = DriveFileMeta(
        id = json.getString("id"),
        name = json.optString("name"),
        createdTimeMillis = parseDriveTime(json.optString("createdTime")),
        modifiedTimeMillis = parseDriveTime(json.optString("modifiedTime")),
        md5Checksum = json.optString("md5Checksum").ifEmpty { null },
        sizeBytes = json.optString("size").toLongOrNull() ?: 0L,
    )

    companion object {
        const val FOLDER_NAME = "Noma Backups"
        const val BACKUP_FILE_NAME = "Noma Backup.zip"
        private const val API_BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val META_FIELDS = "id,name,createdTime,modifiedTime,md5Checksum,size"
        private val JSON_MEDIA = "application/json; charset=UTF-8".toMediaType()
        private val ZIP_MEDIA = "application/zip".toMediaType()

        /** 1.x's canonical-file rule, pure for testing: oldest
         *  createdTime wins; id order breaks exact ties. */
        fun canonicalOf(matches: List<DriveFileMeta>): DriveFileMeta? =
            matches.minWithOrNull(compareBy<DriveFileMeta>({ it.createdTimeMillis }, { it.id }))

        /** Drive timestamps are RFC 3339 ("…Z" or with an offset). */
        fun parseDriveTime(text: String): Long {
            if (text.isEmpty()) return 0L
            return try {
                OffsetDateTime.parse(text).toInstant().toEpochMilli()
            } catch (e: Exception) {
                try {
                    Instant.parse(text).toEpochMilli()
                } catch (e2: Exception) {
                    0L
                }
            }
        }
    }
}
