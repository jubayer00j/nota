package app.nota.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Nota's Room database — schema v1 (Phase 0).
 *
 * Per-account isolation mirrors Noma 1.x (spec §3): one database file per account,
 * named `nota_guest` / `nota_<uid>` (the "nota_" prefix replaces 1.x's "noma_" so the
 * two apps never share a file). Firebase identity arrives in a later phase; Phase 0
 * always opens the guest database.
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
    ],
    version = 1,
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

    companion object {
        const val GUEST_ACCOUNT = "guest"

        fun databaseName(accountId: String): String =
            if (accountId == GUEST_ACCOUNT) "nota_guest" else "nota_$accountId"

        fun build(context: Context, accountId: String = GUEST_ACCOUNT): NotaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                NotaDatabase::class.java,
                databaseName(accountId),
            ).build()
    }
}
