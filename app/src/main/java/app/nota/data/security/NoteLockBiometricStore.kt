package app.nota.data.security

import android.os.Build
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
 * 0.3.1. v2-sealed copies cannot be opened by a v3 cipher, so they
 * are orphaned by this upgrade — the record's credential slots are
 * untouched, the user unlocks once with their backup credential
 * and re-enables fingerprint, which seals a fresh v3 copy. (Phase
 * 2b copies were already orphaned the same way by the v2 upgrade.)
 *
 * PR #55 (Argon F-06): the flag is now set EXPLICITLY from
 * [BiometricEnrollmentPolicy] — `false` below API 28, `true` on
 * API 28+. The v3 key as originally generated omitted the setter,
 * which did NOT disable enrolment invalidation on API 28+ (the
 * platform default is `true`); key parameters are fixed at
 * generation, so existing v3 keys keep the behaviour they were
 * created with and only newly minted keys take the explicit
 * value — no migration. See BiometricEnrollmentPolicy's doc.
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
        // PR #55 (Argon F-10): the orphaned v1/v2 aliases were
        // never deleted — dead keys linger in the Keystore
        // forever. Remove them opportunistically whenever the
        // provider loads; deleting an alias nothing can use any
        // more is always safe.
        deleteLegacyAliases(keyStore)
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
                    // Explicit on every API level (PR #55, Argon
                    // F-06): see BiometricEnrollmentPolicy — the
                    // platform default is `true`, so omitting this
                    // setter never disabled enrolment invalidation
                    // on API 28+.
                    .setInvalidatedByBiometricEnrollment(
                        BiometricEnrollmentPolicy.invalidatedByBiometricEnrollment(
                            Build.VERSION.SDK_INT,
                        ),
                    )
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

        /**
         * Deletes the orphaned pre-v3 aliases from [keyStore]
         * (PR #55, Argon F-10). Best-effort per alias: a keystore
         * that refuses a deletion keeps a dead key, nothing more.
         * Takes the KeyStore as a parameter so the behaviour is
         * unit-testable against an in-memory keystore.
         */
        fun deleteLegacyAliases(keyStore: KeyStore) {
            for (alias in listOf(LEGACY_ALIAS, PRIOR_ALIAS)) {
                runCatching {
                    if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
                }
            }
        }
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
    private val provider: app.nota.data.db.DatabaseProvider,
) {
    /** The account whose library is active — copies are stored per
     *  account (Phase 3a, Codex P2), resolved at call time so an
     *  account switch can never read or clobber another library's
     *  fingerprint copy for the same note id. */
    private fun accountId(): String = provider.accountId.value

    /** The sealed copy [openCipherResult] most recently read,
     *  held only until [readCopy] completes the flow (PR #55,
     *  Argon F-10). */
    private data class PendingCopy(
        val accountId: String,
        val noteId: String,
        val packed: String,
    )

    @Volatile
    private var pendingCopy: PendingCopy? = null

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
        preferences.setNoteLockBiometricCopy(accountId(), noteId, packed)
        // safeEdit swallows IO failures by design, so confirm the copy
        // actually landed before reporting success.
        return preferences.getNoteLockBiometricCopy(accountId(), noteId) == packed
    }

    /**
     * Cipher for an UNLOCK prompt for [noteId] (Phase 2.6: typed —
     * a missing copy and a dead key are different failures and the
     * gate shows which one happened).
     */
    suspend fun openCipherResult(noteId: String): StepResult<Cipher> {
        val account = accountId()
        val packed = try {
            preferences.getNoteLockBiometricCopy(account, noteId)
        } catch (e: Exception) {
            return StepResult.Failed(BiometricStep.TOKEN_READ, stepReason(e))
        } ?: return StepResult.Failed(
            BiometricStep.TOKEN_READ,
            "no fingerprint copy is stored for this note",
        )
        // PR #55 (Argon F-10): the sealed bytes are read ONCE per
        // unlock flow. readCopy used to re-read the preference —
        // a second read that could observe a different value than
        // the one this cipher was built from (a copy replaced
        // mid-flow would fail the GCM check: fail-closed, but a
        // spurious failure). The flow now decrypts exactly the
        // bytes the cipher was opened for.
        pendingCopy = PendingCopy(account, noteId, packed)
        return keyProvider.openCipherResult(packed)
    }

    /**
     * Reads the device copy BY COMPLETING the decryption with the
     * prompt-authorised [cipher]; null when absent or unreadable.
     * The GCM tag check inside doFinal is the unlock proof.
     *
     * Decrypts the copy [openCipherResult] read for this exact
     * (account, note) pair — a [readCopy] with no matching open,
     * or for another note or account, fails closed with null.
     */
    suspend fun readCopy(noteId: String, cipher: Cipher): ByteArray? {
        val pending = pendingCopy
        if (pending == null || pending.noteId != noteId || pending.accountId != accountId()) {
            return null
        }
        val packed = pending.packed
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
        val account = accountId()
        // A removed copy must not linger as this flow's pending
        // bytes (PR #55, Argon F-10).
        pendingCopy?.let { if (it.noteId == noteId && it.accountId == account) pendingCopy = null }
        preferences.removeNoteLockBiometricCopy(account, noteId)
    }
}
