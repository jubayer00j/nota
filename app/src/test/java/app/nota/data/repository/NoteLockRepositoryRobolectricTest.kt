package app.nota.data.repository

import androidx.room.Room
import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.security.NoteLockBiometricStore
import app.nota.data.security.NoteLockCrypto
import app.nota.data.security.NoteLockDeviceKeyProvider
import app.nota.data.security.NoteLockMethod
import app.nota.data.security.NoteLockRecord
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Per-note lock flow test (Phase 2b) under Robolectric: note + real
 * attachment file → lock → sealed at rest (envelope body, empty
 * plainText, title-only FTS, cleared links, envelope attachment file) →
 * wrong credential persists a failed attempt → correct credential
 * decrypts the originals → session save re-seals → biometric copy
 * unlocks → removeNoteLock restores plaintext + full FTS.
 *
 * The biometric device key is an injected raw-key provider (Android
 * Keystore is unreliable under Robolectric); the production provider is
 * exercised on device. PBKDF2 budget: PIN (300k) carries the main flow,
 * pattern (100k) the slot-management flow; the one password slot (600k)
 * is wrapped but never unwrapped here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteLockRepositoryRobolectricTest {

    /** Raw-key device provider: AES-256-GCM with a fixed 32-byte key. */
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
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, packed.copyOfRange(0, 12)),
            )
            cipher.doFinal(packed.copyOfRange(12, packed.size))
        } catch (e: Exception) {
            null
        }
    }

    private lateinit var db: NotaDatabase
    private lateinit var notes: NoteRepository
    private lateinit var store: AttachmentStore
    private lateinit var lockRepo: NoteLockRepository
    private lateinit var biometricStore: NoteLockBiometricStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        notes = NoteRepository(
            db, db.noteDao(), db.noteFtsDao(), db.folderDao(),
            db.tagDao(), db.attachmentDao(), db.savedSearchDao(), db.noteLinkDao(),
        )
        store = AttachmentStore(context, db.attachmentDao())
        biometricStore = NoteLockBiometricStore(PreferencesRepository(context), RawKeyProvider())
        lockRepo = NoteLockRepository(notes, db.attachmentDao(), store, biometricStore)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun fullLockUnlockFlow() = runBlocking {
        val target = notes.createNote(title = "Link Target", plainBody = "destination")
        val originalHtml = "<p>zebra secret body [[Link Target]]</p>"
        val note = notes.createRichNote(title = "Fixture Diary", contentHtml = originalHtml)
        // The outgoing backlink exists before locking.
        assertEquals(
            listOf(note.id),
            notes.observeLinkedFrom(target.id).first().map { it.id },
        )
        // A real attachment file (payload bytes, as imported).
        val payload = "Hello attachment".toByteArray(Charsets.UTF_8)
        val attachment = AttachmentEntity(
            id = "att-flow-1",
            noteId = note.id,
            fileName = "hello.txt",
            mimeType = "text/plain",
            sizeBytes = payload.size.toLong(),
            localPath = "attachments/${note.id}/hello.txt",
            createdAt = System.currentTimeMillis(),
        )
        db.attachmentDao().upsert(attachment)
        store.fileFor(attachment).apply {
            parentFile?.mkdirs()
            writeBytes(payload)
        }

        // ---- Lock with PIN + fingerprint copy.
        val locked = lockRepo.lockNote(
            note.id,
            NoteLockMethod.PIN,
            NoteLockCrypto.canonicalPin("135790"),
            enableBiometric = true,
        )
        assertTrue(locked.locked)
        assertEquals("", locked.plainText)
        assertTrue(NoteLockCrypto.isEncryptedContent(locked.contentHtml))
        val record = NoteLockRecord.parse(locked.lockRecordJson)!!
        assertEquals(setOf(NoteLockMethod.PIN), record.slots.keys)
        assertTrue(record.biometric)

        // FTS title-only: body word misses, title word hits.
        assertTrue(notes.searchNotes("zebra").isEmpty())
        assertEquals(listOf(note.id), notes.searchNotes("Fixture").map { it.id })
        // Outgoing links cleared under the seal.
        assertTrue(notes.observeLinkedFrom(target.id).first().isEmpty())
        // Attachment sealed: flag set, file holds envelope text.
        val sealedRow = db.attachmentDao().getForNote(note.id).single()
        assertTrue(sealedRow.encrypted)
        val sealedText = String(store.fileFor(sealedRow).readBytes(), Charsets.UTF_8)
        assertTrue(NoteLockCrypto.isEncryptedContent(sealedText))

        // ---- Wrong credential: error + persisted attempt count.
        val wrong = lockRepo.verifyCredential(
            locked, NoteLockMethod.PIN, NoteLockCrypto.canonicalPin("999999"),
        )
        assertTrue(wrong is NoteUnlockResult.Error)
        val afterWrong = NoteLockRecord.parse(notes.getById(note.id)!!.lockRecordJson)!!
        assertEquals(1, afterWrong.failedAttempts)

        // ---- Correct credential: NEK out, attempts reset.
        val ok = lockRepo.verifyCredential(
            locked, NoteLockMethod.PIN, NoteLockCrypto.canonicalPin("135790"),
        )
        assertTrue(ok is NoteUnlockResult.Ok)
        val nek = (ok as NoteUnlockResult.Ok).nek
        val afterOk = NoteLockRecord.parse(notes.getById(note.id)!!.lockRecordJson)!!
        assertEquals(0, afterOk.failedAttempts)

        // ---- Decrypt: originals come back exactly.
        val storedLocked = notes.getById(note.id)!!
        val data = lockRepo.decryptNoteData(storedLocked, nek)
        assertEquals(originalHtml, data.contentHtml)
        assertArrayEquals(payload, data.attachmentBytes["att-flow-1"])
        assertEquals(
            NoteLockCrypto.buildDataUrl("text/plain", payload),
            data.attachmentDataUrls["att-flow-1"],
        )

        // ---- Session save re-seals the edited body.
        lockRepo.saveLockedContent(storedLocked, nek, "<p>edited zebra</p>")
        val resealed = notes.getById(note.id)!!
        assertTrue(NoteLockCrypto.isEncryptedContent(resealed.contentHtml))
        assertEquals("", resealed.plainText)
        assertEquals(
            "<p>edited zebra</p>",
            NoteLockCrypto.decryptText(nek, resealed.contentHtml),
        )
        assertTrue(notes.searchNotes("zebra").isEmpty())

        // ---- Biometric copy unlocks with the same key.
        val bio = lockRepo.unlockWithBiometric(resealed)
        assertTrue(bio is NoteUnlockResult.Ok)
        assertArrayEquals(nek, (bio as NoteUnlockResult.Ok).nek)

        // ---- Remove lock: plaintext + full FTS + raw attachment restored.
        lockRepo.removeNoteLock(note.id, nek)
        val restored = notes.getById(note.id)!!
        assertFalse(restored.locked)
        assertNull(restored.lockRecordJson)
        assertEquals("<p>edited zebra</p>", restored.contentHtml)
        assertTrue(restored.plainText.contains("edited zebra"))
        assertEquals(listOf(note.id), notes.searchNotes("zebra").map { it.id })
        val restoredRow = db.attachmentDao().getForNote(note.id).single()
        assertFalse(restoredRow.encrypted)
        assertArrayEquals(payload, store.fileFor(restoredRow).readBytes())
        // The device copy is gone with the lock.
        assertNull(biometricStore.readCopy(note.id))
        nek.fill(0)
    }

    @Test
    fun slotManagement_refusesLastSlotRemoval() = runBlocking {
        val note = notes.createRichNote(title = "Slots", contentHtml = "<p>slot body</p>")
        val locked = lockRepo.lockNote(
            note.id,
            NoteLockMethod.PATTERN,
            NoteLockCrypto.canonicalPattern(listOf(0, 1, 2, 5, 8)),
            enableBiometric = false,
        )
        val ok = lockRepo.verifyCredential(
            locked, NoteLockMethod.PATTERN,
            NoteLockCrypto.canonicalPattern(listOf(0, 1, 2, 5, 8)),
        )
        assertTrue(ok is NoteUnlockResult.Ok)
        val nek = (ok as NoteUnlockResult.Ok).nek

        // Add a password slot; both methods now present.
        lockRepo.setSlot(
            locked, nek, NoteLockMethod.PASSWORD,
            NoteLockCrypto.canonicalPassword("correct horse fixture"),
        )
        var record = NoteLockRecord.parse(notes.getById(note.id)!!.lockRecordJson)!!
        assertEquals(
            setOf(NoteLockMethod.PATTERN, NoteLockMethod.PASSWORD),
            record.slots.keys,
        )

        // Removing one of two works; removing the last is refused.
        assertTrue(lockRepo.removeSlot(notes.getById(note.id)!!, NoteLockMethod.PATTERN))
        assertFalse(lockRepo.removeSlot(notes.getById(note.id)!!, NoteLockMethod.PASSWORD))
        record = NoteLockRecord.parse(notes.getById(note.id)!!.lockRecordJson)!!
        assertEquals(setOf(NoteLockMethod.PASSWORD), record.slots.keys)

        // Biometric toggling round-trips through the device copy.
        val stored = notes.getById(note.id)!!
        assertTrue(lockRepo.setBiometric(stored, nek, true))
        assertNotEquals(
            null,
            NoteLockRecord.parse(notes.getById(note.id)!!.lockRecordJson)!!.biometric
                .takeIf { it },
        )
        assertTrue(lockRepo.setBiometric(notes.getById(note.id)!!, nek, false))
        assertNull(biometricStore.readCopy(note.id))
        nek.fill(0)
    }

    @Test
    fun lockNote_rejectsWeakCredentialsAndDoubleLock() = runBlocking {
        val note = notes.createRichNote(title = "Weak", contentHtml = "<p>x</p>")
        try {
            lockRepo.lockNote(
                note.id, NoteLockMethod.PASSWORD, NoteLockCrypto.canonicalPassword("short"),
            )
            throw AssertionError("weak password accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("at least 8"))
        }
        val locked = lockRepo.lockNote(
            note.id, NoteLockMethod.PIN, NoteLockCrypto.canonicalPin("135790"),
        )
        assertTrue(locked.locked)
        try {
            lockRepo.lockNote(
                note.id, NoteLockMethod.PIN, NoteLockCrypto.canonicalPin("135790"),
            )
            throw AssertionError("double lock accepted")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("already locked"))
        }
    }
}
