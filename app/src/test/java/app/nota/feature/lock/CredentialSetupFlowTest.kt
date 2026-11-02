package app.nota.feature.lock

import app.nota.data.security.AppLockCrypto
import app.nota.data.security.AppLockMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.9 (J1): the App Lock enrolment contract — set + confirm for
 * password, PIN and pattern, including the wrong-confirm path.
 *
 * 0.3.4 behaviour being pinned against: a wrong confirmation reset
 * the whole flow ("Start again"), and a correct one disappeared into
 * a silent derivation window that felt identical. The flow must now
 * keep the first entry on a mismatch (retry the confirmation only),
 * and complete with the exact canonical secret on a match.
 */
class CredentialSetupFlowTest {

    // ---- Password --------------------------------------------------------

    @Test
    fun passwordSetAndConfirmCompletesWithCanonicalSecret() {
        val flow = CredentialSetupFlow(AppLockMethod.PASSWORD)
        val first = flow.submitPassword("correct horse")
        assertEquals(CredentialSetupFlow.Outcome.AdvancedToConfirm, first)
        assertEquals(CredentialSetupFlow.Step.CONFIRM, flow.step)

        val done = flow.submitPassword("correct horse")
        assertEquals(
            CredentialSetupFlow.Outcome.Completed(AppLockCrypto.canonicalPassword("correct horse")),
            done,
        )
        assertEquals(CredentialSetupFlow.Step.COMPLETE, flow.step)
    }

    @Test
    fun passwordTooShortIsRejectedOnFirstEntry() {
        val flow = CredentialSetupFlow(AppLockMethod.PASSWORD)
        val outcome = flow.submitPassword("short")
        assertTrue(outcome is CredentialSetupFlow.Outcome.Rejected)
        assertEquals(CredentialSetupFlow.Step.ENTER_FIRST, flow.step)
        // A valid entry still works after the rejection.
        assertEquals(
            CredentialSetupFlow.Outcome.AdvancedToConfirm,
            flow.submitPassword("long enough"),
        )
    }

    @Test
    fun passwordWrongConfirmKeepsFirstEntryAndRetriesConfirmOnly() {
        val flow = CredentialSetupFlow(AppLockMethod.PASSWORD)
        flow.submitPassword("correct horse")
        val wrong = flow.submitPassword("correct house")
        assertTrue(wrong is CredentialSetupFlow.Outcome.Rejected)
        assertTrue(
            (wrong as CredentialSetupFlow.Outcome.Rejected).message.contains("do not match"),
        )
        // Still on CONFIRM — the first entry was NOT thrown away.
        assertEquals(CredentialSetupFlow.Step.CONFIRM, flow.step)
        // Retrying with the right confirmation completes immediately.
        assertEquals(
            CredentialSetupFlow.Outcome.Completed("correct horse"),
            flow.submitPassword("correct horse"),
        )
    }

    // ---- PIN -------------------------------------------------------------

    @Test
    fun pinSetAndConfirmCompletesWithCanonicalSecret() {
        val flow = CredentialSetupFlow(AppLockMethod.PIN)
        assertEquals(
            CredentialSetupFlow.Outcome.AdvancedToConfirm,
            flow.submitPin("246810"),
        )
        assertEquals(
            CredentialSetupFlow.Outcome.Completed("noma_pin_v1:246810"),
            flow.submitPin("246810"),
        )
    }

    @Test
    fun pinWrongConfirmKeepsFirstEntry() {
        val flow = CredentialSetupFlow(AppLockMethod.PIN)
        flow.submitPin("1357")
        val wrong = flow.submitPin("1358")
        assertTrue(wrong is CredentialSetupFlow.Outcome.Rejected)
        assertEquals(CredentialSetupFlow.Step.CONFIRM, flow.step)
        assertEquals(
            CredentialSetupFlow.Outcome.Completed(AppLockCrypto.canonicalPin("1357")),
            flow.submitPin("1357"),
        )
    }

    @Test
    fun pinOutOfRangeIsRejectedOnFirstEntry() {
        val flow = CredentialSetupFlow(AppLockMethod.PIN)
        assertTrue(flow.submitPin("123") is CredentialSetupFlow.Outcome.Rejected)
        assertTrue(flow.submitPin("1234567") is CredentialSetupFlow.Outcome.Rejected)
        assertEquals(CredentialSetupFlow.Step.ENTER_FIRST, flow.step)
    }

    // ---- Pattern ---------------------------------------------------------

    @Test
    fun patternSetAndConfirmCompletesWithCanonicalSecret() {
        val flow = CredentialSetupFlow(AppLockMethod.PATTERN)
        assertEquals(
            CredentialSetupFlow.Outcome.AdvancedToConfirm,
            flow.submitPattern(listOf(0, 1, 2, 5, 8)),
        )
        assertEquals(
            CredentialSetupFlow.Outcome.Completed("noma_pattern_v1:0-1-2-5-8"),
            flow.submitPattern(listOf(0, 1, 2, 5, 8)),
        )
    }

    @Test
    fun patternWrongConfirmKeepsFirstEntry() {
        val flow = CredentialSetupFlow(AppLockMethod.PATTERN)
        flow.submitPattern(listOf(0, 4, 8, 6))
        val wrong = flow.submitPattern(listOf(0, 4, 8, 7))
        assertTrue(wrong is CredentialSetupFlow.Outcome.Rejected)
        assertTrue(
            (wrong as CredentialSetupFlow.Outcome.Rejected).message.contains("do not match"),
        )
        assertEquals(CredentialSetupFlow.Step.CONFIRM, flow.step)
        assertEquals(
            CredentialSetupFlow.Outcome.Completed(AppLockCrypto.canonicalPattern(listOf(0, 4, 8, 6))),
            flow.submitPattern(listOf(0, 4, 8, 6)),
        )
    }

    @Test
    fun patternTooShortIsRejectedOnEitherStep() {
        val flow = CredentialSetupFlow(AppLockMethod.PATTERN)
        assertTrue(flow.submitPattern(listOf(0, 1, 2)) is CredentialSetupFlow.Outcome.Rejected)
        assertEquals(CredentialSetupFlow.Step.ENTER_FIRST, flow.step)
        flow.submitPattern(listOf(0, 1, 2, 3))
        // A too-short CONFIRMATION reports the length problem, not a
        // mismatch, and the first entry survives it too.
        val outcome = flow.submitPattern(listOf(0, 1))
        assertTrue(outcome is CredentialSetupFlow.Outcome.Rejected)
        assertEquals(CredentialSetupFlow.Step.CONFIRM, flow.step)
        assertEquals(
            CredentialSetupFlow.Outcome.Completed(AppLockCrypto.canonicalPattern(listOf(0, 1, 2, 3))),
            flow.submitPattern(listOf(0, 1, 2, 3)),
        )
    }
}
