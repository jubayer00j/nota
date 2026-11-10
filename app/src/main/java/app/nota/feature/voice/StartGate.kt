package app.nota.feature.voice

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 6 extras fix round (PR #45, Codex round 1): the pending-start
 * gate for [AndroidSpeechEngine]. A start is ASYNC — the dictation-
 * language preference is read off an IO coroutine and the recogniser
 * start is posted to the main looper afterwards. Without a gate, a
 * cancel that lands while the read is in flight posts its destroy
 * first, and the delayed start still creates a recogniser and begins
 * listening after the capture surface has gone away.
 *
 * The gate is a monotonic ticket: [issue] hands the pending start a
 * number, [invalidate] (cancel) moves the counter past it, and the
 * posted start runs only while its ticket is still current. Pure and
 * thread-safe, so the logic is unit-testable without the platform
 * recogniser (StartGateTest).
 *
 * Round 3 (Codex): STOP is a different exit from cancel. The session
 * settles out of STOPPING only when the engine reports an end, so a
 * start dropped by stop() must still report one — [invalidateForStop]
 * records that a settle is owed, and the dropped start consumes it
 * via [consumeSettleOwed] and fires its end callback. Cancel owes no
 * settle: the controller settles itself synchronously. Issuing a
 * fresh start clears any stale settle — the new session owns its
 * own lifecycle.
 */
internal class StartGate {

    private val counter = AtomicInteger(0)
    private val settleOwed = AtomicBoolean(false)

    /** The ticket a new pending start must present when it lands. */
    fun issue(): Int {
        settleOwed.set(false)
        return counter.incrementAndGet()
    }

    /** Invalidate every outstanding ticket (cancel). */
    fun invalidate() {
        counter.incrementAndGet()
    }

    /** Invalidate every outstanding ticket because of a STOP: the
     *  dropped start owes the session an end report. */
    fun invalidateForStop() {
        settleOwed.set(true)
        counter.incrementAndGet()
    }

    /** True while [ticket] is the latest issued and not invalidated. */
    fun isCurrent(ticket: Int): Boolean = counter.get() == ticket

    /** Take the owed settle, if any — true exactly once per stop. */
    fun consumeSettleOwed(): Boolean = settleOwed.getAndSet(false)
}
