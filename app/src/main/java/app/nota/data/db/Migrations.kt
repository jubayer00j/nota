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
