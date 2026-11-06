package app.nota.feature.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5.4: the speech session — reducer transitions and the
 * controller wired to a fake engine (the recogniser itself is
 * device territory; everything around it is pinned here). Mirrors
 * 1.x speech.test.ts's session cases: probe gating, permission
 * denial, soft-miss tolerance, finals preserved on fatal error,
 * restart-on-end continuous dictation, stop settling.
 */
class SpeechSessionTest {

    /** A scripted engine: probe/permission answers are set by the
     *  test; utterance events are emitted through the handlers the
     *  controller registered. stop() settles with an end, like the
     *  platform delivering its last results then ending. */
    private class FakeSpeechEngine(
        var probeResult: SpeechProbe = SpeechProbe(available = true, onDevice = true),
        var permissionGranted: Boolean = true,
    ) : SpeechEngine {
        var startCalls = 0
        var stopCalls = 0
        var cancelCalls = 0
        var lastLanguageTag: String? = null
        private var handlers: SpeechEngineHandlers? = null

        override suspend fun probe(): SpeechProbe = probeResult

        override suspend fun ensurePermission(): Boolean = permissionGranted

        override fun start(languageTag: String, handlers: SpeechEngineHandlers) {
            startCalls++
            lastLanguageTag = languageTag
            this.handlers = handlers
        }

        override fun stop() {
            stopCalls++
            handlers?.onEnd()
        }

        override fun cancel() {
            cancelCalls++
        }

        fun emitPartial(text: String) = handlers?.onPartial(text)
        fun emitFinal(text: String) = handlers?.onFinal(text)
        fun emitEnd() = handlers?.onEnd()
        fun emitError(kind: SpeechErrorKind) = handlers?.onError(kind)
    }

