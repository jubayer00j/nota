package app.nota.feature.lock

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
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
 */
private fun fingerprintProbe(context: Context): BiometricAvailability? {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
        return BiometricAvailability.NO_HARDWARE
    }
    val manager = try {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.FINGERPRINT_SERVICE) as? FingerprintManager
    } catch (e: Exception) {
        null
    } ?: return null
    return try {
        @Suppress("DEPRECATION")
        when {
            !manager.isHardwareDetected -> BiometricAvailability.NO_HARDWARE
            !manager.hasEnrolledFingerprints() -> BiometricAvailability.NONE_ENROLLED
            else -> BiometricAvailability.AVAILABLE
        }
    } catch (e: SecurityException) {
        BiometricAvailability.UNKNOWN
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

private fun buildPrompt(
    activity: FragmentActivity,
    onSucceeded: (BiometricPrompt.AuthenticationResult) -> Unit,
    onError: (String) -> Unit,
): BiometricPrompt = BiometricPrompt(
    activity,
    ContextCompat.getMainExecutor(activity),
    object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSucceeded(result)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            onError(errString.toString())
        }
    },
)

/**
 * Plain (non-crypto) prompt — LEGACY, retained only for callers that
 * have not been migrated. New App Lock / per-note flows MUST use
 * [showBiometricCryptoPrompt]: a bare success boolean here is not
 * proof of anything (it can be hooked/spoofed) and Nota's biometric
 * unlocks are crypto-bound by design (Phase 2.5 requirement).
 */
fun showBiometricPrompt(
    activity: FragmentActivity,
    title: String = "Unlock Nota",
    subtitle: String? = null,
    negativeButtonText: String = "Use PIN, password or pattern",
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setNegativeButtonText(negativeButtonText)
        .setAllowedAuthenticators(BIOMETRIC_WEAK)
        .build()
    try {
        buildPrompt(activity, { onSuccess() }, onError).authenticate(info)
    } catch (e: IllegalStateException) {
        onError(e.message ?: "Biometric prompt unavailable")
    }
}

/**
 * CRYPTO-BOUND prompt (Phase 2.5): [cipher] must come from an
 * auth-required Keystore key (AppLockBiometricStore /
 * NoteLockBiometricStore cipher factories). The prompt is shown with
 * `authenticate(info, CryptoObject(cipher))` and BIOMETRIC_STRONG.
 *
 * [onAuthenticated] receives the SAME cipher, now authorised by the
 * Keystore — the caller MUST complete the cryptographic operation
 * (decrypt the sealed token / sealed NEK copy) with it and treat
 * ONLY a successful doFinal as the unlock proof. If the result
 * carries no crypto object, [onError] fires instead: a prompt
 * success without a crypto result must never unlock anything.
 */
fun showBiometricCryptoPrompt(
    activity: FragmentActivity,
    cipher: Cipher,
    title: String = "Unlock Nota",
    subtitle: String? = null,
    negativeButtonText: String = "Use PIN, password or pattern",
    onAuthenticated: (Cipher) -> Unit,
    onError: (String) -> Unit,
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
                onError("Biometric result carried no crypto proof")
            }
        },
        onError,
    )
    try {
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    } catch (e: IllegalStateException) {
        onError(e.message ?: "Biometric prompt unavailable")
    } catch (e: IllegalArgumentException) {
        onError(e.message ?: "Biometric prompt rejected")
    }
}
