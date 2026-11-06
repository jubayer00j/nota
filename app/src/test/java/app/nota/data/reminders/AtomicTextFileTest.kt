package app.nota.data.reminders

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5.1 fix round (Jules P3): the armed-reminder-ids file is
 * written atomically (temp file + rename) so a crash mid-write can
 * never truncate the only record of which system alarms are armed.
 */
class AtomicTextFileTest {

    private val dir: File = Files.createTempDirectory("atomic-text-file-test").toFile()
    private val target = File(dir, "reminder-armed-ids.txt")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun writeThenReadRoundTrips() {
        AtomicTextFile.write(target, "id-a\nid-b\nid-c")
        assertEquals("id-a\nid-b\nid-c", target.readText())
    }

    @Test
    fun overwriteReplacesPreviousContent() {
        AtomicTextFile.write(target, "old-content-that-is-much-longer")
        AtomicTextFile.write(target, "new")
        assertEquals("new", target.readText())
    }

    @Test
    fun noTempFileLeftBehind() {
        AtomicTextFile.write(target, "x")
        AtomicTextFile.write(target, "y")
        val names: List<String> = dir.list()?.toList() ?: emptyList()
        assertEquals(listOf("reminder-armed-ids.txt"), names)
        assertFalse(File(dir, "reminder-armed-ids.txt.tmp").exists())
    }

    @Test
    fun emptyContentWritesAnEmptyFile() {
        AtomicTextFile.write(target, "")
        assertTrue(target.exists())
        assertEquals("", target.readText())
    }
}
