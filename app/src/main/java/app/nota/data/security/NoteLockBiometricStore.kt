package app.nota.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.nota.data.preferences.PreferencesRepository
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-key access for the per-note biometric copy (Phase 2b), behind an
 * interface so JVM/Robolectric tests can inject a raw-key provider
 * (AndroidKeyStore under Robolectric is unreliable). The operations are
 * encrypt/decrypt — never raw key bytes — because AndroidKeyStore keys
 * are non-exportable by design.
 *
 * Sealed form is b64( iv(12) || ct ) with the GCM tag appended, the same
 * packing as a credential slot's wrappedKey.
 */
interface NoteLockDeviceKeyProvider {
    /** Seals [plain] under the device key; null on any failure. */
    fun encrypt(plain: ByteArray): String?

    /** Opens a sealed copy; null on any failure (never throws). */
    fun decrypt(packedBase64: String): ByteArray?
}

/**
 * Production provider: an AES-256-GCM key in the Android Keystore
 * (alias `nota.notelock.devicekey`) with NO user-authentication
 * requirement on the key itself — exactly like 1.x, a successful
 * BiometricPrompt shown by the caller is the gate; the key merely keeps
 * the copy from being readable as plain stored text.
 */
class AndroidKeystoreNoteLockKeyProvider : NoteLockDeviceKeyProvider {

    override fun encrypt(plain: ByteArray): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey())
        val packed = cipher.iv + cipher.doFinal(plain)
        Base64.getEncoder().encodeToString(packed)
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
                // No setUserAuthenticationRequired: the biometric PROMPT
                // is the gate (1.x posture), the key must stay usable
                // immediately after it.
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ALIAS = "nota.notelock.devicekey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * The on-device NEK copy a successful fingerprint prompt may release
 * (Phase 2b) — modelled EXACTLY as 1.x `note-lock-crypto.ts`'s biometric
 * copy: the copy itself is the NEK sealed under a per-device key, it
 * lives only on this device (DataStore, keyed `notelock_bio_<noteId>`),
 * and it NEVER enters Room or backup data. A backup credential slot is
 * always required alongside it.
 *
 * Any failure reading the copy returns null (the caller falls back to a
 * credential) — this store never crashes the unlock path.
 */
class NoteLockBiometricStore(
    private val preferences: PreferencesRepository,
    private val keyProvider: NoteLockDeviceKeyProvider,
) {
    /** Stores the device copy; false when sealing/persisting failed. */
    suspend fun storeCopy(noteId: String, nek: ByteArray): Boolean {
        val packed = keyProvider.encrypt(nek) ?: return false
        preferences.setNoteLockBiometricCopy(noteId, packed)
        // safeEdit swallows IO failures by design, so confirm the copy
        // actually landed before reporting success (1.x mirrors this:
        // record.biometric = whether the copy actually stored).
        return preferences.getNoteLockBiometricCopy(noteId) == packed
    }

    /** Reads the device copy; null when absent or unreadable. */
    suspend fun readCopy(noteId: String): ByteArray? {
        val packed = try {
            preferences.getNoteLockBiometricCopy(noteId)
        } catch (e: Exception) {
            null
        } ?: return null
        return keyProvider.decrypt(packed)
    }

    suspend fun removeCopy(noteId: String) {
        preferences.removeNoteLockBiometricCopy(noteId)
    }
}
