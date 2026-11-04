package app.nota.data.ai

import android.content.Context
import androidx.room.Room
import app.nota.data.attachments.AttachmentStore
import app.nota.data.backup.BackupRepository
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.NoteRepository
import app.nota.data.templates.TemplateRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The vector backup-exclusion proof (Phase 4.2), mirroring the
 * 4.1 key-exclusion test: with a populated semantic index on the
 * device — at the very filesDir location production uses — a
 * backup ZIP built by the real BackupRepository contains no
 * vector data: not the floats' byte pattern, not an entry named
 * for the index. Structural (backups are built from Room + the
 * attachment store; the index lives in its own filesDir
 * directory), and this test exists so the structure can never
 * quietly change.
 */
@RunWith(RobolectricTestRunner::class)
class SemanticBackupExclusionTest {

    @Test
    fun backupZipContainsNoVectorData() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val provider = DatabaseProvider.forDatabase(context, db)
        val notes = NoteRepository(provider)
        val attachments = AttachmentStore(context, provider)
        val backup = BackupRepository(
            context, provider, TemplateRepository(context), attachments,
        )

        // AI configured, as on a real device using semantic search.
        val store = AiSettingsStore(PreferencesRepository(context), FakeAiKeyCipher())
        store.saveSlot("guest", AiProviderId.GEMINI, AiProviderSlot(apiKey = "key-1"))
        store.setActiveProvider("guest", AiProviderId.GEMINI)

        // A populated index at the production location, with a
        // distinctive sentinel float as every vector component.
        val sentinel = 0.31415927f
        val indexStore = SemanticIndexStore(File(context.filesDir, "semantic-index"))
        indexStore.save(
            "guest",
            StoredSemanticIndex(
                signature = "gemini|native|gemini-embedding-001",
                dims = 4,
                notes = mapOf(
                    "ghost-note" to StoredNoteVectors(
                        "sentinel-fingerprint",
                        listOf(FloatArray(4) { sentinel }),
                    ),
                ),
            ),
        )
        assertTrue(indexStore.fileFor("guest").isFile)

        // A real note so the backup is non-trivial…
        notes.createRichNote(
            title = "Vector exclusion probe",
            contentHtml = "<p>Ordinary note text.</p>",
        )

        val out = File(context.cacheDir, "semantic-exclusion-probe.zip")
        backup.exportToFile(out, null)

        val sentinelBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN).putFloat(sentinel).array()
        val entryNames = mutableListOf<String>()
        var sawNote = false
        ZipFile(out).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryNames += entry.name
                val bytes = zip.getInputStream(entry).readBytes()
                if (String(bytes, Charsets.ISO_8859_1).contains("Vector exclusion probe")) {
                    sawNote = true
                }
                assertFalse(
                    "vector bytes leaked into entry ${entry.name}",
                    containsSubarray(bytes, sentinelBytes),
                )
            }
        }
        // Not vacuous: the note really is in the backup.
        assertTrue(sawNote)
        assertTrue(entryNames.isNotEmpty())
        assertFalse(
            "an entry is named like the semantic index",
            entryNames.any { it.contains("semantic", ignoreCase = true) },
        )
        // The export left the index itself untouched.
        assertTrue(indexStore.fileFor("guest").isFile)
        db.close()
    }

    private fun containsSubarray(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
