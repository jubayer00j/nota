package app.nota.feature.lock

import app.nota.data.security.AppLockCrypto
import app.nota.data.security.AppLockMethod

/**
 * The decision logic of App Lock credential enrolment (Phase 2.9, J1),
 * extracted from [CredentialSetupForm] so the enter → confirm contract
 * is unit-testable without a composition.
 *
 * The 0.3.4 device finding behind this class: a WRONG confirmation
 * reset the whole flow to the first entry ("…do not match. Start
 * again."), while a CORRECT confirmation appeared to do nothing at
 * all for the full length of the save derivation — the two states
 * felt identical, and setup read as "rejected, try again". The
 * contract pinned here:
 *
 * - a valid first entry advances to CONFIRM exactly once;
 * - a matching confirmation completes with the CANONICAL secret
 *   (the exact 1.x forms, via [AppLockCrypto]);
 * - a mismatched confirmation is [Outcome.Rejected] and the flow
 *   STAYS on CONFIRM with the first entry retained — only the
 *   confirmation input needs re-entering, and the message says so.
 *   Nothing is silently cleared and nothing restarts;
 * - an invalid first entry (too short) is Rejected on ENTER_FIRST.
 *
 * Persistence is NOT this class's job: the caller saves the completed
 * secret (one derivation, in [app.nota.data.security.AppLockManager])
 * and owns the busy state that covers that derivation.
 */
class CredentialSetupFlow(val method: AppLockMethod) {

    enum class Step { ENTER_FIRST, CONFIRM, COMPLETE }

    sealed interface Outcome {
        /** First entry accepted; ask for the confirmation. */
        data object AdvancedToConfirm : Outcome

        /** Confirmation matched; [canonicalSecret] is ready to save. */
        data class Completed(val canonicalSecret: String) : Outcome

        /**
         * The submission was refused with a user-visible [message];
         * the flow's step is unchanged, so the user retries the SAME
         * step — never a silent reset to the beginning.
         */
        data class Rejected(val message: String) : Outcome
    }

    var step: Step = Step.ENTER_FIRST
        private set

    /** Canonical form of the accepted first entry, awaiting its match. */
    private var pendingCanonical: String? = null

    fun submitPassword(raw: String): Outcome {
        check(method == AppLockMethod.PASSWORD) { "password submitted to a $method flow" }
        if (step == Step.ENTER_FIRST && raw.length < PASSWORD_MIN_LENGTH) {
            return Outcome.Rejected(
                "Password must be at least $PASSWORD_MIN_LENGTH characters.",
            )
        }
        return submitCanonical(AppLockCrypto.canonicalPassword(raw), noun = "Passwords")
    }

    fun submitPin(digits: String): Outcome {
        check(method == AppLockMethod.PIN) { "PIN submitted to a $method flow" }
        if (step == Step.ENTER_FIRST && digits.length !in PIN_MIN_LENGTH..PIN_MAX_LENGTH) {
            return Outcome.Rejected(
                "PIN must be $PIN_MIN_LENGTH–$PIN_MAX_LENGTH digits.",
            )
        }
        return submitCanonical(AppLockCrypto.canonicalPin(digits), noun = "PINs")
    }

    fun submitPattern(points: List<Int>): Outcome {
        check(method == AppLockMethod.PATTERN) { "pattern submitted to a $method flow" }
        if (points.size < PATTERN_MIN_POINTS) {
            return Outcome.Rejected(
                "Pattern must connect at least $PATTERN_MIN_POINTS dots.",
            )
        }
        return submitCanonical(AppLockCrypto.canonicalPattern(points), noun = "Patterns")
    }

    private fun submitCanonical(canonical: String, noun: String): Outcome {
        return when (step) {
            Step.ENTER_FIRST -> {
                pendingCanonical = canonical
                step = Step.CONFIRM
                Outcome.AdvancedToConfirm
            }
            Step.CONFIRM -> {
                if (canonical == pendingCanonical) {
                    step = Step.COMPLETE
                    Outcome.Completed(canonical)
                } else {
                    // The first entry is RETAINED (pendingCanonical is
                    // untouched): the user re-enters only the
                    // confirmation. This is the J1 fix — 0.3.4 threw
                    // the first entry away and restarted the flow.
                    Outcome.Rejected("$noun do not match — enter the confirmation again.")
                }
            }
            Step.COMPLETE -> Outcome.Rejected("Already saved.")
        }
    }
}
