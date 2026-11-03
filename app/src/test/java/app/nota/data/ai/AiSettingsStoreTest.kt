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
}
