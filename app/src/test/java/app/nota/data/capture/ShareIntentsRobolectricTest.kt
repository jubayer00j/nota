package app.nota.data.capture

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 5.2: share-intent parsing (the intent-reading half of 1.x's
 * NomaSharePlugin): subject/text extraction, single + multiple
 * stream collection, and the isShare gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareIntentsRobolectricTest {

    @Test
    fun isShare_onlyForSendActions() {
        assertTrue(ShareIntents.isShare(Intent(Intent.ACTION_SEND)))
        assertTrue(ShareIntents.isShare(Intent(Intent.ACTION_SEND_MULTIPLE)))
        assertFalse(ShareIntents.isShare(Intent(Intent.ACTION_MAIN)))
        assertFalse(ShareIntents.isShare(Intent(Intent.ACTION_VIEW)))
        assertFalse(ShareIntents.isShare(null))
    }

    @Test
    fun textOnly_parsesSubjectAndText_noStreams() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "An article")
            putExtra(Intent.EXTRA_TEXT, "Worth reading")
        }
        val parsed = ShareIntents.parse(intent)
        assertEquals("An article", parsed.subject)
        assertEquals("Worth reading", parsed.text)
        assertTrue(parsed.streamUris.isEmpty())
    }

    @Test
    fun blankSubject_readsAsNoSubject() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "   ")
            putExtra(Intent.EXTRA_TEXT, "body")
        }
        assertNull(ShareIntents.parse(intent).subject)
    }

    @Test
    fun singleImage_streamIsCollected() {
        val uri = Uri.parse("content://media/external/images/media/7")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        assertEquals(listOf(uri), ShareIntents.parse(intent).streamUris)
    }

    @Test
    fun multipleImages_allStreamsAreCollectedInOrder() {
        val uris = (1..3).map { Uri.parse("content://media/external/images/media/$it") }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        assertEquals(uris, ShareIntents.parse(intent).streamUris)
    }

    @Test
    fun emptySend_parsesToNothing() {
        val parsed = ShareIntents.parse(Intent(Intent.ACTION_SEND))
        assertNull(parsed.subject)
        assertNull(parsed.text)
        assertTrue(parsed.streamUris.isEmpty())
    }
}
