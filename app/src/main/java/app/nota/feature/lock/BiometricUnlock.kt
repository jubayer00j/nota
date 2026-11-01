package app.nota.feature.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Biometric convenience gate (Phase 2a) — the AndroidX BiometricPrompt
 * equivalent of 1.x's native plugin. Posture, identical to 1.x:
 *
 * - Available only as a gate ON TOP of a credential method; it is never
 *   a credential itself and stores nothing.
 * - BIOMETRIC_WEAK (fingerprint / face as the device classifies it).
 * - NO device-credential (PIN/password of the phone) fallback — the
 *   negative button always drops back to Nota's own credentials.
 */
fun isBiometricAvailable(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) ==
        BiometricManager.BIOMETRIC_SUCCESS

/**
 * Shows one prompt. [onError] fires for error/cancel outcomes (including
 * the negative button); a single failed finger/face attempt does NOT
 * fire it — the system prompt stays open and shows its own feedback.
 * The authenticate() call is guarded: if the host activity's state was
 * already saved, the prompt fails closed via [onError] instead of
 * crashing on a fragment transaction.
 */
fun showBiometricPrompt(
    activity: FragmentActivity,
    title: String = "Unlock Nota",
    subtitle: String? = null,
    negativeButtonText: String = "Use PIN, password or pattern",
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setNegativeButtonText(negativeButtonText)
        .setAllowedAuthenticators(BIOMETRIC_WEAK)
        .build()
    try {
        prompt.authenticate(info)
    } catch (e: IllegalStateException) {
        onError(e.message ?: "Biometric prompt unavailable")
    }
}
