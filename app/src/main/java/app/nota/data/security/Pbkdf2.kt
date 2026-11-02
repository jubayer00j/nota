package app.nota.data.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2-HMAC-SHA256 (RFC 2898 / PKCS #5) as a manual loop over the
 * platform `Mac.getInstance("HmacSHA256")` (Phase 2.6, Fix B).
 *
 * WHY NOT `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`:
 * on Android 8.1 (the Galaxy J7 Prime) the provider behind that
 * factory derives SO slowly that every App Lock / per-note unlock
 * took seconds at the 1.x iteration counts (100k–600k), while 1.x —
 * deriving the very same counts through WebCrypto's native PBKDF2 —
 * feels instant. Measurements put the wall time inside the KDF
 * provider, not the iteration count, the threading (verification
 * already ran on Dispatchers.Default) or duplicate verification
 * (the audit found exactly one derivation per unlock attempt on
 * every surface). A tight HMAC loop has a fraction of the factory
 * path's per-iteration overhead on that provider.
 *
 * BYTE PARITY IS THE CONTRACT: this implementation must derive
 * bit-identical output to the factory path it replaces, because
 * stored verifiers, note-lock slots and NOMAENC1 backup headers
 * created by BOTH Nota ≤ 0.3.1 and 1.x (WebCrypto) must keep
 * verifying. The password is encoded UTF-8 — the WebCrypto
 * (TextEncoder) semantics 1.x used, and the encoding the existing
 * known-answer vectors (Python `hashlib.pbkdf2_hmac`) pin.
 * [app.nota.data.security.Pbkdf2Test] proves equivalence against
 * the legacy factory over randomised inputs in CI.
 */
object Pbkdf2 {

    private const val HMAC_SHA256 = "HmacSHA256"

    /**
     * Derives [keyLengthBytes] bytes from [password] + [salt] with
     * [iterations] rounds of HMAC-SHA256. The caller owns (and
     * should zero) [password]; intermediate block material is
     * zeroed here before returning.
     */
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int = 32,
    ): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        require(keyLengthBytes > 0) { "keyLengthBytes must be positive" }
        // HMAC accepts any key length; an EMPTY key is the one shape
        // SecretKeySpec rejects. HMAC pads keys shorter than the
        // 64-byte block with zeros, so a single zero byte produces
        // the identical padded key — and identical output — as an
        // empty key would. (Nota's secrets are never empty; this is
        // belt-and-braces for parity with the factory path.)
        val keyBytes = if (password.isEmpty()) byteArrayOf(0) else password
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(keyBytes, HMAC_SHA256))
        val hashLen = mac.macLength // 32 for SHA-256
        val blockCount = (keyLengthBytes + hashLen - 1) / hashLen
        val derived = ByteArray(keyLengthBytes)
        // SALT || INT_32_BE(block index), reused across blocks.
        val blockSalt = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, blockSalt, 0, salt.size)
        var offset = 0
        for (block in 1..blockCount) {
            blockSalt[salt.size] = (block ushr 24).toByte()
            blockSalt[salt.size + 1] = (block ushr 16).toByte()
            blockSalt[salt.size + 2] = (block ushr 8).toByte()
            blockSalt[salt.size + 3] = block.toByte()
            // U_1 = PRF(password, SALT || INT(block)); T starts as U_1.
            var u = mac.doFinal(blockSalt)
            val t = u.copyOf()
            // U_c = PRF(password, U_{c-1}); T ^= U_c. Mac.doFinal
            // resets the Mac to its post-init state each call, so
            // the same instance carries the whole chain.
            for (round in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }
            val take = minOf(hashLen, keyLengthBytes - offset)
            System.arraycopy(t, 0, derived, offset, take)
            offset += take
            t.fill(0)
            u.fill(0)
        }
        blockSalt.fill(0)
        return derived
    }
}
