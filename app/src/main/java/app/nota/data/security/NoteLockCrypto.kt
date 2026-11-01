package app.nota.data.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-note encryption primitives (Phase 2b) — a direct port of 1.x
 * `note-lock-crypto.ts`, kept pure-JVM (JCE only) so the cross-implementation
 * fixture tests can pin it byte-for-byte:
 *
 * - Each locked note gets a random 32-byte Note Encryption Key (NEK).
 *   The note body and every attachment payload are sealed with
 *   AES-256-GCM under the NEK (12-byte random IV, 128-bit tag appended
 *   to the ciphertext — JCE's default output layout matches WebCrypto).
 * - The content envelope is the JSON string built EXACTLY as
 *   `{"nomaEnc":1,"iv":"<b64>","ct":"<b64>"}` (the string is constructed
 *   manually; key order matters for byte-parity of produced envelopes).
 *   NOTE: an older design doc described the envelope as
 *   `{"enc":"noma-note-v1",…}` — the SHIPPED 1.x code uses `nomaEnc`,
 *   and shipped code wins.
 * - A credential slot wraps the NEK under a KEK derived via
 *   PBKDF2-HMAC-SHA256 — 1.x's slot code imports the same
 *   `deriveKeyHash` from app-lock-crypto, so this port reuses
 *   [AppLockCrypto.deriveKeyHash] unchanged. wrappedKey packs the wrap
 *   envelope as b64( iv(12) || ct ).
 * - Attachment payload plaintext is the attachment's DATA URL string
 *   (`data:<mime>;base64,<bytes>`), NOT raw bytes: 1.x attachments ARE
 *   data URLs, so a Nota-sealed attachment decrypts to a data URL that
 *   1.x can read straight from a backup.
 * - All Base64 via [java.util.Base64] (byte-identical to web btoa/atob
 *   for this alphabet, and not an Android stub under JVM tests).
 */
object NoteLockCrypto {

    /** Iteration counts are the 1.x NOTE_LOCK_ITERATIONS values, shared
     *  with App Lock via [AppLockCrypto.iterationsFor]. */
    fun iterationsFor(method: NoteLockMethod): Int =
        AppLockCrypto.iterationsFor(method.appLockMethod)

    // ------------------------------------------------------------ secrets

    /** Canonical secret forms — exact 1.x strings (see AppLockCrypto). */
    fun canonicalPassword(raw: String): String = AppLockCrypto.canonicalPassword(raw)

    fun canonicalPin(digits: String): String = AppLockCrypto.canonicalPin(digits)

    fun canonicalPattern(points: List<Int>): String = AppLockCrypto.canonicalPattern(points)

    /**
     * 1.x `canonicalNoteSecret(method, secret)`: for pattern the secret is
     * the point list, for PIN the digit string, for password the raw text.
     * Repository/UI call sites pass CANONICAL secrets (built with the three
     * helpers above — the shared credential components already report
     * canonical form), so wrap/unwrap below take canonical strings.
     */
    fun canonicalNoteSecret(method: NoteLockMethod, rawSecret: String, patternPoints: List<Int>? = null): String =
        when (method) {
            NoteLockMethod.PASSWORD -> canonicalPassword(rawSecret)
            NoteLockMethod.PIN -> canonicalPin(rawSecret)
            NoteLockMethod.PATTERN -> canonicalPattern(patternPoints ?: emptyList())
        }

    fun isValidPin(pin: String): Boolean = pin.matches(Regex("^\\d{4,6}$"))

    /**
     * Validates a CANONICAL secret for slot creation (1.x lockNote's
     * caller-side rules): password ≥ 8 chars, PIN `^\d{4,6}$` once the
     * canonical prefix is stripped, pattern ≥ 4 points.
     */
    fun isValidCanonicalSecret(method: NoteLockMethod, canonicalSecret: String): Boolean =
        when (method) {
            NoteLockMethod.PASSWORD -> canonicalSecret.length >= 8
            NoteLockMethod.PIN -> canonicalSecret.startsWith("noma_pin_v1:") &&
                isValidPin(canonicalSecret.removePrefix("noma_pin_v1:"))
            NoteLockMethod.PATTERN -> canonicalSecret.startsWith("noma_pattern_v1:") &&
                canonicalSecret.removePrefix("noma_pattern_v1:")
                    .split("-").count { it.isNotEmpty() } >= 4
        }

    // ------------------------------------------------------------ NEK

    fun generateNoteKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    // ------------------------------------------------------------ envelopes

    private val ivField = Regex(""""iv"\s*:\s*"([^"]*)"""")
    private val ctField = Regex(""""ct"\s*:\s*"([^"]*)"""")

    private fun b64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun b64Decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    /** Builds the envelope string manually — key order is part of the format. */
    private fun envelopeString(iv: ByteArray, ct: ByteArray): String =
        "{\"nomaEnc\":1,\"iv\":\"" + b64Encode(iv) + "\",\"ct\":\"" + b64Encode(ct) + "\"}"

    private fun parseEnvelope(envelopeJson: String): Pair<ByteArray, ByteArray>? {
        if (!isEncryptedContent(envelopeJson)) return null
        val iv = ivField.find(envelopeJson)?.groupValues?.get(1) ?: return null
        val ct = ctField.find(envelopeJson)?.groupValues?.get(1) ?: return null
        return try {
            b64Decode(iv) to b64Decode(ct)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** True when a stored payload is an encryption envelope (1.x `isEncryptedContent`). */
    fun isEncryptedContent(value: String): Boolean {
        if (!value.startsWith("{\"nomaEnc\":1")) return false
        val iv = ivField.find(value)?.groupValues?.get(1) ?: return false
        val ct = ctField.find(value)?.groupValues?.get(1) ?: return false
        return iv.isNotEmpty() && ct.isNotEmpty()
    }

    private fun aesGcm(mode: Int, keyBytes: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        require(keyBytes.size == 32) { "AES-256 key must be 32 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(input)
    }

    /** Seals [data] under [keyBytes] with a fresh random 12-byte IV. */
    fun encryptBytes(keyBytes: ByteArray, data: ByteArray): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return encryptBytesWithIv(keyBytes, data, iv)
    }

    /** Deterministic-IV seal — fixture/test seam only. */
    internal fun encryptBytesWithIv(keyBytes: ByteArray, data: ByteArray, iv: ByteArray): String {
        require(iv.size == 12) { "GCM IV must be 12 bytes" }
        return envelopeString(iv, aesGcm(Cipher.ENCRYPT_MODE, keyBytes, iv, data))
    }

    /** Opens an envelope. Throws on a wrong key, tampering or malformed input. */
    fun decryptBytes(keyBytes: ByteArray, envelopeJson: String): ByteArray {
        val (iv, ct) = parseEnvelope(envelopeJson)
            ?: throw IllegalArgumentException("Not an encryption envelope")
        return aesGcm(Cipher.DECRYPT_MODE, keyBytes, iv, ct)
    }

    fun encryptText(keyBytes: ByteArray, plaintext: String): String =
        encryptBytes(keyBytes, plaintext.toByteArray(Charsets.UTF_8))

    fun decryptText(keyBytes: ByteArray, envelopeJson: String): String =
        String(decryptBytes(keyBytes, envelopeJson), Charsets.UTF_8)

    /** Packs an envelope as b64( iv || ct ) — the slot/biometric-copy form. */
    fun packEnvelope(envelopeJson: String): String? {
        val (iv, ct) = parseEnvelope(envelopeJson) ?: return null
        return b64Encode(iv + ct)
    }

    /** Rebuilds an envelope string from its packed b64( iv || ct ) form. */
    fun envelopeFromPacked(packedBase64: String): String? {
        val packed = try {
            b64Decode(packedBase64)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (packed.size <= 12) return null
        return envelopeString(packed.copyOfRange(0, 12), packed.copyOfRange(12, packed.size))
    }

    // ------------------------------------------------------------ slots

    /** Wraps the NEK under one credential, producing a slot (fresh salt + IV). */
    fun wrapNoteKey(nek: ByteArray, method: NoteLockMethod, canonicalSecret: String): NoteLockSlot =
        wrapNoteKey(nek, method, canonicalSecret, AppLockCrypto.generateSalt(), null)

    /** Deterministic salt/IV wrap — fixture/test seam only. */
    internal fun wrapNoteKey(
        nek: ByteArray,
        method: NoteLockMethod,
        canonicalSecret: String,
        saltBase64: String,
        iv: ByteArray?,
    ): NoteLockSlot {
        val iterations = iterationsFor(method)
        val kek = b64Decode(AppLockCrypto.deriveKeyHash(canonicalSecret, saltBase64, iterations))
        val envelope = if (iv != null) {
            encryptBytesWithIv(kek, nek, iv)
        } else {
            encryptBytes(kek, nek)
        }
        val packed = packEnvelope(envelope)
            ?: throw IllegalStateException("Fresh envelope did not pack")
        return NoteLockSlot(salt = saltBase64, iterations = iterations, wrappedKey = packed)
    }

    /**
     * Unwraps the NEK with one credential. Returns null when the
     * credential is wrong (AES-GCM authentication failure) — never
     * throws for a bad secret (1.x contract).
     */
    fun unwrapNoteKey(slot: NoteLockSlot, method: NoteLockMethod, canonicalSecret: String): ByteArray? {
        return try {
            val kek = b64Decode(
                AppLockCrypto.deriveKeyHash(canonicalSecret, slot.salt, slot.iterations),
            )
            val envelope = envelopeFromPacked(slot.wrappedKey) ?: return null
            decryptBytes(kek, envelope)
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ data URLs

    /** The 1.x attachment payload form: `data:<mime>;base64,<bytes>`. */
    fun buildDataUrl(mimeType: String, bytes: ByteArray): String =
        "data:" + mimeType + ";base64," + b64Encode(bytes)

    /** Parses a data URL back to (mimeType, bytes); null when malformed. */
    fun parseDataUrl(dataUrl: String): Pair<String, ByteArray>? {
        if (!dataUrl.startsWith("data:")) return null
        val marker = ";base64,"
        val markerAt = dataUrl.indexOf(marker)
        if (markerAt < 0) return null
        val mime = dataUrl.substring(5, markerAt).ifEmpty { "application/octet-stream" }
        val bytes = try {
            b64Decode(dataUrl.substring(markerAt + marker.length))
        } catch (e: IllegalArgumentException) {
            return null
        }
        return mime to bytes
    }
}
