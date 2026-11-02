package app.nota.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.nota.data.preferences.PreferencesRepository
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cipher-factory seam for the App Lock biometric token, mirroring
 * NoteLockDeviceKeyProvider so Robolectric tests can inject a
 * raw-key provider (auth-required AndroidKeyStore keys cannot be
 * exercised under Robolectric — see the store tests).
 *
 * Phase 2.6 (Fix A): both factories return [StepResult] — a bare
 * null hid WHICH step failed on the J7 (key generation vs cipher
 * init vs token read), and the caller turned the null into a
 * silent abort. Failures now carry their step + platform reason
 * all the way to the Settings row.
 */
interface AppLockDeviceKeyProvider {
    /** ENCRYPT_MODE cipher over the auth-required device key. */
    fun sealCipherResult(): StepResult<Cipher>

    /** DECRYPT_MODE cipher for [packedBase64] (iv read from the pack). */
    fun openCipherResult(packedBase64: String): StepResult<Cipher>
}

/**
 * Production provider: AES-256-GCM key in the Android Keystore
 * (alias `nota.applock.devicekey.v2`) with
 * `setUserAuthenticationRequired(true)` — EVERY use of the key
 * requires a fresh biometric authentication through a CryptoObject.
 * There is deliberately NO non-authenticated encrypt/decrypt here:
 * the only way to touch the token is an authorised cipher handed
 * back by BiometricPrompt.
 *
 * Phase 2.6 changes, both driven by the J7 failure:
 *
 * - Alias v1 → v2. Keys generated under v1 may already be
 *   permanently invalidated on the affected firmware (see below);
 *   orphaning them lets enabling start from a clean key. Tokens
 *   sealed under a v1 key fail closed and the user re-enables —
 *   the same one-time cost as the 2.5 crypto-binding migration.
 *
 * - `setInvalidatedByBiometricEnrollment(true)` is NOT set any
 *   more. On Samsung's API-27 keystore that flag leaves keys
 *   permanently invalidated from birth (the enrollment version
 *   the keystore compares against is never stable there), so the
 *   very first cipher init threw KeyPermanentlyInvalidatedException
 *   and enabling fingerprint died inside this provider — silently,
 *   behind a null. NAMED TRADEOFF: enrolling an additional
 *   fingerprint no longer auto-destroys the App Lock token; the
 *   newly enrolled finger can authorise the existing sealed token
 *   until the user turns Fingerprint unlock off (which clears the
 *   token) or removes App Lock. The token remains sealed under an
 *   auth-required key and unlock still requires a real decryption
 *   — the CryptoObject binding is unchanged.
 */
class AndroidKeystoreAppLockKeyProvider : AppLockDeviceKeyProvider {

