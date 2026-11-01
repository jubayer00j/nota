package app.nota.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lock-record JSON tests (Phase 2b). Robolectric because record parsing
 * uses org.json (an Android stub on the plain JVM). The literal strings
 * below are shaped exactly like the records 1.x's JSON.stringify wrote
 * (field names + field order), including a record whose `lockoutUntil`
 * is ABSENT rather than null and one carrying an unknown future field —
 * a real 1.x record must always parse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteLockRecordTest {

    private val literal1x = """
        {"v":1,"slots":{"password":{"salt":"MDEyMzQ1Njc4OWFiY2RlZg==","iterations":600000,"wrappedKey":"AAAA"}},"biometric":false,"failedAttempts":0,"lockoutUntil":null,"lockedAt":1730000000000}
    """.trimIndent()

    @Test
    fun roundTrip_againstLiteral1xRecord() {
        val record = NoteLockRecord.parse(literal1x)!!
        assertEquals(1, record.v)
        assertEquals(setOf(NoteLockMethod.PASSWORD), record.slots.keys)
        assertEquals(600_000, record.slots[NoteLockMethod.PASSWORD]?.iterations)
        assertEquals(false, record.biometric)
        assertEquals(0, record.failedAttempts)
        assertNull(record.lockoutUntil)
        assertEquals(1730000000000L, record.lockedAt)
        // Serialising the parsed record reproduces the literal byte-for-byte.
        assertEquals(literal1x, record.toJson())
    }

    @Test
    fun parse_toleratesAbsentLockoutAndUnknownFields() {
        val sparse = """
            {"v":1,"slots":{"pin":{"salt":"AA==","iterations":300000,"wrappedKey":"BB=="}},"futureField":{"x":1},"lockedAt":42}
        """.trimIndent()
        val record = NoteLockRecord.parse(sparse)!!
        assertEquals(setOf(NoteLockMethod.PIN), record.slots.keys)
        assertEquals(false, record.biometric)
        assertEquals(0, record.failedAttempts)
        assertNull(record.lockoutUntil)
        assertEquals(42L, record.lockedAt)
    }

    @Test
    fun parse_rejectsGarbage() {
        assertNull(NoteLockRecord.parse(null))
        assertNull(NoteLockRecord.parse(""))
        assertNull(NoteLockRecord.parse("not json"))
        assertNull(NoteLockRecord.parse("[1,2,3]"))
    }

    @Test
    fun lockoutMath_mirrorsRecordFailedAttempt() {
        val base = NoteLockRecord.parse(literal1x)!!
        // Below the threshold: count increments, deadline NOT overwritten.
        val (after4, seconds4) = base.copy(failedAttempts = 3).withFailedAttempt(now = 1_000L)
        assertEquals(4, after4.failedAttempts)
        assertEquals(0L, seconds4)
        assertNull(after4.lockoutUntil)
        // Fifth failure: 30s lockout lands.
        val (after5, seconds5) = after4.withFailedAttempt(now = 1_000L)
        assertEquals(5, after5.failedAttempts)
        assertEquals(30L, seconds5)
        assertEquals(31_000L, after5.lockoutUntil)
        assertEquals(30L, after5.lockoutSeconds(now = 1_000L))
        assertEquals(0L, after5.lockoutSeconds(now = 31_000L))
        // Reset restores the pristine shape.
        val reset = after5.withResetAttempts()
        assertEquals(0, reset.failedAttempts)
        assertNull(reset.lockoutUntil)
        assertTrue(reset.slots.isNotEmpty())
    }

    @Test
    fun multiSlot_serialisesInRecordFieldOrder() {
        val record = NoteLockRecord(
            slots = linkedMapOf(
                NoteLockMethod.PIN to NoteLockSlot("AA==", 300_000, "BB=="),
                NoteLockMethod.PATTERN to NoteLockSlot("CC==", 100_000, "DD=="),
            ),
            biometric = true,
            failedAttempts = 2,
            lockoutUntil = 99L,
            lockedAt = 7L,
        )
        assertEquals(
            """{"v":1,"slots":{"pin":{"salt":"AA==","iterations":300000,"wrappedKey":"BB=="},"pattern":{"salt":"CC==","iterations":100000,"wrappedKey":"DD=="}},"biometric":true,"failedAttempts":2,"lockoutUntil":99,"lockedAt":7}""",
            record.toJson(),
        )
        val reparsed = NoteLockRecord.parse(record.toJson())!!
        assertEquals(record, reparsed)
    }
}
