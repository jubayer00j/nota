package app.nota.data.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5.1: the notification/request-code derivation must match
 * 1.x's `reminderNotificationId` exactly (djb2-xor, unsigned,
 * mod 2^31-1, never 0) — the expected values below were produced by
 * running 1.x's algorithm itself.
 */
class ReminderIdsTest {

    @Test
    fun matchesOneXHashVectors() {
        assertEquals(2007429684, ReminderIds.notificationId("550e8400-e29b-41d4-a716-446655440000"))
        assertEquals(946385272, ReminderIds.notificationId("reminder-1"))
        assertEquals(193409669, ReminderIds.notificationId("abc"))
        assertEquals(177604, ReminderIds.notificationId("a"))
        assertEquals(5381, ReminderIds.notificationId(""))
    }

    @Test
    fun stableAcrossCallsAndDistinctAcrossIds() {
        val first = ReminderIds.notificationId("550e8400-e29b-41d4-a716-446655440000")
        assertEquals(first, ReminderIds.notificationId("550e8400-e29b-41d4-a716-446655440000"))
        assertEquals(first, ReminderIds.requestCode("550e8400-e29b-41d4-a716-446655440000"))
        val others = listOf("reminder-1", "reminder-2", "abc", "abd", "note-xyz")
            .map { ReminderIds.notificationId(it) }
        assertEquals(others.size, others.toSet().size)
        assertTrue(others.none { it == first })
    }

    @Test
    fun neverZeroAndAlwaysPositive() {
        val ids = (0..500).map { "reminder-$it" } + listOf("", "a", "reminder-xyz")
        ids.forEach { id ->
            val value = ReminderIds.notificationId(id)
            assertTrue("id for '$id' must be >= 1, was $value", value >= 1)
        }
    }
}
