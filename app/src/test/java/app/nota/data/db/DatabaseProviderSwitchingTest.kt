package app.nota.data.db

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Account database switching (Phase 3a) — the isolation contract the
 * whole accounts feature stands on:
 * - each account's rows live in its own database and never leak into
 *   another's,
 * - switching away and back loses nothing (the guest file is sacred:
 *   signing in must not touch it),
 * - switching to the already-active account is a no-op.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseProviderSwitchingTest {

    private lateinit var provider: DatabaseProvider

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        // Clean slate per test: remove any database files a previous
        // test in this sandbox left behind.
        for (account in listOf("guest", "uid-alpha", "uid-beta")) {
            context.deleteDatabase(NotaDatabase.databaseName(account))
        }
        provider = DatabaseProvider(context, NotaDatabase.GUEST_ACCOUNT)
    }

    @After
    fun tearDown() {
        provider.current.close()
    }

    private fun note(id: String, title: String) = NoteEntity(
        id = id,
        title = title,
        contentHtml = "<p>$title</p>",
        plainText = title,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun accountsAreIsolatedAndGuestSurvivesRoundTrip() = runBlocking {
        provider.current.noteDao().upsert(note("g1", "Guest note"))
        assertEquals(NotaDatabase.GUEST_ACCOUNT, provider.accountId.first())

        assertTrue(provider.switchTo("uid-alpha"))
        assertEquals("uid-alpha", provider.accountId.first())
        // The account library starts empty — the guest note is NOT here.
        assertTrue(provider.current.noteDao().getAll().isEmpty())
        provider.current.noteDao().upsert(note("a1", "Account note"))

        // Back to guest: the guest library is exactly as it was left.
        assertTrue(provider.switchTo(NotaDatabase.GUEST_ACCOUNT))
        val guestNotes = provider.current.noteDao().getAll()
        assertEquals(listOf("g1"), guestNotes.map { it.id })

        // And the account kept its own note too.
        assertTrue(provider.switchTo("uid-alpha"))
        assertEquals(listOf("a1"), provider.current.noteDao().getAll().map { it.id })
    }

    @Test
    fun switchingToTheActiveAccountIsANoOp() {
        assertFalse(provider.switchTo(NotaDatabase.GUEST_ACCOUNT))
        assertTrue(provider.switchTo("uid-beta"))
        assertFalse(provider.switchTo("uid-beta"))
    }

    @Test
    fun existingAccountsAreDiscoveredFromDisk() = runBlocking {
        provider.current.noteDao().upsert(note("g1", "Guest note"))
        provider.switchTo("uid-alpha")
        provider.current.noteDao().upsert(note("a1", "Account note"))
        val accounts = provider.existingAccountIds()
        assertTrue(accounts.contains(NotaDatabase.GUEST_ACCOUNT))
        assertTrue(accounts.contains("uid-alpha"))
    }

    // (PR #59 fix round 18 — the BackupRestoreSemanticsTest
    // SQLITE_BUSY flake, third recurrence): a database open's first
    // write is installLibraryMeta's INSERT in onOpen, and another
    // connection can hold the file at that moment — the app-startup
    // orphan sweep opens every account database it finds. With
    // SQLite's default busy timeout of 0 the loser failed instantly
    // with SQLiteDatabaseLockedException. The open now waits up to
    // 5 s (PRAGMA busy_timeout, set first in installLibraryMeta) —
    // this test proves it: a holder takes a
    // write lock for 300 ms while a second handle opens the same
    // file. Without the timeout the open throws immediately; with
    // it, it waits out the hold and succeeds.
    @Test
    fun dbOpenWaitsOutTransientWriteLock() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val accountId = "busy-timeout"
        context.deleteDatabase(NotaDatabase.databaseName(accountId))
        val holder = NotaDatabase.build(context, accountId)
        val holderDb = holder.openHelper.writableDatabase
        // The holder's transaction lives on ONE thread (SQLite
        // transactions are thread-bound): begin + INSERT take the
        // RESERVED lock, the latch signals it is held, the lock is
        // released 300 ms later.
        val lockHeld = CountDownLatch(1)
        var holderError: Throwable? = null
        val holderThread = thread(isDaemon = true) {
            try {
                holderDb.beginTransaction()
                try {
                    holderDb.execSQL(
                        "INSERT OR IGNORE INTO library_meta " +
                            "(id, notesWriteGeneration) VALUES (1, 0)",
                    )
                    lockHeld.countDown()
                    Thread.sleep(300)
                    holderDb.setTransactionSuccessful()
                } finally {
                    holderDb.endTransaction()
                }
            } catch (e: Throwable) {
                holderError = e
                lockHeld.countDown()
            }
        }
        try {
            // Do not start the opener until the lock is provably held
            // — otherwise it could open first and the test would pass
            // vacuously, with or without the fix.
            assertTrue(lockHeld.await(10, TimeUnit.SECONDS))
            assertNull(holderError)
            val opener = NotaDatabase.build(context, accountId)
            try {
                // Bounded: if the timeout ever stopped applying, this
                // fails the test in seconds instead of hanging the shard.
                withTimeout(10_000) {
                    assertTrue(opener.noteDao().getAll().isEmpty())
                }
            } finally {
                opener.close()
            }
            holderThread.join(5_000)
            assertNull(holderError)
        } finally {
            holder.close()
        }
    }
}
