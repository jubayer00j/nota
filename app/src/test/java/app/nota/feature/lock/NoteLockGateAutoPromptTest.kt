package app.nota.feature.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the per-note gate's fingerprint auto-prompt
 * rule (v0.8.1): the prompt fires exactly once per gate appearance —
 * only when biometric unlock is ready, the gate is not locked out,
 * it has not already prompted, the prefetched cipher actually
 * arrived, and no credential verification is in flight.
 */
class NoteLockGateAutoPromptTest {

    @Test
    fun promptsWhenReadyUnlockedNotYetPromptedAndCipherArrived() {
        assertTrue(
            shouldAutoPromptNoteUnlock(
                biometricReady = true,
                lockedOut = false,
                alreadyPrompted = false,
                cipherReady = true,
                unlockInProgress = false,
            ),
        )
    }

    @Test
    fun doesNotPromptWhenBiometricNotReady() {
        assertFalse(
            shouldAutoPromptNoteUnlock(
                biometricReady = false,
                lockedOut = false,
                alreadyPrompted = false,
                cipherReady = true,
                unlockInProgress = false,
            ),
        )
    }

    @Test
    fun doesNotPromptDuringLockout() {
        assertFalse(
            shouldAutoPromptNoteUnlock(
                biometricReady = true,
                lockedOut = true,
                alreadyPrompted = false,
                cipherReady = true,
                unlockInProgress = false,
            ),
        )
    }

    @Test
    fun doesNotPromptTwiceForOneAppearance() {
        assertFalse(
            shouldAutoPromptNoteUnlock(
                biometricReady = true,
                lockedOut = false,
                alreadyPrompted = true,
                cipherReady = true,
                unlockInProgress = false,
            ),
        )
    }

    @Test
    fun doesNotPromptBeforeCipherArrives() {
        assertFalse(
            shouldAutoPromptNoteUnlock(
                biometricReady = true,
                lockedOut = false,
                alreadyPrompted = false,
                cipherReady = false,
                unlockInProgress = false,
            ),
        )
    }

    @Test
    fun doesNotPromptWhenNothingIsReady() {
        assertFalse(
            shouldAutoPromptNoteUnlock(
                biometricReady = false,
                lockedOut = true,
                alreadyPrompted = true,
                cipherReady = false,
                unlockInProgress = true,
            ),
        )
    }

    // Fix round (Codex B): a cipher landing while a credential
    // verification is in flight must NOT pop the prompt over it —
    // the ViewModel drops a biometric result in that window.
    @Test
    fun doesNotPromptWhileUnlockVerificationInFlight() {
        assertFalse(
            shouldAutoPromptNoteUnlock(
                biometricReady = true,
                lockedOut = false,
                alreadyPrompted = false,
                cipherReady = true,
                unlockInProgress = true,
            ),
        )
    }

    // Fix round (Codex B): suppression for an in-flight verify does
    // not consume the appearance's one prompt — with nothing yet
    // prompted, the identical inputs pass once verification ends.
    @Test
    fun suppressionByVerificationDoesNotConsumeTheOnePrompt() {
        assertFalse(
            shouldAutoPromptNoteUnlock(
                biometricReady = true,
                lockedOut = false,
                alreadyPrompted = false,
                cipherReady = true,
                unlockInProgress = true,
            ),
        )
        assertTrue(
            shouldAutoPromptNoteUnlock(
                biometricReady = true,
                lockedOut = false,
                alreadyPrompted = false,
                cipherReady = true,
                unlockInProgress = false,
            ),
        )
    }
}
