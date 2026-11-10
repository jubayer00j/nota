package app.nota.data.reminders

import androidx.room.Room
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.repository.NoteRepository
import app.nota.data.sync.EntityHash
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase 5.1: the ReminderRepository contract under Robolectric —
 * ONE pending reminder per note (in-place replace, 1.x parity),
 * tombstoned removal, the fired state transition, and the FK
 * cascade: hard-deleting a note takes its reminder rows with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderRepositoryRobolectricTest {

    private lateinit var db: NotaDatabase
    private lateinit var notes: NoteRepository
    private lateinit var reminders: ReminderRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val provider = DatabaseProvider.forDatabase(context, db)
        notes = NoteRepository(provider)
        reminders = ReminderRepository(provider)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun setThenReplaceKeepsOneRowAndTheSameId() = runBlocking {
        val note = notes.createNote(title = "Dentist", plainBody = "teeth")
        val first = reminders.setReminder(
            note.id, triggerAt = 1_900_000_000_000L,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
        )
        val replaced = reminders.setReminder(
            note.id, triggerAt = 1_900_003_600_000L,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 2_000L,
        )
        assertEquals(first.id, replaced.id)
        assertEquals(1_900_003_600_000L, replaced.triggerAt)
        assertTrue(replaced.enabled)
        assertNull(replaced.repeatRule)
        val rows = db.reminderDao().getForNote(note.id)
        assertEquals(1, rows.size)
        assertEquals(replaced, reminders.pendingForNote(note.id))
    }

    @Test
    fun setCollapsesPreExistingDuplicateRows() = runBlocking {
        val note = notes.createNote(title = "Dup", plainBody = "")
        // Two stray rows, as a sync merge could in principle land.
        db.reminderDao().upsert(
            app.nota.data.db.ReminderEntity(
                id = "stray-1", noteId = note.id, triggerAt = 5_000L,
                enabled = true, createdAt = 1L,
            ),
        )
        db.reminderDao().upsert(
            app.nota.data.db.ReminderEntity(
                id = "stray-2", noteId = note.id, triggerAt = 6_000L,
                enabled = true, createdAt = 2L,
            ),
        )
        val saved = reminders.setReminder(
            note.id, triggerAt = 1_900_000_000_000L,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 3_000L,
        )
        val rows = db.reminderDao().getForNote(note.id)
        assertEquals(1, rows.size)
        assertEquals(saved.id, rows.single().id)
        // The dropped extra was tombstoned for sync.
        val tombstones = db.syncDao().getTombstones()
        assertTrue(
            tombstones.any {
                it.entity == EntityHash.ENTITY_REMINDER && it.id != saved.id
            },
        )
    }

    @Test
    fun removeForNoteDeletesRowsAndRecordsTombstone() = runBlocking {
        val note = notes.createNote(title = "Gone", plainBody = "")
        val saved = reminders.setReminder(
            note.id, triggerAt = 1_900_000_000_000L,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
        )
        reminders.removeForNote(note.id, nowMillis = 2_000L)
        assertTrue(db.reminderDao().getForNote(note.id).isEmpty())
        assertNull(reminders.pendingForNote(note.id))
        val tombstones = db.syncDao().getTombstones()
        assertTrue(
            tombstones.any {
                it.entity == EntityHash.ENTITY_REMINDER && it.id == saved.id
            },
        )
    }

    @Test
    fun markFiredSpendsTheReminder() = runBlocking {
        val note = notes.createNote(title = "Fire me", plainBody = "")
        val saved = reminders.setReminder(
            note.id, triggerAt = 1_900_000_000_000L,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
        )
        reminders.markFired(saved)
        val row = db.reminderDao().getById(saved.id)
        assertNotNull(row)
        assertFalse(row!!.enabled)
        // No longer pending anywhere.
        assertNull(reminders.pendingForNote(note.id))
        assertNull(reminders.observePendingForNote(note.id).first())
    }

    @Test
    fun hardDeletingTheNoteCascadesTheReminderRows() = runBlocking {
        val note = notes.createNote(title = "Cascade", plainBody = "")
        reminders.setReminder(
            note.id, triggerAt = 1_900_000_000_000L,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
        )
        assertEquals(1, db.reminderDao().getAll().size)
        notes.delete(note)
        assertTrue(db.reminderDao().getAll().isEmpty())
    }

    @Test
    fun phoneAlarmTypeIsStoredAndRepeatStaysDormant() = runBlocking {
        val note = notes.createNote(title = "Wake up", plainBody = "")
        val saved = reminders.setReminder(
            note.id, triggerAt = 1_900_000_000_000L,
            alertType = ReminderAlertType.PHONE_ALARM, nowMillis = 1_000L,
        )
        assertEquals(ReminderAlertType.PHONE_ALARM, saved.alertType)
        assertNull(saved.repeatRule)
        assertEquals(saved, db.reminderDao().getById(saved.id))
    }

    @Test
    fun setReminderPersistsToneFieldsAndUpdateKeepsTheSameId() = runBlocking {
        val note = notes.createNote(title = "Wake up", plainBody = "")
        val saved = reminders.setReminder(
            note.id, triggerAt = 1_900_000_000_000L,
            alertType = ReminderAlertType.PHONE_ALARM, nowMillis = 1_000L,
            tone = AlarmToneSettings(
                mode = AlarmToneMode.SYSTEM_PICKER,
                uri = "content://media/internal/audio/42",
                name = "Morning",
                vibrate = false,
            ),
        )
        assertEquals(AlarmToneMode.SYSTEM_PICKER, saved.alarmToneMode)
        assertEquals("content://media/internal/audio/42", saved.alarmToneUri)
        assertEquals("Morning", saved.alarmToneName)
        assertFalse(saved.alarmVibrate)
        assertEquals(saved, db.reminderDao().getById(saved.id))

        // In-place update (the 5.1 contract): same id, tone replaced.
        val updated = reminders.setReminder(
            note.id, triggerAt = 1_900_003_600_000L,
            alertType = ReminderAlertType.PHONE_ALARM, nowMillis = 2_000L,
            tone = AlarmToneSettings(
                mode = AlarmToneMode.CUSTOM,
                uri = "content://docs/song.mp3",
                name = "song.mp3",
                vibrate = true,
            ),
        )
        assertEquals(saved.id, updated.id)
        assertEquals(AlarmToneMode.CUSTOM, updated.alarmToneMode)
        assertEquals("content://docs/song.mp3", updated.alarmToneUri)
        assertEquals("song.mp3", updated.alarmToneName)
        assertTrue(updated.alarmVibrate)
        assertEquals(1, db.reminderDao().getForNote(note.id).size)
    }

    @Test
    fun setReminderDefaultsToneToSystemDefaultAndVibrateOn() = runBlocking {
        val note = notes.createNote(title = "Plain", plainBody = "")
        val saved = reminders.setReminder(
            note.id, triggerAt = 1_900_000_000_000L,
            alertType = ReminderAlertType.PHONE_ALARM, nowMillis = 1_000L,
        )
        assertEquals(AlarmToneMode.SYSTEM, saved.alarmToneMode)
        assertNull(saved.alarmToneUri)
        assertNull(saved.alarmToneName)
        assertTrue(saved.alarmVibrate)
    }

    @Test
    fun systemModeToneNeverStoresAStaleUri() = runBlocking {
        val note = notes.createNote(title = "Reset tone", plainBody = "")
        val saved = reminders.setReminder(
            note.id, triggerAt = 1_900_000_000_000L,
            alertType = ReminderAlertType.PHONE_ALARM, nowMillis = 1_000L,
            tone = AlarmToneSettings(
                mode = AlarmToneMode.SYSTEM,
                uri = "content://stale/tone",
                name = "Stale",
                vibrate = false,
            ),
        )
        assertEquals(AlarmToneMode.SYSTEM, saved.alarmToneMode)
        assertNull(saved.alarmToneUri)
        assertNull(saved.alarmToneName)
        assertFalse(saved.alarmVibrate)
    }

    @Test
    fun observeRowsJoinsTitlesAndHidesTrashedNotes() = runBlocking {
        val kept = notes.createNote(title = "Kept note", plainBody = "")
        val trashed = notes.createNote(title = "Trashed note", plainBody = "")
        reminders.setReminder(
            kept.id, triggerAt = 1_900_000_000_000L,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
        )
        reminders.setReminder(
            trashed.id, triggerAt = 1_900_000_100_000L,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
        )
        notes.trash(trashed)
        val rows = reminders.observeRows().first()
        assertEquals(1, rows.size)
        assertEquals("Kept note", rows.single().noteTitle)
        assertFalse(rows.single().noteLocked)
    }
}
