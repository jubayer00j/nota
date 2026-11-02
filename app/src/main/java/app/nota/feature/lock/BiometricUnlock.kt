package app.nota.feature.lock

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.nota.data.security.BiometricStep
import app.nota.data.security.StepResult
import javax.crypto.Cipher

/**
 * Biometric availability + prompts (Phase 2.5, F4) — the ONE authoritative
 * availability source for App Lock, per-note lock and Settings.
 *
 * WHY NOT JUST `BiometricManager.canAuthenticate(BIOMETRIC_WEAK)`:
 * on API 28 and below AndroidX does not use the framework
 * BiometricManager at all — it delegates to its internal
 * FingerprintManagerCompat shim (official AndroidX reference). That
 * compat probe is a documented false-negative source on OEM firmware
 * (Samsung/Xiaomi/Huawei: compat `isHardwareDetected()` /
 * `hasEnrolledFingerprints()` return false while the framework
 * FingerprintManager returns true for the same enrolled sensor).
 * The Galaxy J7 Prime (API 27) hit exactly this: the old helper
 * collapsed every non-SUCCESS code into one boolean, so a compat-probe
 * failure rendered as "No fingerprint or face unlock available on
 * this device" even though the sensor works and 1.x — which probes
 * the framework FingerprintManager directly through its plugin —
 * uses it on the same phone.
 *
 * THE FIX: on API < 28, probe the framework FingerprintManager
 * directly (the 1.x path): FEATURE_FINGERPRINT + isHardwareDetected +
 * hasEnrolledFingerprints, SecurityException-safe. On API >= 28 use
 * AndroidX with BIOMETRIC_STRONG — STRONG, not WEAK, because every
 * Nota biometric flow is now CRYPTO-BOUND (see AppLockBiometricStore /
 * NoteLockBiometricStore): crypto authentication is incompatible with
 * Class-2/WEAK biometrics (PromptInfo docs: crypto defaults to STRONG;
 * an explicit WEAK allowance with a CryptoObject is an error), and
 * auth-required Keystore keys can only be released by a Class-3
 * authenticator. A WEAK-only face device is therefore honestly
 * "not available" for Nota's crypto flows.
 *
 * PHASE 2.7 EVIDENCE: the framework availability probe above passed
 * on the same J7, but the 0.3.2 enable flow still failed at the
 * AndroidX prompt with the on-screen step text "Fingerprint prompt:
 * This device does not have a fingerprint sensor" — AndroidX's
 * `ERROR_HW_NOT_PRESENT`. The sensor demonstrably exists and Noma
 * uses it daily. Therefore AndroidX is now out of BOTH halves of
 * the API < 28 loop: availability comes from the framework service,
 * and the prompt executor is the framework fingerprint API (see
 * [LegacyFingerprintPromptCoordinator]). AndroidX remains the
 * executor only on API 28+, where its BiometricPrompt is native.
 */
enum class BiometricAvailability {
    AVAILABLE,
    NO_HARDWARE,
    NONE_ENROLLED,
    HW_UNAVAILABLE,
    UNSUPPORTED,
    UNKNOWN,
    ;

    val isAvailable: Boolean get() = this == AVAILABLE

    /** Settings subtitle — reflects the REAL sensor state. */
    fun settingsSubtitle(enabled: Boolean): String = when (this) {
        AVAILABLE -> if (enabled) {
            "Unlock with your fingerprint — verified against a key sealed on this device"
        } else {
            "Also unlock with your fingerprint — verified against a key sealed on this device"
        }
        NONE_ENROLLED ->
            "No fingerprint enrolled — add one in system Settings, then enable this"
        HW_UNAVAILABLE -> "Fingerprint hardware is temporarily unavailable"
        NO_HARDWARE, UNSUPPORTED, UNKNOWN ->
            "No fingerprint or face unlock available on this device"
    }
}

/**
 * Direct framework fingerprint probe (API 23–27 path; also a fallback
 * on 28/29). Returns null when the framework service is absent.
 * Never throws: a SecurityException (missing permission on some OEM
 * builds) maps to UNKNOWN rather than a false NO_HARDWARE.
 *
 * The service is reached REFLECTIVELY (coordinator fix at assembly):
 * compileSdk 37 has removed `android.hardware.fingerprint
 * .FingerprintManager` and `Context.FINGERPRINT_SERVICE` from the
 * SDK stubs, so the class cannot be referenced at compile time —
 * but it is present and public at runtime on exactly the API 23–27
 * devices this probe serves (28+ primarily uses BiometricManager).
 * Any reflective failure maps to UNKNOWN, never a false NO_HARDWARE.
 */
