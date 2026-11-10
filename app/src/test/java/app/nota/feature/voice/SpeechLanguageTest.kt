package app.nota.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 6 extras (PR #44): the dictation-language resolver — 1.x's
 * speech.ts mapping. "system" means NO override (null), so the
 * recogniser keeps the device default; the two pinned languages
 * resolve to their BCP-47 tags; anything unknown degrades to
 * system.
 */
class SpeechLanguageTest {

    @Test
    fun systemMeansNoOverride() {
        assertNull(SpeechLanguage.resolveTag("system"))
    }

    @Test
    fun englishResolvesToEnUs() {
        assertEquals("en-US", SpeechLanguage.resolveTag("en"))
    }

    @Test
    fun banglaResolvesToBnBd() {
        assertEquals("bn-BD", SpeechLanguage.resolveTag("bn"))
    }

    @Test
    fun unknownDegradesToSystem() {
        assertNull(SpeechLanguage.resolveTag("fr"))
        assertNull(SpeechLanguage.resolveTag(""))
    }
}