    private fun controllerFor(engine: FakeSpeechEngine): SpeechSessionController =
        SpeechSessionController(engine, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun start_unavailableProbe_errorsWithoutTouchingTheEngine() {
        val engine = FakeSpeechEngine(
            probeResult = SpeechProbe(available = false, onDevice = false),
        )
        val controller = controllerFor(engine)
        controller.start("en-US")
        val snapshot = controller.snapshot.value
        assertEquals(SpeechSessionState.ERROR, snapshot.state)
        assertEquals(SpeechErrorKind.UNAVAILABLE, snapshot.errorKind)
        assertEquals(0, engine.startCalls)
    }

    @Test
    fun start_permissionDenied_errorsWithoutStartingRecognition() {
        val engine = FakeSpeechEngine(permissionGranted = false)
        val controller = controllerFor(engine)
        controller.start("bn-BD")
        val snapshot = controller.snapshot.value
        assertEquals(SpeechSessionState.ERROR, snapshot.state)
        assertEquals(SpeechErrorKind.PERMISSION, snapshot.errorKind)
        assertEquals(0, engine.startCalls)
    }

    @Test
    fun session_finalsCommitAndTheEngineRestartsOnEnd() {
        val engine = FakeSpeechEngine()
        val controller = controllerFor(engine)
        controller.start("en-US")
        assertEquals(SpeechSessionState.LISTENING, controller.snapshot.value.state)
        assertEquals("en-US", engine.lastLanguageTag)

        engine.emitPartial("hel")
        assertEquals("hel", controller.snapshot.value.partial)
        engine.emitFinal("Hello there.")
        assertEquals(listOf("Hello there."), controller.snapshot.value.committed)
        assertEquals("", controller.snapshot.value.partial)

        // The utterance ended: continuous dictation restarts it.
        engine.emitEnd()
        assertEquals(2, engine.startCalls)
        assertEquals(SpeechSessionState.LISTENING, controller.snapshot.value.state)

        engine.emitFinal("Second sentence.")
        assertEquals(
            listOf("Hello there.", "Second sentence."),
            controller.snapshot.value.committed,
        )
    }

    @Test
    fun session_softMissesAreToleratedUntilTheThird() {
        val engine = FakeSpeechEngine()
        val controller = controllerFor(engine)
        controller.start("en-US")
        engine.emitFinal("Kept.")

        engine.emitError(SpeechErrorKind.NO_MATCH)
        assertEquals(SpeechSessionState.LISTENING, controller.snapshot.value.state)
        assertEquals(1, controller.snapshot.value.softMisses)
        engine.emitError(SpeechErrorKind.SPEECH_TIMEOUT)
        assertEquals(SpeechSessionState.LISTENING, controller.snapshot.value.state)
        assertEquals(2, controller.snapshot.value.softMisses)

        engine.emitError(SpeechErrorKind.NO_MATCH)
        val snapshot = controller.snapshot.value
        assertEquals(SpeechSessionState.ERROR, snapshot.state)
        assertEquals(SpeechErrorKind.NO_SPEECH, snapshot.errorKind)
        // The committed transcript survives the soft-miss give-up.
        assertEquals(listOf("Kept."), snapshot.committed)
    }

    @Test
    fun session_fatalErrorPreservesCommittedFinals() {
        val engine = FakeSpeechEngine()
        val controller = controllerFor(engine)
        controller.start("en-US")
        engine.emitFinal("Still here.")
        engine.emitError(SpeechErrorKind.NETWORK)
        val snapshot = controller.snapshot.value
        assertEquals(SpeechSessionState.ERROR, snapshot.state)
        assertEquals(SpeechErrorKind.NETWORK, snapshot.errorKind)
        assertEquals(listOf("Still here."), snapshot.committed)
    }

    @Test
    fun session_finalsLandingWhileStoppingStillCount() {
        val engine = FakeSpeechEngine()
        val controller = controllerFor(engine)
        controller.start("en-US")
        engine.emitFinal("One.")
        // Stop settles through STOPPING; a final arriving on the way
        // (the platform's last results) is committed before idle.
        controller.snapshot.value.let { assertEquals(SpeechSessionState.LISTENING, it.state) }
        val stopping = SpeechSessionReducer.reduce(
            controller.snapshot.value,
            SpeechSessionEvent.StopRequested,
        )
        val withLateFinal = SpeechSessionReducer.reduce(
            stopping,
            SpeechSessionEvent.Final("Two."),
        )
        assertEquals(listOf("One.", "Two."), withLateFinal.committed)
        val settled = SpeechSessionReducer.reduce(
            withLateFinal,
            SpeechSessionEvent.EngineEnded,
        )
        assertEquals(SpeechSessionState.IDLE, settled.state)
    }

    @Test
    fun reducer_noMatchWhileStoppingSettlesToIdlePreservingCommitted() {
        // Regression (Codex PR #31, P1): Done is tapped during a
        // pause and stopListening answers NO_MATCH. The session must
        // settle to IDLE — flipping back to LISTENING would restart
        // recognition and leave the capture sheet waiting for a
        // settle that never comes.
        val listening = SpeechSessionSnapshot(
            state = SpeechSessionState.LISTENING,
            committed = listOf("Done soon."),
            partial = "half-heard",
        )
        val stopping = SpeechSessionReducer.reduce(
            listening,
            SpeechSessionEvent.StopRequested,
        )
        assertEquals(SpeechSessionState.STOPPING, stopping.state)
        val settled = SpeechSessionReducer.reduce(
            stopping,
            SpeechSessionEvent.EngineError(SpeechErrorKind.NO_MATCH),
        )
        assertEquals(SpeechSessionState.IDLE, settled.state)
        assertEquals(listOf("Done soon."), settled.committed)
        assertEquals("", settled.partial)
        assertNull(settled.errorKind)
    }

    @Test
    fun reducer_speechTimeoutWhileStoppingSettlesToIdlePreservingCommitted() {
        // Same settle for the other soft kind (Codex PR #31, P1).
        val stopping = SpeechSessionSnapshot(
            state = SpeechSessionState.STOPPING,
            committed = listOf("Kept."),
        )
        val settled = SpeechSessionReducer.reduce(
            stopping,
            SpeechSessionEvent.EngineError(SpeechErrorKind.SPEECH_TIMEOUT),
        )
        assertEquals(SpeechSessionState.IDLE, settled.state)
        assertEquals(listOf("Kept."), settled.committed)
        assertNull(settled.errorKind)
    }

    @Test
    fun reducer_fatalErrorWhileStoppingStillErrors() {
        // Only SOFT kinds settle on Done; a fatal error while
        // stopping still surfaces as the session's error.
        val stopping = SpeechSessionSnapshot(
            state = SpeechSessionState.STOPPING,
            committed = listOf("Kept."),
        )
        val errored = SpeechSessionReducer.reduce(
            stopping,
            SpeechSessionEvent.EngineError(SpeechErrorKind.NETWORK),
        )
        assertEquals(SpeechSessionState.ERROR, errored.state)
        assertEquals(SpeechErrorKind.NETWORK, errored.errorKind)
        assertEquals(listOf("Kept."), errored.committed)
    }

    @Test
    fun stop_settlesToIdleThroughTheEngine() {
        val engine = FakeSpeechEngine()
        val controller = controllerFor(engine)
        controller.start("en-US")
        controller.stop()
        assertEquals(1, engine.stopCalls)
        assertEquals(SpeechSessionState.IDLE, controller.snapshot.value.state)
    }

    @Test
    fun cancel_keepsCommittedAndDropsThePartial() {
        val engine = FakeSpeechEngine()
        val controller = controllerFor(engine)
        controller.start("en-US")
        engine.emitFinal("Banked.")
        engine.emitPartial("half-heard")
        controller.cancel()
        val snapshot = controller.snapshot.value
        assertEquals(SpeechSessionState.IDLE, snapshot.state)
        assertEquals(listOf("Banked."), snapshot.committed)
        assertEquals("", snapshot.partial)
        assertEquals(1, engine.cancelCalls)
    }

    @Test
    fun reducer_ignoresOutOfPhaseEvents() {
        val idle = SpeechSessionSnapshot()
        // Finals and partials outside a session are dropped.
        assertEquals(
            idle,
            SpeechSessionReducer.reduce(idle, SpeechSessionEvent.Final("nope")),
        )
        assertEquals(
            idle,
            SpeechSessionReducer.reduce(idle, SpeechSessionEvent.Partial("nope")),
        )
        // A second start while already listening changes nothing.
        val listening = SpeechSessionSnapshot(state = SpeechSessionState.LISTENING)
        assertEquals(
            listening,
            SpeechSessionReducer.reduce(listening, SpeechSessionEvent.StartRequested),
        )
        // Reset returns a clean slate.
        val dirty = SpeechSessionSnapshot(
            state = SpeechSessionState.ERROR,
            committed = listOf("x"),
            errorKind = SpeechErrorKind.CLIENT,
            softMisses = 2,
        )
        assertEquals(
            SpeechSessionSnapshot(),
            SpeechSessionReducer.reduce(dirty, SpeechSessionEvent.Reset),
        )
    }

    @Test
    fun start_fromErrorState_retriesCleanly() {
        val engine = FakeSpeechEngine(permissionGranted = false)
        val controller = controllerFor(engine)
        controller.start("en-US")
        assertTrue(controller.snapshot.value.state == SpeechSessionState.ERROR)
        engine.permissionGranted = true
        controller.start("en-US")
        assertEquals(SpeechSessionState.LISTENING, controller.snapshot.value.state)
        assertEquals(1, engine.startCalls)
    }
}
