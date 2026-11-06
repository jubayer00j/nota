package app.nota.feature.capture

import app.nota.data.capture.CaptureRecord
import app.nota.data.capture.CaptureRequest
import app.nota.data.capture.CaptureSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 5.2: the shell's capture decisions — never while locked,
 * never for another account (retire instead), deliver otherwise.
 * Delivery removes the record from the store before creating
 * anything, so exactly-once is structural and this decider is
 * stateless.
 */
class PendingCaptureResumerTest {

    private val resumer = PendingCaptureResumer()

    private fun record(accountId: String = "guest") = CaptureRecord(
        id = "cap-1",
        accountId = accountId,
        request = CaptureRequest.NewNote("cap-1", CaptureSource.SHORTCUT),
        stagedAtMillis = 1L,
    )

    @Test
    fun delivers_whenUnlockedAndSameAccount() {
        assertEquals(
            CaptureDecision.DELIVER,
            resumer.decide(locked = false, record = record(), currentAccountId = "guest"),
        )
    }

    @Test
    fun holdsWhileLocked_evenForOwnRecord() {
        assertEquals(
            CaptureDecision.HOLD,
            resumer.decide(locked = true, record = record(), currentAccountId = "guest"),
        )
    }

    @Test
    fun holdsWhenNothingStaged() {
        assertEquals(
            CaptureDecision.HOLD,
            resumer.decide(locked = false, record = null, currentAccountId = "guest"),
        )
    }

    @Test
    fun retiresAnotherAccountsRecord() {
        assertEquals(
            CaptureDecision.RETIRE,
            resumer.decide(
                locked = false,
                record = record(accountId = "uid-other"),
                currentAccountId = "guest",
            ),
        )
    }

    @Test
    fun lockedBeatsRetire_crossAccountRecordWaitsForUnlock() {
        // While locked the shell must not even retire: the record is
        // judged only once the session (and its account) is known.
        assertEquals(
            CaptureDecision.HOLD,
            resumer.decide(
                locked = true,
                record = record(accountId = "uid-other"),
                currentAccountId = "guest",
            ),
        )
    }
}
