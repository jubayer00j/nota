package app.nota.legacyfingerprint;

import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.os.CancellationSignal;
import android.os.Handler;
import javax.crypto.Cipher;

/**
 * Direct bridge to the framework fingerprint API (Phase 2.7) — the
 * API that AndroidX BiometricPrompt replaced, and the one Noma-era
 * plugins drive on the Galaxy J7 Prime (API 27).
 *
 * <p>Why not {@code androidx.core.hardware.fingerprint
 * .FingerprintManagerCompat}? Because in androidx.core 1.18.0 (the
 * version this project's dependency set resolves) that class is a
 * husk: both {@code authenticate} overloads compile to a single
 * {@code return}, {@code hasEnrolledFingerprints} /
 * {@code isHardwareDetected} always return false, and the Api23Impl
 * delegation class no longer exists (verified by disassembling the
 * published AAR after a Codex P1 on PR #12). Calling it would leave
 * Nota's fingerprint dialog listening forever with no scan started.
 *
 * <p>Crypto discipline: the caller's {@link Cipher} is wrapped in
 * the framework {@link FingerprintManager.CryptoObject}; a success
 * callback returns the SAME cipher out of the authentication
 * result, now authorised by the Keystore. The caller still performs
 * the decrypt proof — this bridge never reports a bare boolean.
 */
public final class LegacyFingerprint {

    /** Events of one framework authentication session. */
    public interface Callback {
        /** Terminal framework error (see FingerprintManager FINGERPRINT_ERROR_* codes). */
        void onError(int errorCode, CharSequence message);

        /** Recoverable guidance while the session keeps listening. */
        void onHelp(int helpCode, CharSequence message);

        /** Fingerprint recognised; {@code cipher} is the authorised crypto object, or null. */
        void onSucceeded(Cipher cipher);

        /** A valid fingerprint that is not recognised; the session keeps listening. */
        void onFailed();
    }

    private LegacyFingerprint() {
    }

    /**
     * Starts a framework fingerprint authentication session.
     *
     * @return the session's {@link CancellationSignal} (cancel it to
     * end the session), or {@code null} when the framework
     * fingerprint service is absent on this device.
     */
    @SuppressWarnings("deprecation") // FingerprintManager: the whole point of this module.
    public static CancellationSignal authenticate(
            Context context, Cipher cipher, Callback callback, Handler handler) {
        FingerprintManager manager = context.getSystemService(FingerprintManager.class);
        if (manager == null) {
            return null;
        }
        CancellationSignal signal = new CancellationSignal();
        try {
            manager.authenticate(
                    new FingerprintManager.CryptoObject(cipher),
                    signal,
                    0,
                    new FingerprintManager.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            callback.onError(errorCode, errString);
                        }

                        @Override
                        public void onAuthenticationHelp(int helpCode, CharSequence helpString) {
                            callback.onHelp(helpCode, helpString);
                        }

                        @Override
                        public void onAuthenticationSucceeded(
                                FingerprintManager.AuthenticationResult result) {
                            FingerprintManager.CryptoObject crypto = result.getCryptoObject();
                            callback.onSucceeded(crypto != null ? crypto.getCipher() : null);
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            callback.onFailed();
                        }
                    },
                    handler);
        } catch (SecurityException | RuntimeException e) {
            // PR #55 (Argon F-11): on flaky OEM sensor stacks
            // (API 26-27 is this bridge's whole territory) the
            // framework call itself can throw — a dying fingerprint
            // service, a permission state that changed under us.
            // Letting that escape would crash the caller; the
            // callback contract already has a terminal error
            // channel, so report through it and start no session.
            CharSequence message = e.getMessage() != null
                    ? e.getMessage()
                    : "Fingerprint sensor unavailable";
            callback.onError(FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE, message);
            return null;
        }
        return signal;
    }
}
