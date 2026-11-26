package app.nota.data.importing

import java.io.File

/**
 * Import data model — a faithful port of 1.x Noma's importer shapes
 * (`src/lib/noma/importers.ts`), adapted to Nota's storage: images are
 * staged FILES (the AttachmentStore pipeline is file-based), and a
 * trashed note carries [ImportedNote.trashedAt] instead of 1.x's
 * deleted/deletedAt pair (Nota's trash model).
 */

/** Caps from 1.x, verbatim: one run, one source file / ZIP member. */
object ImportCaps {
    const val MAX_IMPORT_NOTES = 5000
    const val MAX_IMPORT_FILE_BYTES = 100L * 1024 * 1024

    /** Aggregate retained note text per run, in UTF-16 units
     *  ([String.length]) — NOT a 1.x cap: 1.x holds every converted
     *  note in memory, which on Nota's 1 GB target can OOM (5,000
     *  notes × 100 KB bodies is ~500 MB of live strings; the 512 MiB
     *  archive quota bounds ZIP bytes, not the expanded aggregate).
     *  16 M chars ≈ 32 MiB of string storage. Notes past it are
     *  reported as skipped, never silently dropped. */
    const val MAX_AGGREGATE_TEXT_CHARS = 16L * 1024 * 1024
    const val AGGREGATE_TEXT_BUDGET_REASON =
        "Import limit reached (16 million characters of note text per run)."

    /** Largest single note the import will write, in UTF-8 bytes of
     *  title + contentHtml — NOT a 1.x cap either: 1.x's 100 MiB
     *  per-file cap lets a single note through that Android cannot
     *  read back. A row must fit the ~2 MiB CursorWindow, and the
     *  stored row holds contentHtml AND plainText (derived from the
     *  body by stripping markup, never materially longer), so the
     *  bound keeps title + body + its plain-text projection + the
     *  small fixed columns under it: at most ~2 × 1,000,000 bytes
     *  against a 2,097,152-byte window. Over-limit notes are
     *  reported as skipped, never silently dropped. */
    const val MAX_NOTE_CONTENT_BYTES = 1_000_000L
    const val NOTE_TOO_LARGE_REASON =
        "That note is too large to import (over 1 MB of text)."

    /** Largest single raw Keep entry the parser will read, in decoded
     *  characters — NOT the aggregate budget: a Keep JSON carries field
     *  names, timestamps and attachment metadata that are never
     *  retained as note text, so the aggregate (which counts converted
     *  note text) must not bound the raw read (Codex R10-F2). The value
     *  is the same 16M-char safe-retained-text ceiling the aggregate
     *  uses: a raw entry past it is skipped with its own reason rather
     *  than materialized. */
    const val MAX_RAW_ENTRY_CHARS = MAX_AGGREGATE_TEXT_CHARS
    const val RAW_ENTRY_TOO_LARGE_REASON =
        "That note's data is too large to import (over 16 million characters)."

    /** Largest JSON structure one Keep entry may hold, counted in
     *  nodes (every object, array, string and scalar in the raw
     *  text) and in nesting depth — NOT 1.x caps: the raw-char bound
     *  above limits the entry's TEXT, but org.json's tree multiplies
     *  it before any per-array guard can run — a raw-compliant entry
     *  of tiny objects (~500k list items of ~31 chars each, well
     *  under the 16M-char raw bound) materialises a HashMap-backed
     *  object per item, several times the raw size, and deeply
     *  nested containers recurse the tree parser (Codex R29-F1).
     *  The parser therefore counts structure from the raw text —
     *  without building the tree — and refuses an entry past either
     *  bound with its own reason. 150,000 nodes sits far above any
     *  real note (a 20,000-item checklist is ~100k nodes and still
     *  reaches the per-note expansion guard, exactly as before) and
     *  far below an OOM; real Keep JSON nests ~4 deep, so 512 of
     *  depth is headroom no export approaches. */
    const val MAX_JSON_STRUCTURE_NODES = 150_000
    const val MAX_JSON_STRUCTURE_DEPTH = 512
    const val JSON_STRUCTURE_TOO_LARGE_REASON =
        "That note's data is too large to import (over 150,000 items in its lists, labels or attachments)."

