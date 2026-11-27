package app.nota.data.repository

import androidx.room.Room
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Reading stats (v0.37.0): the repository over a real (in-memory)
 * Room database. Pins the eligibility semantics the calculator
 * alone cannot: trashed notes are excluded everywhere, locked
 * notes count toward note totals and writing days but never
 * toward words, archived notes still count (the stats semantics
 * exclude trash only), and the numbers come from the projected
 * query — never a decryption.
 *
 * "Today" is pinned to Wednesday 25 November 2026, noon UTC,
 * so streaks and periods are deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WritingStatsRobolectricTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 11, 25)

    private lateinit var db: NotaDatabase
    private lateinit var repository: WritingStatsRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WritingStatsRepository(
            DatabaseProvider.forDatabase(context, db),
            zone,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun millis(day: LocalDate): Long =
        day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun entity(
        id: String,
        created: LocalDate,
        updated: LocalDate = created,
        plainText: String = "",
        archived: Boolean = false,
        trashedAt: Long? = null,
        locked: Boolean = false,
    ) = NoteEntity(
        id = id,
        title = id,
        contentHtml = "<p>$plainText</p>",
        plainText = plainText,
        createdAt = millis(created),
        updatedAt = millis(updated),
        archived = archived,
        trashedAt = trashedAt,
        locked = locked,
        lockRecordJson = if (locked) "{}" else null,
    )

    private suspend fun stats() = repository.loadStats(today = today, zone = zone)

    @Test
    fun emptyLibrary_zeroStats() = runBlocking {
        val stats = stats()
        assertTrue(stats.isEmpty)
        assertEquals(0, stats.totalNotes)
        assertEquals(0, stats.totalWords)
        assertEquals(0, stats.writingDays)
        assertEquals(0, stats.currentStreak)
        assertEquals(List(WritingStatsCalculator.ACTIVITY_WEEKS) { 0 }, stats.weeklyActivity)
        assertNull(stats.writingSince)
    }

    @Test
    fun trashedNotesExcludedEverywhere() = runBlocking {
        db.noteDao().upsert(
            entity("live", LocalDate.of(2026, 11, 25), plainText = "one two"),
        )
        // The trashed note would win every stat if it leaked in:
        // more words, its own day, a longer streak.
        db.noteDao().upsert(
            entity(
                "gone",
                LocalDate.of(2026, 11, 24),
                plainText = "three four five six seven",
                trashedAt = millis(LocalDate.of(2026, 11, 25)),
            ),
        )
        val stats = stats()
        assertEquals(1, stats.totalNotes)
        assertEquals(2, stats.totalWords)
        assertEquals(1, stats.writingDays)
        assertEquals(1, stats.currentStreak)
        assertEquals(LocalDate.of(2026, 11, 25), stats.writingSince)
    }

    @Test
    fun lockedNoteCountsForNotesAndDaysButNeverWords() = runBlocking {
        db.noteDao().upsert(
            entity("open", LocalDate.of(2026, 11, 25), plainText = "visible words here"),
        )
        // A locked note as the lock flow leaves it: plainText
        // empty under the seal, its day an ordinary timestamp.
        db.noteDao().upsert(
            entity("sealed", LocalDate.of(2026, 11, 24), locked = true),
        )
        val stats = stats()
        assertEquals(2, stats.totalNotes)
        assertEquals(1, stats.lockedNotes)
        assertEquals(3, stats.totalWords)
        // The locked note's day is a writing day, and it keeps
        // the streak alive.
        assertEquals(2, stats.writingDays)
        assertEquals(2, stats.currentStreak)
    }

    @Test
    fun archivedNotesStillCount() = runBlocking {
        // The pinned semantics exclude trash only; an archived
        // note is still the user's writing.
        db.noteDao().upsert(
            entity(
                "arch",
                LocalDate.of(2026, 11, 25),
                plainText = "filed away words",
                archived = true,
            ),
        )
        val stats = stats()
        assertEquals(1, stats.totalNotes)
        assertEquals(3, stats.totalWords)
        assertEquals(1, stats.writingDays)
    }

    @Test
    fun streakAndPeriodsFromRealRows() = runBlocking {
        db.noteDao().upsert(
            entity("a", LocalDate.of(2026, 11, 23), plainText = "a"),
        )
        db.noteDao().upsert(
            entity("b", LocalDate.of(2026, 11, 24), plainText = "b"),
        )
        db.noteDao().upsert(
            entity(
                "c",
                created = LocalDate.of(2026, 10, 15),
                updated = LocalDate.of(2026, 11, 25),
                plainText = "c",
            ),
        )
        val stats = stats()
        assertEquals(3, stats.totalNotes)
        assertEquals(3, stats.writingDays)
        assertEquals(3, stats.currentStreak)
        assertEquals(3, stats.longestStreak)
        // This week (Mon 23 – Sun 29 Nov): a and b started, all
        // three edited (c's last edit is today).
        assertEquals(2, stats.startedThisWeek)
        assertEquals(3, stats.editedThisWeek)
        assertEquals(2, stats.startedThisMonth)
        assertEquals(3, stats.editedThisMonth)
        assertEquals(LocalDate.of(2026, 10, 15), stats.writingSince)
        assertEquals(3, stats.weeklyActivity.last())
    }
}
