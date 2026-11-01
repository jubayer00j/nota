package app.nota.data.backup

import app.nota.data.security.NoteLockCrypto
import app.nota.data.security.NoteLockMethod
import app.nota.data.security.NoteLockSlot
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * NOMAENC1 whole-archive encryption (Phase 2c) — a streaming port of 1.x
 * `backup-crypto.ts`, byte-exact in layout:
 *
 *   ASCII "NOMAENC1" (8) || uint32 BIG-ENDIAN header length ||
 *   header JSON {"v":1,"salt":…,"iterations":…,"wrappedKey":…} ||
 *   envelope JSON {"nomaEnc":1,"iv":…,"ct":…}
 *
 * The envelope seals the whole ZIP under a random 32-byte Backup Master
 * Key (BMK); the header's slot is a PASSWORD slot from PR-B's
 * [NoteLockCrypto.wrapNoteKey] (600k PBKDF2 iterations), reusing that
 * wrap/unwrap code unchanged. Plaintext ZIPs (starting "PK") always pass
 * through. Everything streams file-to-file: no whole-archive byte array
 * exists anywhere in this object.
 */
object BackupCrypto {
    const val MAGIC = "NOMAENC1"
    private const val BUFFER = 8192

    data class Header(val salt: String, val iterations: Int, val wrappedKey: String) {
        fun toSlot(): NoteLockSlot = NoteLockSlot(salt, iterations, wrappedKey)
        fun toJson(): String =
            "{\"v\":1,\"salt\":\"" + JsonPretty.escape(salt) +
                "\",\"iterations\":" + iterations +
                ",\"wrappedKey\":\"" + JsonPretty.escape(wrappedKey) + "\"}"
    }

    fun isEncryptedPrefix(prefix: ByteArray): Boolean =
        prefix.size >= MAGIC.length && String(prefix, 0, MAGIC.length, Charsets.US_ASCII) == MAGIC

    /** Creates the header for a fresh BMK under [passphrase]. Returns
     *  (header, bmk). The caller owns (and should zero) the BMK. */
    fun createHeader(passphrase: String, bmk: ByteArray): Header {
        val slot = NoteLockCrypto.wrapNoteKey(
            bmk, NoteLockMethod.PASSWORD, NoteLockCrypto.canonicalPassword(passphrase),
        )
        return Header(slot.salt, slot.iterations, slot.wrappedKey)
    }

