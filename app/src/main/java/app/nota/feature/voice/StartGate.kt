package app.nota.feature.voice

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
 */
internal class StartGate {

    private val counter = AtomicInteger(0)

    /** The ticket a new pending start must present when it lands. */
    fun issue(): Int = counter.incrementAndGet()

    /** Invalidate every outstanding ticket (cancel). */
    fun invalidate() {
        counter.incrementAndGet()
    }

    /** True while [ticket] is the latest issued and not invalidated. */
    fun isCurrent(ticket: Int): Boolean = counter.get() == ticket
}
