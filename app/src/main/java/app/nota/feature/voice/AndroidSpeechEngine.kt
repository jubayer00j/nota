package app.nota.feature.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.nota.data.preferences.PreferencesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Phase 5.4: the Android speech engine — a port of Noma 1.x's
 * native Capacitor plugin (NomaSpeechPlugin) onto the
 * [SpeechEngine] seam, driving the platform SpeechRecognizer.
 *
 * Main thread only, as the platform requires: every public method
 * posts its recogniser work onto the main looper. A generation
 * counter drops callbacks from a recogniser that has since been
 * cancelled or replaced — a destroyed recogniser can still deliver
 * one late result, and it must never land in a new session.
 *
 * PROBE HONESTY (1.x's shipped design, the reason this class is
 * careful): `SpeechRecognizer.isRecognitionAvailable` alone lies —
 * it stays true when the default speech service's app is DISABLED
 * (Jubayer's J7: Google app disabled → every recognition errors
 * instantly while Gboard voice typing works). So the probe also
 * reads the default service component and its enabled state:
 *  - disabled service + no on-device recogniser → unavailable,
 *    with the reason and the app's label;
 *  - disabled service + on-device recogniser → available (the
 *    on-device recogniser starts below), reason kept as advisory;
 *  - at start, when the platform recogniser is the only path and
 *    its service is disabled, the engine refuses to bind to the
 *    dead service and reports SERVICE_DISABLED itself.
 */
