package app.nota.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written migrations. MIGRATION_1_2 (Phase 1b) adds the saved_searches
 * table; the CREATE TABLE statement matches SavedSearchEntity exactly so
 * Room's post-migration schema validation passes.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `saved_searches` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `query` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}

/**
 * Phase 1c adds the note_links table for resolved [[backlinks]]; the
 * statement matches NoteLinkEntity (columns, composite PK, both CASCADE
 * foreign keys, targetId index) so schema validation passes.
 */
/**
 * Phase 2b (per-note lock) adds the lock-record column to notes and the
 * encrypted flag to attachments. Both are plain ADD COLUMNs matching the
 * entity fields appended in the same order, so schema validation passes.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `lockRecordJson` TEXT")
        db.execSQL("ALTER TABLE `attachments` ADD COLUMN `encrypted` INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `note_links` (`sourceId` TEXT NOT NULL, `targetId` TEXT NOT NULL, " +
                "PRIMARY KEY(`sourceId`, `targetId`), " +
                "FOREIGN KEY(`sourceId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`targetId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_note_links_targetId` ON `note_links` (`targetId`)",
        )
    }
}

/**
 * Phase 3c (Drive sync) adds the tombstones + sync_meta tables; the
 * statements match TombstoneEntity / SyncMetaEntity exactly so
 * Room's post-migration schema validation passes.
 */
/**
 * Phase 5.1 (Reminders) adds the alertType column to reminders —
 * "notification" vs "phone-alarm" (1.x parity). A plain ADD COLUMN in
 * the MIGRATION_3_4 style; existing rows default to "notification",
 * which is exactly how every pre-5.1 reminder behaved.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `reminders` ADD COLUMN `alertType` TEXT NOT NULL DEFAULT 'notification'")
    }
}

/**
 * Phase 6 extras (PR #45) adds the phone-alarm tone fields to
 * reminders — per-reminder, device-local, exactly like alertType:
 * the backup wire format carries none of them, so restored
 * reminders come back with these same defaults. Plain ADD COLUMNs
 * in the MIGRATION_5_6 style; existing rows get the system default
 * tone and vibrate on, which is exactly how every pre-extras
 * phone-alarm reminder behaved (the Clock intent hardcoded
 * vibrate on and sent no ringtone).
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `reminders` ADD COLUMN `alarmToneMode` TEXT NOT NULL DEFAULT 'system'")
        db.execSQL("ALTER TABLE `reminders` ADD COLUMN `alarmToneUri` TEXT")
        db.execSQL("ALTER TABLE `reminders` ADD COLUMN `alarmToneName` TEXT")
        db.execSQL("ALTER TABLE `reminders` ADD COLUMN `alarmVibrate` INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * Search Quality Pass (v0.27.0) widens the FTS index: notes_fts
 * gains `tagsText` and `folderName` columns so tag and folder
 * names are searchable with 1.x's weights. An FTS4 table cannot
 * gain a column in place, so the table is dropped, recreated in
 * the v8 shape (matching NoteFtsEntity exactly, so Room's
 * post-migration schema validation passes) and REBUILT from the
 * live library in the same pass: every non-trashed note gets a
 * fresh row — body blanked for locked notes (the seal), tag
 * names joined from note_tags, folder name from folders. An
 * install that skips this rebuild would search an empty index
 * until every note happened to be re-saved, so the rebuild is
 * part of the migration, not a later backfill.
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `notes_fts`")
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `notes_fts` USING FTS4(" +
                "`noteId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`plainText` TEXT NOT NULL, `tagsText` TEXT NOT NULL, " +
                "`folderName` TEXT NOT NULL)",
        )
        db.execSQL(
            "INSERT INTO `notes_fts` (`noteId`, `title`, `plainText`, `tagsText`, `folderName`) " +
                "SELECT n.`id`, n.`title`, " +
                "CASE WHEN n.`locked` = 1 THEN '' ELSE n.`plainText` END, " +
                "COALESCE((SELECT GROUP_CONCAT(t.`name`, ' ') FROM `note_tags` nt " +
                "JOIN `tags` t ON t.`id` = nt.`tagId` WHERE nt.`noteId` = n.`id`), ''), " +
                "COALESCE((SELECT f.`name` FROM `folders` f WHERE f.`id` = n.`folderId`), '') " +
                "FROM `notes` n WHERE n.`trashedAt` IS NULL",
        )
    }
}

val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tombstones` (`entity` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                "`deletedAt` INTEGER NOT NULL, `deviceId` TEXT, PRIMARY KEY(`entity`, `id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                "PRIMARY KEY(`key`))",
        )
    }
}

/**
 * Creates (idempotently) the schema v9 library write-generation
 * machinery: the `library_meta` table (the CREATE matches
 * [LibraryMetaEntity] exactly, so Room's post-migration schema
 * validation passes), its singleton counter row, and the three
 * triggers that bump the counter on every notes write.
 *
 * Three callers, one definition, so an upgraded install and a
 * fresh install end up identical:
 * - [MIGRATION_8_9], for installs upgrading from v8 (Room has not
 *   created the table there — hence the CREATE TABLE).
 * - The [NotaDatabase] open callback (onCreate + onOpen), for
 *   fresh installs (Room creates the entity table itself; the
 *   callback owes the row and the triggers, which Room's schema
 *   cannot express) and as an every-open self-heal, so a database
 *   that somehow lost the row or a trigger converges on the next
 *   open instead of stamping a frozen generation forever.
 * - Tests that build a database without [NotaDatabase.build] and
 *   need the counter live.
 *
 * The triggers fire inside the writing transaction, so a bulk
 * restore of thousands of notes pays one single-row UPDATE per
 * note inside its own transaction (and a rolled-back write rolls
 * its increments back with it — the counter counts COMMITTED
 * writes, which is exactly the stamp's contract).
 */
