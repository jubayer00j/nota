package app.nota.data.db

import androidx.room.Room
import app.nota.data.repository.NoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase 6 extras (PR #45): MIGRATION_6_7 adds the four device-local
 * tone columns to reminders. The v6-shaped table is recreated by
 * hand (its exact DDL is the v6 exported schema's createSql) inside
 * a live database, seeded with a phone-alarm row, and the REAL
 * migration object runs against it: the new columns must exist
 * with the prescribed types/defaults, and the pre-existing row must
 * read back with the behaviour every pre-extras reminder had —
 * system tone, no URI/name, vibrate on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration67RobolectricTest {

    private lateinit var db: NotaDatabase

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** The v6 reminders DDL (exported schema 6.json, ${TABLE_NAME}
     *  resolved) — the shape installs upgrading to v7 actually have. */
    private fun recreateRemindersAsV6(noteId: String) {
        val supportDb = db.openHelper.writableDatabase
        supportDb.execSQL("DROP TABLE IF EXISTS `reminders`")
        supportDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, " +
                "`noteId` TEXT NOT NULL, `triggerAt` INTEGER NOT NULL, " +
                "`repeatRule` TEXT, `enabled` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `alertType` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        supportDb.execSQL(
            "INSERT INTO `reminders` (`id`, `noteId`, `triggerAt`, `repeatRule`, " +
                "`enabled`, `createdAt`, `alertType`) VALUES " +
                "('r-v6', '$noteId', 1900000000000, NULL, 1, 1000, 'phone-alarm')",
        )
    }

    /** PRAGMA table_info(reminders): column name -> (type, notnull, default). */
    private fun columnInfo(): Map<String, Triple<String, Int, String?>> {
        val out = mutableMapOf<String, Triple<String, Int, String?>>()
        val cursor = db.openHelper.writableDatabase
            .query("PRAGMA table_info(`reminders`)")
        cursor.use {
            val nameIdx = it.getColumnIndex("name")
            val typeIdx = it.getColumnIndex("type")
            val notNullIdx = it.getColumnIndex("notnull")
            val defaultIdx = it.getColumnIndex("dflt_value")
            while (it.moveToNext()) {
                out[it.getString(nameIdx)] = Triple(
                    it.getString(typeIdx),
                    it.getInt(notNullIdx),
                    if (it.isNull(defaultIdx)) null else it.getString(defaultIdx),
                )
            }
        }
        return out
    }

    @Test
    fun migrationAddsTheFourToneColumnsWithDefaults() = runBlocking {
        val note = NoteRepository(DatabaseProvider.forDatabase(
            RuntimeEnvironment.getApplication(), db,
        )).createNote(title = "Migrate me", plainBody = "")
        recreateRemindersAsV6(note.id)

        MIGRATION_6_7.migrate(db.openHelper.writableDatabase)

        val columns = columnInfo()
        assertEquals(Triple("TEXT", 1, "'system'"), columns["alarmToneMode"])
        assertEquals(Triple("TEXT", 0, null), columns["alarmToneUri"])
        assertEquals(Triple("TEXT", 0, null), columns["alarmToneName"])
        assertEquals(Triple("INTEGER", 1, "1"), columns["alarmVibrate"])

        // The pre-existing row reads back through the real DAO with
        // the defaults applied.
        val row = db.reminderDao().getById("r-v6")
        assertTrue(row != null)
        assertEquals("phone-alarm", row!!.alertType)
        assertEquals("system", row.alarmToneMode)
        assertNull(row.alarmToneUri)
        assertNull(row.alarmToneName)
        assertTrue(row.alarmVibrate)
    }

    @Test
    fun rowsInsertedWithOnlyV6ColumnsGetToneDefaultsAfterMigration() = runBlocking {
        val note = NoteRepository(DatabaseProvider.forDatabase(
            RuntimeEnvironment.getApplication(), db,
        )).createNote(title = "Insert after", plainBody = "")
        val supportDb = db.openHelper.writableDatabase
        recreateRemindersAsV6(note.id)
        MIGRATION_6_7.migrate(supportDb)
        supportDb.execSQL(
            "INSERT INTO `reminders` (`id`, `noteId`, `triggerAt`, `repeatRule`, " +
                "`enabled`, `createdAt`, `alertType`) VALUES " +
                "('r-new', '${note.id}', 1900000000000, NULL, 1, 2000, 'notification')",
        )
        val row = db.reminderDao().getById("r-new")
        assertTrue(row != null)
        assertEquals("system", row!!.alarmToneMode)
        assertNull(row.alarmToneUri)
        assertNull(row.alarmToneName)
        assertTrue(row.alarmVibrate)
    }
}
