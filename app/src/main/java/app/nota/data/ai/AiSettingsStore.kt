package app.nota.data.ai

import app.nota.data.preferences.PreferencesRepository

/** One provider's saved slot: its OWN key, model and (for Custom)
 *  endpoint — 1.x #87. A provider only ever receives its own key.
 *  Phase 4.2 adds [embeddingModel]: semantic search's model, a
 *  slot field of its own because it names a different model
 *  family than the chat [model]. */
data class AiProviderSlot(
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
    val embeddingModel: String = "",
)

/**
 * Per-account, per-provider AI settings (Phase 4.1) — the native
 * form of 1.x's local AI record, with two native strengthenings:
 * keys are stored SEALED under an Android Keystore key (never
 * plaintext in the preferences file), and the whole record lives
 * in the device-local preferences store — outside the Room
 * databases and outside any backup, by construction.
 *
 * Field vocabulary (per account): "active_provider" plus, per
 * provider storage id, "<id>_key" (sealed), "<id>_model",
 * "<id>_base_url", "<id>_embedding_model" (Phase 4.2). The store
 * is keyed by account id on every call
 * (guest included), so two accounts on one device can never read
 * or clobber each other's keys — the Phase 3a scoping rule the
 * note-lock copies follow.
 */
class AiSettingsStore(
    private val preferences: PreferencesRepository,
    private val cipher: AiKeyCipher,
) {

    suspend fun activeProvider(accountId: String): AiProviderId =
        AiProviderId.fromStorageId(preferences.getAiField(accountId, FIELD_ACTIVE))

    suspend fun setActiveProvider(accountId: String, provider: AiProviderId) {
        preferences.setAiField(accountId, FIELD_ACTIVE, provider.storageId)
    }

    /** The saved slot for one provider; the key comes back
     *  DECRYPTED (empty when none was saved or the sealed copy is
     *  unreadable — a dead Keystore copy degrades to "no key",
     *  never a crash and never a wrong key). */
    suspend fun slot(accountId: String, provider: AiProviderId): AiProviderSlot {
        val sealed = preferences.getAiField(accountId, keyField(provider))
        val key = sealed?.let { cipher.decrypt(it) }?.let { String(it, Charsets.UTF_8) } ?: ""
        return AiProviderSlot(
            apiKey = key,
            model = preferences.getAiField(accountId, modelField(provider)) ?: "",
            baseUrl = preferences.getAiField(accountId, baseUrlField(provider)) ?: "",
            embeddingModel = preferences.getAiField(accountId, embeddingModelField(provider)) ?: "",
        )
    }

    /** Saves one provider's slot. The key is sealed before it
     *  touches storage; an empty key clears the slot's key (the
     *  user removing their key) while model/endpoint remain. */
    suspend fun saveSlot(accountId: String, provider: AiProviderId, slot: AiProviderSlot) {
        val trimmedKey = slot.apiKey.trim()
        val sealed = if (trimmedKey.isEmpty()) {
            null
        } else {
            cipher.encrypt(trimmedKey.toByteArray(Charsets.UTF_8))
                ?: throw AiException(
                    AiErrorKind.BAD_RESPONSE,
                    "Couldn't secure that key on this device — it was not saved.",
                )
        }
        preferences.setAiField(accountId, keyField(provider), sealed)
        preferences.setAiField(
            accountId, modelField(provider),
            slot.model.trim().ifBlank { null },
        )
        preferences.setAiField(
            accountId, baseUrlField(provider),
            normalizeAiBaseUrl(slot.baseUrl).ifBlank { null },
        )
        preferences.setAiField(
            accountId, embeddingModelField(provider),
            slot.embeddingModel.trim().ifBlank { null },
        )
    }

    /** The effective config for the account's ACTIVE provider,
     *  or null when that provider has no saved key (the editor
     *  actions gate on this and point at Settings). */
    suspend fun activeConfig(accountId: String): AiConfig? {
        val provider = activeProvider(accountId)
        val slot = slot(accountId, provider)
        if (slot.apiKey.isBlank()) return null
        return AiConfig(
            providerId = provider,
            apiKey = slot.apiKey,
            model = slot.model.ifBlank { provider.defaultModel },
            baseUrl = slot.baseUrl.ifBlank { null },
        )
    }

    /** Whether the active provider has a usable saved key. */
    suspend fun hasUsableKey(accountId: String): Boolean =
        activeConfig(accountId) != null

    /**
     * The effective EMBEDDING config for the account's ACTIVE
     * provider (Phase 4.2), or null when semantic search cannot
     * run: no saved key, or no resolvable embedding model (a
     * Custom slot whose embedding model was never filled in).
     * A null here is the search layer's silent-off state — the
     * keyword search never notices.
     */
    suspend fun activeEmbeddingConfig(accountId: String): AiEmbeddingConfig? {
        val provider = activeProvider(accountId)
        val slot = slot(accountId, provider)
        if (slot.apiKey.isBlank()) return null
        val config = AiEmbeddingConfig(
            providerId = provider,
            apiKey = slot.apiKey,
            embeddingModel = slot.embeddingModel,
            baseUrl = slot.baseUrl.ifBlank { null },
        )
        if (config.resolvedEmbeddingModel().isBlank()) return null
        return config
    }

    /** Drops EVERY AI field for the account (sign-out — 1.x
     *  behaviour: keys belong to the sign-in that saved them). */
    suspend fun clearAccount(accountId: String) {
        preferences.clearAiFields(accountId, ALL_FIELDS)
    }

    companion object {
        const val FIELD_ACTIVE = "active_provider"

        fun keyField(provider: AiProviderId) = "${provider.storageId}_key"
        fun modelField(provider: AiProviderId) = "${provider.storageId}_model"
        fun baseUrlField(provider: AiProviderId) = "${provider.storageId}_base_url"
        fun embeddingModelField(provider: AiProviderId) = "${provider.storageId}_embedding_model"

        val ALL_FIELDS: List<String> = buildList {
            add(FIELD_ACTIVE)
            AiProviderId.entries.forEach { provider ->
                add(keyField(provider))
                add(modelField(provider))
                add(baseUrlField(provider))
                add(embeddingModelField(provider))
            }
        }
    }
}
