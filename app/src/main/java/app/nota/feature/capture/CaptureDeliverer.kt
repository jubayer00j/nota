package app.nota.feature.capture

import app.nota.data.attachments.AttachmentStore
import app.nota.data.capture.CaptureRecord
import app.nota.data.capture.CaptureRequest
import app.nota.data.capture.PendingCaptureStore
import app.nota.data.html.NoteDocument
import app.nota.data.repository.NoteRepository
import java.time.LocalDate

/**
 * Phase 5.2: turns a taken [CaptureRecord] into a real note in the
 * ACTIVE account's library and returns the note id to open — the
 * native shape of 1.x's workspace capture handlers:
 *
 * - new-note → a blank note (the notes list's own create path);
 * - today → the daily note, ensured exactly like the Today tab;
 * - share → a note with the share's title and body; each staged
 *   image is imported through the ordinary attachment pipeline
 *   ([AttachmentStore.importImageFile] — downscale + JPEG
 *   re-encode, same as a picker import, from the staged file
 *   directly rather than through a file:// URI) and embedded as
 *   its own `<p><img …></p>` paragraph. One unreadable image never
 *   sinks the whole capture (1.x's rule).
 *
 * The record's staged files are deleted when delivery finishes,
 * whatever happened — the record is already out of the store, so
 * nothing may linger.
 */
class CaptureDeliverer(
    private val noteRepository: NoteRepository,
    private val attachmentStore: AttachmentStore,
    private val captureStore: PendingCaptureStore,
) {

    suspend fun deliver(record: CaptureRecord): String? {
        try {
            return when (val request = record.request) {
                is CaptureRequest.NewNote -> noteRepository.createNote().id
                is CaptureRequest.Today ->
                    noteRepository.ensureDailyNote(LocalDate.now().toString()).id
                // The shell intercepts VoiceNote records and shows
                // the listening sheet instead of calling deliver();
                // if one ever lands here, creating nothing is the
                // only safe outcome (its staging is still cleaned).
                is CaptureRequest.VoiceNote -> null
                is CaptureRequest.Share -> deliverShare(record, request)
            }
        } finally {
            captureStore.deleteStaging(record)
        }
    }

    private suspend fun deliverShare(
        record: CaptureRecord,
        request: CaptureRequest.Share,
    ): String {
        val note = noteRepository.createRichNote(
            title = request.title,
            contentHtml = request.contentHtml,
        )
        var content = request.contentHtml
        for (image in request.images) {
            val attachment = runCatching {
                attachmentStore.importImageFile(
                    note.id,
                    captureStore.stagedFile(record, image),
                )
            }.getOrNull() ?: continue
            // 1.x escapes exactly & and " in the alt attribute.
            val alt = image.name.replace("&", "&amp;").replace("\"", "&quot;")
            content += "<p><img src=\"${NoteDocument.ATTACHMENT_SRC_PREFIX}" +
                "${attachment.id}\" alt=\"$alt\"></p>"
        }
        if (content != request.contentHtml) {
            noteRepository.updateRichBody(note, request.title, content)
        }
        return note.id
    }
}
