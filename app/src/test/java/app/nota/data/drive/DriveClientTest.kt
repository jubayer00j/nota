package app.nota.data.drive

import java.io.File
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Drive REST client behaviour (Phase 3b) against a local stub server
 * (MockWebServer — the JDK's com.sun.net.httpserver isn't on the
 * Android unit-test classpath): folder find-or-create, the
 * canonical-file rule end to end, meta/download/upload request
 * shapes. The semantics mirror 1.x's drive.ts, which is what makes
 * the shared "Noma Backup.zip" crossover work.
 */
@RunWith(RobolectricTestRunner::class)
class DriveClientTest {

    private data class Recorded(
        val method: String,
        val path: String,
        val rawQuery: String?,
        val body: ByteArray,
        val contentType: String?,
    ) {
        val query: String get() = URLDecoder.decode(rawQuery ?: "", "UTF-8")
        val bodyText: String get() = String(body, Charsets.ISO_8859_1)
    }

    private lateinit var server: MockWebServer
    private lateinit var client: DriveClient
    private val requests = mutableListOf<Recorded>()
    private var responder: (Recorded) -> Pair<Int, String> = { 200 to "{}" }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val full = request.path ?: "/"
                val recorded = Recorded(
                    method = request.method ?: "GET",
                    path = full.substringBefore('?'),
                    rawQuery = full.substringAfter('?', "").ifEmpty { null },
                    body = request.body.readByteArray(),
                    contentType = request.headers["Content-Type"],
                )
                requests += recorded
                val (code, response) = responder(recorded)
                return MockResponse()
                    .setResponseCode(code)
                    .setHeader("Content-Type", "application/json")
                    .setBody(response)
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        client = DriveClient(apiBase = "$base/drive/v3", uploadBase = "$base/upload/drive/v3")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun metaJson(
        id: String,
        created: String,
        modified: String = created,
        name: String = "Noma Backup.zip",
    ): String =
        """{"id":"$id","name":"$name","createdTime":"$created","modifiedTime":"$modified",""" +
            """"md5Checksum":"abc123","size":"1234"}"""

    @Test
    fun findFolderReturnsExisting() = runBlocking {
        responder = { 200 to """{"files":[${metaJson("folder-9", "2026-01-01T00:00:00.000Z", name = "Noma Backups")}]}""" }
        val id = client.findOrCreateFolder("token")
        assertEquals("folder-9", id)
        assertEquals(1, requests.size)
        assertTrue(requests[0].query.contains("Noma Backups"))
        assertTrue(requests[0].query.contains("application/vnd.google-apps.folder"))
    }

    @Test
    fun findFolderCreatesWhenMissing() = runBlocking {
        var call = 0
        responder = {
            call++
            if (call == 1) 200 to """{"files":[]}""" else 200 to """{"id":"folder-new"}"""
        }
        val id = client.findOrCreateFolder("token")
        assertEquals("folder-new", id)
        assertEquals("POST", requests[1].method)
        assertTrue(requests[1].bodyText.contains("Noma Backups"))
    }

    @Test
    fun canonicalBackupPicksOldestCreated() = runBlocking {
        responder = {
            200 to """{"files":[
                ${metaJson("b-id", "2026-03-01T00:00:00.000Z")},
                ${metaJson("a-id", "2026-01-01T00:00:00.000Z")},
                ${metaJson("c-id", "2026-02-01T00:00:00.000Z")}
            ]}"""
        }
        val canonical = client.findCanonicalBackup("token", "folder-1")
        assertEquals("a-id", canonical?.id)
        assertTrue(requests[0].query.contains("'folder-1' in parents"))
    }

    @Test
    fun canonicalOfBreaksTiesById() {
        val tied = listOf(
            DriveFileMeta("z-id", "Noma Backup.zip", 100L, 100L, null, 1L),
            DriveFileMeta("a-id", "Noma Backup.zip", 100L, 100L, null, 1L),
        )
        assertEquals("a-id", DriveClient.canonicalOf(tied)?.id)
        assertNull(DriveClient.canonicalOf(emptyList()))
    }

    @Test
    fun getMetaReturnsNullOn404() = runBlocking {
        responder = { 404 to """{"error":{"code":404}}""" }
        assertNull(client.getMeta("token", "gone"))
    }

    @Test
    fun getMetaParsesFields() = runBlocking {
        responder = { 200 to metaJson("f1", "2026-01-01T10:00:00.000Z", "2026-01-02T10:00:00.000Z") }
        val meta = client.getMeta("token", "f1")
        assertNotNull(meta)
        assertEquals("abc123", meta?.md5Checksum)
        assertEquals(1234L, meta?.sizeBytes)
        assertTrue((meta?.modifiedTimeMillis ?: 0) > (meta?.createdTimeMillis ?: Long.MAX_VALUE))
    }

    @Test
    fun downloadWritesBodyBytes() = runBlocking {
        responder = { 200 to "PK-fake-zip-bytes" }
        val out = File(RuntimeEnvironment.getApplication().cacheDir, "dl-test.bin")
        client.download("token", "f1", out)
        assertEquals("PK-fake-zip-bytes", out.readText())
        assertTrue(requests[0].query.contains("alt=media"))
    }

    @Test
    fun uploadCreateSendsMultipartWithFolderParent() = runBlocking {
        responder = { 200 to metaJson("file-1", "2026-01-01T00:00:00.000Z") }
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "up-test.zip")
        file.writeBytes(byteArrayOf(0x50, 0x4B, 1, 2, 3))
        val meta = client.upload("token", "folder-1", null, file)
        assertEquals("file-1", meta.id)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertTrue(request.path.endsWith("/upload/drive/v3/files"))
        assertTrue(request.query.contains("uploadType=multipart"))
        assertTrue(request.contentType?.startsWith("multipart/related") == true)
        assertTrue(request.bodyText.contains("Noma Backup.zip"))
        assertTrue(request.bodyText.contains("folder-1"))
    }

    @Test
    fun uploadUpdatePatchesExistingFile() = runBlocking {
        responder = { 200 to metaJson("file-9", "2026-01-01T00:00:00.000Z") }
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "up-test2.zip")
        file.writeBytes(byteArrayOf(0x50, 0x4B, 9, 9))
        client.upload("token", "folder-1", "file-9", file)
        val request = requests.single()
        assertEquals("PATCH", request.method)
        assertTrue(request.path.endsWith("/upload/drive/v3/files/file-9"))
        assertTrue(request.query.contains("uploadType=media"))
    }

    @Test
    fun parseDriveTimeHandlesZuluOffsetsAndGarbage() {
        val zulu = DriveClient.parseDriveTime("2026-01-01T00:00:00.000Z")
        val offset = DriveClient.parseDriveTime("2026-01-01T06:00:00.000+06:00")
        assertEquals(zulu, offset)
        assertTrue(zulu > 0)
        assertEquals(0L, DriveClient.parseDriveTime("not-a-time"))
        assertEquals(0L, DriveClient.parseDriveTime(""))
    }
}
