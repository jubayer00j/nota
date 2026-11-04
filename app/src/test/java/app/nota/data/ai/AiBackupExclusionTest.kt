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
import java.util.Base64
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The backup-exclusion proof (Phase 4.1): with AI keys fully
 * configured, a backup ZIP built by the real BackupRepository
 * contains no key material — not the plaintext key, not its
 * sealed form, nothing. This is structural (keys live in the
 * device-local preferences store; backups are built from Room),
 * and this test exists so the structure can never quietly change:
 * if AI state ever moves somewhere a backup reads, this fails.
 */
@RunWith(RobolectricTestRunner::class)
class AiBackupExclusionTest {

    @Test
    fun backupZipContainsNoKeyMaterial() = runBlocking {
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

        // Configure AI keys for two providers + the guest account.
        val prefs = PreferencesRepository(context)
        val store = AiSettingsStore(prefs, FakeAiKeyCipher())
        val secretKey = "SECRET-KEY-DO-NOT-LEAK-12345"
        store.saveSlot("guest", AiProviderId.GEMINI, AiProviderSlot(apiKey = secretKey, model = "m"))
        store.saveSlot("guest", AiProviderId.OPENAI, AiProviderSlot(apiKey = "sk-other-secret-678"))
        store.setActiveProvider("guest", AiProviderId.GEMINI)
        // Sanity: the key really is stored (sealed) before we export.
        val sealed = prefs.getAiField("guest", AiSettingsStore.keyField(AiProviderId.GEMINI))
        assertTrue(sealed != null && sealed != secretKey)

        // A note to make the backup non-trivial…
        val note = notes.createRichNote(
            title = "Backup exclusion probe",
            contentHtml = "<p>Ordinary note text.</p>",
        )

        val out = File(context.cacheDir, "ai-exclusion-probe.zip")
        backup.exportToFile(out, null)

        // Read EVERY entry's bytes and search for any form of the keys.
        val allBytes = StringBuilder()
        val entryNames = mutableListOf<String>()
        ZipFile(out).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryNames += entry.name
                val bytes = zip.getInputStream(entry).readBytes()
                allBytes.append(String(bytes, Charsets.ISO_8859_1))
            }
        }
        val zipText = allBytes.toString()
        // Not vacuous: the note we created really is in there.
        assertTrue(zipText.contains("Backup exclusion probe"))
        assertTrue(entryNames.isNotEmpty())

        assertFalse("plaintext Gemini key leaked", zipText.contains(secretKey))
        assertFalse("plaintext OpenAI key leaked", zipText.contains("sk-other-secret-678"))
        assertFalse(
            "sealed key form leaked",
            zipText.contains(Base64.getEncoder().encodeToString(secretKey.toByteArray())),
        )
        assertFalse("sealed marker leaked", zipText.contains(FakeAiKeyCipher.PREFIX))
        assertFalse(
            "an entry is named like AI state",
            entryNames.any { it.contains("ai", ignoreCase = true) && it.contains("key", ignoreCase = true) },
        )
        db.close()
    }
}