fun installLibraryMeta(db: SupportSQLiteDatabase) {
    // (PR #59 fix round 18) How long this connection waits on a
    // locked database before failing with SQLITE_BUSY. The open
    // path's first write is the INSERT below, run from Room's onOpen
    // callback, and another connection can legitimately hold the
    // file at that moment — the app-startup orphan sweep opens every
    // account database it finds, and a test (or a fresh install) can
    // open the same file while the sweep is mid-write. SQLite's
    // default busy timeout is 0, so the loser failed instantly:
    // BackupRestoreSemanticsTest flaked three times (fix rounds 11,
    // 14, 17) with SQLiteDatabaseLockedException at that INSERT. A
    // 5 s wait lets the loser ride out a millisecond write instead;
    // a genuinely stuck lock still fails, 5 s later. Set FIRST, so
    // it covers every write below on every caller (onCreate/onOpen,
    // forDatabase's eager install, MIGRATION_8_9) — PRAGMA
    // busy_timeout is a per-connection setting, not a schema change,
    // so it is safe inside a migration transaction too.
    //
    // It MUST run as a query, not execSQL: `PRAGMA busy_timeout =
    // 5000` returns the new value as a row, and Android's execSQL
    // rejects statements that return data ("Queries can be performed
    // using SQLiteDatabase query or rawQuery methods only" —
    // Migration67RobolectricTest proved it the hard way). Run it as
    // a query and close the cursor.
    db.query("PRAGMA busy_timeout = 5000").close()
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `library_meta` (`id` INTEGER NOT NULL, " +
            "`notesWriteGeneration` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    )
    db.execSQL(
        "INSERT OR IGNORE INTO `library_meta` (`id`, `notesWriteGeneration`) VALUES (1, 0)",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `trg_library_meta_notes_insert` " +
            "AFTER INSERT ON `notes` BEGIN " +
            "UPDATE `library_meta` SET `notesWriteGeneration` = `notesWriteGeneration` + 1 WHERE `id` = 1; " +
            "END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `trg_library_meta_notes_update` " +
            "AFTER UPDATE ON `notes` BEGIN " +
            "UPDATE `library_meta` SET `notesWriteGeneration` = `notesWriteGeneration` + 1 WHERE `id` = 1; " +
            "END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `trg_library_meta_notes_delete` " +
            "AFTER DELETE ON `notes` BEGIN " +
            "UPDATE `library_meta` SET `notesWriteGeneration` = `notesWriteGeneration` + 1 WHERE `id` = 1; " +
            "END",
    )
}

/**
 * Performance chapter 2 R3 (v0.33.0) adds the library
 * write-generation counter: the `library_meta` table, its
 * singleton row (generation 0 — a pre-upgrade library has no
 * recorded writes, and the semantic stamp's own format break
 * guarantees no pre-upgrade stamp can gate a post-upgrade pass)
 * and the notes triggers, all via [installLibraryMeta]. No
 * existing table is touched and no note data moves.
 */
val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        installLibraryMeta(db)
    }
}

