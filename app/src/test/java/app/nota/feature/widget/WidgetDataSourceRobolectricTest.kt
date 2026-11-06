package app.nota.feature.widget

import androidx.room.Room
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteEntity
import app.nota.data.db.ReminderEntity
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
 * Phase 5.3: the widget data source end-to-end over a real Room
 * database — today's-note selection (trashed daily notes never
 * surface), the next-reminder pick (spent / past / trashed-note
 * reminders skipped), per-account scoping (the source reads ONLY
 * the database its provider hands it), and the App Lock posture
 * short-circuit: hidden means the render holds no note data at
 * all, whatever the tables contain.
 *
 * PR #30 fix round (Codex P2) adds the one-snapshot rule: a load
 * captures the account id + database once, so an account switch
 * landing mid-load — driven into the load's two switch windows
 * via the source's midLoadHook test seam, against a REAL
 * two-account provider — can never publish a mixed render.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetDataSourceRobolectricTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 11, 6)
    private val nowMillis: Long = java.time.ZonedDateTime.of(
        2026, 11, 6, 9, 0, 0, 0, zone,
    ).toInstant().toEpochMilli()

    private lateinit var db: NotaDatabase
    private lateinit var provider: DatabaseProvider

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        provider = DatabaseProvider.forDatabase(context, db)
    }

    @After
    fun tearDown() {
        // The switch tests hand this database to a provider that
        // closes it on switchTo — a second close in tearDown must
        // not fail an otherwise-passing test.
        runCatching { db.close() }
    }

    private fun source(hidden: Boolean = false) = WidgetDataSource(
        provider = provider,
        appLockHidden = { hidden },
    )

    private suspend fun load(hidden: Boolean = false) = source(hidden).load(
        today = today,
        zone = zone,
        locale = Locale.UK,
        nowMillis = nowMillis,
    )

    private fun dailyNote(
        id: String = "daily-1",
        title: String = "Friday plans",
        plainText: String = "Buy tickets and pack the bag",
        locked: Boolean = false,
        trashedAt: Long? = null,
    ) = NoteEntity(
        id = id,
        title = title,
        contentHtml = "",
        plainText = plainText,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        dailyDate = today.toString(),
        locked = locked,
        trashedAt = trashedAt,
    )

    private fun reminder(
        id: String,
        noteId: String,
        triggerAt: Long,
        enabled: Boolean = true,
    ) = ReminderEntity(
        id = id,
        noteId = noteId,
        triggerAt = triggerAt,
        repeatRule = null,
        enabled = enabled,
        createdAt = 1_000L,
    )

    @Test
    fun emptyLibraryIsTheEmptyState() = runBlocking {
        val state = load()
        assertEquals(WidgetTodayKind.EMPTY, state.todayKind)
        assertNull(state.reminderLine)
        assertEquals("Friday, 6 November", state.dateLabel)
    }

    @Test
    fun dailyNoteAndNextReminderFlowThrough() = runBlocking {
        db.noteDao().upsert(dailyNote())
        val later = nowMillis + 2 * 3_600_000L
        db.reminderDao().upsert(reminder("r-later", "daily-1", later))
        // An earlier reminder on a TRASHED note must be skipped,
        // and a spent one must never count even when it is earliest.
        // (A reminder on a fully deleted note cannot exist — the
        // reminders table's FK cascades it away with the note.)
        db.noteDao().upsert(
            dailyNote(id = "trashed-1", title = "Old", trashedAt = 5_000L),
        )
        db.reminderDao().upsert(reminder("r-trashed", "trashed-1", nowMillis + 60_000L))
        db.reminderDao().upsert(reminder("r-spent", "daily-1", nowMillis + 30_000L, enabled = false))
        val state = load()
        assertEquals(WidgetTodayKind.CONTENT, state.todayKind)
        assertEquals("Friday plans", state.todayTitle)
        assertEquals("Buy tickets and pack the bag", state.todayExcerpt)
        assertEquals("daily-1", state.todayNoteId)
        assertTrue(state.reminderLine!!.startsWith("Friday plans ·"))
    }

    @Test
    fun trashedDailyNoteNeverSurfaces() = runBlocking {
        db.noteDao().upsert(dailyNote(trashedAt = 5_000L))
        val state = load()
        assertEquals(WidgetTodayKind.EMPTY, state.todayKind)
        assertNull(state.todayNoteId)
    }

    @Test
    fun lockedDailyNoteIsMaskedAtTheSource() = runBlocking {
        db.noteDao().upsert(
            dailyNote(title = "Secret diary", plainText = "", locked = true),
        )
        val state = load()
        assertEquals(WidgetTodayKind.LOCKED, state.todayKind)
        assertEquals(WidgetStateMapper.LOCKED_LABEL, state.todayTitle)
        assertNull(state.todayNoteId)
    }

    @Test
    fun hiddenPostureShowsNoNoteDataWhateverTheTablesHold() = runBlocking {
        db.noteDao().upsert(dailyNote())
        db.reminderDao().upsert(reminder("r1", "daily-1", nowMillis + 3_600_000L))
        val state = load(hidden = true)
        assertEquals(WidgetTodayKind.HIDDEN, state.todayKind)
        assertNull(state.todayNoteId)
        assertNull(state.reminderLine)
    }

    @Test
    fun sourceReadsOnlyItsProvidersDatabase() = runBlocking {
        // Content lives in THIS database...
        db.noteDao().upsert(dailyNote())
        // ...while a second, empty database (another account) sees
        // the empty state: scoping is by construction, via the
        // provider the source was handed.
        val context = RuntimeEnvironment.getApplication()
        val otherDb = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val otherSource = WidgetDataSource(
                provider = DatabaseProvider.forDatabase(context, otherDb),
                appLockHidden = { false },
            )
            val state = otherSource.load(
                today = today, zone = zone, locale = Locale.UK, nowMillis = nowMillis,
            )
            assertEquals(WidgetTodayKind.EMPTY, state.todayKind)
        } finally {
            otherDb.close()
        }
        // And the owning database still shows its note.
        assertEquals(WidgetTodayKind.CONTENT, load().todayKind)
    }

    // --- PR #30 fix round (Codex P2): one account snapshot per load ---

    /** Pre-creates account B's on-disk library (its own daily
     *  note + a reminder on it), so a provider switch has real B
     *  content to land on. Runs on IO: the file-backed database
     *  does not allow main-thread queries. */
    private suspend fun seedAccountB(context: android.content.Context) {
        val bDb = NotaDatabase.build(context, "acct-b")
        try {
            bDb.noteDao().upsert(
                dailyNote(id = "b-daily", title = "B account day"),
            )
            bDb.reminderDao().upsert(
                reminder("b-rem", "b-daily", nowMillis + 3_600_000L),
            )
        } finally {
            bDb.close()
        }
    }

    /** A REAL provider whose active account is "acct-a" backed by
     *  the in-memory fixture db, with "acct-b" seeded on disk —
     *  [DatabaseProvider.switchTo] can genuinely move it. */
    private suspend fun switchableProvider(
        context: android.content.Context,
    ): DatabaseProvider {
        val p = DatabaseProvider(context, "acct-a", db)
        withContext(Dispatchers.IO) { seedAccountB(context) }
        return p
    }

    private suspend fun seedAccountA() {
        db.noteDao().upsert(dailyNote(title = "A account day"))
        db.reminderDao().upsert(reminder("a-rem", "daily-1", nowMillis + 3_600_000L))
    }

    @Test
    fun accountSwitchAfterReadsDiscardsToHidden() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        seedAccountA()
        val p = switchableProvider(context)
        val switchSource = WidgetDataSource(provider = p, appLockHidden = { false })
        var hookCalls = 0
        switchSource.midLoadHook = {
            hookCalls++
            // First window (between the reads): stay on A, so both
            // reads complete against A. Second window (after the
            // reads, before publishing): switch to B.
            if (hookCalls == 2) {
                withContext(Dispatchers.IO) { p.switchTo("acct-b") }
            }
        }
        val state = withContext(Dispatchers.IO) {
            switchSource.load(
                today = today, zone = zone, locale = Locale.UK, nowMillis = nowMillis,
            )
        }
        // Account A's content was fully read — and none of it may
        // be published once the account has moved on.
        assertEquals(WidgetTodayKind.HIDDEN, state.todayKind)
        assertNull(state.todayNoteId)
        assertNull(state.reminderLine)
        assertEquals(2, hookCalls)
        assertEquals("acct-b", p.accountId.value)
    }

    @Test
    fun accountSwitchBetweenReadsNeverPublishesMixedContent() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        seedAccountA()
        val p = switchableProvider(context)
        val switchSource = WidgetDataSource(provider = p, appLockHidden = { false })
        var hookCalls = 0
        switchSource.midLoadHook = {
            hookCalls++
            // First window: the switch lands between the daily
            // read and the reminder read.
            if (hookCalls == 1) {
                withContext(Dispatchers.IO) { p.switchTo("acct-b") }
            }
        }
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                switchSource.load(
                    today = today, zone = zone, locale = Locale.UK, nowMillis = nowMillis,
                )
            }
        }
        // The switch closes A's database under the in-flight load,
        // so the snapshot's reminder read may fail outright (in
        // production NotaWidgetProvider converts any load failure
        // into the same HIDDEN state). What must NEVER happen is
        // a published render mixing the two libraries — A's daily
        // note wearing B's reminder, or any B content at all: a
        // returned state here can only be the hidden discard.
        val state = outcome.getOrNull()
        if (state != null) {
            assertEquals(WidgetTodayKind.HIDDEN, state.todayKind)
            assertNull(state.todayNoteId)
            assertNull(state.reminderLine)
        }
    }
}
