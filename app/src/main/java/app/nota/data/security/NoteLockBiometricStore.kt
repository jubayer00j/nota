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
 * Device-key access for the per-note biometric copy (Phase 2b,
 * crypto-bound in Phase 2.5/F4), behind an interface so
 * JVM/Robolectric tests can inject a raw-key provider
 * (AndroidKeyStore under Robolectric is unreliable, and
 * auth-required keys cannot be exercised there at all).
 *
 * Sealed form is b64( iv(12) || ct ) with the GCM tag appended, the
 * same packing as a credential slot's wrappedKey.
 *
 * Phase 2.5 change: the production key is now USER-AUTHENTICATION
 * REQUIRED, so there is no usable bare encrypt/decrypt any more —
 * sealing and opening both run through ciphers that BiometricPrompt
 * authorises via a CryptoObject ([sealCipherResult] /
 * [openCipherResult]).
 * [encrypt]/[decrypt] remain on the interface ONLY as the test-fake
 * seam and a legacy probe; the production provider implements them
 * against the auth-required key, where they fail (return null)
 * without an authorised prompt — by design.
 *
 * Phase 2.6 (Fix A): the cipher factories return [StepResult] —
 * the same silent-null failure that killed App Lock fingerprint
 * enabling lived here too, and a failed step must carry its reason
 * to the surface that asked for it.
 */
interface NoteLockDeviceKeyProvider {
    /** Seals [plain] under the device key; null on any failure. */
    fun encrypt(plain: ByteArray): String?

    /** Opens a sealed copy; null on any failure (never throws). */
    fun decrypt(packedBase64: String): ByteArray?

    /** ENCRYPT_MODE cipher to be authorised by a biometric prompt. */
    fun sealCipherResult(): StepResult<Cipher>

    /** DECRYPT_MODE cipher for [packedBase64], to be authorised by a prompt. */
    fun openCipherResult(packedBase64: String): StepResult<Cipher>
}

/**
 * Production provider: an AES-256-GCM key in the Android Keystore
 * with `setUserAuthenticationRequired(true)`.
 *
 * The alias is NEW (`nota.notelock.devicekey.v3`): the v2 key
 * (Phase 2.5) was generated WITH
 * `setInvalidatedByBiometricEnrollment(true)`, which on Samsung's
 * API-27 keystore leaves keys permanently invalidated from birth —
 * the same defect that killed App Lock fingerprint enabling in
 * 0.3.1 (see AndroidKeystoreAppLockKeyProvider's doc for the named
 * tradeoff of dropping the flag). v2-sealed copies cannot be
 * opened by a v3 cipher, so they are orphaned by this upgrade —
 * the record's credential slots are untouched, the user unlocks
 * once with their backup credential and re-enables fingerprint,
 * which seals a fresh v3 copy. (Phase 2b copies were already
 * orphaned the same way by the v2 upgrade.)
 */
class AndroidKeystoreNoteLockKeyProvider : NoteLockDeviceKeyProvider {

