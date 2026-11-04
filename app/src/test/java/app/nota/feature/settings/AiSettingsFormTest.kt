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
 *
 * Every test uses its OWN account id: the preferences DataStore
 * file is shared by every Robolectric class in the shard fork,
 * so AI fields written for one account id are visible to all —
 * per-test ids are the isolation (the repo's standing pattern).
 */
@RunWith(RobolectricTestRunner::class)
class AiSettingsFormTest {

    private lateinit var store: AiSettingsStore
    private lateinit var http: FakeAiHttpClient

    private fun geminiOk(): AiHttpResponse = AiHttpResponse(
        200,
        """{"candidates":[{"content":{"parts":[{"text":"ready"}]}}]}""",
    )

    private fun formFor(account: String): AiSettingsForm =
        AiSettingsForm(store, AiService(store, http) { account }) { account }

    @Before
    fun setUp() {
        val prefs = PreferencesRepository(RuntimeEnvironment.getApplication())
        store = AiSettingsStore(prefs, FakeAiKeyCipher())
        http = FakeAiHttpClient()
    }

    @Test
    fun testConnectionUsesTheTypedKeyNotTheSavedOne() = runBlocking {
        val acct = "form-wart"
        val form = formFor(acct)
        // A key is already saved…
        store.saveSlot(acct, AiProviderId.GEMINI, AiProviderSlot(apiKey = "SAVED-KEY"))
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
        assertEquals("SAVED-KEY", store.slot(acct, AiProviderId.GEMINI).apiKey)

        form.save()
        assertFalse(form.isDirty)
        assertTrue(form.justSaved)
        assertEquals("TYPED-KEY", store.slot(acct, AiProviderId.GEMINI).apiKey)
    }

    @Test
    fun testFailureSurfacesTheMappedMessage() = runBlocking {
        val form = formFor("form-fail")
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
        val acct = "form-switch"
        val form = formFor(acct)
        store.saveSlot(acct, AiProviderId.GEMINI, AiProviderSlot(apiKey = "gemini-key", model = "g-model"))
        store.saveSlot(acct, AiProviderId.OPENAI, AiProviderSlot(apiKey = "openai-key", model = "o-model"))
        form.load()
        assertEquals(AiProviderId.GEMINI, form.activeProvider)
        assertEquals("gemini-key", form.draft.key)
        assertEquals("g-model", form.draft.model)

        form.selectProvider(AiProviderId.OPENAI)
        // The pick persists immediately (the editor follows it)…
        assertEquals(AiProviderId.OPENAI, store.activeProvider(acct))
        // …and the fields show OpenAI's own saved values.
        assertEquals("openai-key", form.draft.key)
        assertEquals("o-model", form.draft.model)
        assertFalse(form.isDirty)

        // Edit + save OpenAI's key; Gemini's slot is untouched.
        form.draft.key = "openai-key-2"
        assertTrue(form.isDirty)
        form.save()
        assertEquals("openai-key-2", store.slot(acct, AiProviderId.OPENAI).apiKey)
        assertEquals("gemini-key", store.slot(acct, AiProviderId.GEMINI).apiKey)

        form.selectProvider(AiProviderId.GEMINI)
        assertEquals("gemini-key", form.draft.key)
    }

    @Test
    fun freshFormShowsPresetDefaultsAndIsNotDirty() = runBlocking {
        val acct = "form-fresh"
        val form = formFor(acct)
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
            store.slot(acct, AiProviderId.CUSTOM).baseUrl,
        )
    }

    @Test
    fun savingAClearedModelStoresThePresetDefault() = runBlocking {
        val acct = "form-cleared-model"
        val form = formFor(acct)
        form.load()
        form.selectProvider(AiProviderId.OPENAI)
        assertEquals("gpt-4o-mini", form.draft.model)

        // The user clears the model field and saves. That means
        // "use the default", so the default is what persists —
        // never a blank that reads dirty against the default the
        // form itself displays (Jules review, Phase 4.1).
        form.draft.model = ""
        form.onEdited()
        assertTrue(form.isDirty)
        form.save()
        assertEquals("gpt-4o-mini", store.slot(acct, AiProviderId.OPENAI).model)
        assertEquals("gpt-4o-mini", form.draft.model)
        assertFalse(form.isDirty)

        // Custom has no default: a cleared field stays blank.
        form.selectProvider(AiProviderId.CUSTOM)
        form.draft.model = ""
        form.save()
        assertEquals("", store.slot(acct, AiProviderId.CUSTOM).model)
    }

    @Test
    fun summarizeThroughServiceUsesSavedConfigAndParsesTags() = runBlocking {
        val acct = "form-service"
        // The editor path: actions use the SAVED active config —
        // the mirror image of the form's Test rule.
        store.saveSlot(acct, AiProviderId.GEMINI, AiProviderSlot(apiKey = "SAVED-KEY"))
        val service = AiService(store, http) { acct }
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
