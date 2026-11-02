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
 * Cipher-factory seam for the App Lock biometric token (Phase 2.5),
 * mirroring NoteLockDeviceKeyProvider so Robolectric tests can inject
 * a raw-key provider (auth-required AndroidKeyStore keys cannot be
 * exercised under Robolectric — see NOTES.md).
 */
interface AppLockDeviceKeyProvider {
    /** ENCRYPT_MODE cipher over the auth-required device key. */
    fun sealCipher(): Cipher?

    /** DECRYPT_MODE cipher for [packedBase64] (iv read from the pack). */
    fun openCipher(packedBase64: String): Cipher?
}

/**
 * Production provider: AES-256-GCM key in the Android Keystore
 * (alias `nota.applock.devicekey.v1`) with
 * `setUserAuthenticationRequired(true)` — EVERY use of the key
 * requires a fresh biometric authentication through a CryptoObject,
 * and `setInvalidatedByBiometricEnrollment(true)` destroys the key
 * (and with it the sealed token) when a new fingerprint is enrolled.
 * There is deliberately NO non-authenticated encrypt/decrypt here:
 * the only way to touch the token is an authorised cipher handed
 * back by BiometricPrompt.
 */
class AndroidKeystoreAppLockKeyProvider : AppLockDeviceKeyProvider {

    override fun sealCipher(): Cipher? = try {
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, deviceKey())
        }
    } catch (e: Exception) {
        null
    }

    override fun openCipher(packedBase64: String): Cipher? = try {
        val packed = Base64.getDecoder().decode(packedBase64)
        if (packed.size <= IV_BYTES) {
            null
        } else {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    deviceKey(),
                    GCMParameterSpec(128, packed.copyOfRange(0, IV_BYTES)),
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun deviceKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ALIAS = "nota.applock.devicekey.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}

/**
 * The App Lock biometric token (Phase 2.5) — the crypto-binding that
 * replaces the old bare-boolean gate:
 *
 * - Enabling fingerprint generates a random token
 *   (`"nota-applock-v1:" || 24 random bytes`) and seals it under the
 *   auth-required Keystore key. Sealing itself runs through the
 *   prompt: the caller obtains [sealCipher], shows the crypto prompt,
 *   and hands the AUTHORISED cipher to [sealToken].
 * - The sealed form `b64( iv(12) || ct )` lives in DataStore
 *   (`app_lock_bio_token`); it never enters a backup.
 * - Unlock: the caller obtains [openCipher] (which remembers the
 *   packed form), shows the crypto prompt, and hands the authorised
 *   cipher to [verifyToken]. Unlock succeeds ONLY when
 *   `cipher.doFinal(ct)` succeeds (GCM tag verifies — impossible
 *   without a genuine biometric authorising the Keystore key) AND
 *   the recovered plaintext carries the token prefix. A prompt
 *   success without a working crypto result proves nothing and
 *   unlocks nothing.
 * - If the key is invalidated (new fingerprint enrolled, lock-screen
 *   reset), openCipher/verify fail closed: the user falls back to
 *   their credential and re-enables fingerprint to seal a fresh token.
 */
class AppLockBiometricStore(
    private val preferences: PreferencesRepository,
    private val keyProvider: AppLockDeviceKeyProvider,
) {
    /** Packed form remembered by [openCipher] for [verifyToken]. */
    @Volatile
    private var pendingPacked: ByteArray? = null

    suspend fun hasToken(): Boolean =
        preferences.getAppLockBiometricToken() != null

    /** Cipher for the ENABLING prompt (seal direction). */
    fun sealCipher(): Cipher? = keyProvider.sealCipher()

    /** Cipher for an UNLOCK/verify prompt; null when no token exists. */
    suspend fun openCipher(): Cipher? {
        val packed = preferences.getAppLockBiometricToken() ?: return null
        pendingPacked = try {
            Base64.getDecoder().decode(packed)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return keyProvider.openCipher(packed)
    }

    /** Seals a fresh token with an AUTHORISED [cipher]; read-back verified. */
    suspend fun sealToken(cipher: Cipher): Boolean {
        val token = TOKEN_PREFIX.toByteArray(Charsets.UTF_8) +
            ByteArray(24).also { SecureRandom().nextBytes(it) }
        val packed = try {
            Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(token))
        } catch (e: Exception) {
            return false
        } finally {
            token.fill(0)
        }
        preferences.setAppLockBiometricToken(packed)
        return preferences.getAppLockBiometricToken() == packed
    }

    /**
     * THE unlock proof: decrypts the sealed token with the AUTHORISED
     * [cipher] from [openCipher]'s prompt. True only on a successful
     * GCM decryption whose plaintext carries the token prefix
     * (constant-time prefix comparison).
     */
    fun verifyToken(cipher: Cipher): Boolean {
        val packed = pendingPacked ?: return false
        if (packed.size <= IV_BYTES) return false
        val plain = try {
            cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size))
        } catch (e: Exception) {
            return false
        }
        val prefix = TOKEN_PREFIX.toByteArray(Charsets.UTF_8)
        val ok = plain.size > prefix.size &&
            MessageDigest.isEqual(plain.copyOfRange(0, prefix.size), prefix)
        plain.fill(0)
        return ok
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
