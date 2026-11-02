package app.nota.data.backup

import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.AttachmentEntity
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteEntity
import app.nota.data.html.FileSegment
import app.nota.data.html.ImageSegment
import app.nota.data.html.NoteDocument
import app.nota.data.html.TextSegment
import app.nota.data.templates.TemplateRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase 3.5 (D4): block order is document order. A note whose file
 * and image blocks sit BETWEEN paragraphs must come back from a
 * backup export → import round trip with exactly the same segment
 * sequence — before 3.5 a file had no document position at all, and
 * an image always drifted to wherever the append path put it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BlockOrderBackupRoundTripTest {

    private val dbs = mutableListOf<NotaDatabase>()

    @After
    fun tearDown() {
        dbs.forEach { it.close() }
    }

    private inner class Stack(val db: NotaDatabase) {
        val context = RuntimeEnvironment.getApplication()
        val provider = DatabaseProvider.forDatabase(context, db)
        val store = AttachmentStore(context, provider)
        val backup = BackupRepository(context, provider, TemplateRepository(context), store)
    }

    private var stackSeq = 0

    private fun newStack(): Stack {
        val context = RuntimeEnvironment.getApplication()
        val accountId = "blockorder-${++stackSeq}"
        context.deleteDatabase(NotaDatabase.databaseName(accountId))
        val db = NotaDatabase.build(context, accountId)
        dbs += db
        return Stack(db)
    }

    @Test
    fun blockOrder_survivesExportImportRoundTrip() = runBlocking {
        val source = newStack()
        val segments = listOf(
            TextSegment("<p>Intro</p>"),
            FileSegment("att-file-1", "spec.pdf", "application/pdf"),
            ImageSegment("nota-attachment://att-img-1", "diagram", 50),
            TextSegment("<p>Outro</p>"),
        )
        val html = NoteDocument.serialize(segments)
        source.db.noteDao().upsert(
            NoteEntity(
                id = "note-blocks",
                title = "Blocks",
                contentHtml = html,
                plainText = NoteDocument.plainText(segments),
                createdAt = 1_000,
                updatedAt = 2_000,
            ),
        )
        val attachments = listOf(
            AttachmentEntity(
                id = "att-file-1", noteId = "note-blocks", fileName = "spec.pdf",
                mimeType = "application/pdf", sizeBytes = 3,
                localPath = "attachments/note-blocks/spec.pdf", createdAt = 1_000,
            ),
            AttachmentEntity(
                id = "att-img-1", noteId = "note-blocks", fileName = "diagram.png",
                mimeType = "image/png", sizeBytes = 3,
                localPath = "attachments/note-blocks/diagram.png", createdAt = 1_000,
            ),
        )
        for (attachment in attachments) {
            source.db.attachmentDao().upsert(attachment)
            val file = source.store.fileFor(attachment)
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3))
        }

        val exported = File.createTempFile("block-order", ".zip")
        source.backup.exportToFile(exported, null)

        val target = newStack()
        target.backup.restore(exported, null, RestoreMode.REPLACE)
        val restored = target.db.noteDao().getById("note-blocks")
        assertEquals("note missing after restore", "note-blocks", restored?.id)
        assertEquals(
            "segment order changed across the backup round trip",
            segments,
            NoteDocument.parse(restored!!.contentHtml),
        )
    }
}