class AndroidSpeechEngine(
    context: Context,
    private val preferencesRepository: PreferencesRepository? = null,
) : SpeechEngine {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Phase 6 extras (PR #45): used only to read the dictation-
     * language preference at START time (see [start]) — reads are
     * one-shot (`first()`), never a standing collection, so an
     * engine instance holds no subscription open.
     */
    private val languageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Pending-start gate: a cancel landing while the language read
     *  below is in flight must stop the delayed start from creating
     *  a recogniser after the capture surface has gone (Codex PR #45
     *  round 1). See [StartGate]. */
    private val startGate = StartGate()

    private var recognizer: SpeechRecognizer? = null
    private var generation = 0

    /**
     * Bridges the runtime permission request: the hosting surface
     * registers a launcher-backed requester (see
     * [rememberSpeechPermissionRequester]); without one,
     * [ensurePermission] can only report the current grant state.
     */
    @Volatile
    var permissionRequester: (suspend () -> Boolean)? = null

    /* ---------------- Probe ---------------- */

    override suspend fun probe(): SpeechProbe {
        val recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
        val onDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        } else {
            false
        }
        val disabled = disabledDefaultServiceApp()
        val available = when {
            disabled != null -> onDevice
            else -> recognitionAvailable
        }
        return SpeechProbe(
            available = available,
            onDevice = onDevice,
            reason = if (disabled != null) SpeechUnavailableReason.SERVICE_DISABLED else null,
            serviceApp = disabled,
        )
    }

    /**
     * The label of the default speech-service app when that service
     * is DISABLED, else null. Reads the secure setting that names
     * the service component, then checks both the app and the
     * component enabled states — exactly 1.x's plugin check.
     */
    private fun disabledDefaultServiceApp(): String? {
        val flat = runCatching {
            Settings.Secure.getString(
                appContext.contentResolver,
                "voice_recognition_service",
            )
        }.getOrNull()
        if (flat.isNullOrBlank()) return null
        val component = ComponentName.unflattenFromString(flat) ?: return null
        val pm = appContext.packageManager
        val appEnabled = runCatching {
            val info = pm.getApplicationInfo(component.packageName, 0)
            info.enabled && info.flags and ApplicationInfo.FLAG_INSTALLED != 0
        }.getOrDefault(false)
        val componentEnabled = runCatching {
            pm.getComponentEnabledSetting(component)
        }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        val componentOn = componentEnabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
            componentEnabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        if (appEnabled && componentOn) return null
        return runCatching {
            val info = pm.getApplicationInfo(component.packageName, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrNull() ?: component.packageName
    }

    /* ---------------- Permission ---------------- */

    override suspend fun ensurePermission(): Boolean {
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        val requester = permissionRequester ?: return false
        return runCatching { requester() }.getOrDefault(false)
    }

    /* ---------------- Recognition ---------------- */

    override fun start(languageTag: String, handlers: SpeechEngineHandlers) {
        // PR #45: the dictation-language preference is read NOW, at
        // start time — not snapshotted when the engine was built —
        // so changing it in Settings steers the very next dictation
        // on both voice surfaces. "system" resolves to null (no
        // override): the caller-supplied device-default tag stands.
        val repository = preferencesRepository
        val ticket = startGate.issue()
        if (repository == null) {
            mainHandler.post {
                if (startGate.isCurrent(ticket)) startOnMain(languageTag, handlers)
            }
        } else {
            languageScope.launch {
                val override = runCatching {
                    SpeechLanguage.resolveTag(repository.speechLanguageNow())
                }.getOrNull()
                mainHandler.post {
                    if (startGate.isCurrent(ticket)) {
                        startOnMain(override ?: languageTag, handlers)
                    }
                }
            }
        }
    }

    private fun startOnMain(languageTag: String, handlers: SpeechEngineHandlers) {
        destroyRecognizer()
        val gen = generation
        fun live() = gen == generation

        val onDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        } else {
            false
        }
        if (!onDevice && disabledDefaultServiceApp() != null) {
            // The platform recogniser would bind to the disabled
            // service and die opaquely — name the real failure
            // instead (1.x's plugin does exactly this).
            handlers.onError(SpeechErrorKind.SERVICE_DISABLED)
            return
        }
        val created: SpeechRecognizer? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && onDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
        } catch (t: Throwable) {
            null
        }
        if (created == null) {
            handlers.onError(SpeechErrorKind.CLIENT)
            return
        }
        recognizer = created
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                // Deliberately NOT forwarded: the platform also
                // calls this before the final results land, and
                // restarting here would drop them. onResults /
                // onError are the only end signals (1.x's rule).
            }

            override fun onError(error: Int) {
                if (!live()) return
                handlers.onError(platformErrorKind(error))
            }

            override fun onResults(results: Bundle?) {
                if (!live()) return
                val first = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!first.isNullOrEmpty()) {
                    handlers.onFinal(first)
                }
                // End AFTER onFinal: the controller restarts for
                // the next utterance only once the final is banked.
                handlers.onEnd()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!live()) return
                val first = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!first.isNullOrEmpty()) {
                    handlers.onPartial(first)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        try {
            created.startListening(intent)
        } catch (t: Throwable) {
            handlers.onError(SpeechErrorKind.CLIENT)
        }
    }

    override fun stop() {
        mainHandler.post {
            try {
                recognizer?.stopListening()
            } catch (t: Throwable) {
                // Stopping a dead recogniser is harmless.
            }
        }
    }

    override fun cancel() {
        // Invalidate any pending start FIRST (synchronously, before
        // the destroy below is even posted): a delayed language read
        // completing after this cancel must not start listening.
        startGate.invalidate()
        mainHandler.post { destroyRecognizer() }
    }

    /** Destroy + bump the generation on the main thread. */
    private fun destroyRecognizer() {
        val existing = recognizer
        recognizer = null
        generation++
        if (existing != null) {
            try {
                existing.destroy()
            } catch (t: Throwable) {
                // Already gone — nothing to release.
            }
        }
    }

    companion object {
        /** The platform error-code map (1.x's plugin table). */
        fun platformErrorKind(error: Int): SpeechErrorKind = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> SpeechErrorKind.NO_MATCH
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechErrorKind.SPEECH_TIMEOUT
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            -> SpeechErrorKind.NETWORK
            SpeechRecognizer.ERROR_AUDIO -> SpeechErrorKind.AUDIO
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechErrorKind.PERMISSION
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechErrorKind.BUSY
            SpeechRecognizer.ERROR_SERVER -> SpeechErrorKind.SERVER
            else -> SpeechErrorKind.CLIENT
        }
    }
}

/**
 * Registers an [AndroidSpeechEngine]'s permission requester backed
 * by the standard runtime-permission launcher — the in-context
 * RECORD_AUDIO request (first voice use, never app start), the
 * same launcher shape the reminders flow uses. Denial simply
 * completes false; the session surfaces the plain message and
 * nothing re-nags.
 */
@Composable
fun rememberSpeechPermissionRequester(engine: AndroidSpeechEngine) {
    var pending by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pending?.complete(granted)
        pending = null
    }
    DisposableEffect(engine) {
        engine.permissionRequester = {
            if (pending != null) {
                false
            } else {
                val deferred = CompletableDeferred<Boolean>()
                pending = deferred
                launcher.launch(Manifest.permission.RECORD_AUDIO)
                deferred.await()
            }
        }
        onDispose { engine.permissionRequester = null }
    }
}
