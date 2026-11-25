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
}

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
     *  aggregate budget (Codex R6-F1): converted bodies stay live
     *  from parse through the write phase, so the count cap and the
     *  archive quota do not bound the live heap on their own. */
    var retainedTextChars: Long = 0L
        private set

    /** Adds [note] when the aggregate text budget allows it;
     *  otherwise records the skip and returns false. Callers that
     *  can estimate the size before converting (the parsers) check
     *  first and skip without converting, so this is the true,
     *  defence-in-depth check on the converted body. */
    fun addNote(note: ImportedNote): Boolean {
        val chars = note.contentHtml.length.toLong()
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
     *  whose sum still exceeds the budget. */
    fun enforceContentBudget() {
        while (retainedTextChars > ImportCaps.MAX_AGGREGATE_TEXT_CHARS && notes.isNotEmpty()) {
            val dropped = notes.removeAt(notes.lastIndex)
            retainedTextChars -= dropped.contentHtml.length
            skippedFiles += SkippedImportFile(
                dropped.sourceName,
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
            retainedTextChars -= note.contentHtml.length
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
