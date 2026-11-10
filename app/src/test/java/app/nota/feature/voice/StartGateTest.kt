package app.nota.feature.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #45 fix round (Codex round 1): the pending-start gate behind
 * AndroidSpeechEngine's async start. A cancel landing while the
 * dictation-language read is in flight must invalidate the pending
 * start so no recogniser begins listening after the capture surface
 * has gone; a later start must still work.
 */
class StartGateTest {

    @Test
    fun issuedTicketIsCurrentUntilInvalidated() {
        val gate = StartGate()
        val ticket = gate.issue()
        assertTrue(gate.isCurrent(ticket))
        gate.invalidate()
        assertFalse(gate.isCurrent(ticket))
    }

    @Test
    fun aNewerStartSupersedesAnOlderPendingOne() {
        val gate = StartGate()
        val first = gate.issue()
        val second = gate.issue()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun startAfterCancelWorks() {
        val gate = StartGate()
        gate.issue()
        gate.invalidate()
        val fresh = gate.issue()
        assertTrue(gate.isCurrent(fresh))
    }

    @Test
    fun stopInvalidationOwesASettle_cancelDoesNot() {
        val stopped = StartGate()
        stopped.issue()
        stopped.invalidateForStop()
        assertTrue(stopped.consumeSettleOwed())

        val cancelled = StartGate()
        cancelled.issue()
        cancelled.invalidate()
        assertFalse(cancelled.consumeSettleOwed())
    }

    @Test
    fun anOwedSettleIsConsumedExactlyOnce() {
        val gate = StartGate()
        gate.issue()
        gate.invalidateForStop()
        assertTrue(gate.consumeSettleOwed())
        assertFalse(gate.consumeSettleOwed())
    }

    @Test
    fun aFreshStartClearsAStaleOwedSettle() {
        val gate = StartGate()
        gate.issue()
        gate.invalidateForStop()
        gate.issue()
        assertFalse(gate.consumeSettleOwed())
    }

    @Test
    fun aSupersededStartOwesNoSettle() {
        val gate = StartGate()
        gate.issue()
        gate.issue()
        assertFalse(gate.consumeSettleOwed())
    }
}
