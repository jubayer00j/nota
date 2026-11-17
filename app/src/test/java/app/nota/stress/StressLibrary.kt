package app.nota.stress

import app.nota.data.db.AttachmentEntity
import app.nota.data.db.FolderEntity
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteEntity
import app.nota.data.db.NoteTagCrossRef
import app.nota.data.db.TagEntity
import app.nota.data.html.NoteDocument
import app.nota.data.repository.refreshFtsEntry
import kotlin.random.Random

/**
 * Performance chapter 2: the shared stress-scale library seeder.
 *
 * Builds a deterministic 2,000-note library shaped like the one
 * the Noma stress pass used (~2,000 notes on the J7): mostly small
 * notes, a slice of medium ones, two deliberately huge notes (one
 * byte-huge, one block-huge), folders, tags, [[links]] and
 * attachment rows. Rows are written through the DAOs directly,
 * with plainText derived by the production [NoteDocument] path and
 * FTS rows by the production [refreshFtsEntry] builder, so the
 * seeded library is indistinguishable from a real one at the DAO
 * surface the performance fixes read.
 *
 * Deterministic: a fixed seed drives every choice, so two runs
 * seed identical libraries and count-based assertions are stable.
 */
object StressLibrary {

    const val DEFAULT_NOTE_COUNT = 2_000

    /** Epoch base for the seeded updatedAt spread (2023-11-14). */
    const val BASE_TIME = 1_700_000_000_000L

    data class Seeded(
        val noteIds: List<String>,
        val hugeByteNoteId: String,
        val hugeBlockNoteId: String,
        val folderIds: List<String>,
        val tagIds: List<String>,
    )

    private val WORDS = listOf(
        "river", "market", "ledger", "monsoon", "harbour", "cotton", "ledgerline",
        "archive", "signal", "timber", "lantern", "copper", "meadow", "cipher",
        "voyage", "granite", "saffron", "orchard", "velvet", "compass", "harvest",
        "mosaic", "quarry", "zephyr", "indigo", "falcon", "willow", "summit",
        "tapestry", "onyx", "prism", "delta", "cobalt", "juniper", "marble",
        "saffronfield", "notebook", "chapter", "margin", "index",
    )

    private fun Random.sentence(): String {
        val words = (6..12).random(this)
        val sb = StringBuilder()
        repeat(words) { i ->
            if (i > 0) sb.append(' ')
            sb.append(WORDS[nextInt(WORDS.size)])
        }
        sb.setCharAt(0, sb[0].uppercaseChar())
        return sb.append('.').toString()
    }

    private fun Random.paragraph(sentences: IntRange = 3..6): String {
        val count = sentences.random(this)
        return buildString {
            append("<p>")
            repeat(count) { i ->
                if (i > 0) append(' ')
                append(sentence())
            }
            append("</p>")
        }
    }

    private fun smallHtml(rng: Random, index: Int, linkTargetTitle: String?): String =
        buildString {
            repeat(rng.nextInt(1, 4)) { append(rng.paragraph()) }
            if (linkTargetTitle != null) {
                append("<p>See also [[")
                append(linkTargetTitle)
                append("]] for the earlier figures.</p>")
            }
            if (index % 25 == 0) {
                append("<ul data-type=\"taskList\">")
                append("<li data-type=\"taskItem\" data-checked=\"false\"><div>Check the totals</div></li>")
                append("<li data-type=\"taskItem\" data-checked=\"true\"><div>File the copy</div></li>")
                append("</ul>")
            }
        }

    private fun mediumHtml(rng: Random): String = buildString {
        repeat(rng.nextInt(30, 61)) { append(rng.paragraph()) }
    }

    /** ~1 MB of HTML: 1,500 span-rich paragraphs, 20 task lists,
     *  5 rich tables (10x4) and 3 code blocks. */
    fun hugeByteHtml(rng: Random): String = buildString {
        repeat(1_500) { p ->
            append("<p>Paragraph ")
            append(p)
            append(" opens with <strong>bold weight</strong>, leans on <em>italic stress</em>, ")
            append("cites <a href=\"https://example.com/ref")
            append(p % 40)
            append("\">a reference</a>, marks ")
            append("<span style=\"color:#B3261E\">a red phrase</span> and ")
            append("<span style=\"background-color:#FDE68A\">a highlighted one</span>, then ")
            repeat(4) { append(rng.sentence()).append(' ') }
            append("</p>")
        }
        repeat(20) { t ->
            append("<ul data-type=\"taskList\">")
            repeat(5) { i ->
                append("<li data-type=\"taskItem\" data-checked=\"")
                append(if (i % 2 == 0) "true" else "false")
                append("\"><div>Task ")
                append(t * 5 + i)
                append(" for the review pass</div></li>")
            }
            append("</ul>")
        }
        repeat(5) { t ->
            append("<table><tbody>")
            repeat(10) { r ->
                append("<tr>")
                repeat(4) { c ->
                    append("<td>Cell ")
                    append(t).append('-').append(r).append('-').append(c)
                    append("</td>")
                }
                append("</tr>")
            }
            append("</tbody></table>")
        }
        repeat(3) { c ->
            append("<pre><code>fun block")
            append(c)
            append("() = compute(values) // stress fixture code</code></pre>")
        }
    }

