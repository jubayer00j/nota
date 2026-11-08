package app.nota.feature.search

import androidx.room.Room
import app.nota.data.ai.AiProviderId
import app.nota.data.ai.AiProviderSlot
import app.nota.data.ai.AiSettingsStore
import app.nota.data.ai.FakeAiKeyCipher
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.NoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/**
 * Beginner AI Setup #1 (F7): Search's "search by meaning is off"
 * state is true exactly while the ACTIVE account's active
 * provider has no saved key — another account's key must not
 * retire it, and a key saved for this account retires it on
 * refresh. Same harness family as the Ask suite: an in-memory
 * database and the real settings store over the fake cipher.
 */
@RunWith(RobolectricTestRunner::class)
class SearchSemanticHintRobolectricTest {

    private lateinit var db: NotaDatabase
    private lateinit var store: AiSettingsStore
    private lateinit var viewModel: SearchViewModel

    private val account = "acct-hint"

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = AiSettingsStore(PreferencesRepository(context), FakeAiKeyCipher())
        // The store sits on the app-wide DataStore, which outlives
        // each method: a key one test saves would otherwise still be
        // there for the next, making outcomes depend on run order.
        // Wipe both accounts this suite uses BEFORE the view model
        // exists, so its first state read sees the pristine no-key
        // state (clearing after construction could race that read).
        runBlocking {
            store.clearAccount(account)
            store.clearAccount("someone-else")
        }
        viewModel = SearchViewModel(
            repository = NoteRepository(DatabaseProvider.forDatabase(context, db)),
            semanticIndexer = null,
            aiSettingsStore = store,
            accountIdProvider = { account },
        )
    }

    @After
    fun tearDown() {
        // Leave the shared DataStore as setUp found it: nothing a
        // test saved may leak into a later method (JUnit runs this
        // even when the test itself fails).
        runBlocking {
            store.clearAccount(account)
            store.clearAccount("someone-else")
        }
        db.close()
    }

    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (runCatching { condition() }.getOrDefault(false)) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for: $description")
    }

    /** Gives pending main-looper work every chance to land, for
     *  assertions that a state did NOT change. */
    private fun settle() {
        repeat(40) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    @Test
    fun noKey_hintOn_thenKeySaved_hintOffAfterRefresh() {
        awaitTrue("hint on with no key saved") { viewModel.semanticNeedsKey.value }
        runBlocking {
            store.saveSlot(account, AiProviderId.GEMINI, AiProviderSlot(apiKey = "key-1"))
        }
        viewModel.refreshSemanticHint()
        awaitTrue("hint off once a key exists") { !viewModel.semanticNeedsKey.value }
    }

    @Test
    fun keyForAnotherAccount_doesNotRetireTheHint() {
        awaitTrue("hint on with no key saved") { viewModel.semanticNeedsKey.value }
        runBlocking {
            store.saveSlot(
                "someone-else", AiProviderId.GEMINI,
                AiProviderSlot(apiKey = "key-2"),
            )
            // The seam itself is account-scoped…
            assertTrue(store.hasUsableKey("someone-else"))
            assertFalse(store.hasUsableKey(account))
        }
        viewModel.refreshSemanticHint()
        settle()
        // …so this account's hint survives the refresh.
        assertTrue(viewModel.semanticNeedsKey.value)
    }

    @Test
    fun noStore_hintStaysOff() {
        val context = RuntimeEnvironment.getApplication()
        val bare = SearchViewModel(
            repository = NoteRepository(DatabaseProvider.forDatabase(context, db)),
        )
        assertFalse(bare.semanticNeedsKey.value)
    }
}
