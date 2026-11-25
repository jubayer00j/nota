package app.nota.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Nota's Room database — schema v9 (Phase 1b added saved_searches, Phase 1c
 * note_links for [[backlinks]], Phase 2b the per-note lock columns,
 * Phase 3c the sync tombstones + sync_meta tables, Phase 5.1 the reminder
 * alertType column, Phase 6 extras the device-local reminder tone columns,
 * Search Quality Pass the tags/folder columns on notes_fts,
 * Performance chapter 2 R3 the library_meta write-generation counter;
 * migrations carry installs forward).
 *
 * Per-account isolation mirrors Noma 1.x (spec §3): one database file per account,
 * named `nota_guest` / `nota_<uid>`.
 */
@Database(
    entities = [
        FolderEntity::class,
        NoteEntity::class,
        TagEntity::class,
        NoteTagCrossRef::class,
        AttachmentEntity::class,
        ReminderEntity::class,
        NoteVersionEntity::class,
        NoteFtsEntity::class,
        SavedSearchEntity::class,
        NoteLinkEntity::class,
        TombstoneEntity::class,
        SyncMetaEntity::class,
        LibraryMetaEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class NotaDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
    abstract fun tagDao(): TagDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun reminderDao(): ReminderDao
    abstract fun noteVersionDao(): NoteVersionDao
    abstract fun noteFtsDao(): NoteFtsDao
    abstract fun savedSearchDao(): SavedSearchDao
    abstract fun noteLinkDao(): NoteLinkDao
    abstract fun syncDao(): SyncDao

    companion object {
        const val GUEST_ACCOUNT = "guest"

        fun databaseName(accountId: String): String =
            if (accountId == GUEST_ACCOUNT) "nota_guest" else "nota_$accountId"

        /**
         * Keeps the schema v9 write-generation machinery present
         * on every database this factory opens (see
         * [installLibraryMeta]): onCreate covers fresh installs —
         * Room creates the library_meta table from the entity but
         * cannot express its counter row or the notes triggers —
         * and onOpen re-asserts both on every later open, so the
         * library stamp's generation can never silently freeze.
         * Idempotent; a few IF NOT EXISTS statements per open.
         */
        private val libraryMetaCallback: RoomDatabase.Callback =
            object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    installLibraryMeta(db)
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    installLibraryMeta(db)
                }
            }

        fun build(context: Context, accountId: String = GUEST_ACCOUNT): NotaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                NotaDatabase::class.java,
                databaseName(accountId),
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .addCallback(libraryMetaCallback)
                .build()
    }
}
