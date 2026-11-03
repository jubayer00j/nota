package app.nota.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nota.data.ai.AiConfig
import app.nota.data.ai.AiException
import app.nota.data.ai.AiProviderId
import app.nota.data.ai.AiProviderSlot
import app.nota.data.ai.AiService
import app.nota.data.ai.AiSettingsStore
import app.nota.data.ai.normalizeAiBaseUrl

/**
 * Settings → AI features form logic (Phase 4.1), extracted from
 * the composable so the contract is unit-testable without a
 * composition (the CredentialSetupFlow pattern).
 *
 * The contract that matters most — the 1.x wart, designed out:
 * **Test connection exercises the values currently in the form.**
 * In 1.x, Test read the SAVED record, so a user who typed a key
 * and tapped Test was testing the old (or no) key and had to know
 * to save first. Here [test] builds its [AiConfig] from the draft
 * fields on screen — typed key included — and saving is a
 * separate, explicit action with a visible dirty state.
 *
 * Other pinned behaviour:
 * - Each provider has its OWN draft (key + model + endpoint):
 *   switching providers in the picker swaps the visible fields to
 *   that provider's saved values and never carries one provider's
 *   key into another's fields.
 * - The provider pick itself persists immediately (it is a
 *   preference, like the other Settings switches); key/model/
 *   endpoint edits persist ONLY on Save.
 * - The model field shows the provider's default when the slot
 *   never stored one (the preset-fill behaviour of 1.x), and the
 *   dirty check compares effective values, so a pristine form is
 *   never dirty.
 * - Saving a cleared model field stores the provider's default,
 *   never a blank: the saved slot always names the model a call
 *   will use (Custom's default is blank, so its cleared field
 *   stays blank).
 */
class AiSettingsForm(
    private val store: AiSettingsStore,
    private val service: AiService,
    private val accountIdProvider: () -> String,
) {

    /** One provider's editable fields. */
    class Draft(key: String, model: String, baseUrl: String) {
        var key by mutableStateOf(key)
        var model by mutableStateOf(model)
        var baseUrl by mutableStateOf(baseUrl)
    }

    sealed interface TestState {
        data object Idle : TestState
        data object Testing : TestState
        data object Success : TestState
        data class Failed(val message: String) : TestState
    }

    var loaded by mutableStateOf(false)
        private set

    var activeProvider by mutableStateOf(AiProviderId.GEMINI)
        private set

    var testState by mutableStateOf<TestState>(TestState.Idle)
        private set

    /** Set after a successful save; cleared by the next edit. */
    var justSaved by mutableStateOf(false)
        private set

    private val drafts = mutableMapOf<AiProviderId, Draft>()
    private val savedSlots = mutableMapOf<AiProviderId, AiProviderSlot>()
    private var accountId: String = ""

    /** The on-screen fields for the active provider. */
    val draft: Draft
        get() = drafts.getValue(activeProvider)

    suspend fun load() {
        accountId = accountIdProvider()
        activeProvider = store.activeProvider(accountId)
        AiProviderId.entries.forEach { provider ->
            val slot = store.slot(accountId, provider)
            savedSlots[provider] = slot
            drafts[provider] = Draft(
                key = slot.apiKey,
                model = slot.model.ifBlank { provider.defaultModel },
                baseUrl = slot.baseUrl,
            )
        }
        testState = TestState.Idle
        justSaved = false
        loaded = true
    }

    /** Switches the visible provider AND persists the choice (the
     *  editor actions follow the active provider immediately);
     *  the previous provider's unsaved edits stay in its draft. */
    suspend fun selectProvider(provider: AiProviderId) {
        if (provider == activeProvider) return
        activeProvider = provider
        testState = TestState.Idle
        justSaved = false
        store.setActiveProvider(accountId, provider)
    }

    /** True when the active provider's fields differ from its
     *  saved slot (effective values: an unset model IS the
     *  default; endpoints compare normalized). */
    val isDirty: Boolean
        get() {
            if (!loaded) return false
            val provider = activeProvider
            val saved = savedSlots.getValue(provider)
            val current = drafts.getValue(provider)
            if (current.key.trim() != saved.apiKey.trim()) return true
            if (current.model.trim() != saved.model.ifBlank { provider.defaultModel }) return true
            if (provider == AiProviderId.CUSTOM &&
                normalizeAiBaseUrl(current.baseUrl) != normalizeAiBaseUrl(saved.baseUrl)
            ) {
                return true
            }
            return false
        }

    /** The config Test connection will exercise: the form's
     *  current values, never the saved record. */
    fun formConfig(): AiConfig = AiConfig(
        providerId = activeProvider,
        apiKey = draft.key,
        model = draft.model,
        baseUrl = draft.baseUrl.ifBlank { null },
    )

    /** Runs the tiny test prompt against [formConfig]. */
    suspend fun test() {
        testState = TestState.Testing
        justSaved = false
        testState = try {
            service.testConnection(formConfig())
            TestState.Success
        } catch (e: AiException) {
            TestState.Failed(e.displayMessage)
        }
    }

    /** Persists the active provider's draft as its saved slot. */
    suspend fun save() {
        val provider = activeProvider
        val current = drafts.getValue(provider)
        // A cleared model field means "the default", never a
        // stored blank: persist the resolved value and show it,
        // so a preset's slot round-trips to its default and the
        // form can't read dirty against the very default it
        // displays (Jules review, Phase 4.1). Custom's default
        // is blank, so a cleared Custom model stays blank.
        current.model = current.model.trim().ifBlank { provider.defaultModel }
        store.saveSlot(
            accountId,
            provider,
            AiProviderSlot(
                apiKey = current.key,
                model = current.model,
                baseUrl = current.baseUrl,
            ),
        )
        savedSlots[provider] = store.slot(accountId, provider)
        justSaved = true
        testState = TestState.Idle
    }

    /** Any field edit routes through here so stale save/test
     *  indicators clear as soon as the form changes. */
    fun onEdited() {
        justSaved = false
        if (testState is TestState.Success || testState is TestState.Failed) {
            testState = TestState.Idle
        }
    }
}
