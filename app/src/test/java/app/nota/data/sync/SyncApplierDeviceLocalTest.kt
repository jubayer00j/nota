package app.nota.data.sync

import androidx.room.Room
import app.nota.data.attachments.AttachmentStore
import app.nota.data.backup.BackupManifest
import app.nota.data.backup.BackupWriter
import app.nota.data.backup.BxNote
import app.nota.data.backup.BxReminder
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.db.ReminderEntity
import app.nota.data.reminders.AlarmToneMode
import app.nota.data.reminders.ReminderAlertType
import app.nota.data.repository.NoteRepository
import app.nota.data.templates.TemplateRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * PR #45 fix round (Codex round 1): a sync apply must not erase
 * DEVICE-LOCAL reminder fields. The sync wire format (BxReminder)
 * carries no alertType and no tone fields, so applying the merged
 * reminder used to replace the local row with defaults — a
 * phone-alarm reminder silently became a notification reminder and
 * its picked tone was forgotten. An existing row's local fields
 * carry over the apply; the wire fields (trigger, status) still
 * update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncApplierDeviceLocalTest {

    private lateinit var db: NotaDatabase
    private lateinit var provider: DatabaseProvider
    private lateinit var applier: SyncApplier

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        provider = DatabaseProvider.forDatabase(context, db)
        applier = SyncApplier(
            context,
            provider,
            AttachmentStore(context, provider),
            TemplateRepository(context),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun bxNote(id: String) = BxNote(
        id = id, title = "Dentist", content = "<p>Dentist</p>",
        createdAt = 10L, updatedAt = 100L, folderId = null,
        tagIds = emptyList(), pinned = false, favorite = false,
        archived = false, deleted = false, deletedAt = null,
        reminderAt = null, wordCount = 1, dailyDate = null,
        lockJson = null, isTemplate = false, dailyDefault = null,
    )

    private fun exportData(noteId: String, reminder: BxReminder) =
        BackupWriter.ExportData(
            manifest = BackupManifest(
                backupFormatVersion = 2, ownerId = "uid-1",
                libraryId = "lib", nomaVersion = "0.24.0",
                createdAt = "2026-11-10T00:00:00Z", noteCount = 1,
                folderCount = 0, tagCount = 0, attachmentCount = 0,
                reminderCount = 1, deviceId = "device-a",
            ),
            folders = emptyList(), tags = emptyList(),
            reminders = listOf(reminder),
            syncSyncedAtIso = "2026-11-10T00:00:00Z",
            notes = listOf(bxNote(noteId)),
            attachments = emptyList(), tombstones = emptyList(),
        )

    @Test
    fun syncApplyPreservesDeviceLocalReminderFields() = runBlocking {
        val note = NoteRepository(provider).createNote(
            title = "Dentist", plainBody = "teeth",
        )
        db.reminderDao().upsert(
            ReminderEntity(
                id = "r1", noteId = note.id, triggerAt = 1_000L,
                enabled = true, createdAt = 10L,
                alertType = ReminderAlertType.PHONE_ALARM,
                alarmToneMode = AlarmToneMode.CUSTOM,
                alarmToneUri = "content://docs/alarm.mp3",
                alarmToneName = "alarm.mp3",
                alarmVibrate = false,
            ),
        )
        val merged = exportData(
            note.id,
            BxReminder(
                id = "r1", noteId = note.id, scheduledAt = 2_000L,
                status = "pending", createdAt = 10L, updatedAt = 100L,
            ),
        )
        applier.apply(
            merged = merged,
            remoteHandle = null,
            conflictAttachmentSources = emptyMap(),
            knownEntityIds = mapOf(
                EntityHash.ENTITY_NOTE to setOf(note.id),
                EntityHash.ENTITY_FOLDER to emptySet(),
                EntityHash.ENTITY_TAG to emptySet(),
                EntityHash.ENTITY_REMINDER to setOf("r1"),
                EntityHash.ENTITY_ATTACHMENT to emptySet(),
            ),
            mergedAttachmentContent = emptyMap(),
            localAttachmentContent = emptyMap(),
        )
        val row = db.reminderDao().getById("r1")
        assertEquals(2_000L, row?.triggerAt)
        assertEquals(ReminderAlertType.PHONE_ALARM, row?.alertType)
        assertEquals(AlarmToneMode.CUSTOM, row?.alarmToneMode)
        assertEquals("content://docs/alarm.mp3", row?.alarmToneUri)
        assertEquals("alarm.mp3", row?.alarmToneName)
        assertFalse(row?.alarmVibrate ?: true)
    }

    /**
     * PR #53: the repeat rule is device-local on the same terms — a
     * merge updates the wire fields (the trigger moves) but must not
     * strip the series this device is running.
     */
    @Test
    fun syncApplyPreservesTheRepeatRule() = runBlocking {
        val note = NoteRepository(provider).createNote(
            title = "Dentist", plainBody = "teeth",
        )
        db.reminderDao().upsert(
            ReminderEntity(
                id = "r1", noteId = note.id, triggerAt = 1_000L,
                enabled = true, createdAt = 10L,
                alertType = ReminderAlertType.NOTIFICATION,
                repeatRule = "DAILY",
            ),
        )
        val merged = exportData(
            note.id,
            BxReminder(
                id = "r1", noteId = note.id, scheduledAt = 2_000L,
                status = "pending", createdAt = 10L, updatedAt = 100L,
            ),
        )
        applier.apply(
            merged = merged,
            remoteHandle = null,
            conflictAttachmentSources = emptyMap(),
            knownEntityIds = mapOf(
                EntityHash.ENTITY_NOTE to setOf(note.id),
                EntityHash.ENTITY_FOLDER to emptySet(),
                EntityHash.ENTITY_TAG to emptySet(),
                EntityHash.ENTITY_REMINDER to setOf("r1"),
                EntityHash.ENTITY_ATTACHMENT to emptySet(),
            ),
            mergedAttachmentContent = emptyMap(),
            localAttachmentContent = emptyMap(),
        )
        val row = db.reminderDao().getById("r1")
        assertEquals(2_000L, row?.triggerAt)
        assertEquals("DAILY", row?.repeatRule)
    }
}
