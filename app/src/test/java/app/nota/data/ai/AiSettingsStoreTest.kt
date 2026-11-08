package app.nota.data.ai

import app.nota.data.preferences.PreferencesRepository
import kotlinx.coroutines.runBlocking
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
 * AI settings store behaviour (Phase 4.1): per-provider AND
 * per-account isolation (the Phase 3a scoping rule), sealed-at-rest
 * keys, sign-out clearing, and the active-config projection the
 * editor actions gate on.
 */
@RunWith(RobolectricTestRunner::class)
class AiSettingsStoreTest {

    private lateinit var prefs: PreferencesRepository
    private lateinit var store: AiSettingsStore

    @Before
    fun setUp() {
        prefs = PreferencesRepository(RuntimeEnvironment.getApplication())
        store = AiSettingsStore(prefs, FakeAiKeyCipher())
    }

    @Test
    fun slotRoundTrip() = runBlocking {
        store.saveSlot(
            "acct-rt", AiProviderId.GEMINI,
            AiProviderSlot(apiKey = "key-1", model = "model-1"),
        )
        val slot = store.slot("acct-rt", AiProviderId.GEMINI)
        assertEquals("key-1", slot.apiKey)
        assertEquals("model-1", slot.model)
    }

    @Test
    fun keysAreSealedAtRest() = runBlocking {
        store.saveSlot(
            "acct-seal", AiProviderId.GEMINI,
            AiProviderSlot(apiKey = "super-secret-key"),
        )
        val raw = prefs.getAiField("acct-seal", AiSettingsStore.keyField(AiProviderId.GEMINI))
        assertFalse("stored value must not be the key", raw == "super-secret-key")
        assertFalse(
            "stored value must not contain the key",
            raw!!.contains("super-secret-key"),
        )
        // …and the sealed form still opens back to the key.
        assertEquals("super-secret-key", store.slot("acct-seal", AiProviderId.GEMINI).apiKey)
    }

    @Test
    fun providersKeepTheirOwnKeyAndModel() = runBlocking {
        store.saveSlot(
            "acct-iso", AiProviderId.GEMINI,
            AiProviderSlot(apiKey = "gemini-key", model = "gemini-model"),
        )
        store.saveSlot(
            "acct-iso", AiProviderId.OPENAI,
            AiProviderSlot(apiKey = "openai-key", model = "openai-model"),
        )
        store.saveSlot(
            "acct-iso", AiProviderId.CUSTOM,
            AiProviderSlot(apiKey = "custom-key", model = "custom-model", baseUrl = "https://x.example/v1/"),
        )
        assertEquals("gemini-key", store.slot("acct-iso", AiProviderId.GEMINI).apiKey)
        assertEquals("openai-key", store.slot("acct-iso", AiProviderId.OPENAI).apiKey)
        assertEquals("openai-model", store.slot("acct-iso", AiProviderId.OPENAI).model)
        val custom = store.slot("acct-iso", AiProviderId.CUSTOM)
        assertEquals("custom-key", custom.apiKey)
        assertEquals("https://x.example/v1", custom.baseUrl)
    }

    @Test
    fun accountsAreIsolated() = runBlocking {
        // Distinct ids per test throughout this class: the
        // DataStore file is shared across the shard fork, so an
        // id another suite also writes (e.g. the literal "guest"
        // the form test once used) would leak state between
        // classes.
        store.saveSlot("uid-a", AiProviderId.GEMINI, AiProviderSlot(apiKey = "a-key"))
        store.saveSlot("acct-guestlike", AiProviderId.GEMINI, AiProviderSlot(apiKey = "guest-key"))
        assertEquals("a-key", store.slot("uid-a", AiProviderId.GEMINI).apiKey)
        assertEquals("guest-key", store.slot("acct-guestlike", AiProviderId.GEMINI).apiKey)
        assertEquals("", store.slot("uid-b", AiProviderId.GEMINI).apiKey)
    }