    fun generateBmk(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** Unwraps the BMK; null on a wrong passphrase (GCM verdict, 1.x). */
    fun unwrapBmk(header: Header, passphrase: String): ByteArray? =
        NoteLockCrypto.unwrapNoteKey(
            header.toSlot(), NoteLockMethod.PASSWORD,
            NoteLockCrypto.canonicalPassword(passphrase),
        )

    // ------------------------------------------------------------- seal

    /**
     * Streams [zipFile] into [out] as a NOMAENC1 file: header first, then
     * the envelope JSON whose `ct` is the AES-256-GCM ciphertext of the
     * ZIP, Base64-encoded unbroken and streamed (cipher update chunks feed
     * a carry-aware encoder — see [StreamingBase64Encoder]).
     */
    fun seal(zipFile: File, passphrase: String, out: OutputStream) {
        val bmk = generateBmk()
        try {
            val header = createHeader(passphrase, bmk)
            val headerBytes = header.toJson().toByteArray(Charsets.UTF_8)
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.write(
                byteArrayOf(
                    (headerBytes.size ushr 24).toByte(), (headerBytes.size ushr 16).toByte(),
                    (headerBytes.size ushr 8).toByte(), headerBytes.size.toByte(),
                ),
            )
            out.write(headerBytes)

            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            out.write(
                ("{\"nomaEnc\":1,\"iv\":\"" + Base64.getEncoder().encodeToString(iv) + "\",\"ct\":\"")
                    .toByteArray(Charsets.US_ASCII),
            )
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(bmk, "AES"), GCMParameterSpec(128, iv))
            val encoder = StreamingBase64Encoder(out)
            zipFile.inputStream().buffered().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val enc = cipher.update(buf, 0, n)
                    if (enc != null && enc.isNotEmpty()) encoder.write(enc)
                }
                val tail = cipher.doFinal()
                if (tail.isNotEmpty()) encoder.write(tail)
            }
            encoder.finish()
            out.write("\"}".toByteArray(Charsets.US_ASCII))
            out.flush()
        } finally {
            bmk.fill(0)
        }
    }

    // ------------------------------------------------------------- open

    /** Result of reading the fixed-size NOMAENC1 prefix. */
    class OpenedEnvelope(val header: Header, val iv: ByteArray, val ctStream: CtStream)

    /**
     * A character-level reader over the envelope JSON remainder: it has
     * already consumed `{"nomaEnc":1,"iv":"…","ct":"` when constructed, and
     * [pumpDecoded] streams the still-Base64 `ct` characters into a
     * decoder until the closing quote.
     */
    class CtStream internal constructor(private val input: InputStream) {
        /** Decodes the whole ct into [sink]; returns at the closing quote. */
        fun pumpDecoded(sink: (ByteArray, Int, Int) -> Unit) {
            val decoder = StreamingBase64Decoder(sink)
            val reader = input.reader(Charsets.US_ASCII)
            while (true) {
                val ch = reader.read()
                if (ch < 0) throw BackupUnlockError(DAMAGED)
                if (ch == '"'.code) break
                decoder.feed(ch.toChar())
            }
            decoder.finish()
        }
    }

    private const val DAMAGED = "This encrypted backup couldn't be read — the file looks damaged."
    private const val UNOPENABLE =
        "This encrypted backup couldn't be opened — the passphrase may be wrong or the file is damaged."
    const val KEY_NEEDED = "This backup is encrypted. Enter your backup passphrase to continue."
    const val INCORRECT = "Incorrect passphrase"

    /**
     * Parses the NOMAENC1 prefix from [input] (positioned at byte 0,
     * magic already verified by the caller): reads the header length +
     * header JSON, unwraps the BMK with [passphrase] (null passphrase ->
     * [BackupKeyNeededError]; wrong -> [BackupUnlockError] BEFORE any
     * other work), then scans the envelope JSON prefix up to `ct`.
     * Returns the BMK + a positioned ct stream.
     */
    fun openEnvelope(input: InputStream, passphrase: String?): Pair<ByteArray, OpenedEnvelope> {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val lenBytes = ByteArray(4)
        readFully(buffered, lenBytes)
        val headerLen = ((lenBytes[0].toInt() and 0xFF) shl 24) or
            ((lenBytes[1].toInt() and 0xFF) shl 16) or
            ((lenBytes[2].toInt() and 0xFF) shl 8) or
            (lenBytes[3].toInt() and 0xFF)
        if (headerLen <= 0 || headerLen > 1_000_000) throw BackupUnlockError(DAMAGED)
        val headerBytes = ByteArray(headerLen)
        readFully(buffered, headerBytes)
        val header = try {
            val parsed = JSONObject(String(headerBytes, Charsets.UTF_8))
            if (parsed.optInt("v", -1) != 1) throw BackupUnlockError(DAMAGED)
            Header(
                salt = parsed.getString("salt"),
                iterations = parsed.getInt("iterations"),
                wrappedKey = parsed.getString("wrappedKey"),
            )
        } catch (e: BackupUnlockError) {
            throw e
        } catch (e: Exception) {
            throw BackupUnlockError(DAMAGED)
        }
        if (passphrase == null) throw BackupKeyNeededError(KEY_NEEDED)
        val bmk = unwrapBmk(header, passphrase) ?: throw BackupUnlockError(INCORRECT)

        // Scan the envelope JSON prefix incrementally: fields in writer
        // order (nomaEnc, iv, ct). Only iv is materialised (12 bytes);
        // the ct payload is never buffered.
        val reader = buffered.reader(Charsets.US_ASCII)
        val iv = try {
            expect(reader, "{\"nomaEnc\":1,\"iv\":\"")
            val ivText = readUntilQuote(reader)
            val decoded = Base64.getDecoder().decode(ivText)
            if (decoded.size != 12) throw BackupUnlockError(DAMAGED)
            expect(reader, ",\"ct\":\"")
            decoded
        } catch (e: BackupUnlockError) {
            bmk.fill(0)
            throw e
        } catch (e: Exception) {
            bmk.fill(0)
            throw BackupUnlockError(DAMAGED)
        }
        return bmk to OpenedEnvelope(header, iv, CtStream(ForwardingInputStream(reader)))
    }

    /**
     * Decrypts a positioned envelope fully into [zipOut] (a temp file),
     * verifying the GCM tag via doFinal — any failure raises
     * [BackupUnlockError] and the caller deletes the temp file. Only when
     * this returns has decryption succeeded; the DB is untouched until then.
     */
    fun decryptTo(envelope: OpenedEnvelope, bmk: ByteArray, zipOut: OutputStream) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(bmk, "AES"), GCMParameterSpec(128, envelope.iv))
        try {
            envelope.ctStream.pumpDecoded { bytes, offset, length ->
                val plain = cipher.update(bytes, offset, length)
                if (plain != null && plain.isNotEmpty()) zipOut.write(plain)
            }
            val tail = cipher.doFinal()
            if (tail.isNotEmpty()) zipOut.write(tail)
            zipOut.flush()
        } catch (e: BackupUnlockError) {
            throw e
        } catch (e: Exception) {
            throw BackupUnlockError(UNOPENABLE)
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var read = 0
        while (read < target.size) {
            val n = input.read(target, read, target.size - read)
            if (n < 0) throw BackupUnlockError(DAMAGED)
            read += n
        }
    }

    private fun expect(reader: java.io.Reader, literal: String) {
        for (c in literal) {
            val ch = reader.read()
            if (ch < 0 || ch.toChar() != c) throw BackupUnlockError(DAMAGED)
        }
    }

    private fun readUntilQuote(reader: java.io.Reader): String {
        val sb = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch < 0) throw BackupUnlockError(DAMAGED)
            if (ch == '"'.code) return sb.toString()
            sb.append(ch.toChar())
        }
    }

    /** Bridges a Reader back to an InputStream for CtStream's own reader
     *  (the envelope is pure ASCII, so char/byte mapping is identity). */
    private class ForwardingInputStream(private val reader: java.io.Reader) : InputStream() {
        override fun read(): Int = reader.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var i = 0
            while (i < len) {
                val ch = reader.read()
                if (ch < 0) return if (i == 0) -1 else i
                b[off + i] = ch.toByte()
                i++
            }
            return i
        }
    }
}
