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
import org.junit.Assert.assertEquals
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
 * Beginner AI Setup #1 (F7), config-aware since fix round 2
 * (Codex P2): Search's "search by meaning is off" hint follows
 * whether an embedding config actually RESOLVES for the ACTIVE
 * account:
 *  - a preset with no key is NEEDS_KEY (a key completes a
 *    preset's setup — its endpoint and embedding model are
 *    pre-filled, which is also why a preset slot whose stored
 *    embedding field is blank still resolves: the provider
 *    default fills it);
 *  - Custom at any incomplete stage, and any key whose
 *    embedding config still doesn't resolve, is NEEDS_SETUP —
 *    saving just a key on Custom must NOT retire the hint,
 *    because semantic search still can't run;
 *  - fix round 3 (Codex P2): a Custom config can RESOLVE on
 *    paper (key + embedding model — all the store's check
 *    looks at) while its endpoint is blank or invalid; every
 *    embedding call from it fails NOT_CONFIGURED, so it stays
 *    NEEDS_SETUP until the endpoint is a usable https URL;
 *  - a resolved config (any provider) with a usable endpoint
 *    is OFF.
 * Account scoping is pinned too: another account's key changes
 * nothing here. Same harness family as the Ask suite: an
 * in-memory database and the real settings store over the fake
 * cipher.
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

    private fun awaitHint(stage: SemanticHint, description: String) =
        awaitTrue(description) { viewModel.semanticHint.value == stage }

    /** Gives pending main-looper work every chance to land, for
     *  assertions that a state did NOT change. */
    private fun settle() {
        repeat(40) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    @Test
    fun presetNoKey_needsKey_thenKeySaved_hintOffAfterRefresh() {
        // (a) The default active provider is the Gemini preset:
        // no key yet, and a key is all a preset is missing.
        awaitHint(SemanticHint.NEEDS_KEY, "NEEDS_KEY with no key saved")
        runBlocking {
            store.saveSlot(account, AiProviderId.GEMINI, AiProviderSlot(apiKey = "key-1"))
        }
        viewModel.refreshSemanticHint()
        // (e) The saved key completes the preset's config — its
        // embedding model resolves from the provider default
        // even though the slot stored none — so the hint retires.
        awaitHint(SemanticHint.OFF, "hint off once the preset config resolves")
    }

    @Test
    fun keyForAnotherAccount_doesNotRetireTheHint() {
        awaitHint(SemanticHint.NEEDS_KEY, "NEEDS_KEY with no key saved")
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
        // …so this account's hint survives the refresh, unmoved.
        assertEquals(SemanticHint.NEEDS_KEY, viewModel.semanticHint.value)
    }

    @Test
    fun noStore_hintStaysOff() {
        val context = RuntimeEnvironment.getApplication()
        val bare = SearchViewModel(
            repository = NoteRepository(DatabaseProvider.forDatabase(context, db)),
        )
        assertEquals(SemanticHint.OFF, bare.semanticHint.value)
    }

    @Test
    fun customNoKey_needsSetup() {
        // (b) Custom with nothing saved: a key alone would NOT
        // switch semantic search on (endpoint and embedding
        // model are missing too), so the honest stage is
        // NEEDS_SETUP from the start.
        runBlocking { store.setActiveProvider(account, AiProviderId.CUSTOM) }
        viewModel.refreshSemanticHint()
        awaitHint(SemanticHint.NEEDS_SETUP, "NEEDS_SETUP for a fresh Custom provider")
    }

    @Test
    fun customKeyOnly_staysNeedsSetup() {
        // (c) The Codex scenario: on Custom, saving ONLY a key
        // must not retire the guidance — the embedding config
        // still doesn't resolve, so semantic search still
        // can't run.
        runBlocking { store.setActiveProvider(account, AiProviderId.CUSTOM) }
        viewModel.refreshSemanticHint()
        awaitHint(SemanticHint.NEEDS_SETUP, "NEEDS_SETUP before the key")
        runBlocking {
            store.saveSlot(account, AiProviderId.CUSTOM, AiProviderSlot(apiKey = "key-3"))
            assertTrue(store.hasUsableKey(account))
        }
        viewModel.refreshSemanticHint()
        settle()
        assertEquals(SemanticHint.NEEDS_SETUP, viewModel.semanticHint.value)
    }

    @Test
    fun customKeyAndEndpoint_noEmbeddingModel_needsSetup() {
        // (d) A key AND the endpoint, but the embedding model
        // was never filled in: Custom has no default to fall
        // back to, so the config is still incomplete.
        runBlocking {
            store.setActiveProvider(account, AiProviderId.CUSTOM)
            store.saveSlot(
                account, AiProviderId.CUSTOM,
                AiProviderSlot(apiKey = "key-4", baseUrl = "https://example.com/v1"),
            )
        }
        viewModel.refreshSemanticHint()
        awaitHint(SemanticHint.NEEDS_SETUP, "NEEDS_SETUP while the embedding model is missing")
    }

    @Test
    fun customCompleteConfig_hintOff() {
        // (e) Key + a valid https endpoint + embedding model:
        // the config resolves, its endpoint can serve the
        // call, and the hint retires.
        awaitHint(SemanticHint.NEEDS_KEY, "NEEDS_KEY before setup")
        runBlocking {
            store.setActiveProvider(account, AiProviderId.CUSTOM)
            store.saveSlot(
                account, AiProviderId.CUSTOM,
                AiProviderSlot(
                    apiKey = "key-5",
                    baseUrl = "https://example.com/v1",
                    embeddingModel = "embed-1",
                ),
            )
        }
        viewModel.refreshSemanticHint()
        awaitHint(SemanticHint.OFF, "hint off once the Custom config resolves")
    }

    @Test
    fun customKeyAndModel_blankEndpoint_needsSetup() {
        // (f) Fix round 3 (Codex P2): key + embedding model
        // but NO endpoint. The store RESOLVES a config here —
        // its completeness check covers the key and the model
        // only (pinned by the assert below) — yet every
        // embedding call would fail NOT_CONFIGURED on the
        // missing endpoint, so the hint must not retire.
        runBlocking {
            store.setActiveProvider(account, AiProviderId.CUSTOM)
            store.saveSlot(
                account, AiProviderId.CUSTOM,
                AiProviderSlot(apiKey = "key-6", embeddingModel = "embed-1"),
            )
            assertTrue(store.activeEmbeddingConfig(account) != null)
        }
        viewModel.refreshSemanticHint()
        awaitHint(SemanticHint.NEEDS_SETUP, "NEEDS_SETUP while the endpoint is blank")
    }

    @Test
    fun customKeyAndModel_invalidEndpoint_needsSetup() {
        // (g) The same gap with an endpoint present but
        // unusable: the embedding call demands https and a
        // parseable URL, so neither an http:// address nor a
        // malformed https one completes the setup. The store
        // saves the raw text either way (validation lives at
        // call time), which is exactly how such a slot reaches
        // a device.
        runBlocking {
            store.setActiveProvider(account, AiProviderId.CUSTOM)
            store.saveSlot(
                account, AiProviderId.CUSTOM,
                AiProviderSlot(
                    apiKey = "key-7",
                    baseUrl = "http://insecure.example/v1",
                    embeddingModel = "embed-1",
                ),
            )
            assertTrue(store.activeEmbeddingConfig(account) != null)
        }
        viewModel.refreshSemanticHint()
        awaitHint(SemanticHint.NEEDS_SETUP, "NEEDS_SETUP for an http endpoint")
        runBlocking {
            store.saveSlot(
                account, AiProviderId.CUSTOM,
                AiProviderSlot(
                    apiKey = "key-7",
                    baseUrl = "https://exa mple.invalid/v1",
                    embeddingModel = "embed-1",
                ),
            )
        }
        viewModel.refreshSemanticHint()
        settle()
        assertEquals(SemanticHint.NEEDS_SETUP, viewModel.semanticHint.value)
    }
}