    @Test
    fun activeConfigProjection() = runBlocking {
        assertNull(store.activeConfig("acct-proj"))
        store.saveSlot("acct-proj", AiProviderId.OPENROUTER, AiProviderSlot(apiKey = "or-key"))
        // Active provider is still the default (Gemini, no key).
        assertNull(store.activeConfig("acct-proj"))
        store.setActiveProvider("acct-proj", AiProviderId.OPENROUTER)
        val config = store.activeConfig("acct-proj")
        assertEquals(AiProviderId.OPENROUTER, config!!.providerId)
        assertEquals("or-key", config.apiKey)
        // No model stored → the preset default stands in.
        assertEquals("openai/gpt-4o-mini", config.resolvedModel())
        assertEquals("https://openrouter.ai/api/v1", config.resolvedBaseUrl())
    }

    @Test
    fun savingAnEmptyKeyClearsOnlyTheKey() = runBlocking {
        store.saveSlot(
            "acct-clear", AiProviderId.GEMINI,
            AiProviderSlot(apiKey = "key", model = "model"),
        )
        store.saveSlot(
            "acct-clear", AiProviderId.GEMINI,
            AiProviderSlot(apiKey = "", model = "model"),
        )
        val slot = store.slot("acct-clear", AiProviderId.GEMINI)
        assertEquals("", slot.apiKey)
        assertEquals("model", slot.model)
        assertNull(store.activeConfig("acct-clear"))
    }

    @Test
    fun clearAccountDropsEverythingForThatAccountOnly() = runBlocking {
        store.saveSlot("uid-drop", AiProviderId.GEMINI, AiProviderSlot(apiKey = "drop-me"))
        store.saveSlot("uid-drop", AiProviderId.OPENAI, AiProviderSlot(apiKey = "drop-me-too"))
        store.setActiveProvider("uid-drop", AiProviderId.OPENAI)
        store.saveSlot("uid-keep", AiProviderId.GEMINI, AiProviderSlot(apiKey = "keep-me"))

        store.clearAccount("uid-drop")

        assertEquals("", store.slot("uid-drop", AiProviderId.GEMINI).apiKey)
        assertEquals("", store.slot("uid-drop", AiProviderId.OPENAI).apiKey)
        assertEquals(AiProviderId.GEMINI, store.activeProvider("uid-drop"))
        assertNull(prefs.getAiField("uid-drop", AiSettingsStore.keyField(AiProviderId.GEMINI)))
        assertEquals("keep-me", store.slot("uid-keep", AiProviderId.GEMINI).apiKey)
        assertTrue(store.hasUsableKey("uid-keep"))
    }

    // ---- Embedding model (Phase 4.2) -------------------------------

    @Test
    fun embeddingModelRoundTripsPerProvider() = runBlocking {
        store.saveSlot(
            "acct-emb", AiProviderId.GEMINI,
            AiProviderSlot(apiKey = "k", embeddingModel = "gem-emb-x"),
        )
        store.saveSlot(
            "acct-emb", AiProviderId.OPENAI,
            AiProviderSlot(apiKey = "k2", embeddingModel = "oai-emb-y"),
        )
        assertEquals("gem-emb-x", store.slot("acct-emb", AiProviderId.GEMINI).embeddingModel)
        assertEquals("oai-emb-y", store.slot("acct-emb", AiProviderId.OPENAI).embeddingModel)
        // The chat model slot is a separate field and stays empty.
        assertEquals("", store.slot("acct-emb", AiProviderId.GEMINI).model)
    }

    @Test
    fun activeEmbeddingConfigProjection() = runBlocking {
        // No key anywhere → no embedding config.
        assertNull(store.activeEmbeddingConfig("acct-embproj"))
        store.saveSlot("acct-embproj", AiProviderId.OPENAI, AiProviderSlot(apiKey = "o-key"))
        store.setActiveProvider("acct-embproj", AiProviderId.OPENAI)
        val config = store.activeEmbeddingConfig("acct-embproj")
        assertEquals(AiProviderId.OPENAI, config!!.providerId)
        assertEquals("o-key", config.apiKey)
        // No embedding model stored → the 1.x default stands in.
        assertEquals("text-embedding-3-large", config.resolvedEmbeddingModel())
        assertEquals("https://api.openai.com/v1", config.resolvedBaseUrl())

        // Custom with no embedding model named is NOT configured:
        // there is no default to resolve to.
        store.saveSlot(
            "acct-embproj", AiProviderId.CUSTOM,
            AiProviderSlot(apiKey = "c-key", baseUrl = "https://llm.example.com/v1"),
        )
        store.setActiveProvider("acct-embproj", AiProviderId.CUSTOM)
        assertNull(store.activeEmbeddingConfig("acct-embproj"))
        store.saveSlot(
            "acct-embproj", AiProviderId.CUSTOM,
            AiProviderSlot(
                apiKey = "c-key",
                baseUrl = "https://llm.example.com/v1",
                embeddingModel = "my-embedder",
            ),
        )
        val custom = store.activeEmbeddingConfig("acct-embproj")
        assertEquals("my-embedder", custom!!.resolvedEmbeddingModel())
        assertEquals("https://llm.example.com/v1", custom.resolvedBaseUrl())
    }

