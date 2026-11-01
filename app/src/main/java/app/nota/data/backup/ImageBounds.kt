package app.nota.data.backup

import java.io.File

/**
 * Image dimension probing for restored attachments (Phase 2c): parses
 * just the header bytes of PNG / JPEG / GIF / BMP / WebP(VP8X) files.
 *
 * Deliberately NOT BitmapFactory: a bounds decode is a platform call
 * whose behaviour varies (and under Robolectric its shadow actually
 * consumes the probed file — caught by the crossover test), while the
 * header layouts are fixed and the probe only ever reads a bounded
 * prefix (64 KB) of the staged file.
 */
object ImageBounds {
    private const val MAX_HEADER_BYTES = 65_536

    fun read(file: File): Pair<Int?, Int?> {
        return try {
            val header = file.inputStream().buffered().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() < MAX_HEADER_BYTES) {
                    val n = input.read(buf, 0, minOf(buf.size, MAX_HEADER_BYTES - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            parse(header)
        } catch (e: Exception) {
            null to null
        }
    }

    internal fun parse(b: ByteArray): Pair<Int?, Int?> {
        if (b.size >= 24 && isPng(b)) {
            return be32(b, 16) to be32(b, 20)
        }
        if (b.size >= 10 && isGif(b)) {
            return le16(b, 6) to le16(b, 8)
        }
        if (b.size >= 26 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()) {
            return le32(b, 18) to le32(b, 22)?.let { kotlin.math.abs(it) }
        }
        if (b.size >= 30 && isWebp(b)) {
            // VP8X: canvas size is stored minus-one as 24-bit LE at 24/27.
            if (b[12] == 'V'.code.toByte() && b[15] == 'X'.code.toByte()) {
                val w = (le24(b, 24) ?: return null to null) + 1
                val h = (le24(b, 27) ?: return null to null) + 1
                return w to h
            }
            return null to null
        }
        if (b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) {
            return parseJpeg(b)
        }
        return null to null
    }

    private fun parseJpeg(b: ByteArray): Pair<Int?, Int?> {
        var i = 2
        while (i + 3 < b.size) {
            if (b[i] != 0xFF.toByte()) { i++; continue }
            val marker = b[i + 1].toInt() and 0xFF
            // Standalone markers carry no length (RSTn, SOI, EOI, TEM).
            if (marker in 0xD0..0xD9 || marker == 0x01) { i += 2; continue }
            val length = be16(b, i + 2) ?: return null to null
            if (length < 2) return null to null
            // SOF markers (C0..CF except DHT C4, JPG C8, DAC CC) hold the frame size.
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (i + 9 >= b.size) return null to null
                val height = be16(b, i + 5)
                val width = be16(b, i + 7)
                return width to height
            }
            i += 2 + length
        }
        return null to null
    }

    private fun isPng(b: ByteArray): Boolean {
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return sig.indices.all { b[it] == sig[it] }
    }

    private fun isGif(b: ByteArray): Boolean =
        b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()

    private fun isWebp(b: ByteArray): Boolean =
        b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    private fun be32(b: ByteArray, at: Int): Int? =
        if (at + 4 > b.size) null
        else ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun be16(b: ByteArray, at: Int): Int? =
        if (at + 2 > b.size) null
        else ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun le16(b: ByteArray, at: Int): Int? =
        if (at + 2 > b.size) null
        else (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Int? =
        if (at + 4 > b.size) null
        else (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun le24(b: ByteArray, at: Int): Int? =
        if (at + 3 > b.size) null
        else (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16)
}
