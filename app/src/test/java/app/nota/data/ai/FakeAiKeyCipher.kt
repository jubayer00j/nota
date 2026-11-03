package app.nota.data.ai

import java.util.Base64

/** Reversible test cipher: "seals" by Base64 with a marker prefix,
 *  so tests can assert both that storage never holds the plaintext
 *  key AND that the sealed form round-trips. NOT secure — a test
 *  double for [AiKeyCipher], never shipped in main code. */
class FakeAiKeyCipher : AiKeyCipher {
    override fun encrypt(plain: ByteArray): String =
        PREFIX + Base64.getEncoder().encodeToString(plain)

    override fun decrypt(packedBase64: String): ByteArray? = try {
        if (!packedBase64.startsWith(PREFIX)) {
            null
        } else {
            Base64.getDecoder().decode(packedBase64.removePrefix(PREFIX))
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val PREFIX = "sealed:"
    }
}
