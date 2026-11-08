package app.nota.feature.export

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Per-note Export #1 fix round — NoteExportDelivery.cacheFile:
 * two artifacts with the same file name must land in distinct
 * files. The flat exports/<fileName> layout let a second export
 * overwrite the file backing an earlier share's FileProvider URI,
 * so a recipient reading that URI later received the newer note.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteExportDeliveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sameNameExportsGetDistinctFilesWithTheirOwnContents() {
        val first = NoteExportArtifact("Trip.md", "text/markdown", "first body")
        val second = NoteExportArtifact("Trip.md", "text/markdown", "second body")
        val firstFile = NoteExportDelivery.cacheFile(context, first)
        val secondFile = NoteExportDelivery.cacheFile(context, second)

        assertNotEquals(firstFile.absolutePath, secondFile.absolutePath)
        // The leaf name stays the artifact's clean file name, one
        // level under the exports cache dir (its unique subdir).
        assertEquals("Trip.md", firstFile.name)
        assertEquals("Trip.md", secondFile.name)
        assertEquals("exports", firstFile.parentFile?.parentFile?.name)
        assertEquals("exports", secondFile.parentFile?.parentFile?.name)
        assertNotEquals(firstFile.parentFile, secondFile.parentFile)
        // Neither write clobbered the other.
        assertEquals("first body", firstFile.readText())
        assertEquals("second body", secondFile.readText())
    }

    @Test
    fun staleExportDirsArePrunedFreshOnesKept() {
        val root = File(context.cacheDir, "exports").apply { mkdirs() }
        val stale = File(root, "share-stale").apply { mkdirs() }
        File(stale, "Old.md").writeText("old")
        check(stale.setLastModified(System.currentTimeMillis() - 48 * 60 * 60 * 1000L))

        val fresh = NoteExportDelivery.cacheFile(
            context,
            NoteExportArtifact("New.md", "text/markdown", "new"),
        )

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertEquals("new", fresh.readText())
    }
    @org.junit.After
    fun clearExportSaveStore() {
        ExportSaveStore.take()
        ExportSaveStore.launcher = null
    }

    @Test
    fun exportSaveStoreStagesAndTakesOnce() {
        val artifact = NoteExportArtifact("A.md", "text/markdown", "body")
        ExportSaveStore.stage(artifact)
        assertEquals(artifact, ExportSaveStore.take())
        assertEquals(null, ExportSaveStore.take())
    }

    @Test
    fun exportSaveStoreLaunchNeedsAnInstalledLauncher() {
        ExportSaveStore.launcher = null
        assertFalse(ExportSaveStore.launch(Intent(Intent.ACTION_CREATE_DOCUMENT)))
        var received: Intent? = null
        ExportSaveStore.launcher = { received = it }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        assertTrue(ExportSaveStore.launch(intent))
        assertEquals(intent, received)
    }

}
