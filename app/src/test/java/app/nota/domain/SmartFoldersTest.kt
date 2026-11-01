package app.nota.domain

import app.nota.data.db.NoteEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartFoldersTest {
    private val now = 1_700_000_000_000L

    private fun note(
        updatedAt: Long = now,
        favourite: Boolean = false,
        locked: Boolean = false,
        archived: Boolean = false,
        trashedAt: Long? = null,
    ) = NoteEntity(
        id = "n1",
        title = "t",
        createdAt = now,
        updatedAt = updatedAt,
        favourite = favourite,
        locked = locked,
        archived = archived,
        trashedAt = trashedAt,
    )

    @Test
    fun untaggedRule() {
        assertTrue(SmartFolders.matches(SmartFolderKey.UNTAGGED, note(), emptySet(), 0, now))
        assertFalse(SmartFolders.matches(SmartFolderKey.UNTAGGED, note(), setOf("tag1"), 0, now))
    }

    @Test
    fun last7DaysBoundaryIsIncluded() {
        val exactly7 = note(updatedAt = now - SmartFolders.SEVEN_DAYS_MS)
        assertTrue(SmartFolders.matches(SmartFolderKey.LAST_7_DAYS, exactly7, emptySet(), 0, now))
        val justOlder = note(updatedAt = now - SmartFolders.SEVEN_DAYS_MS - 1)
        assertFalse(SmartFolders.matches(SmartFolderKey.LAST_7_DAYS, justOlder, emptySet(), 0, now))
        assertTrue(SmartFolders.matches(SmartFolderKey.LAST_7_DAYS, note(updatedAt = now), emptySet(), 0, now))
    }

    @Test
    fun hasAttachmentsRule() {
        assertTrue(SmartFolders.matches(SmartFolderKey.HAS_ATTACHMENTS, note(), emptySet(), 1, now))
        assertFalse(SmartFolders.matches(SmartFolderKey.HAS_ATTACHMENTS, note(), emptySet(), 0, now))
    }

    @Test
    fun favouritesRule() {
        assertTrue(SmartFolders.matches(SmartFolderKey.FAVOURITES, note(favourite = true), emptySet(), 0, now))
        assertFalse(SmartFolders.matches(SmartFolderKey.FAVOURITES, note(), emptySet(), 0, now))
    }

    @Test
    fun lockedTrashedArchivedAlwaysExcluded() {
        val locked = note(favourite = true, locked = true)
        val trashed = note(favourite = true, trashedAt = now)
        val archived = note(favourite = true, archived = true)
        for (key in SmartFolderKey.entries) {
            assertFalse("locked in $key", SmartFolders.matches(key, locked, emptySet(), 3, now))
            assertFalse("trashed in $key", SmartFolders.matches(key, trashed, emptySet(), 3, now))
            assertFalse("archived in $key", SmartFolders.matches(key, archived, emptySet(), 3, now))
        }
    }
}
