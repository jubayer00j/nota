package app.nota.data.attachments

import kotlin.math.ceil

/**
 * Pure image-downscale maths for [AttachmentStore] (Codex fix G4).
 *
 * Kept free of Android types so the cap logic is unit-testable on the JVM:
 * the old inline loop doubled the sample while EITHER dimension halved
 * stayed >= the cap, which for a 4000x3000 photo stopped at sample 1
 * (3000 / 2 = 1500 < 2048) and decoded the image full-size.
 */
object ImageSampling {

    /**
     * Smallest power-of-two [BitmapFactory.Options.inSampleSize] such that
     * BOTH decoded dimensions are <= [maxDim]. Decoded dimensions are
     * estimated with ceiling division (decoders may round up), so the
     * estimate never under-predicts the real size.
     */
    fun inSampleSize(width: Int, height: Int, maxDim: Int): Int {
        require(width > 0 && height > 0 && maxDim > 0) { "dimensions must be positive" }
        var sample = 1
        while (ceilDiv(width, sample) > maxDim || ceilDiv(height, sample) > maxDim) {
            sample *= 2
        }
        return sample
    }

    /**
     * Exact target dimensions fitting [width]x[height] inside
     * [maxDim]x[maxDim], preserving aspect ratio; returns the input
     * unchanged when it already fits. Used as the post-decode exact scale:
     * power-of-two sampling alone can still leave a dimension above the
     * cap for extreme aspect ratios or decoder rounding.
     */
    fun fitWithin(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxDim > 0) { "dimensions must be positive" }
        val max = maxOf(width, height)
        if (max <= maxDim) return width to height
        val scale = maxDim.toDouble() / max.toDouble()
        val newWidth = ceil(width * scale).toInt().coerceIn(1, maxDim)
        val newHeight = ceil(height * scale).toInt().coerceIn(1, maxDim)
        return newWidth to newHeight
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
}
