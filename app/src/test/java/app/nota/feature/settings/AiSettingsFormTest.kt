package app.nota.feature.settings

import app.nota.data.ai.AiHttpResponse
import app.nota.data.ai.AiProviderId
import app.nota.data.ai.AiProviderSlot
import app.nota.data.ai.AiService
import app.nota.data.ai.AiSettingsStore
import app.nota.data.ai.FakeAiHttpClient
import app.nota.data.ai.FakeAiKeyCipher
import app.nota.data.preferences.PreferencesRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Settings → AI features form logic (Phase 4.1). The centrepiece
 * is the 1.x-wart regression: in 1.x, "Test connection" silently
 * used the SAVED key, so a freshly typed key tested as a failure
 * (or an old key tested as a success). Here Test must exercise
 * the values currently in the form — proven by recording exactly
 * which key the fake transport receives.
 */
@RunWith(RobolectricTestRunner::class)
class AiSettingsFormTest {

    private lateinit var store: AiSettingsStore
    private lateinit var http: FakeAiHttpClient
    private lateinit var form: AiSettingsForm

    private fun geminiOk(): AiHttpResponse = AiHttpResponse(
        200,
        """{"candidates":[{"content":{"parts":[{"text":"ready"}]}}]}""",
    )

    @Before
    fun setUp() {
        val prefs = PreferencesRepository(RuntimeEnvironment.getApplication())
        store = AiSettingsStore(prefs, FakeAiKeyCipher())
        http = FakeAiHttpClient()
        val service = AiService(store, http) { "guest" }
        form = AiSettingsForm(store, service) { "guest" }
    }

    @Test
    fun testConnectionUsesTheTypedKeyNotTheSavedOne() = runBlocking {
        // A key is already saved…
        store.saveSlot("guest", AiProviderId.GEMINI, AiProviderSlot(apiKey = "SAVED-KEY"))
        form.load()
        assertEquals("SAVED-KEY", form.draft.key)
        assertFalse(form.isDirty)

        // …the user types a NEW key and taps Test without saving.
        form.draft.key = "TYPED-KEY"
        form.onEdited()
        assertTrue(form.isDirty)
        http.next = geminiOk()
        form.test()

        assertEquals(AiSettingsForm.TestState.Success, form.testState)
        // The transport saw the TYPED key — the wart is dead.
        assertEquals("TYPED-KEY", http.calls.single().headers["x-goog-api-key"])
        // And the saved record still holds the old key until Save.
        assertEquals("SAVED-KEY", store.slot("guest", AiProviderId.GEMINI).apiKey)

        form.save()
        assertFalse(form.isDirty)
        assertTrue(form.justSaved)
        assertEquals("TYPED-KEY", store.slot("guest", AiProviderId.GEMINI).apiKey)
    }

    @Test
    fun testFailureSurfacesTheMappedMessage() = runBlocking {
        form.load()
        form.draft.key = "bad-key"
        http.next = AiHttpResponse(401, """{"error":{"message":"API key not valid"}}""")
        form.test()
        val state = form.testState
        assertTrue(state is AiSettingsForm.TestState.Failed)
        assertTrue(
            (state as AiSettingsForm.TestState.Failed).message.contains("didn't work"),
        )
    }

    @Test
    fun providerSwitchingKeepsEachProvidersOwnValues() = runBlocking {
        store.saveSlot("guest", AiProviderId.GEMINI, AiProviderSlot(apiKey = "gemini-key", model = "g-model"))
        store.saveSlot("guest", AiProviderId.OPENAI, AiProviderSlot(apiKey = "openai-key", model = "o-model"))
        form.load()
        assertEquals(AiProviderId.GEMINI, form.activeProvider)
        assertEquals("gemini-key", form.draft.key)
        assertEquals("g-model", form.draft.model)

        form.selectProvider(AiProviderId.OPENAI)
        // The pick persists immediately (the editor follows it)…
        assertEquals(AiProviderId.OPENAI, store.activeProvider("guest"))
        // …and the fields show OpenAI's own saved values.
        assertEquals("openai-key", form.draft.key)
        assertEquals("o-model", form.draft.model)
        assertFalse(form.isDirty)

        // Edit + save OpenAI's key; Gemini's slot is untouched.
        form.draft.key = "openai-key-2"
        assertTrue(form.isDirty)
        form.save()
        assertEquals("openai-key-2", store.slot("guest", AiProviderId.OPENAI).apiKey)
        assertEquals("gemini-key", store.slot("guest", AiProviderId.GEMINI).apiKey)

        form.selectProvider(AiProviderId.GEMINI)
        assertEquals("gemini-key", form.draft.key)
    }

    @Test
    fun freshFormShowsPresetDefaultsAndIsNotDirty() = runBlocking {
        form.load()
        assertEquals("", form.draft.key)
        assertEquals("gemini-3.5-flash-lite", form.draft.model)
        assertFalse(form.isDirty)
        form.selectProvider(AiProviderId.CUSTOM)
        assertEquals("", form.draft.model)
        assertEquals("", form.draft.baseUrl)
        // Typing a custom endpoint makes the form dirty.
        form.draft.baseUrl = "https://llm.example.com/v1/"
        form.onEdited()
        assertTrue(form.isDirty)
        form.save()
        assertFalse(form.isDirty)
        assertEquals(
            "https://llm.example.com/v1",
            store.slot("guest", AiProviderId.CUSTOM).baseUrl,
        )
    }

    @Test
    fun summarizeThroughServiceUsesSavedConfigAndParsesTags() = runBlocking {
        // The editor path: actions use the SAVED active config —
        // the mirror image of the form's Test rule.
        store.saveSlot("guest", AiProviderId.GEMINI, AiProviderSlot(apiKey = "SAVED-KEY"))
        val service = AiService(store, http) { "guest" }
        http.next = AiHttpResponse(
            200,
            """{"candidates":[{"content":{"parts":[{"text":"[\"work\", \"ideas\"]"}]}}]}""",
        )
        val outcome = service.suggestTags(
            title = "Trip plan",
            plainText = "Book flights and plan the itinerary",
            existingTagNames = listOf("travel"),
            currentTagNames = emptyList(),
        )
        assertEquals(listOf("work", "ideas"), outcome.tags)
        assertEquals("SAVED-KEY", http.calls.single().headers["x-goog-api-key"])
    }
}
