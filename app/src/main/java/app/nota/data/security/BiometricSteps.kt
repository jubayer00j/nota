package app.nota.data.security

/**
 * Typed step results for the crypto-bound biometric flows
 * (Phase 2.6, Fix A).
 *
 * WHY: on the Galaxy J7 Prime (Android 8.1) enabling App Lock
 * fingerprint in 0.3.1 ran the credential re-auth and then died
 * with the toggle off and NO message anywhere — every failure
 * point in the enable path collapsed to a silent null/false
 * (`sealCipher() ?: return`, `onError = { /* unchanged */ }`, an
 * unchecked `sealToken` result). A failure the user cannot see
 * cannot be reported, screenshotted or fixed.
 *
 * THE RULE FROM 2.6 ON: every step of the pipeline — key
 * generation, cipher setup, sealed-token read, seal, persist,
 * verification decrypt — returns a [StepResult]. Callers surface
 * [StepResult.Failed.userMessage] on the Settings row / lock
 * screen, and a flow may only report success when every step
 * genuinely completed (for enabling: the token was sealed AND
 * read back from storage).
 */
enum class BiometricStep(val label: String) {
    KEY_GENERATION("key generation"),
    CIPHER_INIT("cipher setup"),
    TOKEN_READ("reading the sealed token"),
    SEAL("sealing"),
    PERSIST("saving"),
    DECRYPT("verification"),
    PROMPT("fingerprint prompt"),
}

/** Outcome of one biometric pipeline step. */
sealed interface StepResult<out T> {
    data class Ok<T>(val value: T) : StepResult<T>

    data class Failed(val step: BiometricStep, val reason: String) : StepResult<Nothing> {
        /**
         * Short, human-readable, screenshot-able summary for the
         * Settings row / lock screen: what couldn't be done, at
         * which step, and the platform's reason (exception name —
         * e.g. KeyPermanentlyInvalidatedException — kept verbatim
         * so a screenshot is a diagnosis, not a shrug).
         */
        val userMessage: String
            get() = when (step) {
                BiometricStep.KEY_GENERATION ->
                    "Couldn't create the fingerprint key on this device (step: key generation — $reason)"
                BiometricStep.CIPHER_INIT ->
                    "Couldn't prepare the fingerprint cipher on this device (step: cipher setup — $reason)"
                BiometricStep.TOKEN_READ ->
                    "Fingerprint needs re-enabling — its sealed token couldn't be read (step: reading the sealed token — $reason)"
                BiometricStep.SEAL ->
                    "Couldn't seal the fingerprint token (step: sealing — $reason)"
                BiometricStep.PERSIST ->
                    "Couldn't save the fingerprint token (step: saving — $reason)"
                BiometricStep.DECRYPT ->
                    "Fingerprint couldn't be verified on this device (step: verification — $reason)"
                BiometricStep.PROMPT ->
                    "Fingerprint prompt: $reason"
            }
    }
}

/** Compact platform-reason string: exception type + short message. */
internal fun stepReason(e: Exception): String {
    val name = e.javaClass.simpleName.ifBlank { "Exception" }
    val message = e.message?.trim()?.take(80)
    return if (message.isNullOrEmpty()) name else "$name: $message"
}