    override fun encrypt(plain: ByteArray): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey())
        val packed = cipher.iv + cipher.doFinal(plain)
        Base64.getEncoder().encodeToString(packed)
    } catch (e: Exception) {
        // Includes UserNotAuthenticatedException: without a prompt-
        // authorised cipher this MUST fail under the auth-required key.
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

    override fun sealCipherResult(): StepResult<Cipher> {
        val key = when (val loaded = loadKey()) {
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
            return StepResult.Failed(BiometricStep.TOKEN_READ, "stored copy is not valid Base64")
        }
        if (packed.size <= 12) {
            return StepResult.Failed(BiometricStep.TOKEN_READ, "stored copy is truncated")
        }
        val key = when (val loaded = loadKey()) {
            is StepResult.Ok -> loaded.value
            is StepResult.Failed -> return loaded
        }
        return try {
            StepResult.Ok(
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        key,
                        GCMParameterSpec(128, packed.copyOfRange(0, 12)),
                    )
                },
            )
        } catch (e: Exception) {
            StepResult.Failed(BiometricStep.CIPHER_INIT, stepReason(e))
        }
    }

    /** The raw key for the legacy encrypt/decrypt probe paths. */
    private fun deviceKey(): SecretKey =
        when (val loaded = loadKey()) {
            is StepResult.Ok -> loaded.value
            is StepResult.Failed -> throw IllegalStateException(loaded.reason)
        }

    private fun loadKey(): StepResult<SecretKey> {
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
        const val ALIAS = "nota.notelock.devicekey.v3"

        /** Phase 2.5 alias — orphaned in 2.6 (see class doc). */
        const val PRIOR_ALIAS = "nota.notelock.devicekey.v2"

        /** Phase 2b alias — orphaned by the crypto-binding upgrade. */
        const val LEGACY_ALIAS = "nota.notelock.devicekey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * The on-device NEK copy a crypto-bound fingerprint prompt may
 * release (Phase 2b, reworked Phase 2.5) — the copy itself is the
 * NEK sealed under the per-device auth-required key, it lives only
 * on this device (DataStore, keyed `notelock_bio_<noteId>`), and it
 * NEVER enters Room or backup data. A backup credential slot is
 * always required alongside it.
 *
 * Unlock discipline (the Phase 2.5 fix): previously the flow was
 * "prompt returns success → [readCopy] decrypts separately" — a bare
 * boolean gate in front of a freely usable key. Now the sealed NEK
 * cipher IS the prompt's CryptoObject: [openCipherResult] builds the
 * DECRYPT cipher, the prompt authorises it, and [readCopy] completes
 * the decryption with that authorised cipher. No authorised cipher,
 * no NEK — a spoofed prompt success releases nothing.
 *
 * Any failure reading the copy returns null (the caller falls back
 * to a credential) — this store never crashes the unlock path.
 */
class NoteLockBiometricStore(
    private val preferences: PreferencesRepository,
    private val keyProvider: NoteLockDeviceKeyProvider,
) {
    /** Cipher for an ENABLING prompt (seal direction). */
    fun sealCipherResult(): StepResult<Cipher> = keyProvider.sealCipherResult()

    /**
     * Stores the device copy BY COMPLETING the seal with the
     * prompt-authorised [cipher]; false when sealing/persisting failed.
     */
    suspend fun storeCopy(noteId: String, nek: ByteArray, cipher: Cipher): Boolean {
        val packed = try {
            Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(nek))
        } catch (e: Exception) {
            return false
        }
        preferences.setNoteLockBiometricCopy(noteId, packed)
        // safeEdit swallows IO failures by design, so confirm the copy
        // actually landed before reporting success.
        return preferences.getNoteLockBiometricCopy(noteId) == packed
    }

    /**
     * Cipher for an UNLOCK prompt for [noteId] (Phase 2.6: typed —
     * a missing copy and a dead key are different failures and the
     * gate shows which one happened).
     */
    suspend fun openCipherResult(noteId: String): StepResult<Cipher> {
        val packed = try {
            preferences.getNoteLockBiometricCopy(noteId)
        } catch (e: Exception) {
            return StepResult.Failed(BiometricStep.TOKEN_READ, stepReason(e))
        } ?: return StepResult.Failed(
            BiometricStep.TOKEN_READ,
            "no fingerprint copy is stored for this note",
        )
        return keyProvider.openCipherResult(packed)
    }

    /**
     * Reads the device copy BY COMPLETING the decryption with the
     * prompt-authorised [cipher]; null when absent or unreadable.
     * The GCM tag check inside doFinal is the unlock proof.
     */
    suspend fun readCopy(noteId: String, cipher: Cipher): ByteArray? {
        val packed = try {
            preferences.getNoteLockBiometricCopy(noteId)
        } catch (e: Exception) {
            null
        } ?: return null
        val raw = try {
            Base64.getDecoder().decode(packed)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (raw.size <= 12) return null
        return try {
            cipher.doFinal(raw.copyOfRange(12, raw.size))
        } catch (e: Exception) {
            null
        }
    }

    suspend fun removeCopy(noteId: String) {
        preferences.removeNoteLockBiometricCopy(noteId)
    }
}