    /** Aggregate ceiling for image bytes extracted from ZIPs during
     *  one import selection — NOT a 1.x cap: 1.x extracts per archive
     *  with no cross-archive bound. 512 MiB, the same ceiling as the
     *  per-archive uncompressed quota (BackupQuota): one ZIP's image
     *  walk is already bounded to 512 MiB by that quota, so the
     *  aggregate only bites across ZIPs in one selection. */
    const val MAX_EXTRACTED_IMAGE_BYTES = 512L * 1024 * 1024

    /** Ceilings for the image references the Markdown pre-scan
     *  retains during one ZIP parse — NOT 1.x caps: 1.x has no
     *  pre-scan. The retained target/basename sets live for the
     *  whole parse, and the scan's inputs are bounded only by the
     *  512 MiB archive quota and the per-member text bound, while
     *  distinct references are cheap to mint (`![](1.png)`,
     *  `![](2.png)`, …) — uncapped, a crafted archive could grow
     *  both sets to millions of entries and OOM before any later
     *  budget check runs (Codex R20-F1). The count caps set-node
     *  overhead for tiny references; the retained characters (the
     *  target + basename strings, in UTF-16 units) cap string
     *  storage for long ones. Both sit far above any real export's
     *  references and far below an OOM. A reference past either
     *  bound is simply not collected: its image is not staged, the
     *  reference stays unresolved (counted at the reference), and
     *  the note still imports. */
    const val MAX_RETAINED_IMAGE_REFS = 50_000
    const val MAX_RETAINED_IMAGE_REF_CHARS = 1024L * 1024

    /** Largest number of tags one imported note may carry, and the
     *  largest single tag name, in UTF-8 bytes — NOT 1.x caps: 1.x
     *  accumulates every label/tag name unbounded, and a
     *  raw-limit-compliant source with a huge labels array and a
     *  tiny body retains millions of label strings while no text
     *  budget trips (Codex R27-F2). 100 tags and 1,000 bytes per
     *  name sit far above any real export (a handful of short
     *  labels) and far below an OOM. Labels past the count cap and
     *  names past the byte cap are dropped, never truncated — the
     *  note still imports with the labels that fit. */
    const val MAX_TAGS_PER_NOTE = 100
    const val MAX_TAG_NAME_BYTES = 1_000L

    /** Largest number of image attachments one imported Keep note
     *  may retain, and the largest number of attachment specs one
     *  run may retain in total — NOT 1.x caps: 1.x retains a spec
     *  per resolved attachment, and the R26-F3 dedupe only
     *  suppresses repeats WITHIN one note. Pass 2 accumulates every
     *  draft in one list (capped only at 5,000 notes), so a Takeout
     *  whose notes reference thousands of distinct images each
     *  retains millions of specs — three strings apiece, kept alive
     *  until parsing finishes — before any later budget trips
     *  (Codex R31-F2). 100 per note and 5,000 per run sit far above
     *  any real export (a Keep note carries a handful of images)
     *  and far below an OOM (~1.5 MB of specs at the run cap).
     *  References past either cap are counted as skipped, never
     *  silently dropped — the note still imports with the images
     *  that fit. */
    const val MAX_ATTACHMENTS_PER_NOTE = 100
    const val MAX_ATTACHMENTS_PER_RUN = 5_000

    /** Largest number of unresolved-attachment paragraphs one
     *  imported Keep note may carry (Codex R34-F2): an ambiguous
     *  reference keeps its file name in the note as a `<p>` — but
     *  the append happens in the attachment loop, before the
     *  R26-F3 dedupe and the R31-F2 spec caps, which only cover
     *  resolved entries. A note repeating one ambiguous reference
     *  tens of thousands of times therefore appended once per
     *  occurrence, and `contentHtml` is an immutable String, so
     *  each append copied the whole growing body — quadratic —
     *  and the HTML could grow well past the per-note limit before
     *  the engine's size check refused it. 100 paragraphs sits far
     *  above any real export (a Keep note carries a handful of
     *  images) and matches the per-note attachment ceiling above.
     *  References past the cap are still counted as skipped, never
     *  silently dropped — the note still imports with the file
     *  names that fit. */
    const val MAX_AMBIGUOUS_ATTACHMENT_PARAGRAPHS = 100

