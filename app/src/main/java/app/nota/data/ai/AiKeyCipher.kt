package app.nota.data.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals API keys at rest (Phase 4.1), behind an interface so tests
 * can inject a raw-key fake — the same seam shape as
 * NoteLockDeviceKeyProvider. Sealed form is b64( iv(12) || ct )
 * with the GCM tag appended, the packing the lock stores use.
 */
interface AiKeyCipher {
    /** Seals [plain] under the device key; null on any failure. */
    fun encrypt(plain: ByteArray): String?

    /** Opens a sealed value; null on any failure (never throws). */
    fun decrypt(packedBase64: String): ByteArray?
}

/**
 * Production cipher: an AES-256-GCM key in the Android Keystore.
 *
 * Unlike the per-note-lock device key, this key is NOT
 * user-authentication required: an AI call must be able to use the
 * key silently once the user has saved it (there is no prompt in
 * the Summarize flow). The protection goal is narrower and stated
 * plainly: the key is never stored in plaintext, never enters Room
 * or a backup, and a copied preferences file is useless without
 * this device's Keystore.
 *
 * No setInvalidatedByBiometricEnrollment either — on Samsung
 * API-27 firmware that flag poisons keys at birth (the Phase 2.6
 * lesson), and this key has no biometric binding to protect.
 */
class AndroidKeystoreAiKeyCipher : AiKeyCipher {

    override fun encrypt(plain: ByteArray): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey())
        Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain))
    } catch (e: Exception) {
        null
    }

    override fun decrypt(packedBase64: String): ByteArray? = try {
        val packed = Base64.getDecoder().decode(packedBase64)
        if (packed.size <= 12) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                deviceKey(),
                GCMParameterSpec(128, packed.copyOfRange(0, 12)),
            )
            cipher.doFinal(packed.copyOfRange(12, packed.size))
        }
    } catch (e: Exception) {
        null
    }

    private fun deviceKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ALIAS = "nota.ai.keys.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
