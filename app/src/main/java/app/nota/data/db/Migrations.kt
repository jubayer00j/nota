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
