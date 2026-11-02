package app.nota.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