    @Test
    fun clearAccountAlsoDropsEmbeddingModels() = runBlocking {
        store.saveSlot(
            "uid-drop-emb", AiProviderId.GEMINI,
            AiProviderSlot(apiKey = "k", embeddingModel = "emb-1"),
        )
        store.clearAccount("uid-drop-emb")
        assertEquals("", store.slot("uid-drop-emb", AiProviderId.GEMINI).embeddingModel)
        assertNull(
            prefs.getAiField(
                "uid-drop-emb", AiSettingsStore.embeddingModelField(AiProviderId.GEMINI),
            ),
        )
    }

    // ---- Legacy OpenRouter embedding default (fix round 5) -------
    //
    // 0.14.0 and earlier shipped "text-embedding-3-large" as the
    // OpenRouter embedding default, and Save persisted that
    // resolved default into slots. The id is not a real OpenRouter
    // model (the catalogue namespaces it), so slot() migrates
    // exactly that value on read — OpenRouter only.

    @Test
    fun legacyOpenRouterEmbeddingDefaultMigratesOnRead() = runBlocking {
        store.saveSlot(
            "acct-ormig", AiProviderId.OPENROUTER,
            AiProviderSlot(apiKey = "or-key", embeddingModel = "text-embedding-3-large"),
        )
        // The slot read resolves the legacy id to the prefixed one…
        assertEquals(
            "openai/text-embedding-3-large",
            store.slot("acct-ormig", AiProviderId.OPENROUTER).embeddingModel,
        )
        // …and so does the embedding config built from the slot.
        store.setActiveProvider("acct-ormig", AiProviderId.OPENROUTER)
        assertEquals(
            "openai/text-embedding-3-large",
            store.activeEmbeddingConfig("acct-ormig")!!.resolvedEmbeddingModel(),
        )
    }

    @Test
    fun openAiEmbeddingDefaultIsNotMigrated() = runBlocking {
        // The identical string is OpenAI's CORRECT default — the
        // migration is scoped to OpenRouter and must not touch it.
        store.saveSlot(
            "acct-oainomig", AiProviderId.OPENAI,
            AiProviderSlot(apiKey = "o-key", embeddingModel = "text-embedding-3-large"),
        )
        assertEquals(
            "text-embedding-3-large",
            store.slot("acct-oainomig", AiProviderId.OPENAI).embeddingModel,
        )
        store.setActiveProvider("acct-oainomig", AiProviderId.OPENAI)
        assertEquals(
            "text-embedding-3-large",
            store.activeEmbeddingConfig("acct-oainomig")!!.resolvedEmbeddingModel(),
        )
    }

    @Test
    fun userChosenOpenRouterEmbeddingModelIsNotMigrated() = runBlocking {
        store.saveSlot(
            "acct-orcustom", AiProviderId.OPENROUTER,
            AiProviderSlot(apiKey = "or-key", embeddingModel = "my-own-embedder"),
        )
        assertEquals(
            "my-own-embedder",
            store.slot("acct-orcustom", AiProviderId.OPENROUTER).embeddingModel,
        )
    }

    @Test
    fun blankOpenRouterEmbeddingResolvesTheCurrentDefault() = runBlocking {
        store.saveSlot(
            "acct-orblank", AiProviderId.OPENROUTER,
            AiProviderSlot(apiKey = "or-key"),
        )
        store.setActiveProvider("acct-orblank", AiProviderId.OPENROUTER)
        assertEquals(
            "openai/text-embedding-3-large",
            store.activeEmbeddingConfig("acct-orblank")!!.resolvedEmbeddingModel(),
        )
    }
}