    override fun sealCipherResult(): StepResult<Cipher> {
        val key = when (val loaded = loadOrGenerateKey()) {
            is StepResult.Ok -> loaded.value
            is StepResult.Failed -> return loaded
        }
        return try {
            StepResult.Ok(
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.ENCRYPT_MODE, key)
                },
            )
        } catch (e: Exception) {
            StepResult.Failed(BiometricStep.CIPHER_INIT, stepReason(e))
        }
    }

    override fun openCipherResult(packedBase64: String): StepResult<Cipher> {
        val packed = try {
            Base64.getDecoder().decode(packedBase64)
        } catch (e: IllegalArgumentException) {
            return StepResult.Failed(BiometricStep.TOKEN_READ, "stored token is not valid Base64")
        }
        if (packed.size <= IV_BYTES) {
            return StepResult.Failed(BiometricStep.TOKEN_READ, "stored token is truncated")
        }
        val key = when (val loaded = loadOrGenerateKey()) {
            is StepResult.Ok -> loaded.value
            is StepResult.Failed -> return loaded
        }
        return try {
            StepResult.Ok(
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        key,
                        GCMParameterSpec(128, packed.copyOfRange(0, IV_BYTES)),
                    )
                },
            )
        } catch (e: Exception) {
            StepResult.Failed(BiometricStep.CIPHER_INIT, stepReason(e))
        }
    }

    private fun loadOrGenerateKey(): StepResult<SecretKey> {
        val keyStore = try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        } catch (e: Exception) {
            return StepResult.Failed(BiometricStep.KEY_GENERATION, stepReason(e))
        }
        try {
            (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
                return StepResult.Ok(it.secretKey)
            }
        } catch (e: Exception) {
            return StepResult.Failed(BiometricStep.KEY_GENERATION, stepReason(e))
        }
        return try {
            val generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    // Deliberately NO setInvalidatedByBiometricEnrollment:
                    // see the class doc — on Samsung API-27 firmware the
                    // flag poisons the key at birth.
                    .build(),
            )
            StepResult.Ok(generator.generateKey())
        } catch (e: Exception) {
            StepResult.Failed(BiometricStep.KEY_GENERATION, stepReason(e))
        }
    }

    companion object {
        const val ALIAS = "nota.applock.devicekey.v2"

        /** Phase 2.5 alias — orphaned in 2.6 (see class doc). */
        const val LEGACY_ALIAS = "nota.applock.devicekey.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}

/**
 * The App Lock biometric token — the crypto-binding that replaces
 * the old bare-boolean gate:
 *
 * - Enabling fingerprint generates a random token
 *   (`"nota-applock-v1:" || 24 random bytes`) and seals it under the
 *   auth-required Keystore key. Sealing itself runs through the
 *   prompt: the caller obtains [sealCipherResult], shows the crypto
 *   prompt, and hands the AUTHORISED cipher to [sealToken].
 * - The sealed form `b64( iv(12) || ct )` lives in DataStore
 *   (`app_lock_bio_token`); it never enters a backup.
 * - Unlock: the caller obtains [openCipherResult] (which remembers
 *   the packed form), shows the crypto prompt, and hands the
 *   authorised cipher to [verifyTokenResult]. Unlock succeeds ONLY
 *   when `cipher.doFinal(ct)` succeeds (GCM tag verifies —
 *   impossible without a genuine biometric authorising the
 *   Keystore key) AND the recovered plaintext carries the token
 *   prefix. A prompt success without a working crypto result
 *   proves nothing and unlocks nothing.
 * - If the key is invalidated (lock-screen reset etc.), the typed
 *   result fails closed WITH its step + reason: callers show it,
 *   the user falls back to their credential and re-enables
 *   fingerprint to seal a fresh token.
 *
 * Phase 2.6 (Fix A): every operation returns [StepResult]; the
 * nullable/Boolean forms are gone — a silent failure in this flow
 * is how 0.3.1 shipped a fingerprint toggle that could never turn
 * on and never said why.
 */
class AppLockBiometricStore(
    private val preferences: PreferencesRepository,
    private val keyProvider: AppLockDeviceKeyProvider,
) {
    /** Packed form remembered by [openCipherResult] for [verifyTokenResult]. */
    @Volatile
    private var pendingPacked: ByteArray? = null

    suspend fun hasToken(): Boolean =
        preferences.getAppLockBiometricToken() != null

    /**
     * True when a stored token exists, parses, AND the device key
     * can still build a decrypt cipher for it. Settings uses this
     * to show a visible "needs re-enabling" state instead of an ON
     * toggle backed by a dead token (stale/corrupt record fails
     * closed, and now also fails VISIBLY). Building the cipher is
     * init-only — it needs no authentication and touches no secret.
     */
    suspend fun tokenUsable(): Boolean {
        val packed = preferences.getAppLockBiometricToken() ?: return false
        return keyProvider.openCipherResult(packed) is StepResult.Ok
    }

    /** Cipher for the ENABLING prompt (seal direction). */
    fun sealCipherResult(): StepResult<Cipher> = keyProvider.sealCipherResult()

    /** Cipher for an UNLOCK/verify prompt; Failed when no token exists. */
    suspend fun openCipherResult(): StepResult<Cipher> {
        val packed = preferences.getAppLockBiometricToken()
            ?: return StepResult.Failed(
                BiometricStep.TOKEN_READ,
                "no fingerprint token is stored on this device",
            )
        pendingPacked = try {
            Base64.getDecoder().decode(packed)
        } catch (e: IllegalArgumentException) {
            return StepResult.Failed(BiometricStep.TOKEN_READ, "stored token is not valid Base64")
        }
        if (pendingPacked!!.size <= IV_BYTES) {
            return StepResult.Failed(BiometricStep.TOKEN_READ, "stored token is truncated")
        }
        return keyProvider.openCipherResult(packed)
    }

    /**
     * Seals a fresh token with an AUTHORISED [cipher] and verifies
     * the write by reading it back: Ok only when the sealed token
     * genuinely landed in storage.
     */
    suspend fun sealToken(cipher: Cipher): StepResult<Unit> {
        val token = TOKEN_PREFIX.toByteArray(Charsets.UTF_8) +
            ByteArray(24).also { SecureRandom().nextBytes(it) }
        val packed = try {
            Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(token))
        } catch (e: Exception) {
            return StepResult.Failed(BiometricStep.SEAL, stepReason(e))
        } finally {
            token.fill(0)
        }
        preferences.setAppLockBiometricToken(packed)
        return if (preferences.getAppLockBiometricToken() == packed) {
            StepResult.Ok(Unit)
        } else {
            StepResult.Failed(BiometricStep.PERSIST, "the sealed token did not read back from storage")
        }
    }

    /**
     * THE unlock proof: decrypts the sealed token with the
     * AUTHORISED [cipher] from [openCipherResult]'s prompt. Ok only
     * on a successful GCM decryption whose plaintext carries the
     * token prefix (constant-time prefix comparison).
     */
    fun verifyTokenResult(cipher: Cipher): StepResult<Unit> {
        val packed = pendingPacked
            ?: return StepResult.Failed(
                BiometricStep.TOKEN_READ,
                "no token was opened for this attempt",
            )
        if (packed.size <= IV_BYTES) {
            return StepResult.Failed(BiometricStep.TOKEN_READ, "stored token is truncated")
        }
        val plain = try {
            cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size))
        } catch (e: Exception) {
            return StepResult.Failed(BiometricStep.DECRYPT, stepReason(e))
        }
        val prefix = TOKEN_PREFIX.toByteArray(Charsets.UTF_8)
        val ok = plain.size > prefix.size &&
            MessageDigest.isEqual(plain.copyOfRange(0, prefix.size), prefix)
        plain.fill(0)
        return if (ok) {
            StepResult.Ok(Unit)
        } else {
            StepResult.Failed(BiometricStep.DECRYPT, "the decrypted value was not the sealed token")
        }
    }

    suspend fun clear() {
        pendingPacked = null
        preferences.removeAppLockBiometricToken()
    }

    companion object {
        const val TOKEN_PREFIX = "nota-applock-v1:"
        private const val IV_BYTES = 12
    }
}
