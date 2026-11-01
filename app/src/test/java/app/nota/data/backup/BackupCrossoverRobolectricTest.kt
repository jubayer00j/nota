package app.nota.data.backup

import androidx.room.Room
import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.NoteLockRepository
import app.nota.data.repository.NoteRepository
import app.nota.data.repository.NoteUnlockResult
import app.nota.data.security.NoteLockBiometricStore
import app.nota.data.security.NoteLockCrypto
import app.nota.data.security.NoteLockDeviceKeyProvider
import app.nota.data.security.NoteLockMethod
import app.nota.data.templates.TemplateRepository
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The 1.x⇄Nota crossover proof (Phase 2c): a fixture ZIP built in-test
 * from literal JSON strings shaped EXACTLY like 1.x's writer output —
 * including a locked note + locked attachment built from PR-B's fixture
 * constants (NEK 0x00..0x1f, pattern [0,1,2,5,8]) — is restored through
 * the REAL reader + repository, asserted, re-exported through the real
 * writer, and restored again into a second fresh database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupCrossoverRobolectricTest {

    private class RawKeyProvider : NoteLockDeviceKeyProvider {
        private val key = ByteArray(32) { (0x40 + it).toByte() }
        override fun encrypt(plain: ByteArray): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain))
        }
        override fun decrypt(packedBase64: String): ByteArray? = try {
            val packed = Base64.getDecoder().decode(packedBase64)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            cipher.doFinal(packed.copyOfRange(12, packed.size))
        } catch (e: Exception) { null }
    }

    // PR-B fixture constants (NoteLockCryptoTest).
    private val fixtureHtml = "<p>Hello fixture world</p>"
    private val contentEnvelope = """{"nomaEnc":1,"iv":"oKGio6Slpqeoqaqr","ct":"2mhCZSCnbtBCA+6rcw+yu1DbNmL+035D7DA3Y3Mx+tYXjHwbXse5ATqs"}"""
    private val attachmentEnvelope = """{"nomaEnc":1,"iv":"sLGys7S1tre4ubq7","ct":"/TQuyta53icz1+fOrDTm+eZdOrcjGqNmGZjxkxu6lU9dGRNjNJDJ2vIN1S59hL+x1AKIWsPPgOi562BCgupl"}"""
    private val slotPatternJson = """{"salt":"cGF0dGVybi1zYWx0LTEyMw==","iterations":100000,"wrappedKey":"4OHi4+Tl5ufo6erri+CwbG9VLeFgz2OnBklAeToCTUiBMORiYo+45Pvv94HTaKCoPafw5J9KRzSyBbQN"}"""
    private val lockJson =
        """{"v":1,"slots":{"pattern":$slotPatternJson},"biometric":false,"failedAttempts":0,"lockoutUntil":null,"lockedAt":1730000000000}"""

    private val pngBytes: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8Xw8AAwMCAO+ip1sAAAAASUVORK5CYII=",
    )
    private val plainHtml =
        "<p>Plain hello</p><p><img src=\"attachments/att-img-1\" width=\"50%\"></p><p>See [[Locked Fixture]]</p>"

    private val dbs = mutableListOf<NotaDatabase>()

    @After
    fun tearDown() {
        dbs.forEach { it.close() }
    }

    private inner class Stack(val db: NotaDatabase) {
        val context = RuntimeEnvironment.getApplication()
        val notes = NoteRepository(db, db.noteDao(), db.noteFtsDao(), db.folderDao(), db.tagDao(), db.attachmentDao(), db.savedSearchDao(), db.noteLinkDao())
        val store = AttachmentStore(context, db.attachmentDao())
        val templates = TemplateRepository(context)
        val backup = BackupRepository(context, db, templates, store)
        val lockRepo = NoteLockRepository(notes, db.attachmentDao(), store, NoteLockBiometricStore(PreferencesRepository(context), RawKeyProvider()))
    }

    private fun newStack(): Stack {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries().build()
        dbs += db
        return Stack(db)
    }

    private fun esc(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    /** The 1.x-shaped fixture ZIP (entries in 1.x writer order). */
    private fun fixtureZip(): File {
        val file = File.createTempFile("crossover-fixture", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun text(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            fun bytes(name: String, body: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body); zip.closeEntry()
            }
            text(
                "manifest.json",
                """{
  "backupFormatVersion": 2,
  "ownerId": null,
  "libraryId": "nota_guest",
  "nomaVersion": "1.2.0",
  "createdAt": "2024-10-26T00:00:00.000Z",
  "noteCount": 3,
  "folderCount": 1,
  "tagCount": 1,
  "attachmentCount": 2,
  "reminderCount": 1,
  "deviceId": null
}""",
            )
            text("folders.json", """[{"id": "folder-1", "name": "Work", "parentId": null, "createdAt": 1730000000000, "updatedAt": 1730000000000}]""")
            text("tags.json", """[{"id": "tag-1", "name": "important", "createdAt": 1730000000000}]""")
            text("reminders.json", """[{"id": "rem-1", "noteId": "note-plain-1", "scheduledAt": 1893456000000, "status": "pending", "createdAt": 1730000000000, "updatedAt": 1730000000000}]""")
            text("settings.json", "null")
            text("sync.json", """{"deviceId": null, "syncedAt": "2024-10-26T00:00:00.000Z", "tombstones": []}""")
            text(
                "notes/note-plain-1.json",
                """{
  "id": "note-plain-1",
  "title": "Plain Fixture",
  "content": "${esc(plainHtml)}",
  "contentFormat": "tiptap-html",
  "createdAt": 1730000000000,
  "updatedAt": 1730000001000,
  "folderId": "folder-1",
  "tagIds": ["tag-1"],
  "pinned": false,
  "favorite": true,
  "archived": false,
  "deleted": false,
  "deletedAt": null,
  "reminderAt": 1893456000000,
  "wordCount": 5
}""",
            )
            text(
                "notes/note-locked-1.json",
                """{
  "id": "note-locked-1",
  "title": "Locked Fixture",
  "content": "${esc(contentEnvelope)}",
  "contentFormat": "tiptap-html",
  "createdAt": 1730000000000,
  "updatedAt": 1730000001000,
  "folderId": null,
  "tagIds": [],
  "pinned": false,
  "favorite": false,
  "archived": false,
  "deleted": false,
  "deletedAt": null,
  "reminderAt": null,
  "wordCount": 0,
  "lock": $lockJson
}""",
            )
            text(
                "notes/tmpl-1.json",
                """{
  "id": "tmpl-1",
  "title": "Imported Template Title",
  "content": "<p>template body</p>",
  "contentFormat": "tiptap-html",
  "createdAt": 1730000000000,
  "updatedAt": 1730000001000,
  "folderId": null,
  "tagIds": [],
  "pinned": false,
  "favorite": false,
  "archived": false,
  "deleted": false,
  "deletedAt": null,
  "reminderAt": null,
  "wordCount": 2,
  "isTemplate": true
}""",
            )
            val pngDataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes)
            text(
                "attachments/index.json",
                """[
  {"id": "att-img-1", "noteId": "note-plain-1", "name": "photo.png", "mimeType": "image/png", "size": ${pngBytes.size}, "createdAt": 1730000000000},
  {"id": "att-locked-1", "noteId": "note-locked-1", "name": "hello.txt", "mimeType": "text/plain", "size": ${attachmentEnvelope.length}, "createdAt": 1730000000000, "encrypted": true}
]""",
            )
            bytes("attachments/att-img-1-photo.png", pngBytes)
            text(
                "attachments/att-img-1.json",
                """{"id": "att-img-1", "noteId": "note-plain-1", "name": "photo.png", "mimeType": "image/png", "size": ${pngBytes.size}, "createdAt": 1730000000000, "data": "$pngDataUrl"}""",
            )
            text("attachments/att-locked-1-hello.txt.encrypted", attachmentEnvelope)
            text(
                "attachments/att-locked-1.json",
                """{"id": "att-locked-1", "noteId": "note-locked-1", "name": "hello.txt", "mimeType": "text/plain", "size": ${attachmentEnvelope.length}, "createdAt": 1730000000000, "data": "${esc(attachmentEnvelope)}", "encrypted": true}""",
            )
        }
        return file
    }

    @Test
    fun crossoverFixture_restoreExportRestore() = runBlocking {
        val stack = newStack()
        // Split read/apply so a failure names its stage.
        val handle = stack.backup.readBackup(fixtureZip(), null)
        assertEquals(
            "reader staged attachments: " + handle.attachments.map {
                it.meta.id + "|" + it.meta.noteId + "|" + it.meta.name + "|enc=" + it.meta.encrypted +
                    "|raw=" + it.meta.rawEntrySuffix + "|len=" + it.stagedFile.length()
            },
            setOf("note-plain-1", "note-locked-1"),
            handle.attachments.map { it.meta.noteId }.toSet(),
        )
        val counts = stack.backup.applyBackup(handle, RestoreMode.REPLACE)
        handle.close()
        assertEquals("counts (folders+tags+notes+attachments+reminders)", 7, counts.added)
        val filesDir = stack.context.filesDir
        val walked = File(filesDir, "attachments").walkTopDown().filter { it.isFile }
            .map { it.relativeTo(filesDir).path + "(" + it.length() + ")" }.toList()
        assertEquals(
            "db attachments after apply: " + stack.db.attachmentDao().getAll().map { it.id + "@" + it.localPath } +
                " files: " + walked,
            2, stack.db.attachmentDao().getAll().size,
        )

        // --- Library wiring.
        val db = stack.db
        assertEquals(listOf("folder-1"), db.folderDao().getAll().map { it.id })
        assertEquals(listOf("tag-1"), db.tagDao().getAllTags().map { it.id })
        val plain = db.noteDao().getById("note-plain-1")!!
        assertEquals("folder-1", plain.folderId)
        assertTrue(plain.favourite)
        assertEquals(plainHtml, plain.contentHtml)
        assertTrue(plain.contentHtml.contains("width=\"50%\""))
        assertEquals(
            listOf("tag-1"),
            stack.notes.observeTagsForNote("note-plain-1").first().map { it.id },
        )
        // FTS finds the plain note by body text.
        assertEquals(listOf("note-plain-1"), stack.notes.searchNotes("Plain").map { it.id })

        // --- Image attachment: bytes identical to the fixture PNG.
        val imgRow = db.attachmentDao().getForNote("note-plain-1").single()
        assertFalse(imgRow.encrypted)
        assertArrayEquals(pngBytes, stack.store.fileFor(imgRow).readBytes())

        // --- Locked note: verbatim seal, unlocks with the fixture pattern.
        val locked = db.noteDao().getById("note-locked-1")!!
        assertTrue(locked.locked)
        assertEquals(contentEnvelope, locked.contentHtml)
        assertEquals(lockJson, locked.lockRecordJson)
        assertEquals("", locked.plainText)
        val unlock = stack.lockRepo.verifyCredential(
            locked, NoteLockMethod.PATTERN,
            NoteLockCrypto.canonicalPattern(listOf(0, 1, 2, 5, 8)),
        )
        assertTrue("unlock was $unlock", unlock is NoteUnlockResult.Ok)
        val nek = (unlock as NoteUnlockResult.Ok).nek
        val decrypted = stack.lockRepo.decryptNoteData(locked, nek)
        assertEquals(fixtureHtml, decrypted.contentHtml)
        assertArrayEquals("Hello attachment".toByteArray(), decrypted.attachmentBytes["att-locked-1"])
        nek.fill(0)

        // --- Reminder row + template routing.
        val reminders = db.reminderDao().getAll()
        assertEquals(1, reminders.size)
        assertEquals("note-plain-1", reminders[0].noteId)
        assertEquals(1893456000000L, reminders[0].triggerAt)
        assertNull(db.noteDao().getById("tmpl-1"))
        val templates = stack.templates.snapshot().filter { !it.builtIn }
        assertEquals(1, templates.size)
        assertEquals("tmpl-1", templates[0].id)
        assertEquals("Imported Template Title", templates[0].name)
        assertEquals("<p>template body</p>", templates[0].contentHtml)

        // --- Export through the real writer and inspect the archive.
        val exported = File.createTempFile("crossover-export", ".zip")
        stack.backup.exportToFile(exported, null)
        ZipFile(exported).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(names.containsAll(listOf(
                "manifest.json", "folders.json", "tags.json", "reminders.json",
                "settings.json", "sync.json",
                "notes/note-plain-1.json", "notes/note-locked-1.json", "notes/tmpl-1.json",
                "attachments/index.json",
                "attachments/att-img-1-photo.png", "attachments/att-img-1.json",
                "attachments/att-locked-1-hello.txt.encrypted", "attachments/att-locked-1.json",
            )))
            fun entryText(name: String): String =
                zip.getInputStream(zip.getEntry(name)).readBytes().toString(Charsets.UTF_8)
            // Settings: literal null, and no lock material anywhere.
            assertEquals("null", entryText("settings.json"))
            for (name in names) {
                val entry = zip.getEntry(name)
                if (entry.name.endsWith(".encrypted")) continue
                val text = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                assertFalse("app-lock key leaked in $name", text.contains("appLock"))
                assertFalse("password hash leaked in $name", text.contains("passwordHash"))
            }
            val manifest = JSONObject(entryText("manifest.json"))
            assertEquals(2, manifest.getInt("backupFormatVersion"))
            assertEquals("0.3.0", manifest.getString("nomaVersion"))
            assertEquals("nota_guest", manifest.getString("libraryId"))
            // Locked note round-trips byte-identically.
            val lockedJson = JSONObject(entryText("notes/note-locked-1.json"))
            assertEquals(contentEnvelope, lockedJson.getString("content"))
            // The lock record rides verbatim: the raw exported text holds
            // the exact fixture lock string.
            assertTrue(entryText("notes/note-locked-1.json").contains(lockJson))
            val patternSlot = lockedJson.getJSONObject("lock").getJSONObject("slots").getJSONObject("pattern")
            assertEquals("cGF0dGVybi1zYWx0LTEyMw==", patternSlot.getString("salt"))
            assertEquals(100000, patternSlot.getInt("iterations"))
            assertTrue(entryText("notes/note-locked-1.json").contains(esc(contentEnvelope)))
            // Plain note keeps its width attribute.
            val plainJson = JSONObject(entryText("notes/note-plain-1.json"))
            assertTrue(plainJson.getString("content").contains("width=\"50%\""))
            // Template rides as an isTemplate note.
            val tmplJson = JSONObject(entryText("notes/tmpl-1.json"))
            assertTrue(tmplJson.getBoolean("isTemplate"))
            // Index carries no data fields; the .encrypted raw entry is verbatim.
            assertFalse(entryText("attachments/index.json").contains("\"data\""))
            assertEquals(
                attachmentEnvelope,
                zip.getInputStream(zip.getEntry("attachments/att-locked-1-hello.txt.encrypted"))
                    .readBytes().toString(Charsets.UTF_8),
            )
            val lockedAttJson = JSONObject(entryText("attachments/att-locked-1.json"))
            assertEquals(attachmentEnvelope, lockedAttJson.getString("data"))
            assertTrue(lockedAttJson.getBoolean("encrypted"))
            assertArrayEquals(
                pngBytes,
                zip.getInputStream(zip.getEntry("attachments/att-img-1-photo.png")).readBytes(),
            )
        }

        // --- Restore the export into a second fresh DB: same state.
        val stack2 = newStack()
        stack2.backup.restore(exported, null, RestoreMode.REPLACE)
        val db2 = stack2.db
        val plain2 = db2.noteDao().getById("note-plain-1")!!
        assertEquals(plainHtml, plain2.contentHtml)
        val locked2 = db2.noteDao().getById("note-locked-1")!!
        assertTrue(locked2.locked)
        assertEquals(contentEnvelope, locked2.contentHtml)
        assertNotNull(locked2.lockRecordJson)
        val unlock2 = stack2.lockRepo.verifyCredential(
            locked2, NoteLockMethod.PATTERN,
            NoteLockCrypto.canonicalPattern(listOf(0, 1, 2, 5, 8)),
        )
        assertTrue(unlock2 is NoteUnlockResult.Ok)
        val nek2 = (unlock2 as NoteUnlockResult.Ok).nek
        assertEquals(fixtureHtml, stack2.lockRepo.decryptNoteData(locked2, nek2).contentHtml)
        nek2.fill(0)
        val imgRow2 = db2.attachmentDao().getForNote("note-plain-1").single()
        assertArrayEquals(pngBytes, stack2.store.fileFor(imgRow2).readBytes())
        assertEquals(1, db2.reminderDao().getAll().size)
    }
}
