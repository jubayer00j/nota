package app.nota.data.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5.2: the 1.x `normalizeSharePayload` port (capture.ts) —
 * title rules, the bare-URL link rule, the caps, image filtering
 * and the degrade-to-new-note rule for empty payloads.
 */
class ShareNormalizerTest {

    private fun image(
        name: String = "photo.jpg",
        mimeType: String = "image/jpeg",
        sizeBytes: Long = 1024L,
        fileName: String = "img-0.jpg",
    ) = RawShareImage(name, mimeType, sizeBytes, fileName)

    private fun share(request: CaptureRequest): CaptureRequest.Share {
        assertTrue("expected Share, got $request", request is CaptureRequest.Share)
        return request as CaptureRequest.Share
    }

    @Test
    fun textOnly_becomesShareWithParagraphedBody() {
        val request = ShareNormalizer.normalize(
            RawSharePayload(subject = null, text = "Hello\n\nWorld", images = emptyList()),
            id = "cap-1",
        )
        val result = share(request)
        assertEquals("", result.title)
        assertEquals("<p>Hello</p><p>World</p>", result.contentHtml)
        assertEquals(CaptureSource.ANDROID_SHARE, result.source)
        assertTrue(result.images.isEmpty())
    }

    @Test
    fun subjectBecomesTheTitle() {
        val result = share(
            ShareNormalizer.normalize(
                RawSharePayload(subject = "  Page title  ", text = "body", images = emptyList()),
                id = "cap-2",
            ),
        )
        assertEquals("Page title", result.title)
        assertEquals("<p>body</p>", result.contentHtml)
    }

    @Test
    fun titleIsCappedAt200Chars() {
        val result = share(
            ShareNormalizer.normalize(
                RawSharePayload(subject = "x".repeat(250), text = "b", images = emptyList()),
                id = "cap-3",
            ),
        )
        assertEquals(200, result.title.length)
    }

    @Test
    fun bareUrlText_isALinkShare_bodyIsTheUrl() {
        val result = share(
            ShareNormalizer.normalize(
                RawSharePayload(
                    subject = "Shared link",
                    text = "https://example.com/article",
                    images = emptyList(),
                ),
                id = "cap-4",
            ),
        )
        assertEquals("<p>https://example.com/article</p>", result.contentHtml)
    }

    @Test
    fun textContainingAUrl_keepsTheTextVerbatim() {
        val result = share(
            ShareNormalizer.normalize(
                RawSharePayload(
                    subject = null,
                    text = "read this https://example.com later",
                    images = emptyList(),
                ),
                id = "cap-5",
            ),
        )
        assertEquals("<p>read this https://example.com later</p>", result.contentHtml)
    }

    @Test
    fun bodyTextIsHtmlEscaped() {
        val result = share(
            ShareNormalizer.normalize(
                RawSharePayload(subject = null, text = "<b>bold</b> & co", images = emptyList()),
                id = "cap-6",
            ),
        )
        assertEquals("<p>&lt;b&gt;bold&lt;/b&gt; &amp; co</p>", result.contentHtml)
    }

    @Test
    fun textIsCappedAt100kChars() {
        val result = share(
            ShareNormalizer.normalize(
                RawSharePayload(subject = null, text = "a".repeat(100_001), images = emptyList()),
                id = "cap-7",
            ),
        )
        assertEquals("<p>" + "a".repeat(100_000) + "</p>", result.contentHtml)
    }

    @Test
    fun images_filteredByMimeSizeAndCount() {
        val images = mutableListOf<RawShareImage>()
        // 11 good images — only 10 survive.
        for (i in 0 until 11) {
            images += image(name = "p$i.jpg", fileName = "img-$i.jpg")
        }
        images += image(name = "clip.mp4", mimeType = "video/mp4", fileName = "img-11.mp4")
        images += image(
            name = "huge.jpg",
            sizeBytes = ShareNormalizer.MAX_IMAGE_BYTES + 1,
            fileName = "img-12.jpg",
        )
        val result = share(
            ShareNormalizer.normalize(
                RawSharePayload(subject = null, text = null, images = images),
                id = "cap-8",
            ),
        )
        assertEquals(10, result.images.size)
        assertTrue(result.images.all { it.mimeType.startsWith("image/") })
    }

    @Test
    fun imageWithBlankName_fallsBackToImage() {
        val result = share(
            ShareNormalizer.normalize(
                RawSharePayload(subject = null, text = null, images = listOf(image(name = "  "))),
                id = "cap-9",
            ),
        )
        assertEquals("image", result.images.single().name)
    }

    @Test
    fun imagesOnly_isAShareWithEmptyBody() {
        val result = share(
            ShareNormalizer.normalize(
                RawSharePayload(subject = null, text = null, images = listOf(image())),
                id = "cap-10",
            ),
        )
        assertEquals("", result.contentHtml)
        assertEquals(1, result.images.size)
    }

    @Test
    fun emptyPayload_degradesToNewNote() {
        val request = ShareNormalizer.normalize(
            RawSharePayload(subject = "  ", text = "   ", images = emptyList()),
            id = "cap-11",
        )
        assertTrue(request is CaptureRequest.NewNote)
        assertEquals(CaptureSource.ANDROID_SHARE, request.source)
    }

    @Test
    fun payloadWhoseImagesAllFilterOut_degradesToNewNote() {
        val request = ShareNormalizer.normalize(
            RawSharePayload(
                subject = null,
                text = null,
                images = listOf(image(mimeType = "application/pdf", fileName = "img-0.pdf")),
            ),
            id = "cap-12",
        )
        assertTrue(request is CaptureRequest.NewNote)
    }

    @Test
    fun isBareUrl_cases() {
        assertTrue(ShareNormalizer.isBareUrl("https://example.com/a?b=c"))
        assertTrue(ShareNormalizer.isBareUrl("http://example.com"))
        assertFalse(ShareNormalizer.isBareUrl("see https://example.com"))
        assertFalse(ShareNormalizer.isBareUrl("https://example.com and text"))
        assertFalse(ShareNormalizer.isBareUrl("ftp://example.com"))
        assertFalse(ShareNormalizer.isBareUrl("example.com"))
        assertFalse(ShareNormalizer.isBareUrl(""))
    }
}
