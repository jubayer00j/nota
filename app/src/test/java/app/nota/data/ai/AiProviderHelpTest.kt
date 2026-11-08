package app.nota.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Beginner AI Setup #1: the provider table's beginner layer —
 * the one-line descriptions (F2), the get-a-key help (F1) and
 * the verified OpenRouter embedding default (item 8) — pinned
 * as data, so a future provider edit cannot silently drop the
 * guidance or the namespaced model id.
 */
class AiProviderHelpTest {

    @Test
    fun everyProviderHasAPlainDescription() {
        AiProviderId.entries.forEach { provider ->
            assertTrue("$provider description blank", provider.description.isNotBlank())
            assertTrue(
                "$provider description too long: ${provider.description}",
                provider.description.length <= 90,
            )
        }
        // Only Gemini — the default — carries the suggested-start
        // marking; two "start here" claims would be no guidance.
        assertTrue(AiProviderId.GEMINI.description.contains("suggested"))
        AiProviderId.entries.filter { it != AiProviderId.GEMINI }.forEach { provider ->
            assertTrue(
                "$provider must not claim the suggested-start slot",
                !provider.description.contains("suggested", ignoreCase = true),
            )
        }
    }

    @Test
    fun presetProvidersPointAtTheirOwnKeyPages() {
        // The same key pages 1.x's shipped table named.
        assertEquals(
            "https://aistudio.google.com/app/apikey",
            AiProviderId.GEMINI.keyPageUrl,
        )
        assertEquals(
            "https://platform.openai.com/api-keys",
            AiProviderId.OPENAI.keyPageUrl,
        )
        assertEquals("https://openrouter.ai/keys", AiProviderId.OPENROUTER.keyPageUrl)
    }

    @Test
    fun customHasNoKeyPageLink_andSaysWhereItsKeysLive() {
        assertNull(AiProviderId.CUSTOM.keyPageUrl)
        assertTrue(AiProviderId.CUSTOM.keySteps.isEmpty())
        assertTrue(AiProviderId.CUSTOM.keyHelpNote.contains("dashboard"))
    }

    @Test
    fun presetProvidersGiveTwoOrThreeShortSteps() {
        listOf(AiProviderId.GEMINI, AiProviderId.OPENAI, AiProviderId.OPENROUTER)
            .forEach { provider ->
                assertTrue(
                    "$provider step count ${provider.keySteps.size}",
                    provider.keySteps.size in 2..3,
                )
                provider.keySteps.forEach { step ->
                    assertTrue("$provider blank step", step.isNotBlank())
                }
                assertTrue("$provider help note blank", provider.keyHelpNote.isNotBlank())
            }
    }

    @Test
    fun openRouterEmbeddingDefaultIsNamespaced() {
        // Item 8: OpenRouter's own model page for this model
        // instructs the namespaced id ("Use
        // `openai/text-embedding-3-large` with the OpenRouter
        // API"); the unprefixed form was the drift.
        assertEquals(
            "openai/text-embedding-3-large",
            AiProviderId.OPENROUTER.defaultEmbeddingModel,
        )
    }
}