    /** ~3,000 top-level segments: 750 cycles of text + task list +
     *  quote + code block. */
    fun hugeBlockHtml(rng: Random): String = buildString {
        repeat(750) { cycle ->
            append("<p>Block ")
            append(cycle)
            append(" — ")
            append(rng.sentence())
            append("</p>")
            append("<ul data-type=\"taskList\">")
            append("<li data-type=\"taskItem\" data-checked=\"false\"><div>Step one of block ")
            append(cycle)
            append("</div></li>")
            append("<li data-type=\"taskItem\" data-checked=\"false\"><div>Step two of block ")
            append(cycle)
            append("</div></li>")
            append("</ul>")
            append("<blockquote><p>Quoted line for block ")
            append(cycle)
            append(".</p></blockquote>")
            append("<pre><code>snippet(")
            append(cycle)
            append(")</code></pre>")
        }
    }

    fun noteTitle(index: Int): String = "Stress note $index"

    fun noteId(index: Int): String = "stress-note-" + index.toString().padStart(4, '0')

    /**
     * Seeds [noteCount] ordinary notes plus the two huge notes
     * into [db]. Ordinary note i (0-based):
     * - ~5% medium (5–10 KB), the rest small (200–800 chars);
     * - ~10% carry a [[link]] to another seeded note's title;
     * - ~30% carry 1–2 tags; ~50% sit in one of 10 folders;
     * - ~10% own an attachment row (alternating image / PDF);
     * - updatedAt spreads hourly from [BASE_TIME], so all three
     *   sort orders see a meaningful order.
     */
    suspend fun seed(db: NotaDatabase, noteCount: Int = DEFAULT_NOTE_COUNT): Seeded {
        val rng = Random(2_026_111_2)
        val folderIds = (0 until 10).map { "stress-folder-$it" }
        val tagIds = (0 until 12).map { "stress-tag-$it" }
        folderIds.forEachIndexed { i, id ->
            db.folderDao().upsert(
                FolderEntity(
                    id = id,
                    name = "Stress Folder $i",
                    sortOrder = i,
                    createdAt = BASE_TIME,
                    updatedAt = BASE_TIME,
                ),
            )
        }
        tagIds.forEachIndexed { i, id ->
            db.tagDao().upsert(
                TagEntity(id = id, name = "Topic $i", createdAt = BASE_TIME),
            )
        }

        val noteIds = ArrayList<String>(noteCount + 2)
        for (i in 0 until noteCount) {
            val id = noteId(i)
            val linkTarget = if (i % 10 == 3 && noteCount > 1) {
                noteTitle((i * 7 + 11) % noteCount)
            } else {
                null
            }
            val html = if (i % 20 == 7) mediumHtml(rng) else smallHtml(rng, i, linkTarget)
            val updatedAt = BASE_TIME + i * 3_600_000L
            val note = NoteEntity(
                id = id,
                folderId = if (i % 2 == 0) folderIds[i % folderIds.size] else null,
                title = noteTitle(i),
                contentHtml = html,
                plainText = NoteDocument.plainText(NoteDocument.parse(html)),
                createdAt = updatedAt - 86_400_000L,
                updatedAt = updatedAt,
                pinned = i % 50 == 0,
            )
            db.noteDao().upsert(note)
            if (i % 10 < 3) {
                db.tagDao().link(NoteTagCrossRef(id, tagIds[i % tagIds.size]))
                if (i % 20 < 3) {
                    db.tagDao().link(NoteTagCrossRef(id, tagIds[(i + 5) % tagIds.size]))
                }
            }
            if (i % 10 == 5) {
                val image = i % 20 == 5
                db.attachmentDao().upsert(
                    AttachmentEntity(
                        id = "stress-att-$i",
                        noteId = id,
                        fileName = if (image) "stress-$i.png" else "stress-$i.pdf",
                        mimeType = if (image) "image/png" else "application/pdf",
                        sizeBytes = 10_000L + i,
                        localPath = "stress/$i",
                        createdAt = updatedAt,
                    ),
                )
            }
            db.refreshFtsEntry(note)
            noteIds += id
        }

        val hugeByteId = "stress-huge-byte"
        val hugeByteHtml = hugeByteHtml(rng)
        val hugeByte = NoteEntity(
            id = hugeByteId,
            title = "Stress huge byte note",
            contentHtml = hugeByteHtml,
            plainText = NoteDocument.plainText(NoteDocument.parse(hugeByteHtml)),
            createdAt = BASE_TIME,
            updatedAt = BASE_TIME + noteCount * 3_600_000L,
        )
        db.noteDao().upsert(hugeByte)
        db.refreshFtsEntry(hugeByte)
        noteIds += hugeByteId

        val hugeBlockId = "stress-huge-block"
        val hugeBlockHtml = hugeBlockHtml(rng)
        val hugeBlock = NoteEntity(
            id = hugeBlockId,
            title = "Stress huge block note",
            contentHtml = hugeBlockHtml,
            plainText = NoteDocument.plainText(NoteDocument.parse(hugeBlockHtml)),
            createdAt = BASE_TIME,
            updatedAt = BASE_TIME + (noteCount + 1) * 3_600_000L,
        )
        db.noteDao().upsert(hugeBlock)
        db.refreshFtsEntry(hugeBlock)
        noteIds += hugeBlockId

        return Seeded(noteIds, hugeByteId, hugeBlockId, folderIds, tagIds)
    }
}
