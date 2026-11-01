package app.nota.data.backup

import androidx.room.Room
import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.NoteEntity
import app.nota.data.db.NotaDatabase
import app.nota.data.templates.TemplateRepository
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Restore semantics, manifest validation, NOMAENC1 and streaming tests
 * (Phase 2c).
 *
 * The PRECOMPUTED NOMAENC1 fixture below was generated INDEPENDENTLY of
 * the production code: a Python program using `cryptography`'s AESGCM +
 * hashlib.pbkdf2_hmac, written directly from `backup-crypto.ts`
 * parameters (BMK = bytes 0x20..0x3f, salt = the 16 ASCII bytes
 * "backup-salt-1234", wrap IV = 0xc0..0xcb, envelope IV = 0xa0..0xab,
 * 600_000 PBKDF2 iterations, passphrase "fixture backup passphrase",
 * inner ZIP carrying one note titled "Fixture Encrypted Note"). The
 * generator shares no code with BackupCrypto, so the reader opening it
 * proves the FORMAT, not self-consistency.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRestoreSemanticsTest {

    private val dbs = mutableListOf<NotaDatabase>()

    @After
    fun tearDown() { dbs.forEach { it.close() } }

    private inner class Stack(val db: NotaDatabase) {
        val context = RuntimeEnvironment.getApplication()
        val store = AttachmentStore(context, db.attachmentDao())
        val backup = BackupRepository(context, db, TemplateRepository(context), store)
    }

    private fun newStack(): Stack {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries().build()
        dbs += db
        return Stack(db)
    }

    private fun zipOf(entries: Map<String, String>, binaries: Map<String, ByteArray> = emptyMap()): File {
        val file = File.createTempFile("backup-test", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            for ((name, body) in binaries) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body); zip.closeEntry()
            }
        }
        return file
    }

    private fun manifestJson(noteCount: Int, format: Int = 2): String =
        """{"backupFormatVersion": $format, "ownerId": null, "libraryId": "nota_guest", "nomaVersion": "1.2.0", "createdAt": "2024-10-26T00:00:00.000Z", "noteCount": $noteCount, "folderCount": 0, "tagCount": 0, "attachmentCount": 0, "reminderCount": 0, "deviceId": null}"""

    private fun noteJson(id: String, title: String, updatedAt: Long, content: String = "<p>body</p>"): String =
        """{"id": "$id", "title": "$title", "content": "$content", "contentFormat": "tiptap-html", "createdAt": 1000, "updatedAt": $updatedAt, "folderId": null, "tagIds": [], "pinned": false, "favorite": false, "archived": false, "deleted": false, "deletedAt": null, "reminderAt": null, "wordCount": 1}"""

    private fun payloadZip(notes: List<String>, syncJson: String? = null): File {
        val entries = linkedMapOf(
            "manifest.json" to manifestJson(notes.size),
            "folders.json" to "[]",
            "tags.json" to "[]",
            "reminders.json" to "[]",
            "settings.json" to "null",
            "sync.json" to (syncJson ?: """{"deviceId": null, "syncedAt": "2024-10-26T00:00:00.000Z", "tombstones": []}"""),
        )
        for (note in notes) {
            val id = Regex(""""id": "([^"]+)"""").find(note)!!.groupValues[1]
            entries["notes/$id.json"] = note
        }
        return zipOf(entries)
    }

    private suspend fun insertLocalNote(stack: Stack, id: String, title: String, updatedAt: Long) {
        stack.db.noteDao().upsert(
            NoteEntity(
                id = id, title = title, contentHtml = "<p>$title</p>", plainText = title,
                createdAt = 1000, updatedAt = updatedAt,
            ),
        )
    }

    // ------------------------------------------------------------ merge

    @Test
    fun merge_newestWinsPerNote() = runBlocking {
        val stack = newStack()
        insertLocalNote(stack, "n1", "Local Newer", updatedAt = 5000)
        // Older payload does NOT overwrite.
        stack.backup.restore(payloadZip(listOf(noteJson("n1", "Payload Older", 4000))), null, RestoreMode.MERGE)
        assertEquals("Local Newer", stack.db.noteDao().getById("n1")!!.title)
        // Newer payload does.
        stack.backup.restore(payloadZip(listOf(noteJson("n1", "Payload Newer", 6000))), null, RestoreMode.MERGE)
        assertEquals("Payload Newer", stack.db.noteDao().getById("n1")!!.title)
        // A brand-new payload note is added.
        stack.backup.restore(payloadZip(listOf(noteJson("n2", "Fresh", 1000))), null, RestoreMode.MERGE)
        assertNotNull(stack.db.noteDao().getById("n2"))
    }

    @Test
    fun merge_tombstoneDeletesWithCascade_andIsIgnoredWhenLocalNewer() = runBlocking {
        val stack = newStack()
        insertLocalNote(stack, "n-del", "Doomed", updatedAt = 5000)
        val payload = "payload-bytes".toByteArray()
        val att = AttachmentEntity(
            id = "att-del", noteId = "n-del", fileName = "a.txt", mimeType = "text/plain",
            sizeBytes = payload.size.toLong(), localPath = "attachments/n-del/a.txt",
            createdAt = 1000,
        )
        stack.db.attachmentDao().upsert(att)
        stack.store.fileFor(att).apply { parentFile?.mkdirs(); writeBytes(payload) }
        insertLocalNote(stack, "n-keep", "Survivor", updatedAt = 7000)

        val sync = """{"deviceId": null, "syncedAt": "2024-10-26T00:00:00.000Z", "tombstones": [
            {"entity": "note", "id": "n-del", "deletedAt": 6000, "deviceId": "dev-1"},
            {"entity": "note", "id": "n-keep", "deletedAt": 6000, "deviceId": "dev-1"}
        ]}"""
        val counts = stack.backup.restore(payloadZip(emptyList(), sync), null, RestoreMode.MERGE)
        assertNull(stack.db.noteDao().getById("n-del"))
        assertTrue(stack.db.attachmentDao().getForNote("n-del").isEmpty())
        assertTrue(!stack.store.fileFor(att).exists())
        assertTrue(counts.deleted >= 1)
        // Local edit after the deletion revives the note: tombstone ignored.
        assertNotNull(stack.db.noteDao().getById("n-keep"))
    }

    @Test
    fun replace_wipesLocalOnlyNotes() = runBlocking {
        val stack = newStack()
        insertLocalNote(stack, "local-only", "Local Only", updatedAt = 5000)
        stack.backup.restore(payloadZip(listOf(noteJson("from-backup", "From Backup", 1000))), null, RestoreMode.REPLACE)
        assertNull(stack.db.noteDao().getById("local-only"))
        assertNotNull(stack.db.noteDao().getById("from-backup"))
    }

    // ------------------------------------------------------- validation

    @Test
    fun validation_noManifest() = runBlocking {
        val stack = newStack()
        val zip = zipOf(mapOf("folders.json" to "[]"))
        try {
            stack.backup.restore(zip, null, RestoreMode.REPLACE)
            fail("expected BackupError")
        } catch (e: BackupError) {
            assertTrue(e.message!!.contains("no Noma manifest"))
        }
    }

    @Test
    fun validation_newerFormat() = runBlocking {
        val stack = newStack()
        val zip = zipOf(mapOf("manifest.json" to manifestJson(0, format = 3)))
        try {
            stack.backup.restore(zip, null, RestoreMode.REPLACE)
            fail("expected BackupError")
        } catch (e: BackupError) {
            assertTrue(e.message!!.contains("newer version"))
        }
    }

    @Test
    fun validation_corruptJsonFile() = runBlocking {
        val stack = newStack()
        val zip = zipOf(mapOf("manifest.json" to manifestJson(0), "folders.json" to "this is not json"))
        try {
            stack.backup.restore(zip, null, RestoreMode.REPLACE)
            fail("expected BackupError")
        } catch (e: BackupError) {
            assertTrue(e.message!!.contains("looks corrupted"))
        }
    }

    @Test
    fun validation_notAZip() = runBlocking {
        val stack = newStack()
        val file = File.createTempFile("not-a-zip", ".bin")
        file.writeBytes("definitely not a zip".toByteArray())
        try {
            stack.backup.restore(file, null, RestoreMode.REPLACE)
            fail("expected BackupError")
        } catch (e: BackupError) {
            assertTrue(e.message!!.contains("readable ZIP"))
        }
    }

    // --------------------------------------------------------- NOMAENC1

    @Test
    fun nomaEnc_productionSeal_wrongPassphraseFailsClean_rightOpens() = runBlocking {
        val stack = newStack()
        val plainZip = payloadZip(listOf(noteJson("sealed-1", "Sealed Note", 1000)))
        val sealed = File.createTempFile("sealed", ".zip")
        sealed.outputStream().buffered().use { out -> BackupCrypto.seal(plainZip, "correct passphrase", out) }

        try {
            stack.backup.readBackup(sealed, "wrong passphrase")
            fail("expected BackupUnlockError")
        } catch (e: BackupUnlockError) {
            assertEquals("Incorrect passphrase", e.message)
        }
        // Nothing was written by the failed open.
        assertTrue(stack.db.noteDao().getAll().isEmpty())
        try {
            stack.backup.readBackup(sealed, null)
            fail("expected BackupKeyNeededError")
        } catch (e: BackupKeyNeededError) {
            // expected: a prompt, not a failure
        }
        val counts = stack.backup.restore(sealed, "correct passphrase", RestoreMode.REPLACE)
        assertTrue(counts.added >= 1)
        assertEquals("Sealed Note", stack.db.noteDao().getById("sealed-1")!!.title)
    }

    @Test
    fun nomaEnc_precomputedIndependentFixture_opens() = runBlocking {
        val stack = newStack()
        val file = File.createTempFile("precomputed", ".bin")
        file.writeBytes(Base64.getDecoder().decode(PRECOMPUTED_NOMAENC1))
        try {
            stack.backup.readBackup(file, "not the passphrase")
            fail("expected BackupUnlockError")
        } catch (e: BackupUnlockError) {
            // expected
        }
        val handle = stack.backup.readBackup(file, "fixture backup passphrase")
        try {
            assertTrue(handle.encrypted)
            assertEquals(listOf("Fixture Encrypted Note"), handle.notes.map { it.title })
        } finally {
            handle.close()
        }
        stack.backup.restore(file, "fixture backup passphrase", RestoreMode.REPLACE)
        assertEquals("Fixture Encrypted Note", stack.db.noteDao().getById("note-enc-1")!!.title)
    }

    // --------------------------------------------------------- streaming

    @Test
    fun streaming_twoMegabyteAttachment_roundTripsByteExact() = runBlocking {
        val stack = newStack()
        insertLocalNote(stack, "big-note", "Big", updatedAt = 5000)
        val pattern = ByteArray(2 * 1024 * 1024) { i -> (i * 31 % 251).toByte() }
        val att = AttachmentEntity(
            id = "att-big", noteId = "big-note", fileName = "big.bin",
            mimeType = "application/octet-stream", sizeBytes = pattern.size.toLong(),
            localPath = "attachments/big-note/big.bin", createdAt = 1000,
        )
        stack.db.attachmentDao().upsert(att)
        stack.store.fileFor(att).apply { parentFile?.mkdirs(); writeBytes(pattern) }

        val exported = File.createTempFile("big-export", ".zip")
        stack.backup.exportToFile(exported, null)

        val stack2 = newStack()
        stack2.backup.restore(exported, null, RestoreMode.REPLACE)
        val restored = stack2.db.attachmentDao().getForNote("big-note").single()
        assertEquals(pattern.size.toLong(), restored.sizeBytes)
        assertArrayEquals(pattern, stack2.store.fileFor(restored).readBytes())
    }

    // ------------------------------------------- Codex review regressions

    @Test
    fun restore_attachmentWithTraversalNoteId_isSkipped() = runBlocking {
        val stack = newStack()
        val note = noteJson("good-note", "Good", 1000)
        val evilAttJson = """{"id": "att-evil", "noteId": "../../evil", "name": "evil.txt", "mimeType": "text/plain", "size": 4, "createdAt": 1000, "data": "data:text/plain;base64,ZXZpbA=="}"""
        val zip = zipOf(
            linkedMapOf(
                "manifest.json" to manifestJson(1),
                "folders.json" to "[]",
                "tags.json" to "[]",
                "reminders.json" to "[]",
                "settings.json" to "null",
                "sync.json" to """{"deviceId": null, "syncedAt": "2024-10-26T00:00:00.000Z", "tombstones": []}""",
                "notes/good-note.json" to note,
                "attachments/att-evil.json" to evilAttJson,
            ),
        )
        stack.backup.restore(zip, null, RestoreMode.REPLACE)
        assertTrue(stack.db.attachmentDao().getAll().isEmpty())
        // Nothing was written outside the attachments root.
        val filesDir = stack.context.filesDir
        assertTrue(!java.io.File(filesDir.parentFile, "evil").exists())
        assertTrue(!java.io.File(filesDir, "evil").exists())
    }

    @Test
    fun nomaEnc_absurdIterationCount_rejectedBeforeDeriving() = runBlocking {
        val stack = newStack()
        val header = """{"v":1,"salt":"YmFja3VwLXNhbHQtMTIzNA==","iterations":2000000000,"wrappedKey":"AAAA"}"""
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val file = File.createTempFile("crafted", ".bin")
        file.outputStream().buffered().use { out ->
            out.write("NOMAENC1".toByteArray(Charsets.US_ASCII))
            out.write(
                byteArrayOf(
                    (headerBytes.size ushr 24).toByte(), (headerBytes.size ushr 16).toByte(),
                    (headerBytes.size ushr 8).toByte(), headerBytes.size.toByte(),
                ),
            )
            out.write(headerBytes)
            out.write("""{"nomaEnc":1,"iv":"AAAAAAAAAAAAAAAAAAAAAA==","ct":"AAAA"}""".toByteArray(Charsets.US_ASCII))
        }
        try {
            stack.backup.readBackup(file, "any passphrase")
            fail("expected BackupUnlockError")
        } catch (e: BackupUnlockError) {
            assertTrue(e.message!!.contains("damaged"))
        }
    }

    @Test
    fun restore_lockMemberFoundStructurally_notInsideTitle() = runBlocking {
        val stack = newStack()
        val realLock = """{"v":1,"slots":{},"biometric":false,"failedAttempts":0,"lockoutUntil":null,"lockedAt":1730000000000}"""
        // The title itself contains a fake `"lock": {...}` fragment; the
        // real member follows later in the object.
        val noteJson = """{"id": "note-tricky", "title": "see \"lock\": {\"v\": 9} here", "content": "${"{\"nomaEnc\":1,\"iv\":\"oKGio6Slpqeoqaqr\",\"ct\":\"2mhCZSCnbtBCA+6rcw+yu1DbNmL+035D7DA3Y3Mx+tYXjHwbXse5ATqs\"}".replace("\\", "\\\\").replace("\"", "\\\"")}", "contentFormat": "tiptap-html", "createdAt": 1000, "updatedAt": 1000, "folderId": null, "tagIds": [], "pinned": false, "favorite": false, "archived": false, "deleted": false, "deletedAt": null, "reminderAt": null, "wordCount": 0, "lock": $realLock}"""
        val zip = payloadZip(listOf(noteJson))
        stack.backup.restore(zip, null, RestoreMode.REPLACE)
        val stored = stack.db.noteDao().getById("note-tricky")!!
        assertTrue(stored.locked)
        assertEquals(realLock, stored.lockRecordJson)
    }

    companion object {
        private const val PRECOMPUTED_NOMAENC1 =
        "Tk9NQUVOQzEAAACdeyJ2IjoxLCJzYWx0IjoiWW1GamEzVndMWE5oYkhRdE1USXpOQT09IiwiaXRl" +
        "cmF0aW9ucyI6NjAwMDAwLCJ3cmFwcGVkS2V5Ijoid01IQ3c4VEZ4c2ZJeWNyTGo1YVBYN1RTWXVZ" +
        "bmUvRDdVR1lwY2txMy9NNS84RXljT1pscW1pek5xcjVLSnpGNjE2VFlxTDVKMHY4K3ZnV1oifXsi" +
        "bm9tYUVuYyI6MSwiaXYiOiJvS0dpbzZTbHBxZW9xYXFyIiwiY3QiOiJMbmVuTU5EWGdLcXBLRjBa" +
        "UndDSy9IUnRZZ2M4dENvakQ1TEcrZE5td3BseFBGdlJkZDN0RUxtTnZZaUJPNVk4Umw5OXpFdzYw" +
        "cHVkS002cFRLamZ5b2FpVVp0b0JsQlcrZE9kRGN1cFp0Q25QaCtac01tbC9ZUFlqZmphN3VXZ2hy" +
        "Z2ZFZ0lkS3VGMHc4ZzZZcGs2TC94MG1uZG9RRkJNRVU0NTVsTHAyekdCZm5WZE1tYzJIRWl1dVdS" +
        "Rk5SdmltTlgzeVcrcmFsRjJtdlFKTjZ4WDh1N00xMTgwOVRaZ1I1UTFLU041YnAxZFBQeml5blpn" +
        "d1V4ZHhlZkdINld1NGQwVkF2UVBmV3F1SXF5d0dNZDFhS05LdGJUaGluRGVkelZxQ0xwcVdGWDJE" +
        "Q1JndDV0THlIdmN2QUFwU3VKUzd4bzF0S3g3YTBnRFM1OUxMdEFEK2x2RWpNbFBoYUptOE1Xa0d5" +
        "MzVqVFdNVWNyYmJ1RE9pYTNla3BlRE5XUWc1Sml0cDY3UnBwOGVOWVRUdVNlZW1hbkp3ZTVKejVC" +
        "d3ZMTlBLaHhkZnVnVW9Dck1JY1dJSFZxNlgybGtwVE9ncCtGajJ0dTExTGVwdzd6UG1FS3R2b3Fr" +
        "MVNsWUdlSnAwbzBWVmtEUDZNa3ZHUzRwV3I5eXltTTVZYWxSNWlTcFBDSVNwa0UzN0Npc2pnRTJv" +
        "WDdIWDFBZWtXTDlZc1VWQ0RkT3UzOXQ4bzlrNG5yV2tjaS9qam04V1RsbjdmTjRObzVacDBwcTJn" +
        "Q1VwY2hLTWI5ZzhpbjRyLzI1R3RvcG55QUtoM1pvcXk4UVo5RnVKUHpobGQ3UWlrYXBXSGRoYmly" +
        "d0lUalRIRVUvN1FNZHRGMy80d0EwWmNYTUliU3VvckQ2MXNoajByQ1lmVlpMbHh1Rmx5bHdpQWha" +
        "QTRUQndoMXJqb29qNG5mV0xPMFUxM0tZUU9ReW53eU1vUXlQTFhxRHJ1d2x3bCtrWjJoTjVLS21t" +
        "YXA3MDBFSE10TWZEd3hxZFluNEpNSEs2bTE1eG5mRTZVL0xMa3pudjdKNVBoUW5KdEpOMHdIa2JW" +
        "ZUdRaFE0dDgyNHlQSm9BQUsvZXp0WmVCOFNPVU9xakl6dmI2NjRsTUdEakNSbWFiSXRSUmNXcVFN" +
        "N3JVZCsxbkpLUllIOS9ZTHV5T1RsRnFpbnpSRVJIeEtSbUJzZkhqRHNpcmU0VkxLV3QzaFdXUU05" +
        "ZjhFbXl2TnRjYmM2STk1clJMVElEcnF1a0V5ajlPZlMrQXJiY1lKc0ZId3JyR3J6eFZMWXhKbldD" +
        "Q0wzOUhyL3hPOG9HUHRZbDhQNXpaQ2xLUVJldzhvT05TU0MrbDBwY3hHTXdJT2VXMit5ZkxIK1BM" +
        "RkZiZmlQcmkza09XdUprUlBFdDFRUE9tV2hVR0ZiS0dET0xHVGdSZHhvTUNoYlJsSnVHeHJ5KytZ" +
        "b053OTVrVnUrV0NwU1lReGtCUkJJZWZ6VC9nTnM4dVY3NElPUzVRSWxlU2JSSWh0QXZMN2VoNVpV" +
        "OFhVcFJaYktTaTZoNFdnck5rVmFVUWU5Q2xNMUc1SFBZa3JKYzRIZWM4SmxVNHlObXlBMVVjUXhi" +
        "ZzczR09tUU5Cc3ZzOTBlMEpZY0lJL0VkdElOTG9aaEVSd3h4ZWtVSmQ1Vk9PM29rZkhraVliOFlP" +
        "UUpRdUVONkhIQVZONm9HdmltYWV5dFFVQkVtb0RjSWdaL25RdUwxTjNTdzJKMTZaaHh3RTFoUE9H" +
        "eWhZcUpxWEtXU1RpWGNtTFBxT0xmUHJtQWhydEtnVlF5ZmZ5aGZ1TUlRTmZLUFZqYUNEZStMTVpD" +
        "VEJNUWpadUF0akMxZVRUNjZVblVkOGd1Q0ttdmpub3l2Rk4waUppajhBVFZzaFI2VXNvNWtqKzI1" +
        "azI3djJnSitBbkVncWIzM1lOeWVkcnB0MHd3bm5PM05HUXhjUGF3cVprR1huQ21DbmFtQlBZZW9E" +
        "UW1IaXhLVVNsaGE2YVZ6OVk2RnlhZW84K3hxKzF5QmlUVmJ2aGNPMS82NFU1M0dlYmJHbmhKeVox" +
        "RklWZytwN3VIT1VFOWxQbHA2a0JKTE1ZN3RhUnA1cUk1OUFPNmJaMEZMc0JQTmJXeUR2NkF4dz09" +
        "In0="
    }
}
