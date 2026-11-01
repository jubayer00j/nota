package app.nota.data.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex fix G4: the import downscale must actually enforce the
 * [AttachmentStore.MAX_IMAGE_DIM] cap. The old loop decoded a 4000x3000
 * photo at sample 1 (full size).
 */
class ImageSamplingTest {

    private val max = AttachmentStore.MAX_IMAGE_DIM

    @Test
    fun sampleSizeEnforcesCap() {
        assertEquals(2, ImageSampling.inSampleSize(4000, 3000, max))
        assertEquals(1, ImageSampling.inSampleSize(1600, 1200, max))
        // Exactly at the cap needs no sampling.
        assertEquals(1, ImageSampling.inSampleSize(2048, 2048, max))
        // Just over the cap on one dimension.
        assertEquals(2, ImageSampling.inSampleSize(2049, 100, max))
        assertEquals(2, ImageSampling.inSampleSize(100, 2049, max))
        assertEquals(4, ImageSampling.inSampleSize(8000, 1000, max))
        // Extreme aspect ratio: the long side decides.
        assertEquals(64, ImageSampling.inSampleSize(100_000, 100, max))
    }

    @Test
    fun sampledDimensionsNeverExceedCap() {
        val cases = listOf(
            4000 to 3000, 1600 to 1200, 2049 to 2049, 8000 to 1000,
            100_000 to 100, 100 to 100_000, 1 to 1, 2048 to 1,
        )
        for ((w, h) in cases) {
            val sample = ImageSampling.inSampleSize(w, h, max)
            assertTrue("sample must be a power of two", sample and (sample - 1) == 0)
            assertTrue(
                "ceil($w/$sample) <= $max",
                (w + sample - 1) / sample <= max && (h + sample - 1) / sample <= max,
            )
        }
    }

    @Test
    fun fitWithinScalesExactlyToCap() {
        assertEquals(2048 to 1536, ImageSampling.fitWithin(4000, 3000, max))
        assertEquals(1600 to 1200, ImageSampling.fitWithin(1600, 1200, max))
        assertEquals(2048 to 2048, ImageSampling.fitWithin(2048, 2048, max))
        // Extreme aspect: long side lands exactly on the cap, short side >= 1.
        val (w, h) = ImageSampling.fitWithin(100_000, 100, max)
        assertEquals(2048, w)
        assertTrue(h in 1..max)
        val (w2, h2) = ImageSampling.fitWithin(100, 100_000, max)
        assertEquals(2048, h2)
        assertTrue(w2 in 1..max)
    }
}
