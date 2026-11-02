package app.nota.feature.editor

import androidx.room.Room
import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.NoteLockRepository
import app.nota.data.repository.NoteRepository
import app.nota.data.repository.NoteUnlockResult
import app.nota.data.security.AppLockMethod
import app.nota.data.security.NoteLockBiometricStore
import app.nota.data.security.NoteLockCrypto
import app.nota.data.security.NoteLockDeviceKeyProvider
import app.nota.data.security.NoteLockMethod
import app.nota.data.security.NoteLockRecord
import app.nota.data.templates.TemplateRepository
import java.time.Duration
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock

/**
 * Failing-first reproduction for the Phase 2.5 F3 device bug: "a locked
 * note takes multiple attempts to open even with the correct password".
 *
 * The repository state machine is NOT what these tests indict (it is a
 * faithful 1.x port — see NoteLockRepositoryRobolectricTest). They drive
 * the REAL unlock path the user drives — RichNoteEditorViewModel, whose
 * published `note` state is exactly what NoteLockGate renders its record,
 * countdown and form from — and assert that after every attempt the
 * published state matches the persisted record.
 *
 * On main (pre-fix) the ViewModel keeps only the error string from a
 * failed attempt and NEVER refreshes `_note` after an attempt, so:
 *  - after a wrong attempt the published record still shows
 *    failedAttempts == 0 (the gate can never show progress to lockout);
 *  - after the attempt that imposes a lockout the published record
 *    still shows lockoutUntil == null, so the gate's countdown /
 *    input-disable never engages: the form stays live and a correct
 *    password entered during the backoff is refused by the repository's
 *    lockout-first check with no visible wait — the reported bug.
 * Both assertions below therefore FAIL on main and pass with the fix.
 *
 * Written against the pre-existing public API surface only
 * (RichNoteEditorViewModel.note / lockError / sessionActive /
 * unlockWithCredential and NoteLockRepository), so it compiles
 * unchanged against main.
 *
 * Time: Robolectric shadows System.currentTimeMillis(), so the 30 s
 * backoff is elapsed with ShadowSystemClock.advanceBy instead of a real
 * wait. PBKDF2 budget: PIN slots (300k) throughout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteLockUnlockStateRobolectricTest {

    /** Same raw-key device provider pattern as the repository test. */
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
        val biometricStore = NoteLockBiometricStore(PreferencesRepository(context), RawKeyProvider())
        lockRepo = NoteLockRepository(notes, db.attachmentDao(), store, biometricStore)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newViewModel(): RichNoteEditorViewModel {
        val context = RuntimeEnvironment.getApplication()
        return RichNoteEditorViewModel(notes, store, TemplateRepository(context), lockRepo)
    }

    /** Pumps the (paused) main looper until [condition] holds. The
     *  ViewModel's verify runs its PBKDF2 on Dispatchers.Default — a
     *  real thread — and resumes on Main, so this loop alternates
     *  looper pumps with short real sleeps. */
    private fun awaitCondition(description: String, condition: () -> Boolean) {
        repeat(3_000) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for: $description")
    }

    /** Drains queued main-looper work after an awaited attempt, so a
     *  following submission can never land while the previous attempt's
     *  coroutine is still publishing its outcome. */
    private fun settle() {
        repeat(100) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
    }

    private fun publishedRecord(vm: RichNoteEditorViewModel): NoteLockRecord? =
        NoteLockRecord.parse(vm.note.value?.lockRecordJson)

    @Test
    fun correctPin_opensOnFirstAttemptFromColdStart_andPublishedStateMatchesRecord() = runBlocking {
        val note = notes.createRichNote(title = "Cold Start", contentHtml = "<p>cold body</p>")
        val locked = lockRepo.lockNote(
            note.id, NoteLockMethod.PIN, NoteLockCrypto.canonicalPin("135790"),
        )

        // Cold start: a brand-new ViewModel, as on a fresh editor open.
        val vm = newViewModel()
        vm.load(note.id)
        awaitCondition("note loaded") { vm.note.value?.id == note.id }
        assertEquals(0, publishedRecord(vm)!!.failedAttempts)

        // One wrong attempt THROUGH the ViewModel (the user's path).
        vm.unlockWithCredential(AppLockMethod.PIN, NoteLockCrypto.canonicalPin("999999"))
        awaitCondition("wrong attempt reported") { vm.lockError.value != null }
        settle()

        // The persisted record says 1 failed attempt...
        val persisted = NoteLockRecord.parse(notes.getById(note.id)!!.lockRecordJson)!!
        assertEquals(1, persisted.failedAttempts)
        // ...and the state the gate renders from must say the same.
        // FAILS on main: the ViewModel never refreshes its note after an
        // attempt, so the published record still shows 0.
        assertEquals(
            "gate-visible record must track the persisted failed-attempt count",
            1,
            publishedRecord(vm)!!.failedAttempts,
        )

        // The correct PIN now opens on the first try, and the published
        // record shows the reset the repository persisted.
        vm.unlockWithCredential(AppLockMethod.PIN, NoteLockCrypto.canonicalPin("135790"))
        awaitCondition("session started") { vm.sessionActive.value }
        assertEquals(0, publishedRecord(vm)!!.failedAttempts)
        assertNull(publishedRecord(vm)!!.lockoutUntil)
        assertNull(vm.lockError.value)
        // Sanity: the locked entity the VM started from was the same note.
        assertEquals(locked.id, vm.note.value!!.id)
    }

    @Test
    fun lockoutImposedThroughViewModel_isVisibleInPublishedState_andCorrectPinOpensFirstTryAfterBackoff() =
        runBlocking {
            val note = notes.createRichNote(title = "Backoff", contentHtml = "<p>backoff body</p>")
            val locked = lockRepo.lockNote(
                note.id, NoteLockMethod.PIN, NoteLockCrypto.canonicalPin("135790"),
            )

            // Four wrong attempts directly against the repository bring
            // the record to the edge of lockout (the 5-failure threshold
            // is 1.x parity). This models a record the user arrives at
            // the gate with — e.g. accumulated earlier in the sitting.
            repeat(4) {
                val result = lockRepo.verifyCredential(
                    locked, NoteLockMethod.PIN, NoteLockCrypto.canonicalPin("999999"),
                )
                assertTrue(result is NoteUnlockResult.Error)
            }
            val atEdge = NoteLockRecord.parse(notes.getById(note.id)!!.lockRecordJson)!!
            assertEquals(4, atEdge.failedAttempts)
            assertNull(atEdge.lockoutUntil)

            // Cold start onto that record; ONE wrong attempt through the
            // ViewModel imposes the 30 s lockout.
            val vm = newViewModel()
            vm.load(note.id)
            awaitCondition("note loaded") { vm.note.value?.id == note.id }
            vm.unlockWithCredential(AppLockMethod.PIN, NoteLockCrypto.canonicalPin("999999"))
            awaitCondition("lockout reported") {
                vm.lockError.value?.contains("Locked out") == true
            }
            settle()
            val persisted = NoteLockRecord.parse(notes.getById(note.id)!!.lockRecordJson)!!
            assertEquals(5, persisted.failedAttempts)
            assertNotNull(persisted.lockoutUntil)

            // The gate renders its countdown from the ViewModel's
            // published record, so that record MUST now carry the
            // deadline. FAILS on main: the published record is still the
            // load-time one (lockoutUntil == null), the gate never shows
            // the wait or disables its form, and correct passwords keep
            // being refused invisibly until the deadline passes.
            val published = publishedRecord(vm)!!
            assertNotNull(
                "gate-visible record must carry the imposed lockout deadline",
                published.lockoutUntil,
            )
            assertTrue(published.lockoutUntil!! > System.currentTimeMillis())

            // During the backoff even the correct PIN is legitimately
            // refused (1.x parity) — and the refusal must not be counted
            // as another failed attempt.
            vm.unlockWithCredential(AppLockMethod.PIN, NoteLockCrypto.canonicalPin("135790"))
            awaitCondition("backoff refusal reported") {
                vm.lockError.value?.contains("Too many attempts") == true
            }
            settle()
            assertTrue(!vm.sessionActive.value)
            assertEquals(
                5,
                NoteLockRecord.parse(notes.getById(note.id)!!.lockRecordJson)!!.failedAttempts,
            )

            // The legitimate backoff elapses (shadow clock: no real wait).
            ShadowSystemClock.advanceBy(Duration.ofSeconds(31))

            // The correct PIN opens on the FIRST try after the backoff.
            vm.unlockWithCredential(AppLockMethod.PIN, NoteLockCrypto.canonicalPin("135790"))
            awaitCondition("session started after backoff") { vm.sessionActive.value }
            val afterOpen = NoteLockRecord.parse(notes.getById(note.id)!!.lockRecordJson)!!
            assertEquals(0, afterOpen.failedAttempts)
            assertNull(afterOpen.lockoutUntil)
        }
}
