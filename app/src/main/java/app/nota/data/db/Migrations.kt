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
