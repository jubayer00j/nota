package app.nota.data.reminders

import androidx.room.Room
import app.nota.data.attachments.AttachmentStore
import app.nota.data.backup.BackupRepository
import app.nota.data.backup.RestoreMode
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.repository.NoteRepository
import app.nota.data.templates.TemplateRepository
import java.io.File
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
 * Phase 5.1: a reminder set through the UI path (ReminderRepository)
 * must be exactly what the backup layer exports — the pending
 * BxReminder row AND the note's reminderAt mirror — because the
 * crossover reads the same Room tables. Removing it through the UI
 * path must clear both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderBackupConsistencyRobolectricTest {

    private lateinit var db: NotaDatabase
    private lateinit var notes: NoteRepository
    private lateinit var reminders: ReminderRepository
    private lateinit var backup: BackupRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val provider = DatabaseProvider.forDatabase(context, db)
        notes = NoteRepository(provider)
        reminders = ReminderRepository(provider)
        backup = BackupRepository(
            context,
            provider,
            TemplateRepository(context),
            AttachmentStore(context, provider),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun uiPathSetIsExportedAsPendingReminderAndReminderAt() = runBlocking {
        val note = notes.createNote(title = "Export me", plainBody = "body")
        val triggerAt = 1_900_000_000_000L
        val saved = reminders.setReminder(
            note.id, triggerAt = triggerAt,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
        )

        val export = backup.collectExport(nowMillis = 5_000L)
        val bxReminder = export.reminders.singleOrNull { it.id == saved.id }
        assertTrue("export must carry the BxReminder", bxReminder != null)
        assertEquals(note.id, bxReminder!!.noteId)
        assertEquals(triggerAt, bxReminder.scheduledAt)
        assertEquals("pending", bxReminder.status)
        val bxNote = export.notes.single { it.id == note.id }
        assertEquals(triggerAt, bxNote.reminderAt)
    }

    /**
     * PR #45: the tone fields are device-local like alertType — the
     * full backup round-trip must bring the reminder back with the
     * system default tone and vibrate on (and, per the 2c contract,
     * as a notification reminder), never the tone picked here.
     */
    @Test
    fun toneFieldsDoNotSurviveABackupRoundTrip() = runBlocking {
        val note = notes.createNote(title = "Tone trip", plainBody = "body")
        val triggerAt = 1_900_000_000_000L
        reminders.setReminder(
            note.id, triggerAt = triggerAt,
            alertType = ReminderAlertType.PHONE_ALARM, nowMillis = 1_000L,
            tone = AlarmToneSettings(
                mode = AlarmToneMode.SYSTEM_PICKER,
                uri = "content://media/internal/audio/42",
                name = "Morning",
                vibrate = false,
            ),
        )
        val zip = File.createTempFile("nota-tone-roundtrip", ".zip")
        try {
            backup.exportToFile(zip, null)

            val context = RuntimeEnvironment.getApplication()
            val db2 = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            try {
                val provider2 = DatabaseProvider.forDatabase(context, db2)
                val backup2 = BackupRepository(
                    context,
                    provider2,
                    TemplateRepository(context),
                    AttachmentStore(context, provider2),
                )
                backup2.restore(zip, null, RestoreMode.REPLACE)
                val restored = db2.reminderDao().getForNote(note.id).single()
                assertEquals(ReminderAlertType.NOTIFICATION, restored.alertType)
                assertEquals(AlarmToneMode.SYSTEM, restored.alarmToneMode)
                assertNull(restored.alarmToneUri)
                assertNull(restored.alarmToneName)
                assertTrue(restored.alarmVibrate)
                assertTrue(restored.enabled)
            } finally {
                db2.close()
            }
        } finally {
            zip.delete()
        }
    }

    @Test
    fun uiPathRemoveClearsExportAndFiredReminderExportsDismissed() = runBlocking {
        val note = notes.createNote(title = "Export me too", plainBody = "body")
        val triggerAt = 1_900_000_000_000L
        val saved = reminders.setReminder(
            note.id, triggerAt = triggerAt,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
        )

        // Fired → exported as dismissed, reminderAt cleared.
        reminders.markFired(saved)
        val afterFire = backup.collectExport(nowMillis = 5_000L)
        assertEquals(
            "dismissed",
            afterFire.reminders.single { it.id == saved.id }.status,
        )
        assertNull(afterFire.notes.single { it.id == note.id }.reminderAt)

        // Re-set then remove → nothing exported at all.
        reminders.setReminder(
            note.id, triggerAt = triggerAt,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 6_000L,
        )
        reminders.removeForNote(note.id, nowMillis = 7_000L)
        val afterRemove = backup.collectExport(nowMillis = 8_000L)
        assertTrue(afterRemove.reminders.none { it.noteId == note.id })
        assertNull(afterRemove.notes.single { it.id == note.id }.reminderAt)
    }
}