/**
 * Reading stats fix round 1 (v0.37.0, Codex R1-F1): the
 * content-edited timestamp. `updatedAt` cannot tell a content
 * edit from a metadata bump (pin, favourite, archive,
 * trash/restore, move, lock/unlock all bump it), and stopping
 * the bump is not an option — the library sorts by `updatedAt`
 * and the sync merge is whole-row newest-wins on it, so a
 * metadata op that stopped bumping could lose to a newer remote
 * copy. A new nullable column is the minimal correct fix: no
 * existing table is touched beyond the one added column, and no
 * note data moves.
 *
 * - New rows are written with `contentEditedAt` set by their
 *   content-edit path (create / updateBody / updateTitle /
 *   updateRichBody / restoreVersion / saveLockedContent); the
 *   archive converters (`BxNote.toEntity`, import) set it to the
 *   source's `updatedAt`; metadata ops never touch it.
 * - Existing rows are backfilled to their `updatedAt` — the only
 *   edit timestamp a pre-upgrade install has. Honest residual:
 *   a note whose last pre-upgrade bump was metadata-only keeps
 *   that day in the stats until its next real content edit.
 *
 * Reading stats fix round 2 (v0.37.0, Codex R2-F1): the stored
 * word count. The stats query used to select unbounded
 * `plainText` to derive word totals — one note over Android's
 * ~2 MiB CursorWindow fails the whole stats load. The count is
 * now a column, maintained at every write (see NoteEntity),
 * and the migration backfills it for existing rows. The
 * backfill reads each body in bounded SUBSTR chunks (never a
 * whole `plainText` cell) and counts with the exact same
 * definition as BackupRepository.wordCount — maximal runs of
 * non-whitespace, where "whitespace" is Java `\s` (space, tab,
 * LF, VT, FF, CR) and nothing else. A character-length
 * approximation would not be an exact count and is not used.
 */
val MIGRATION_9_10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `contentEditedAt` INTEGER")
        db.execSQL("UPDATE `notes` SET `contentEditedAt` = `updatedAt`")
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `wordCount` INTEGER NOT NULL DEFAULT 0")
        backfillWordCounts(db)
    }
}

/**
 * The migration's word-count backfill (fix round 2): counts the
 * words of every note's stored body without ever reading a whole
 * `plainText` cell — a single oversized cell is exactly what
 * broke the stats load. Each body is read in [WORD_COUNT_CHUNK]
 * slices via SUBSTR (1-based, character — not byte — offsets),
 * and the counter carries its mid-word state across slices, so
 * a word straddling a boundary counts once. The separator set
 * is Java `\s` exactly, matching BackupRepository.wordCount.
 */
internal const val WORD_COUNT_CHUNK_CHARS = 65_536

internal fun countWordsChunked(
    readChunk: (offset: Int, size: Int) -> String,
    chunkSize: Int = WORD_COUNT_CHUNK_CHARS,
): Int {
    var words = 0
    var inWord = false
    var offset = 1
    while (true) {
        val chunk = readChunk(offset, chunkSize)
        if (chunk.isEmpty()) break
        for (ch in chunk) {
            // Java \s exactly: space, tab, LF, VT, FF, CR.
            // Kotlin's Char.isWhitespace is wider (it also
            // counts NBSP and other Unicode spaces), so it must
            // NOT be used here — the count would drift from
            // BackupRepository.wordCount on such text.
            if (ch == ' ' || ch == '\t' || ch == '\n' ||
                ch == '\u000B' || ch == '\u000C' || ch == '\r'
            ) {
                inWord = false
            } else if (!inWord) {
                words++
                inWord = true
            }
        }
        if (chunk.length < chunkSize) break
        offset += chunkSize
    }
    return words
}

private fun backfillWordCounts(db: SupportSQLiteDatabase) {
    val ids = db.query("SELECT `id` FROM `notes`")
    ids.use {
        while (it.moveToNext()) {
            val id = it.getString(0)
            val words = countWordsChunked { offset, size ->
                db.query(
                    "SELECT SUBSTR(`plainText`, ?, ?) FROM `notes` WHERE `id` = ?",
                    arrayOf<Any?>(offset, size, id),
                ).use { chunkCursor ->
                    if (chunkCursor.moveToFirst()) chunkCursor.getString(0) ?: "" else ""
                }
            }
            db.execSQL(
                "UPDATE `notes` SET `wordCount` = ? WHERE `id` = ?",
                arrayOf<Any?>(words, id),
            )
        }
    }
}
