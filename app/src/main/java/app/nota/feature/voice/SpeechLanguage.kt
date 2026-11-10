package app.nota.feature.voice

import app.nota.data.preferences.PreferencesRepository

/**
 * Phase 6 extras (PR #45): the dictation-language resolver —
 * Noma 1.x's speech.ts language resolution. The stored preference
 * (PreferencesRepository SPEECH_LANGUAGE_*) maps to the BCP-47 tag
 * the recogniser is asked to listen in:
 *
 *  - "system" → null: NO override — the caller keeps its device
 *    default tag, exactly the pre-PR #45 behaviour;
 *  - "en" → "en-US";
 *  - "bn" → "bn-BD";
 *  - anything unknown → null (system), matching the preference
 *    layer's degrade-to-system posture.
 *
 * Pure, so the mapping is unit-testable (SpeechLanguageTest).
 */
object SpeechLanguage {

    fun resolveTag(setting: String): String? = when (setting) {
        PreferencesRepository.SPEECH_LANGUAGE_ENGLISH -> "en-US"
        PreferencesRepository.SPEECH_LANGUAGE_BANGLA -> "bn-BD"
        else -> null
    }
}