private fun fingerprintProbe(context: Context): BiometricAvailability? {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
        return BiometricAvailability.NO_HARDWARE
    }
    // Context.FINGERPRINT_SERVICE == "fingerprint" (the literal is
    // used because the constant is gone from the compileSdk 37 stubs).
    val manager = try {
        context.getSystemService("fingerprint")
    } catch (e: Exception) {
        null
    } ?: return null
    return try {
        val cls = manager.javaClass
        val hardware = cls.getMethod("isHardwareDetected").invoke(manager) as? Boolean
        val enrolled = cls.getMethod("hasEnrolledFingerprints").invoke(manager) as? Boolean
        when {
            hardware != true -> BiometricAvailability.NO_HARDWARE
            enrolled != true -> BiometricAvailability.NONE_ENROLLED
            else -> BiometricAvailability.AVAILABLE
        }
    } catch (e: Exception) {
        BiometricAvailability.UNKNOWN
    }
}

/** The authoritative availability check — every surface uses this. */
fun biometricAvailability(context: Context): BiometricAvailability {
    // API < 28: AndroidX delegates to FingerprintManagerCompat (see
    // header) — bypass it and probe the framework service directly.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return fingerprintProbe(context) ?: BiometricAvailability.UNKNOWN
    }
    val code = BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)
    val mapped = when (code) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
        -> BiometricAvailability.HW_UNAVAILABLE
        BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> BiometricAvailability.UNKNOWN
        else -> BiometricAvailability.UNSUPPORTED
    }
    // Belt-and-braces on 28/29 (the other FingerprintManagerCompat
    // delegation band): if AndroidX says no hardware / unknown but the
    // framework fingerprint service sees an enrolled sensor, trust the
    // framework service — that is the sensor the prompt will drive.
    if ((mapped == BiometricAvailability.NO_HARDWARE || mapped == BiometricAvailability.UNKNOWN) &&
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R
    ) {
        val probe = fingerprintProbe(context)
        if (probe == BiometricAvailability.AVAILABLE) return probe
    }
    return mapped
}

/** Boolean convenience for gate visibility — delegates to the enum. */
fun isBiometricAvailable(context: Context): Boolean =
    biometricAvailability(context).isAvailable

/** Which prompt implementation a device gets — SDK-gated, never mixed. */
enum class BiometricPromptBackend {
    ANDROIDX,
    FRAMEWORK_FINGERPRINT,
}

/** Pure selection rule (unit-tested): API 28+ AndroidX, below it framework. */
fun biometricPromptBackendForSdk(sdkInt: Int): BiometricPromptBackend =
    if (sdkInt >= Build.VERSION_CODES.P) {
        BiometricPromptBackend.ANDROIDX
    } else {
        BiometricPromptBackend.FRAMEWORK_FINGERPRINT
    }

fun biometricPromptBackend(): BiometricPromptBackend =
    biometricPromptBackendForSdk(Build.VERSION.SDK_INT)

/**
 * The prompt executor seam (Phase 2.7). EVERY crypto-bound biometric
 * call site in the app enters through [showBiometricCryptoPrompt],
 * which delegates to exactly one executor for the running SDK:
 *
 * - API 28+: [AndroidXBiometricPromptExecutor] — unchanged.
 * - API < 28: [FrameworkFingerprintPromptExecutor] — the framework
 *   fingerprint API behind Nota's own dialog. AndroidX is not
 *   consulted anywhere on this path.
 *
 * Both executors carry the caller's cipher as a CryptoObject and
 * report prompt failures as typed [StepResult.Failed] values at
 * [BiometricStep.PROMPT]. An executor success is never by itself an
 * unlock: the caller must still complete the decrypt proof.
 */
interface BiometricPromptExecutor {
    fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String?,
        negativeButtonText: String,
        onAuthenticated: (Cipher) -> Unit,
        onError: (StepResult.Failed) -> Unit,
    )
}

private fun promptFailure(reason: String): StepResult.Failed =
    StepResult.Failed(BiometricStep.PROMPT, reason)

