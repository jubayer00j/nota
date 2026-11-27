package app.nota.data.repository

import androidx.room.Room
import app.nota.data.backup.BackupRepository
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteEntity
import app.nota.data.repository.NoteRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
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
        contentEdited: LocalDate? = updated,
        plainText: String = "",
        archived: Boolean = false,
        trashedAt: Long? = null,
        locked: Boolean = false,
    ) = NoteEntity(
        id = id,
        title = id,
        contentHtml = "<p>$plainText</p>",
        plainText = plainText,
        // Fix round 2: fixtures state the stored count, as a
        // real writer would — the stats read the column, never
        // the body.
        wordCount = BackupRepository.wordCount(plainText),
        createdAt = millis(created),
        updatedAt = millis(updated),
        contentEditedAt = contentEdited?.let { millis(it) },
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

    /**
     * Fix round 1 (R1-F1): pinning is a metadata operation, not
     * writing. The note was created (and last content-edited) on
     * Monday 16 November; the pin below bumps `updatedAt` to real
     * now (Friday 27 November) through the real repository path.
     * Before the fix the pin's day counted — two writing days and
     * an edit this week; after it, the stats see only the 16th.
     */
    @Test
    fun pinningDoesNotCountAsWriting() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val notes = NoteRepository(DatabaseProvider.forDatabase(context, db))
        db.noteDao().upsert(
            entity("solo", LocalDate.of(2026, 11, 16), plainText = "solo"),
        )
        notes.setPinned(db.noteDao().getById("solo")!!, true)
        val stored = db.noteDao().getById("solo")!!
        // The pin moved updatedAt to today but left the content
        // edit where it was.
        assertTrue(stored.updatedAt > millis(LocalDate.of(2026, 11, 25)))
        assertEquals(millis(LocalDate.of(2026, 11, 16)), stored.contentEditedAt)
        val stats = stats()
        assertEquals(1, stats.totalNotes)
        assertEquals(1, stats.writingDays)
        assertEquals(0, stats.currentStreak)
        // The pin's day (27 Nov) is in this week — the content
        // edit (16 Nov) is not, so no edit counts this week.
        assertEquals(0, stats.editedThisWeek)
        // …but the content edit IS in November, so the month
        // count still sees it; only the week is pin-free.
        assertEquals(1, stats.editedThisMonth)
        assertEquals(0, stats.startedThisWeek)
        assertEquals(1, stats.startedThisMonth)
    }

    /**
     * Fix round 2 (R2-F1): a note whose body is past Android's
     * ~2 MiB CursorWindow must not fail the stats load. The
     * projection carries no body text at all — the 1.2M-char
     * body below (240,000 words, ~2.4 MB as UTF-16) is counted
     * from its stored column, and the load succeeds with the
     * exact total.
     */
    @Test
    fun hugeNoteCannotFailStatsLoad() = runBlocking {
        db.noteDao().upsert(
            entity(
                "huge",
                LocalDate.of(2026, 11, 25),
                plainText = "word ".repeat(240_000),
            ),
        )
        db.noteDao().upsert(
            entity("small", LocalDate.of(2026, 11, 25), plainText = "one two"),
        )
        val stats = stats()
        assertEquals(2, stats.totalNotes)
        assertEquals(240_002, stats.totalWords)
        assertEquals(1, stats.writingDays)
        assertEquals(1, stats.currentStreak)
    }

    /**
     * Fix round 2: the stored count is maintained by the
     * repository's write paths — created and edited bodies get
     * their exact count, and a metadata op (pin) leaves both
     * the text and its count alone.
     */
    @Test
    fun wordCountMaintainedByRepositoryWrites() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val notes = NoteRepository(DatabaseProvider.forDatabase(context, db))
        val created = notes.createNote(title = "t", plainBody = "one two three")
        assertEquals(3, db.noteDao().getById(created.id)!!.wordCount)
        val edited = notes.updateBody(created, "t", "one two three four five")
        assertEquals(5, db.noteDao().getById(edited.id)!!.wordCount)
        notes.setPinned(edited, true)
        val stored = db.noteDao().getById(edited.id)!!
        assertEquals(5, stored.wordCount)
        assertEquals("one two three four five", stored.plainText)
    }

    /**
     * Fix round 3 (R3-F1): a metadata op on a huge note must not
     * recount its words. The 300K-char body below is counted once,
     * at creation, by the streaming counter; pin/favourite/archive/
     * move/trash/restore then reuse the stored count — the
     * wordCountRecomputations seam stays put and the stored count
     * (and the stats total) never moves. A real content edit does
     * recount, streaming, to the exact new total.
     *
     * Why 300K chars, not 1.2M: a 1.2M-char body makes the note's
     * full row exceed Android's ~2 MiB CursorWindow, so the
     * repository's own full-row reads (getById, and
     * relinkReferencingNotes' getAll on a title change) fail before
     * the skip is even reached — a PRE-EXISTING gap in the write
     * path, not this finding (see fix3 SUMMARY). 300K chars keeps
     * the row readable while the split it avoids is still a real
     * 60,000-token list; the seam, not the size, proves the skip.
     */
    @Test
    fun metadataOpsOnHugeNoteReuseStoredWordCount() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val notes = NoteRepository(DatabaseProvider.forDatabase(context, db))
        val huge = "word ".repeat(60_000) // 300K chars, 60,000 words
        val created = notes.createNote(title = "huge", plainBody = huge)
        val id = created.id
        assertEquals(60_000, db.noteDao().getById(id)!!.wordCount)
        // The create counted once (streaming); nothing below may
        // recount while the text is unchanged.
        assertEquals(1, notes.wordCountRecomputations)
        notes.setPinned(db.noteDao().getById(id)!!, true)
        notes.setFavourite(db.noteDao().getById(id)!!, true)
        notes.setArchived(db.noteDao().getById(id)!!, true)
        notes.moveToFolder(db.noteDao().getById(id)!!, null)
        notes.trash(db.noteDao().getById(id)!!)
        notes.restore(db.noteDao().getById(id)!!)
        assertEquals(1, notes.wordCountRecomputations)
        val stored = db.noteDao().getById(id)!!
        assertEquals(60_000, stored.wordCount)
        assertEquals(huge, stored.plainText)
        assertEquals(60_000, stats().totalWords)
        // A real content edit DOES recount — streaming, exact.
        notes.updateBody(stored, stored.title, "one two")
        assertEquals(2, notes.wordCountRecomputations)
        assertEquals(2, db.noteDao().getById(id)!!.wordCount)
        assertEquals(2, stats().totalWords)
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
        // Four writing days: c contributes two (created 15 Oct,
        // last edited today) alongside a's and b's single days.
        assertEquals(4, stats.writingDays)
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

    /**
     * Fix round 5 (R5-F1): the repository is a Koin singleton, so
     * a zone captured at construction is frozen for the process
     * lifetime. A note created at 23:00 UTC on 24 November falls
     * on the 24th in UTC but on the 25th in Asia/Dhaka — so the
     * zone the load resolves with is directly observable in
     * `writingSince`. Constructed with no zone while the system
     * zone is UTC, then loaded after the system zone moves to
     * Dhaka, the stats must follow Dhaka, not the construction
     * zone. The JVM default zone is restored in a finally block:
     * it is process-global, and no other test may see the move.
     */
    @Test
    fun zoneChangeBetweenConstructionAndLoadIsHonoured() = runBlocking {
        val instant = LocalDateTime.of(2026, 11, 24, 23, 0)
            .atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        db.noteDao().upsert(
            NoteEntity(
                id = "late",
                title = "late",
                contentHtml = "<p>late words</p>",
                plainText = "late words",
                wordCount = 2,
                createdAt = instant,
                updatedAt = instant,
                contentEditedAt = instant,
            ),
        )
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val context = RuntimeEnvironment.getApplication()
            // No zone argument: production shape, resolved at load.
            val liveRepository = WritingStatsRepository(
                DatabaseProvider.forDatabase(context, db),
            )
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka"))
            val stats = liveRepository.loadStats(
                today = LocalDate.of(2026, 11, 25),
            )
            assertEquals(1, stats.totalNotes)
            assertEquals(1, stats.writingDays)
            // 23:00 UTC is 05:00 on the 25th in Dhaka.
            assertEquals(LocalDate.of(2026, 11, 25), stats.writingSince)
            assertEquals(1, stats.currentStreak)
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    /**
     * Fix round 5 (R5-F1): the other half of the contract — an
     * explicitly pinned zone (tests, and any caller that passes
     * one) still wins over the system zone. The setUp repository
     * pins UTC; with the system zone moved to Dhaka the load
     * must still group the 23:00 UTC note onto the 24th.
     */
    @Test
    fun constructorZoneStillPinsWhenSystemZoneChanges() = runBlocking {
        val instant = LocalDateTime.of(2026, 11, 24, 23, 0)
            .atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        db.noteDao().upsert(
            NoteEntity(
                id = "late",
                title = "late",
                contentHtml = "<p>late words</p>",
                plainText = "late words",
                wordCount = 2,
                createdAt = instant,
                updatedAt = instant,
                contentEditedAt = instant,
            ),
        )
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka"))
            val stats = repository.loadStats(
                today = LocalDate.of(2026, 11, 25),
            )
            assertEquals(1, stats.totalNotes)
            assertEquals(1, stats.writingDays)
            assertEquals(LocalDate.of(2026, 11, 24), stats.writingSince)
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }
}
