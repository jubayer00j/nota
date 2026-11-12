package app.nota.data.reminders

import androidx.room.Room
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.repository.NoteRepository
import app.nota.data.sync.EntityHash
import java.time.Instant
import java.time.ZoneId
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

    // --- PR #53: repeats are stored, advanced, reconciled --------

    @Test
    fun setReminderStoresTheRepeatRuleCanonically() = runBlocking {
        val note = notes.createNote(title = "Standup", plainBody = "")
        val saved = reminders.setReminder(
            note.id, triggerAt = 1_900_000_000_000L,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
            repeatRule = "days:5,1,5", zone = ZoneId.of("UTC"),
        )
        // Parsed and re-encoded: lowercase + duplicates + disorder
        // never reach the row. Round 2: the stored form also
        // carries the series time derived from the saved trigger
        // (1_900_000_000_000 is 17:46 UTC).
        assertEquals("DAYS:1,5@17:46", saved.repeatRule)
        assertEquals(
            "DAYS:1,5@17:46",
            db.reminderDao().getById(saved.id)?.repeatRule,
        )

        // The update-in-place path stores the rule too — and an edit
        // back to no rule clears it to a one-shot.
        val cleared = reminders.setReminder(
            note.id, triggerAt = 1_900_003_600_000L,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 2_000L,
        )
        assertEquals(saved.id, cleared.id)
        assertNull(cleared.repeatRule)
        assertNull(db.reminderDao().getById(saved.id)?.repeatRule)
    }

    @Test
    fun setReminderStoresTheSeriesTimeFromTheSavedTrigger() = runBlocking {
        val note = notes.createNote(title = "Early bird", plainBody = "")
        // 02:30 UTC on the nose: the stored rule carries the saved
        // trigger's local time as its @HH:MM series time (PR #53
        // round 2), so the series can never drift off it.
        val saved = reminders.setReminder(
            note.id,
            triggerAt = Instant.parse("2030-03-17T02:30:00Z").toEpochMilli(),
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
            repeatRule = "DAILY", zone = ZoneId.of("UTC"),
        )
        assertEquals("DAILY@02:30", saved.repeatRule)
        assertEquals(
            "DAILY@02:30",
            db.reminderDao().getById(saved.id)?.repeatRule,
        )

        // The update-in-place path re-derives the suffix from the
        // NEW trigger being saved — same row, new series time.
        val updated = reminders.setReminder(
            note.id,
            triggerAt = Instant.parse("2030-03-18T09:15:00Z").toEpochMilli(),
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 2_000L,
            repeatRule = "DAILY", zone = ZoneId.of("UTC"),
        )
        assertEquals(saved.id, updated.id)
        assertEquals("DAILY@09:15", updated.repeatRule)
        assertEquals(
            "DAILY@09:15",
            db.reminderDao().getById(saved.id)?.repeatRule,
        )
    }

    @Test
    fun setReminderDegradesAGarbageRuleToOneShot() = runBlocking {
        val note = notes.createNote(title = "Garbage rule", plainBody = "")
        val saved = reminders.setReminder(
            note.id, triggerAt = 1_900_000_000_000L,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
            repeatRule = "FORTNIGHTLY",
        )
        assertNull(saved.repeatRule)
    }

    @Test
    fun markFiredAdvancesARepeatingReminder() = runBlocking {
        val note = notes.createNote(title = "Daily pill", plainBody = "")
        // Minute-aligned, like every real picked trigger: the
        // stored rule's @HH:MM series time (round 2) then carries
        // the trigger exactly, with no seconds to lose.
        val triggerAt = 1_900_000_020_000L
        val saved = reminders.setReminder(
            note.id, triggerAt = triggerAt,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
            repeatRule = "DAILY", zone = ZoneId.of("UTC"),
        )
        val outcome = reminders.markFired(
            saved, nowMillis = triggerAt, zone = ZoneId.of("UTC"),
        )
        assertTrue(outcome is ReminderRepository.FireOutcome.Advanced)
        val row = db.reminderDao().getById(saved.id)
        assertNotNull(row)
        assertTrue(row!!.enabled)
        // UTC has no DST: the next daily occurrence is +24h sharp.
        assertEquals(triggerAt + 86_400_000L, row.triggerAt)
        assertEquals(row, reminders.pendingForNote(note.id))
    }

    @Test
    fun advanceOverdueRepeatsMovesOnlyEnabledRepeats() = runBlocking {
        // Minute-aligned base (see markFiredAdvancesARepeatingReminder).
        val base = 1_900_000_020_000L
        val dailyNote = notes.createNote(title = "Daily", plainBody = "")
        val onceNote = notes.createNote(title = "Once", plainBody = "")
        val futureNote = notes.createNote(title = "Future", plainBody = "")
        val spentNote = notes.createNote(title = "Spent", plainBody = "")
        val daily = reminders.setReminder(
            dailyNote.id, triggerAt = base,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
            repeatRule = "DAILY", zone = ZoneId.of("UTC"),
        )
        val oneShot = reminders.setReminder(
            onceNote.id, triggerAt = base,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
        )
        val future = reminders.setReminder(
            futureNote.id, triggerAt = base + 7_200_000L,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
            repeatRule = "DAILY", zone = ZoneId.of("UTC"),
        )
        val spent = reminders.setReminder(
            spentNote.id, triggerAt = base,
            alertType = ReminderAlertType.NOTIFICATION, nowMillis = 1_000L,
            repeatRule = "DAILY",
        )
        reminders.markSpent(spent)

        // One hour past the base time: only the enabled, overdue
        // DAILY row advances (to the next day); the overdue one-shot
        // keeps its Overdue semantics, the future repeat is not due,
        // and the spent row stays spent where it was.
        val advanced = reminders.advanceOverdueRepeats(
            nowMillis = base + 3_600_000L, zone = ZoneId.of("UTC"),
        )
        assertEquals(1, advanced)
        assertEquals(base + 86_400_000L, db.reminderDao().getById(daily.id)?.triggerAt)
        assertEquals(base, db.reminderDao().getById(oneShot.id)?.triggerAt)
        assertEquals(base + 7_200_000L, db.reminderDao().getById(future.id)?.triggerAt)
        assertEquals(base, db.reminderDao().getById(spent.id)?.triggerAt)
    }

    @Test
    fun advanceOverdueRepeatsMovesPhoneAlarmRowsToo() = runBlocking {
        // Minute-aligned base (see markFiredAdvancesARepeatingReminder).
        val base = 1_900_000_020_000L
        val note = notes.createNote(title = "Wake up", plainBody = "")
        val alarm = reminders.setReminder(
            note.id, triggerAt = base,
            alertType = ReminderAlertType.PHONE_ALARM, nowMillis = 1_000L,
            repeatRule = "DAILY", zone = ZoneId.of("UTC"),
        )

        // The Clock app gives Nota no fire signal, so a phone-alarm
        // repeat's row is advanced only by this reconciliation pass:
        // one hour past the base time it moves to the next day, the
        // same as a notification repeat.
        val advanced = reminders.advanceOverdueRepeats(
            nowMillis = base + 3_600_000L, zone = ZoneId.of("UTC"),
        )
        assertEquals(1, advanced)
        assertEquals(base + 86_400_000L, db.reminderDao().getById(alarm.id)?.triggerAt)
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
