package app.nota.feature.lock

import app.nota.data.security.BiometricStep
import app.nota.data.security.StepResult
import javax.crypto.Cipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Legacy fingerprint prompt selection + state machine (Phase 2.7).
 *
 * The device evidence: on API 27 the AndroidX prompt returned
 * ERROR_HW_NOT_PRESENT while the framework fingerprint service saw
 * the enrolled sensor. These tests pin the replacement contract —
 * the SDK gate, the framework error/help mapping, and the dialog
 * state machine (listening → failed → retry → success, terminal
 * error held until dismissed, cancel delivered exactly once).
 *
 * The framework authenticate call itself is NOT exercised here:
 * Robolectric has no fingerprint HAL, so that call remains
 * device-only proof (stated in the PR body, not papered over).
 */
class LegacyFingerprintPromptStateMachineTest {

    @Test
    fun backendSelection_api27AndBelow_framework_api28AndAbove_androidx() {
        assertEquals(
            BiometricPromptBackend.FRAMEWORK_FINGERPRINT,
            biometricPromptBackendForSdk(26),
        )
        assertEquals(
            BiometricPromptBackend.FRAMEWORK_FINGERPRINT,
            biometricPromptBackendForSdk(27),
        )
        assertEquals(BiometricPromptBackend.ANDROIDX, biometricPromptBackendForSdk(28))
        assertEquals(BiometricPromptBackend.ANDROIDX, biometricPromptBackendForSdk(36))
    }

    @Test
    fun stateMachine_listeningFailedRetrySuccess() {
        val machine = LegacyFingerprintPromptStateMachine()
        assertEquals(LegacyFingerprintPromptPhase.LISTENING, machine.state.phase)

        machine.authenticationFailed()
        assertEquals(LegacyFingerprintPromptPhase.FAILED, machine.state.phase)
        assertEquals(1, machine.state.failedAttempts)

        machine.retry()
        assertEquals(LegacyFingerprintPromptPhase.RETRYING, machine.state.phase)

        machine.authenticationHelp("Press the fingerprint sensor more firmly")
        assertEquals(LegacyFingerprintPromptPhase.FAILED, machine.state.phase)
        assertEquals("Press the fingerprint sensor more firmly", machine.state.message)

        machine.retry()
        machine.succeeded()
        assertEquals(LegacyFingerprintPromptPhase.SUCCESS, machine.state.phase)

        // Terminal: a late failure cannot drag a success back.
        machine.authenticationFailed()
        assertEquals(LegacyFingerprintPromptPhase.SUCCESS, machine.state.phase)
    }

    @Test
    fun errorMapping_hwNotPresent_isTheScreenshotSentence() {
        val failure = legacyFingerprintPromptFailure(12, "This device does not have a fingerprint sensor")
        assertEquals(BiometricStep.PROMPT, failure.step)
        assertTrue(failure.reason.contains("This device does not have a fingerprint sensor"))
        assertTrue(failure.reason.contains("framework error 12"))
        assertEquals("Fingerprint prompt: ${failure.reason}", failure.userMessage)
    }

    @Test
    fun errorMapping_eachNamedCodeGetsItsOwnHonestMessage() {
        assertTrue(legacyFingerprintPromptFailure(1, null).reason.contains("temporarily unavailable"))
        assertTrue(legacyFingerprintPromptFailure(3, null).reason.contains("timed out"))
        assertTrue(legacyFingerprintPromptFailure(7, null).reason.contains("temporarily locked"))
        assertTrue(legacyFingerprintPromptFailure(9, null).reason.contains("locked until a credential is used"))
        assertTrue(legacyFingerprintPromptFailure(11, null).reason.contains("No fingerprint is enrolled"))
        assertEquals("Cancelled", legacyFingerprintPromptFailure(5, null).reason)
        assertEquals("Cancelled", legacyFingerprintPromptFailure(10, null).reason)
        val vendor = legacyFingerprintPromptFailure(8, "Sensor exploded")
        assertTrue(vendor.reason.contains("Sensor exploded"))
        assertTrue(vendor.reason.contains("framework error 8"))
        val unknown = legacyFingerprintPromptFailure(999, "Mystery")
        assertTrue(unknown.reason.contains("Mystery"))
        assertTrue(unknown.reason.contains("framework error 999"))
    }

