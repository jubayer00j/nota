package app.nota.data.backup

import java.io.OutputStream
import java.util.Base64

/**
 * Carry-aware streaming Base64 (Phase 2c). The NOMAENC1 envelope and the
 * attachment data URLs are single UNBROKEN Base64 strings (a MIME encoder
 * would inject CRLF and corrupt the surrounding JSON), and neither may be
 * held whole in memory on a 3 GB device — so encoding/decoding runs in
 * small chunks with the partial group carried across calls:
 *
 * - [Encoder]: input is consumed in 3-byte groups (only whole groups are
 *   encoded per flush); [Encoder.finish] emits the padded tail.
 * - [Decoder]: input characters are consumed in 4-char groups; `=` padding
 *   is only legal in the final group, exactly like [Base64.getDecoder].
 */
class StreamingBase64Encoder(private val out: OutputStream) {
    private val encoder = Base64.getEncoder()
    private var carry = ByteArray(0)

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (length == 0) return
        val merged = if (carry.isEmpty()) {
            bytes.copyOfRange(offset, offset + length)
        } else {
            val m = ByteArray(carry.size + length)
            carry.copyInto(m, 0)
            bytes.copyInto(m, carry.size, offset, offset + length)
            m
        }
        val whole = merged.size - (merged.size % 3)
        if (whole > 0) {
            out.write(encoder.encodeToString(merged.copyOfRange(0, whole)).toByteArray(Charsets.US_ASCII))
        }
        carry = merged.copyOfRange(whole, merged.size)
    }

    fun finish() {
        if (carry.isNotEmpty()) {
            out.write(encoder.encodeToString(carry).toByteArray(Charsets.US_ASCII))
            carry = ByteArray(0)
        }
        out.flush()
    }
}

/**
 * Streaming Base64 decoder feeding decoded bytes to [sink]. Characters
 * are fed one JSON-string character at a time by the caller (the string
 * may be a data URL body or an envelope `ct`), so [feed] also absorbs the
 * data-URL header: everything before [dataUrlCommaSeen] is not possible
 * here — callers that parse data URLs use [DataUrlBase64Sink] instead.
 */
class StreamingBase64Decoder(private val sink: (ByteArray, Int, Int) -> Unit) {
    private val decoder = Base64.getDecoder()
    private val group = CharArray(4)
    private var groupLen = 0
    private var finished = false

    fun feed(c: Char) {
        check(!finished) { "Base64 data after padding" }
        require(c == '=' || c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/') {
            "Illegal Base64 character: $c"
        }
        group[groupLen++] = c
        if (groupLen == 4) {
            val decoded = decoder.decode(String(group))
            sink(decoded, 0, decoded.size)
            if (group.contains('=')) finished = true
            groupLen = 0
        }
    }

    fun feedAll(text: CharSequence) {
        for (c in text) feed(c)
    }

    fun finish() {
        check(groupLen == 0) { "Truncated Base64 data" }
    }
}

/**
 * Consumes the characters of a data URL (`data:<mime>;base64,<payload>`)
 * one at a time: discards the header up to the first comma, then streams
 * the Base64 payload through [StreamingBase64Decoder] into [sink].
 */
class DataUrlBase64Sink(sink: (ByteArray, Int, Int) -> Unit) {
    private val decoder = StreamingBase64Decoder(sink)
    private var seenComma = false

    fun feed(c: Char) {
        if (!seenComma) {
            if (c == ',') seenComma = true
            return
        }
        decoder.feed(c)
    }

    fun finish() {
        check(seenComma) { "Data URL has no payload comma" }
        decoder.finish()
    }
}
