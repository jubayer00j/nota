package app.nota.data.reminders

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.repository.NoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Phase 5.1: the fire-time flow — an unlocked note posts a titled
 * notification; a LOCKED note posts only the generic pair (its title
 * appears nowhere in the posted notification); a trashed note posts
 * nothing. Every path spends the one-shot and drops it from the
 * armed bookkeeping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderFireHandlerRobolectricTest {

    private class FakeGateway : ReminderAlarmGateway {
        val armed = mutableSetOf<String>()
        val armTimes = mutableMapOf<String, Long>()
        override fun arm(reminderId: String, triggerAtMillis: Long) {
            armed += reminderId
            armTimes[reminderId] = triggerAtMillis
        }
        override fun disarm(reminderId: String) {
            armed -= reminderId
            armTimes -= reminderId
        }
        override fun armedReminderIds(): Set<String> = armed.toSet()
        override fun noteArmedReminderIds(ids: Set<String>) {
            armed.clear()
            armed += ids
        }
        override fun canScheduleExactAlarms(): Boolean = true
    }

    private lateinit var db: NotaDatabase
    private lateinit var notes: NoteRepository
    private lateinit var reminders: ReminderRepository
    private lateinit var gateway: FakeGateway
    private lateinit var handler: ReminderFireHandler
    private lateinit var notificationManager: NotificationManager
    private lateinit var shadowNotifications: ShadowNotificationManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val provider = DatabaseProvider.forDatabase(context, db)
        notes = NoteRepository(provider)
        reminders = ReminderRepository(provider)
        gateway = FakeGateway()
        handler = ReminderFireHandler(
            context,
            provider,
            reminders,
            ReminderScheduler(gateway, provider),
        )
        notificationManager = context.getSystemService(NotificationManager::class.java)
        shadowNotifications = Shadow.extract(notificationManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun futureTrigger() = System.currentTimeMillis() + 3_600_000L

    @Test
    fun unlockedNotePostsTitledNotificationAndSpendsReminder() = runBlocking {
        val note = notes.createNote(title = "Dentist appointment", plainBody = "teeth")
        val saved = reminders.setReminder(
            note.id, triggerAt = futureTrigger(),
            alertType = ReminderAlertType.NOTIFICATION,
        )
        gateway.arm(saved.id, saved.triggerAt)

        handler.onAlarmFired(saved.id)

        val posted = shadowNotifications.allNotifications
        assertEquals(1, posted.size)
        val notification = posted.single()
        assertEquals(
            "Dentist appointment",
            notification.extras.getCharSequence("android.title")?.toString(),
        )
        val text = notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(text.startsWith("Reminder · "))
        // Spent + disarmed.
        assertFalse(db.reminderDao().getById(saved.id)!!.enabled)
        assertTrue(gateway.armed.isEmpty())
        // The channel the notification targets is the 5.1 channel.
        assertEquals(ReminderNotifications.CHANNEL_ID, notification.channelId)
    }

    @Test
    fun lockedNotePostsGenericNotificationOnly() = runBlocking {
        val note = notes.createNote(title = "Therapy session notes", plainBody = "secret")
        // Mark the note locked the way the lock repository leaves it:
        // the fire path only reads the locked flag + title.
        db.noteDao().upsert(note.copy(locked = true))
        val saved = reminders.setReminder(
            note.id, triggerAt = futureTrigger(),
            alertType = ReminderAlertType.NOTIFICATION,
        )

        handler.onAlarmFired(saved.id)

        val posted = shadowNotifications.allNotifications
        assertEquals(1, posted.size)
        val notification = posted.single()
        val title = notification.extras.getCharSequence("android.title")?.toString()
        val text = notification.extras.getCharSequence("android.text")?.toString()
        assertEquals("Reminder", title)
        assertEquals("You have a note reminder", text)
        assertFalse(title.orEmpty().contains("Therapy"))
        assertFalse(text.orEmpty().contains("Therapy"))
    }

    @Test
    fun trashedNotePostsNothingAndReminderIsSpent() = runBlocking {
        val note = notes.createNote(title = "Old plan", plainBody = "")
        val saved = reminders.setReminder(
            note.id, triggerAt = futureTrigger(),
            alertType = ReminderAlertType.NOTIFICATION,
        )
        notes.trash(note)

        handler.onAlarmFired(saved.id)

        assertTrue(shadowNotifications.allNotifications.isEmpty())
        assertFalse(db.reminderDao().getById(saved.id)!!.enabled)
    }

    // --- PR #53: a fired repeat advances instead of spending -----

    @Test
    fun repeatingReminderFiresAdvancesAndRearms() = runBlocking {
        val note = notes.createNote(title = "Water the plants", plainBody = "")
        val anchor = System.currentTimeMillis() + 60_000L
        val saved = reminders.setReminder(
            note.id, triggerAt = anchor,
            alertType = ReminderAlertType.NOTIFICATION,
            repeatRule = "DAILY",
        )
        gateway.arm(saved.id, saved.triggerAt)

        handler.onAlarmFired(saved.id, nowMillis = anchor)

        // Posted exactly like a one-shot fire…
        assertEquals(1, shadowNotifications.allNotifications.size)
        // …but the row is NOT spent: still enabled, trigger moved to
        // the next occurrence, and the gateway holds the same id
        // armed at the new time.
        val row = db.reminderDao().getById(saved.id)!!
        assertTrue(row.enabled)
        assertTrue(row.triggerAt > anchor)
        assertEquals(setOf(saved.id), gateway.armed)
        assertEquals(row.triggerAt, gateway.armTimes[saved.id])
    }

    @Test
    fun repeatingReminderOnTrashedNoteSpendsAndDoesNotAdvance() = runBlocking {
        val note = notes.createNote(title = "Old series", plainBody = "")
        val anchor = System.currentTimeMillis() + 60_000L
        val saved = reminders.setReminder(
            note.id, triggerAt = anchor,
            alertType = ReminderAlertType.NOTIFICATION,
            repeatRule = "DAILY",
        )
        notes.trash(note)

        handler.onAlarmFired(saved.id, nowMillis = anchor)

        // The series ends with the note: nothing posted, row spent,
        // trigger untouched (no advance past a dead note).
        assertTrue(shadowNotifications.allNotifications.isEmpty())
        val row = db.reminderDao().getById(saved.id)!!
        assertFalse(row.enabled)
        assertEquals(anchor, row.triggerAt)
    }

    @Test
    fun unknownReminderIdIsASilentNoOp() = runBlocking {
        gateway.arm("ghost", futureTrigger())
        handler.onAlarmFired("ghost")
        assertTrue(shadowNotifications.allNotifications.isEmpty())
        assertTrue(gateway.armed.isEmpty())
    }

    @Test
    fun rescheduleAllArmsOnlyTheActiveAccountsDueReminders() = runBlocking {
        val live = notes.createNote(title = "Live", plainBody = "")
        val trashed = notes.createNote(title = "Trashed", plainBody = "")
        val due = reminders.setReminder(
            live.id, triggerAt = futureTrigger(),
            alertType = ReminderAlertType.NOTIFICATION,
        )
        reminders.setReminder(
            live.id, triggerAt = futureTrigger(),
            alertType = ReminderAlertType.NOTIFICATION,
        )
        reminders.setReminder(
            trashed.id, triggerAt = futureTrigger(),
            alertType = ReminderAlertType.NOTIFICATION,
        )
        val phoneAlarmNote = notes.createNote(title = "Alarm", plainBody = "")
        reminders.setReminder(
            phoneAlarmNote.id, triggerAt = futureTrigger(),
            alertType = ReminderAlertType.PHONE_ALARM,
        )
        notes.trash(trashed)
        // A leftover from a previous account is in the armed set.
        gateway.arm("other-account-leftover", futureTrigger())

        ReminderScheduler(gateway, DatabaseProvider.forDatabase(
            RuntimeEnvironment.getApplication(), db,
        )).rescheduleAll()

        assertEquals(setOf(due.id), gateway.armed)
    }
}
