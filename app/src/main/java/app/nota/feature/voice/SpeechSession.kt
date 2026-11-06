package app.nota.feature.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 5.4: the speech session — a faithful port of Noma 1.x's
 * speech.ts session layer (PR-A's engine contract, reducer and
 * controller), reshaped onto Kotlin coroutines.
 *
 * The shape mirrors 1.x exactly, for the reasons 1.x chose it:
 *  - an engine is a replaceable seam (the Android SpeechRecognizer
 *    engine in the app, a fake in tests) emitting partial / final /
 *    end / error events for ONE single-utterance recognition at a
 *    time;
 *  - a pure reducer owns the session state — continuous dictation
 *    is the controller restarting a finished utterance while the
 *    session is still live, so a pause between sentences never
 *    kills it;
 *  - committed finals are never lost on error.
 *
 * Only transcript text ever exists: no engine records or stores
 * audio. Recognition is performed by the device's own speech
 * service; on Android 12+ an on-device recogniser is preferred when
 * present (the engine's business — see [AndroidSpeechEngine]).
 */

/** Error kinds the UI maps to calm states. Wire names are 1.x's. */
enum class SpeechErrorKind(val wire: String) {
    UNAVAILABLE("unavailable"),
    SERVICE_DISABLED("service-disabled"),
    PERMISSION("permission"),
    NO_SPEECH("no-speech"),
    NO_MATCH("no-match"),
    SPEECH_TIMEOUT("speech-timeout"),
    NETWORK("network"),
    AUDIO("audio"),
    BUSY("busy"),
    SERVER("server"),
    CLIENT("client"),
    ;

    companion object {
        /** An unknown platform kind degrades to CLIENT, never a crash. */
        fun fromWire(wire: String?): SpeechErrorKind =
            entries.firstOrNull { it.wire == wire } ?: CLIENT
    }
}

/** Why no recogniser can run, when the platform can say so. */
enum class SpeechUnavailableReason(val wire: String) {
    SERVICE_DISABLED("service-disabled"),
}

/**
 * What an engine probe reports. The reason fields are the native
 * probe's honesty pass (1.x): a registered-but-disabled default
 * speech service is reported WITH the reason and the app's label.
 * When no on-device recogniser exists that makes the probe
 * unavailable and the UI stops offering the mic; when one does, the
 * mic stays offered and the reason rides along as an advisory.
 * Naming a runtime failure is NOT built on the advisory: only the
 * engine knows which recogniser actually ran, so the engine itself
 * reports a dead-service start as SERVICE_DISABLED.
 */
data class SpeechProbe(
    val available: Boolean,
    val onDevice: Boolean,
    val reason: SpeechUnavailableReason? = null,
    val serviceApp: String? = null,
)

/** Callbacks for ONE single-utterance recognition at a time. */
interface SpeechEngineHandlers {
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onEnd()
    fun onError(kind: SpeechErrorKind)
}

/**
 * One engine = one platform recogniser. start() runs a single
 * utterance; the controller restarts it for continuous dictation.
 * stop() asks for the in-flight utterance's final results before
 * the end; cancel() abandons it silently.
 */
interface SpeechEngine {
    suspend fun probe(): SpeechProbe

    /** True when the microphone may be used — asking in context
     *  when the platform requires a runtime request. */
    suspend fun ensurePermission(): Boolean
    fun start(languageTag: String, handlers: SpeechEngineHandlers)
    fun stop()
    fun cancel()
}

/* ---------------- Session state machine ---------------- */

enum class SpeechSessionState {
    IDLE,
    REQUESTING_PERMISSION,
    LISTENING,
    STOPPING,
    ERROR,
}

data class SpeechSessionSnapshot(
    val state: SpeechSessionState = SpeechSessionState.IDLE,
    /** Final segments, in order — the transcript so far. Never lost on error. */
    val committed: List<String> = emptyList(),
    /** The in-progress utterance's latest partial text (not committed). */
    val partial: String = "",
    val errorKind: SpeechErrorKind? = null,
    /** Consecutive soft misses since the last final (drives NO_SPEECH). */
    val softMisses: Int = 0,
)

sealed interface SpeechSessionEvent {
    data object StartRequested : SpeechSessionEvent
    data object Unavailable : SpeechSessionEvent
    data object PermissionGranted : SpeechSessionEvent
    data object PermissionDenied : SpeechSessionEvent
    data class Partial(val text: String) : SpeechSessionEvent
    data class Final(val text: String) : SpeechSessionEvent
    data object EngineEnded : SpeechSessionEvent
    data class EngineError(val kind: SpeechErrorKind) : SpeechSessionEvent
    data object StopRequested : SpeechSessionEvent
    data object Cancelled : SpeechSessionEvent
    data object Reset : SpeechSessionEvent
}

/**
 * The pure session reducer — 1.x's speechSessionReducer, transition
 * for transition. "no-match" and "speech-timeout" are SOFT: an
 * utterance window closed with nothing recognised — mid-dictation
 * pauses produce them, so they only end the session after
 * [MAX_SOFT_MISSES] in a row (surfaced as NO_SPEECH). While STOPPING
 * they settle the session to IDLE at once instead — Done must
 * finish, not resume listening. Everything else is fatal to the
 * session, with any committed finals preserved.
 */
object SpeechSessionReducer {

    const val MAX_SOFT_MISSES = 3

    private val SOFT_KINDS: Set<SpeechErrorKind> =
        setOf(SpeechErrorKind.NO_MATCH, SpeechErrorKind.SPEECH_TIMEOUT)

    fun reduce(
        session: SpeechSessionSnapshot,
        event: SpeechSessionEvent,
    ): SpeechSessionSnapshot = when (event) {
        SpeechSessionEvent.StartRequested -> when (session.state) {
            SpeechSessionState.LISTENING,
            SpeechSessionState.STOPPING,
            SpeechSessionState.REQUESTING_PERMISSION,
            -> session
            else -> session.copy(
                state = SpeechSessionState.REQUESTING_PERMISSION,
                errorKind = null,
                partial = "",
            )
        }
        SpeechSessionEvent.PermissionGranted ->
            if (session.state != SpeechSessionState.REQUESTING_PERMISSION) {
                session
            } else {
                session.copy(state = SpeechSessionState.LISTENING)
            }
        SpeechSessionEvent.Unavailable ->
            if (session.state != SpeechSessionState.REQUESTING_PERMISSION) {
                session
            } else {
                session.copy(
                    state = SpeechSessionState.ERROR,
                    errorKind = SpeechErrorKind.UNAVAILABLE,
                    partial = "",
                )
            }
        SpeechSessionEvent.PermissionDenied ->
            if (session.state != SpeechSessionState.REQUESTING_PERMISSION) {
                session
            } else {
                session.copy(
                    state = SpeechSessionState.ERROR,
                    errorKind = SpeechErrorKind.PERMISSION,
                    partial = "",
                )
            }
        is SpeechSessionEvent.Partial ->
            if (session.state != SpeechSessionState.LISTENING) {
                session
            } else {
                session.copy(partial = event.text)
            }
        is SpeechSessionEvent.Final ->
            if (session.state != SpeechSessionState.LISTENING &&
                session.state != SpeechSessionState.STOPPING
            ) {
                session
            } else {
                session.copy(
                    committed = SpeechText.appendFinalSegment(session.committed, event.text),
                    partial = "",
                    softMisses = 0,
                )
            }
        SpeechSessionEvent.EngineEnded ->
            if (session.state == SpeechSessionState.STOPPING) {
                session.copy(state = SpeechSessionState.IDLE, partial = "")
            } else {
                // Listening: the utterance ended; the controller
                // restarts the engine while the session lives.
                session
            }
        is SpeechSessionEvent.EngineError -> {
            if (session.state != SpeechSessionState.LISTENING &&
                session.state != SpeechSessionState.STOPPING
            ) {
                session
            } else if (event.kind in SOFT_KINDS &&
                session.state == SpeechSessionState.STOPPING
            ) {
                // Done was tapped and the engine answered with a
                // soft miss: there is nothing more to hear, so the
                // session settles — mirroring EngineEnded while
                // STOPPING — instead of flipping back to LISTENING
                // (which would restart recognition and leave the
                // capture sheet waiting for a settle that never
                // comes). Committed finals are preserved.
                session.copy(state = SpeechSessionState.IDLE, partial = "")
            } else if (event.kind in SOFT_KINDS) {
                val softMisses = session.softMisses + 1
                if (softMisses >= MAX_SOFT_MISSES) {
                    session.copy(
                        state = SpeechSessionState.ERROR,
                        errorKind = SpeechErrorKind.NO_SPEECH,
                        partial = "",
                        softMisses = softMisses,
                    )
                } else {
                    session.copy(
                        state = SpeechSessionState.LISTENING,
                        partial = "",
                        softMisses = softMisses,
                    )
                }
            } else {
                session.copy(
                    state = SpeechSessionState.ERROR,
                    errorKind = event.kind,
                    partial = "",
                )
            }
        }
        SpeechSessionEvent.StopRequested ->
            if (session.state != SpeechSessionState.LISTENING) {
                session
            } else {
                session.copy(state = SpeechSessionState.STOPPING)
            }
        SpeechSessionEvent.Cancelled -> session.copy(
            state = SpeechSessionState.IDLE,
            partial = "",
            errorKind = null,
            softMisses = 0,
        )
        SpeechSessionEvent.Reset -> SpeechSessionSnapshot()
    }
}

/* ---------------- Session controller ---------------- */

/**
 * Wires an engine to the reducer. Continuous dictation lives here:
 * when an utterance ends (or softly misses) and the session is
 * still "listening", the engine is restarted for the next
 * utterance. One controller per surface (per editor, per capture
 * sheet opening) — a surface that goes away cancels its session,
 * so a recogniser can never run behind the app.
 */
class SpeechSessionController(
    private val engine: SpeechEngine,
    private val scope: CoroutineScope,
) {
    private val _snapshot = MutableStateFlow(SpeechSessionSnapshot())
    val snapshot: StateFlow<SpeechSessionSnapshot> = _snapshot.asStateFlow()

    private var languageTag: String = "en-US"
    private var restartQueued = false

    private val handlers = object : SpeechEngineHandlers {
        override fun onPartial(text: String) =
            dispatch(SpeechSessionEvent.Partial(text))

        override fun onFinal(text: String) =
            dispatch(SpeechSessionEvent.Final(text))

        override fun onEnd() {
            dispatch(SpeechSessionEvent.EngineEnded)
            maybeRestart()
        }

        override fun onError(kind: SpeechErrorKind) {
            dispatch(SpeechSessionEvent.EngineError(kind))
            maybeRestart()
        }
    }

    private fun dispatch(event: SpeechSessionEvent) {
        val next = SpeechSessionReducer.reduce(_snapshot.value, event)
        if (next != _snapshot.value) {
            _snapshot.value = next
        }
    }

    private fun maybeRestart() {
        if (_snapshot.value.state != SpeechSessionState.LISTENING || restartQueued) return
        restartQueued = true
        scope.launch {
            restartQueued = false
            if (_snapshot.value.state == SpeechSessionState.LISTENING) {
                engine.start(languageTag, handlers)
            }
        }
    }

    /**
     * Probe first, then permission, then listen — 1.x's order. A
     * probe that says no recogniser can run ends the start in the
     * UNAVAILABLE error state without ever touching the microphone;
     * a denied permission ends it in the PERMISSION error state.
     */
    fun start(tag: String) {
        val state = _snapshot.value.state
        if (state == SpeechSessionState.LISTENING ||
            state == SpeechSessionState.STOPPING ||
            state == SpeechSessionState.REQUESTING_PERMISSION
        ) {
            return
        }
        languageTag = tag
        dispatch(SpeechSessionEvent.StartRequested)
        scope.launch {
            val stillRequesting =
                { _snapshot.value.state == SpeechSessionState.REQUESTING_PERMISSION }
            val probe = runCatching { engine.probe() }
                .getOrElse { SpeechProbe(available = false, onDevice = false) }
            if (!stillRequesting()) return@launch
            if (!probe.available) {
                dispatch(SpeechSessionEvent.Unavailable)
                return@launch
            }
            val granted = runCatching { engine.ensurePermission() }.getOrDefault(false)
            if (!stillRequesting()) return@launch
            if (!granted) {
                dispatch(SpeechSessionEvent.PermissionDenied)
                return@launch
            }
            dispatch(SpeechSessionEvent.PermissionGranted)
            engine.start(languageTag, handlers)
        }
    }

    fun stop() {
        if (_snapshot.value.state != SpeechSessionState.LISTENING) return
        dispatch(SpeechSessionEvent.StopRequested)
        engine.stop()
    }

    fun cancel() {
        engine.cancel()
        dispatch(SpeechSessionEvent.Cancelled)
    }

    fun reset() {
        dispatch(SpeechSessionEvent.Reset)
    }
}

/* ---------------- Calm UI copy + probe mapping ---------------- */

/** Calm one-liners for the voice surfaces, one per error kind —
 *  1.x's SPEECH_ERROR_LINES, verbatim where the meaning matches. */
object SpeechErrorLines {

    fun line(kind: SpeechErrorKind): String = when (kind) {
        SpeechErrorKind.PERMISSION ->
            "Microphone access was denied — you can allow it in your device settings."
        SpeechErrorKind.UNAVAILABLE ->
            "Voice capture isn't available on this device."
        SpeechErrorKind.SERVICE_DISABLED ->
            "Your phone's speech service is disabled, so dictation can't run."
        SpeechErrorKind.NO_SPEECH ->
            "Didn't catch that — try again, a little closer to the mic."
        SpeechErrorKind.NO_MATCH ->
            "Didn't catch that — try again."
        SpeechErrorKind.SPEECH_TIMEOUT ->
            "Didn't catch that — try again."
        SpeechErrorKind.NETWORK ->
            "The speech service needs a connection on this device."
        SpeechErrorKind.AUDIO ->
            "The microphone couldn't be read."
        SpeechErrorKind.BUSY ->
            "The recogniser is busy — try again in a moment."
        SpeechErrorKind.SERVER ->
            "The speech service had a problem — try again."
        SpeechErrorKind.CLIENT ->
            "Voice capture stopped unexpectedly."
    }
}

/** What a probe means for a surface that wants to offer the mic. */
sealed interface VoiceAvailability {
    /** Offer the mic. [disabledServiceApp] is the advisory case:
     *  an on-device recogniser carries the session even though the
     *  (online) default service is disabled — the app label rides
     *  along so the surface can say so. */
    data class Available(
        val onDevice: Boolean,
        val disabledServiceApp: String?,
    ) : VoiceAvailability

    /** Never offer the mic; say why instead. */
    data class Unavailable(
        val reason: SpeechUnavailableReason?,
        val serviceApp: String?,
    ) : VoiceAvailability
}

/**
 * The probe → UI-state mapping (pure): surfaces consume this, never
 * the raw probe, so the honesty rules live in exactly one place —
 * the same one-place shape as 1.x's widget privacy builder.
 */
object VoiceProbeUi {

    fun fromProbe(probe: SpeechProbe?): VoiceAvailability {
        if (probe == null) {
            return VoiceAvailability.Unavailable(reason = null, serviceApp = null)
        }
        return if (probe.available) {
            VoiceAvailability.Available(
                onDevice = probe.onDevice,
                disabledServiceApp =
                    if (probe.reason == SpeechUnavailableReason.SERVICE_DISABLED) {
                        probe.serviceApp
                    } else {
                        null
                    },
            )
        } else {
            VoiceAvailability.Unavailable(
                reason = probe.reason,
                serviceApp = probe.serviceApp,
            )
        }
    }

    /** The capture sheet's honest unavailable copy (1.x's words). */
    fun unavailableMessage(unavailable: VoiceAvailability.Unavailable): String =
        if (unavailable.reason == SpeechUnavailableReason.SERVICE_DISABLED) {
            val app = unavailable.serviceApp?.takeIf { it.isNotBlank() }
            "Your phone's speech service" +
                (if (app != null) " — $app —" else "") +
                " is disabled, so dictation can't run. Enable it, or choose a " +
                "different voice input in your system settings, to use voice capture."
        } else {
            "No speech recogniser is available on this device, so voice " +
                "capture can't run here."
        }

    /** The keyboard fallback every unavailable/failed surface points
     *  to (1.x's stance: Gboard voice typing keeps working on
     *  devices whose system recogniser is dead). */
    const val KEYBOARD_HINT: String =
        "You can still dictate with your keyboard's own voice typing."
}