    /** Largest number of suffix entries the Keep entry-name index
     *  may retain in total — NOT a 1.x cap: 1.x scans names
     *  linearly per attachment and keeps no index. The suffix arm
     *  used to contribute one map key + backing list per slash in
     *  every name, so 8 MiB of slash-heavy names could hold
     *  millions of entries — HashMap nodes, keys, lists and
     *  reference slots — and exhaust the heap before any note was
     *  parsed (Codex R35-F1). The R31 fix stopped copying suffix
     *  strings and the R34 fix made hashing linear, but neither
     *  bounded how MANY entries the index holds. 250,000 entries
     *  sits far above any real export (even a max-size 20,000-entry
     *  archive with 12-segment paths contributes ~220,000, and a
     *  real Takeout's paths are 3–4 segments) and far below an OOM
     *  (~30 MB worst case, one key + list + reference per entry).
     *  the ceiling, so their verdicts are unchanged.
     *  Suffixes past the ceiling are simply not indexed: their
     *  lookup falls through to the basename arm, which stays
     *  correct — a unique basename still resolves, a shared one
     *  still stays ambiguous — so the note still imports. */
    const val MAX_SUFFIX_INDEX_ENTRIES = 250_000
}

/**
 * Running total for image bytes extracted from ZIPs during one import
 * selection (Codex R11-F1): the per-image copy cap bounds each image,
 * but several highly compressible ZIPs in one selection could still
 * expand into gigabytes of staged images in the shared staging
 * directory. One instance is shared by every parseImportZip call in
 * the selection; an image that would exceed what remains is not
 * staged at all, so its references stay unresolved (counted at the
 * reference) instead. Mirrors [PickedFileStaging.StagingBudget],
 * which bounds staged picks the same way.
 */
class ExtractedImageBudget(
    private val totalBytes: Long = ImportCaps.MAX_EXTRACTED_IMAGE_BYTES,
) {
    private var usedBytes = 0L

    fun remaining(): Long = (totalBytes - usedBytes).coerceAtLeast(0L)

    /** The per-image cap for the next extraction: the smaller of the
     *  100 MiB per-file cap and what the budget still allows. */
    fun nextImageCapBytes(): Long =
        minOf(ImportCaps.MAX_IMPORT_FILE_BYTES, remaining())

    /** Records [bytes] of staged image bytes; call only after a
     *  successful extraction. */
    fun record(bytes: Long) {
        usedBytes += bytes
    }
}

/** UTF-8 byte length of [text], without allocating a copy. */
internal fun utf8ByteLength(text: String): Long {
    var bytes = 0L
    var i = 0
    while (i < text.length) {
        val c = text[i]
        bytes += when {
            c < '\u0080' -> 1L
            c < '\u0800' -> 2L
            c.isHighSurrogate() && i + 1 < text.length &&
                text[i + 1].isLowSurrogate() -> {
                i++
                4L
            }
            else -> 3L
        }
        i++
    }
    return bytes
}

/** SHA-256 of [text] as lowercase hex — a content identity for
 *  unknown-date notes (Codex R23-F1, R26-F2): a picked file's
 *  display name is not an identity (two SAF picks from different
 *  folders can share it), and neither is a ZIP member's bare
 *  path (the same entry path can appear in two archives of one
 *  selection). The decoded text is already in memory when this
 *  runs; two sources with the same decoded text convert to the
 *  same note, so the same hash is the right identity for them. */
