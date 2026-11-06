package app.nota.feature.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the per-note gate's fingerprint auto-prompt
 * rule (v0.8.1): the prompt fires exactly once per gate appearance —
 * only when biometric unlock is ready, the gate is not locked out,
 * it has not already prompted, and the prefetched cipher actually
 * arrived.
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
            ),
        )
    }
}