/**
 * AndroidX prompt error → typed failure (Phase 2.7, Codex P3).
 * Cancellation-shaped AndroidX errors (user cancel, negative
 * button, system cancel) normalise to the SAME "Cancelled" sentinel
 * the framework path produces, so the call sites' suppression of
 * intentional cancels works identically on both executors instead
 * of leaking platform cancel strings as errors under the form.
 */
internal fun androidxPromptFailure(errorCode: Int, errString: CharSequence): StepResult.Failed =
    if (errorCode == BiometricPrompt.ERROR_CANCELED ||
        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
    ) {
        promptFailure("Cancelled")
    } else {
        promptFailure(errString.toString())
    }

private fun buildPrompt(
    activity: FragmentActivity,
    onSucceeded: (BiometricPrompt.AuthenticationResult) -> Unit,
    onError: (StepResult.Failed) -> Unit,
): BiometricPrompt = BiometricPrompt(
    activity,
    ContextCompat.getMainExecutor(activity),
    object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSucceeded(result)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            onError(androidxPromptFailure(errorCode, errString))
        }
    },
)

/** API 28+ executor: AndroidX BiometricPrompt, BIOMETRIC_STRONG. */
object AndroidXBiometricPromptExecutor : BiometricPromptExecutor {
    override fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String?,
        negativeButtonText: String,
        onAuthenticated: (Cipher) -> Unit,
        onError: (StepResult.Failed) -> Unit,
    ) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        val prompt = buildPrompt(
            activity,
            { result ->
                val authorised = result.cryptoObject?.cipher
                if (authorised != null) {
                    onAuthenticated(authorised)
                } else {
                    onError(promptFailure("Biometric result carried no crypto proof"))
                }
            },
            onError,
        )
        try {
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } catch (e: IllegalStateException) {
            onError(promptFailure(e.message ?: "Biometric prompt unavailable"))
        } catch (e: IllegalArgumentException) {
            onError(promptFailure(e.message ?: "Biometric prompt rejected"))
        }
    }
}

/**
 * API < 28 executor: hands the request to the legacy prompt
 * coordinator. [LegacyFingerprintPromptHost] (composed once at the
 * MainActivity root) renders Nota's dialog and starts the actual
 * framework authentication session; see LegacyFingerprintPrompt.kt.
 */
object FrameworkFingerprintPromptExecutor : BiometricPromptExecutor {
    override fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String?,
        negativeButtonText: String,
        onAuthenticated: (Cipher) -> Unit,
        onError: (StepResult.Failed) -> Unit,
    ) {
        LegacyFingerprintPromptCoordinator.begin(
            cipher = cipher,
            title = title,
            subtitle = subtitle,
            negativeButtonText = negativeButtonText,
            onAuthenticated = onAuthenticated,
            onError = onError,
        )
    }
}

/**
 * CRYPTO-BOUND prompt entry point — the only biometric prompt API
 * call sites use. [cipher] must come from an auth-required Keystore
 * key (AppLockBiometricStore / NoteLockBiometricStore cipher
 * factories). The selected executor shows the prompt with that
 * cipher as its CryptoObject, and [onAuthenticated] receives the
 * SAME cipher, now authorised by the authenticator.
 *
 * The caller MUST complete the cryptographic operation (decrypt the
 * sealed token / sealed NEK copy) with the authorised cipher and
 * treat ONLY a successful doFinal as the unlock proof. If a result
 * carries no crypto object, [onError] fires instead: a prompt
 * success without a crypto result must never unlock anything.
 * There is deliberately no non-crypto prompt variant.
 */
fun showBiometricCryptoPrompt(
    activity: FragmentActivity,
    cipher: Cipher,
    title: String = "Unlock Nota",
    subtitle: String? = null,
    negativeButtonText: String = "Use PIN, password or pattern",
    onAuthenticated: (Cipher) -> Unit,
    onError: (StepResult.Failed) -> Unit,
) {
    val executor: BiometricPromptExecutor = when (biometricPromptBackend()) {
        BiometricPromptBackend.ANDROIDX -> AndroidXBiometricPromptExecutor
        BiometricPromptBackend.FRAMEWORK_FINGERPRINT -> FrameworkFingerprintPromptExecutor
    }
    executor.authenticate(
        activity = activity,
        cipher = cipher,
        title = title,
        subtitle = subtitle,
        negativeButtonText = negativeButtonText,
        onAuthenticated = onAuthenticated,
        onError = onError,
    )
}