internal fun contentHashHex(text: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

/** The row-heavy bytes of a note (title + body), measured against
 *  [ImportCaps.MAX_NOTE_CONTENT_BYTES]. plainText is derived from
 *  the body and never materially longer, so the stored row is at
 *  most ~2× this plus small fixed columns. */
internal fun noteRowBytes(title: String, contentHtml: String): Long =
    utf8ByteLength(title) + utf8ByteLength(contentHtml)

class ImportedImage(
    val name: String,
    val mimeType: String,
    /** Staged file holding the image bytes. The import engine consumes
     *  (deletes) it after the save attempt. */
    val stagedFile: File,
    val sizeBytes: Long,
    /** Alt text: kept as the image's alt on success, and replaces the
     *  image in the content when the save fails. */
    val alt: String,
    /** Placeholder token inside the note's contentHtml to swap for the
     *  saved embed; null = append the image at the end of the note. */
    val token: String?,
    /** True when this staged file is exclusively this note's (Keep
     *  attachments are staged per note) and the engine deletes it after
     *  the save attempt. Resolver-sourced files (Markdown) may be
     *  shared by several notes, so the engine leaves them; their
     *  staging directory is cleaned up by the caller instead. */
    val deleteAfterUse: Boolean = true,
)

class ImportedNote(
    val title: String,
    val contentHtml: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val archived: Boolean,
    /** Epoch millis when trashed; null = not trashed. */
    val trashedAt: Long?,
    val tagNames: List<String>,
    val folderName: String?,
    val images: List<ImportedImage>,
    /** Original file/entry name — named in the report when the write fails. */
    val sourceName: String,
    /** Stable per-source identity for the run dedupe of
     *  unknown-date notes (Codex R23-F1, R26-F2, R28-F2): null means the
     *  source name plays that role. A loose pick's display name is
     *  NOT one — two SAF picks from different folders can share it —
     *  so the loose-file path sets this to a content hash of the
     *  file instead; a ZIP member's bare path is not one either —
     *  the same entry path can appear in two archives of one
     *  selection — so the ZIP path sets it to the full entry path
     *  plus the content hash, and Keep notes carry the same shape
     *  (their basename alone collapsed two nested JSONs sharing it).
     *  Never persisted; the run key is in-memory only. */
    val sourceIdentity: String? = null,
)

data class SkippedImportFile(val name: String, val reason: String)

/** What a parser made of one import source, before anything is written. */
class ImportParseResult {
    val notes = mutableListOf<ImportedNote>()
    val skippedFiles = mutableListOf<SkippedImportFile>()
    /** Keep attachments that were not images, could not be found, or
     *  exceeded the per-file cap. */
    var attachmentsSkipped = 0
    /** Image references that could not be resolved to an importable
     *  image (remote refs, missing files, non-images, oversize). */
    var imagesUnresolved = 0
    /** Keep notes whose colour was not DEFAULT. Nota does not map Keep
     *  colours (1.x defines no mapping); they are counted so the report
     *  can say so honestly. */
    var coloredNotes = 0

    /** UTF-16 units of note text retained in [notes] — the run-wide
     *  aggregate budget (Codex R6-F1): converted titles + bodies stay
     *  live from parse through the write phase, so the count cap and
     *  the archive quota do not bound the live heap on their own.
     *  The title counts (Codex R19-F1): a ~1 MB title with a tiny
     *  body is a legal single note, and 5,000 of them would retain
     *  ~5 GB while a body-only counter barely moved. Tag names count
     *  too (Codex R27-F2): they are retained strings as well, and a
     *  note with a tiny body but thousands of labels would otherwise
     *  slip past the budget the same way. */
    var retainedTextChars: Long = 0L
        private set

    /** UTF-16 units retained for one note: title + body + tag names. */
    private fun retainedChars(note: ImportedNote): Long =
        note.title.length.toLong() + note.contentHtml.length.toLong() +
            note.tagNames.sumOf { it.length.toLong() }

    /** Adds [note] when the aggregate text budget allows it;
     *  otherwise records the skip and returns false. Callers that
     *  can estimate the size before converting (the parsers) check
     *  first and skip without converting, so this is the true,
     *  defence-in-depth check on the converted note — title AND
     *  body (Codex R19-F1) plus tag names (Codex R27-F2). A note past the per-note bound is
     *  refused first (Codex R7-F1): it would
     *  write fine and then break the run-end rebuild, whose
     *  `noteDao.getAll()` cannot read a row past Android's ~2 MiB
     *  CursorWindow — the UI would report failure while the note
     *  sat in the database. */
    fun addNote(note: ImportedNote): Boolean {
        if (noteRowBytes(note.title, note.contentHtml) > ImportCaps.MAX_NOTE_CONTENT_BYTES) {
            skippedFiles += SkippedImportFile(
                note.sourceName,
                ImportCaps.NOTE_TOO_LARGE_REASON,
            )
            return false
        }
        val chars = retainedChars(note)
        if (retainedTextChars + chars > ImportCaps.MAX_AGGREGATE_TEXT_CHARS) {
            skippedFiles += SkippedImportFile(
                note.sourceName,
                ImportCaps.AGGREGATE_TEXT_BUDGET_REASON,
            )
            return false
        }
        notes += note
        retainedTextChars += chars
        return true
    }

    /** Drops tail notes past the aggregate text budget into
     *  [skippedFiles] — the merge-time counterpart of [addNote],
     *  for runs assembled from several already-budgeted sources
     *  whose sum still exceeds the budget. Dropped notes are
     *  reported in parse order, like [enforceNoteCap] does. The
     *  subtraction counts title + body + tags, like [addNote] does
     *  (Codex R19-F1, R27-F2) — subtracting bodies only would leave the
     *  counter overstated by the dropped titles. */
    fun enforceContentBudget() {
        val dropped = mutableListOf<ImportedNote>()
        while (retainedTextChars > ImportCaps.MAX_AGGREGATE_TEXT_CHARS && notes.isNotEmpty()) {
            val note = notes.removeAt(notes.lastIndex)
            retainedTextChars -= retainedChars(note)
            dropped += note
        }
        for (note in dropped.asReversed()) {
            skippedFiles += SkippedImportFile(
                note.sourceName,
                ImportCaps.AGGREGATE_TEXT_BUDGET_REASON,
            )
        }
    }

    /** 1.x mergeParseResults: fold another source's parse into this one. */
    fun absorb(other: ImportParseResult) {
        notes += other.notes
        retainedTextChars += other.retainedTextChars
        skippedFiles += other.skippedFiles
        attachmentsSkipped += other.attachmentsSkipped
        imagesUnresolved += other.imagesUnresolved
        coloredNotes += other.coloredNotes
    }

    /** The 5,000-note cap applies to the whole run, not per source:
     *  merging several capped sources can still exceed it. Excess
     *  notes are dropped into [skippedFiles] with the same reason the
     *  per-source caps use — never silently dropped. */
    fun enforceNoteCap() {
        if (notes.size <= ImportCaps.MAX_IMPORT_NOTES) return
        val excess = notes.subList(ImportCaps.MAX_IMPORT_NOTES, notes.size).toList()
        notes.subList(ImportCaps.MAX_IMPORT_NOTES, notes.size).clear()
        for (note in excess) {
            // Title + body + tags, like addNote counts them (Codex R19-F1, R27-F2).
            retainedTextChars -= retainedChars(note)
            skippedFiles += SkippedImportFile(
                note.sourceName,
                "Import limit reached (5,000 notes per run).",
            )
        }
    }
}

/** The engine's write report — 1.x `ImportReport` verbatim. */
data class ImportReport(
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val failedNames: List<String>,
)

/** One import source end to end: what parsing made of it + what the
 *  engine did with it. */
class ImportOutcome(
    val parse: ImportParseResult,
    val report: ImportReport,
    /** Images whose bytes resolved but which Nota's image pipeline
     *  could not save (e.g. SVG). Counted into the summary's
     *  imagesUnresolved so no image is ever dropped silently. */
    val imageSaveFailures: Int,
) {
    /** The user-facing summary — 1.x ImportSummary's fields. */
    fun toSummary(): ImportSummary = ImportSummary(
        imported = report.imported,
        duplicates = report.duplicates,
        failed = report.failed,
        unreadable = parse.skippedFiles +
            report.failedNames.map { SkippedImportFile(it, "Couldn't be saved.") },
        attachmentsSkipped = parse.attachmentsSkipped,
        imagesUnresolved = parse.imagesUnresolved + imageSaveFailures,
        coloredNotes = parse.coloredNotes,
    )

    companion object {
        fun summarize(outcomes: List<ImportOutcome>): ImportSummary {
            val parse = ImportParseResult()
            var imported = 0
            var duplicates = 0
            var failed = 0
            val failedNames = mutableListOf<String>()
            var saveFailures = 0
            for (outcome in outcomes) {
                parse.absorb(outcome.parse)
                imported += outcome.report.imported
                duplicates += outcome.report.duplicates
                failed += outcome.report.failed
                failedNames += outcome.report.failedNames
                saveFailures += outcome.imageSaveFailures
            }
            return ImportOutcome(
                parse,
                ImportReport(imported, duplicates, failed, failedNames),
                saveFailures,
            ).toSummary()
        }
    }
}

/** The terminal summary the Settings section renders (held by
 *  [PendingImportStore] until dismissed). */
data class ImportSummary(
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val unreadable: List<SkippedImportFile>,
    val attachmentsSkipped: Int,
    val imagesUnresolved: Int,
    val coloredNotes: Int,
)