    @Test
    fun helpMapping_guidesTheNextTouch() {
        assertTrue(legacyFingerprintHelpMessage(1, null).contains("part of your fingerprint"))
        assertTrue(legacyFingerprintHelpMessage(2, null).contains("more firmly"))
        assertTrue(legacyFingerprintHelpMessage(3, null).contains("Clean the fingerprint sensor"))
        assertTrue(legacyFingerprintHelpMessage(4, null).contains("more slowly"))
        assertTrue(legacyFingerprintHelpMessage(5, null).contains("so quickly"))
        assertEquals("Vendor hint", legacyFingerprintHelpMessage(1000, "Vendor hint"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyFingerprintPromptCoordinatorTest {

    @After
    fun tearDown() {
        LegacyFingerprintPromptCoordinator.resetForTest()
    }

    private fun newCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    @Test
    fun failedRetrySuccess_deliversTheAuthorisedCipherExactlyOnce() {
        val cipher = newCipher()
        var authenticated: Cipher? = null
        var errorCount = 0
        LegacyFingerprintPromptCoordinator.begin(
            cipher = cipher,
            title = "Unlock Nota",
            subtitle = null,
            negativeButtonText = "Cancel",
            onAuthenticated = { authenticated = it },
            onError = { errorCount++ },
        )
        val request = LegacyFingerprintPromptCoordinator.activeRequest.value
        assertTrue(request != null)

        LegacyFingerprintPromptCoordinator.authenticationFailed(request!!)
        assertEquals(
            LegacyFingerprintPromptPhase.FAILED,
            LegacyFingerprintPromptCoordinator.uiState.value.phase,
        )
        LegacyFingerprintPromptCoordinator.retry(request)
        assertEquals(
            LegacyFingerprintPromptPhase.RETRYING,
            LegacyFingerprintPromptCoordinator.uiState.value.phase,
        )
        LegacyFingerprintPromptCoordinator.authenticationSucceeded(request, cipher)
        assertEquals(
            LegacyFingerprintPromptPhase.SUCCESS,
            LegacyFingerprintPromptCoordinator.uiState.value.phase,
        )
        LegacyFingerprintPromptCoordinator.completeSuccess(request)

        assertSame(cipher, authenticated)
        assertEquals(0, errorCount)
        assertNull(LegacyFingerprintPromptCoordinator.activeRequest.value)

        // A duplicate completion is a no-op: exactly one delivery.
        LegacyFingerprintPromptCoordinator.completeSuccess(request)
        assertEquals(0, errorCount)
    }

    @Test
    fun frameworkError_isHeldForTheDialog_thenDeliveredOnDismiss() {
        var delivered: StepResult.Failed? = null
        LegacyFingerprintPromptCoordinator.begin(
            cipher = newCipher(),
            title = "Unlock Nota",
            subtitle = null,
            negativeButtonText = "Cancel",
            onAuthenticated = { },
            onError = { delivered = it },
        )
        val request = LegacyFingerprintPromptCoordinator.activeRequest.value!!

        val failure = legacyFingerprintPromptFailure(12, null)
        LegacyFingerprintPromptCoordinator.authenticationError(request, failure)
        // Held: the dialog shows the error state; nothing delivered yet.
        assertEquals(
            LegacyFingerprintPromptPhase.ERROR,
            LegacyFingerprintPromptCoordinator.uiState.value.phase,
        )
        assertNull(delivered)

        LegacyFingerprintPromptCoordinator.dismissError(request)
        assertSame(failure, delivered)
        assertNull(LegacyFingerprintPromptCoordinator.activeRequest.value)
    }

    @Test
    fun cancel_deliversCancelledOnce_andLateFrameworkCallbacksAreIgnored() {
        val failures = mutableListOf<StepResult.Failed>()
        LegacyFingerprintPromptCoordinator.begin(
            cipher = newCipher(),
            title = "Unlock Nota",
            subtitle = null,
            negativeButtonText = "Cancel",
            onAuthenticated = { },
            onError = { failures.add(it) },
        )
        val request = LegacyFingerprintPromptCoordinator.activeRequest.value!!

        LegacyFingerprintPromptCoordinator.cancel(request)
        assertEquals(1, failures.size)
        assertEquals("Cancelled", failures.single().reason)
        assertNull(LegacyFingerprintPromptCoordinator.activeRequest.value)

        // The framework answers our cancellation with its own
        // CANCELED error — it must not deliver a second callback.
        LegacyFingerprintPromptCoordinator.authenticationError(
            request,
            legacyFingerprintPromptFailure(5, null),
        )
        assertEquals(1, failures.size)
    }

    @Test
    fun secondPromptWhileOneIsActive_isRejectedAndTheFirstSurvives() {
        LegacyFingerprintPromptCoordinator.begin(
            cipher = newCipher(),
            title = "First",
            subtitle = null,
            negativeButtonText = "Cancel",
            onAuthenticated = { },
            onError = { },
        )
        val first = LegacyFingerprintPromptCoordinator.activeRequest.value

        var secondError: StepResult.Failed? = null
        LegacyFingerprintPromptCoordinator.begin(
            cipher = newCipher(),
            title = "Second",
            subtitle = null,
            negativeButtonText = "Cancel",
            onAuthenticated = { },
            onError = { secondError = it },
        )
        assertTrue(secondError != null)
        assertEquals(BiometricStep.PROMPT, secondError!!.step)
        assertSame(first, LegacyFingerprintPromptCoordinator.activeRequest.value)
    }
}
