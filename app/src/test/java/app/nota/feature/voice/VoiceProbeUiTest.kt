package app.nota.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5.4: the probe → UI-state mapping — the honesty rules in
 * one place. A disabled default speech service (the J7 case: the
 * Google app disabled, so the platform recogniser dies instantly
 * while keyboard voice typing works) must never surface as a dead
 * mic button: no on-device fallback ⇒ unavailable, NAMED; an
 * on-device recogniser ⇒ the mic stays, with the disabled app as an
 * advisory.
 */
class VoiceProbeUiTest {

    @Test
    fun nullProbe_isUnavailable() {
        val mapped = VoiceProbeUi.fromProbe(null)
        assertTrue(mapped is VoiceAvailability.Unavailable)
    }

    @Test
    fun availableProbe_offersTheMicWithoutAdvisory() {
        val mapped = VoiceProbeUi.fromProbe(
            SpeechProbe(available = true, onDevice = true),
        )
        assertEquals(
            VoiceAvailability.Available(onDevice = true, disabledServiceApp = null),
            mapped,
        )
    }

    @Test
    fun disabledServiceWithOnDeviceRecognizer_keepsTheMicWithAnAdvisory() {
        val mapped = VoiceProbeUi.fromProbe(
            SpeechProbe(
                available = true,
                onDevice = true,
                reason = SpeechUnavailableReason.SERVICE_DISABLED,
                serviceApp = "Google",
            ),
        )
        assertEquals(
            VoiceAvailability.Available(onDevice = true, disabledServiceApp = "Google"),
            mapped,
        )
    }

    @Test
    fun disabledServiceWithoutOnDeviceRecognizer_isUnavailableAndNamed() {
        val mapped = VoiceProbeUi.fromProbe(
            SpeechProbe(
                available = false,
                onDevice = false,
                reason = SpeechUnavailableReason.SERVICE_DISABLED,
                serviceApp = "Google",
            ),
        )
        assertEquals(
            VoiceAvailability.Unavailable(
                reason = SpeechUnavailableReason.SERVICE_DISABLED,
                serviceApp = "Google",
            ),
            mapped,
        )
        val message = VoiceProbeUi.unavailableMessage(
            mapped as VoiceAvailability.Unavailable,
        )
        assertTrue(message.contains("Google"))
        assertTrue(message.contains("disabled"))
    }

    @Test
    fun genericUnavailable_messageDoesNotInventAnApp() {
        val message = VoiceProbeUi.unavailableMessage(
            VoiceAvailability.Unavailable(reason = null, serviceApp = null),
        )
        assertTrue(message.contains("No speech recogniser is available"))
    }

    @Test
    fun keyboardHint_pointsAtKeyboardVoiceTyping() {
        assertTrue(VoiceProbeUi.KEYBOARD_HINT.contains("keyboard"))
        assertTrue(VoiceProbeUi.KEYBOARD_HINT.contains("voice typing"))
    }

    @Test
    fun everyErrorKind_hasACalmLine() {
        for (kind in SpeechErrorKind.entries) {
            val line = SpeechErrorLines.line(kind)
            assertTrue("blank line for $kind", line.isNotBlank())
        }
        assertTrue(
            SpeechErrorLines.line(SpeechErrorKind.PERMISSION).contains("denied"),
        )
        assertTrue(
            SpeechErrorLines.line(SpeechErrorKind.SERVICE_DISABLED)
                .contains("disabled"),
        )
        assertTrue(
            SpeechErrorLines.line(SpeechErrorKind.UNAVAILABLE)
                .contains("isn't available"),
        )
    }

    @Test
    fun errorKindWireNames_roundTripAndDegrade() {
        for (kind in SpeechErrorKind.entries) {
            assertEquals(kind, SpeechErrorKind.fromWire(kind.wire))
        }
        assertEquals(
            SpeechErrorKind.SERVICE_DISABLED,
            SpeechErrorKind.fromWire("service-disabled"),
        )
        assertEquals(SpeechErrorKind.CLIENT, SpeechErrorKind.fromWire("mystery"))
        assertEquals(SpeechErrorKind.CLIENT, SpeechErrorKind.fromWire(null))
    }
}
