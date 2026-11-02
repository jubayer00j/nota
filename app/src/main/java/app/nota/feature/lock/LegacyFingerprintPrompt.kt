package app.nota.feature.lock

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.os.CancellationSignal
import app.nota.data.security.BiometricStep
import app.nota.data.security.StepResult
import app.nota.data.security.stepReason
import app.nota.legacyfingerprint.LegacyFingerprint
import app.nota.ui.components.NotaBrandLockup
import app.nota.ui.theme.NotaSpacing
import javax.crypto.Cipher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Legacy (API < 28) fingerprint prompt: state machine, framework
 * session, coordinator and Nota's own Compose dialog (Phase 2.7).
 *
 * WHY THIS EXISTS: on the Galaxy J7 Prime (API 27), AndroidX
 * BiometricPrompt failed the enable flow with ERROR_HW_NOT_PRESENT
 * ("This device does not have a fingerprint sensor") while the
 * framework fingerprint service — probed directly since Phase 2.5 —
 * saw the enrolled sensor fine, and Noma uses that same framework
 * API on the device daily. FingerprintManager ships no UI, so the
 * listening surface below is Nota's: brand lockup, fingerprint
 * target, honest state text, and a Cancel button wired to a real
 * CancellationSignal.
 *
 * CRYPTO DISCIPLINE IS IDENTICAL TO THE ANDROIDX PATH: the
 * framework CryptoObject wraps the caller's cipher (the App Lock
 * token cipher / the per-note NEK cipher). A success callback only
 * MARKS the request succeeded; the coordinator hands the authorised
 * cipher back and the caller's decrypt proof still decides the
 * unlock. A result without a crypto object is a typed prompt
 * failure, never a success.
 */

/** Dialog phases for one legacy prompt session. */
enum class LegacyFingerprintPromptPhase {
    LISTENING,
    FAILED,
    RETRYING,
    SUCCESS,
    ERROR,
    CANCELLED,
}

/** Immutable snapshot the dialog renders. */
data class LegacyFingerprintPromptUiState(
    val phase: LegacyFingerprintPromptPhase = LegacyFingerprintPromptPhase.LISTENING,
    val message: String = "Touch the sensor",
    val failure: StepResult.Failed? = null,
    val failedAttempts: Int = 0,
)

/**
 * Pure dialog/session state machine (unit-tested without Android):
 * LISTENING → FAILED → RETRYING → SUCCESS, with ERROR and
 * CANCELLED as terminal phases. A failed or helped authentication
 * does NOT end the framework session — the sensor keeps listening,
 * so FAILED is a display state and RETRYING is the machine
 * returning to its listening presentation while the same session
 * continues underneath.
 */
class LegacyFingerprintPromptStateMachine {
    var state: LegacyFingerprintPromptUiState = LegacyFingerprintPromptUiState()
        private set

    private val isListening: Boolean
        get() = state.phase == LegacyFingerprintPromptPhase.LISTENING ||
            state.phase == LegacyFingerprintPromptPhase.FAILED ||
            state.phase == LegacyFingerprintPromptPhase.RETRYING

    fun authenticationFailed() {
        if (!isListening) return
        state = state.copy(
            phase = LegacyFingerprintPromptPhase.FAILED,
            message = "Fingerprint not recognised",
            failedAttempts = state.failedAttempts + 1,
        )
    }

    fun authenticationHelp(message: String) {
        if (!isListening) return
        state = state.copy(
            phase = LegacyFingerprintPromptPhase.FAILED,
            message = message,
            failedAttempts = state.failedAttempts + 1,
        )
    }

    /** FAILED presentation elapsed: return to listening text. */
    fun retry() {
        if (state.phase != LegacyFingerprintPromptPhase.FAILED) return
        state = state.copy(
            phase = LegacyFingerprintPromptPhase.RETRYING,
            message = "Touch the sensor again",
        )
    }

    fun succeeded() {
        if (!isListening) return
        state = state.copy(
            phase = LegacyFingerprintPromptPhase.SUCCESS,
            message = "Fingerprint recognised",
            failure = null,
        )
    }

    fun error(failure: StepResult.Failed) {
        if (state.phase == LegacyFingerprintPromptPhase.SUCCESS ||
            state.phase == LegacyFingerprintPromptPhase.CANCELLED
        ) {
            return
        }
        state = state.copy(
            phase = LegacyFingerprintPromptPhase.ERROR,
            message = failure.userMessage,
            failure = failure,
        )
    }

    fun cancelled(failure: StepResult.Failed) {
        state = state.copy(
            phase = LegacyFingerprintPromptPhase.CANCELLED,
            message = failure.userMessage,
            failure = failure,
        )
    }
}

// Framework FingerprintManager error codes (API 23+). They are
// mirrored here as literals because compileSdk 37 no longer exposes
// the framework fingerprint constants to this module.
private const val FINGERPRINT_ERROR_HW_UNAVAILABLE = 1
private const val FINGERPRINT_ERROR_UNABLE_TO_PROCESS = 2
private const val FINGERPRINT_ERROR_TIMEOUT = 3
private const val FINGERPRINT_ERROR_NO_SPACE = 4
private const val FINGERPRINT_ERROR_CANCELED = 5
private const val FINGERPRINT_ERROR_LOCKOUT = 7
private const val FINGERPRINT_ERROR_VENDOR = 8
private const val FINGERPRINT_ERROR_LOCKOUT_PERMANENT = 9
private const val FINGERPRINT_ERROR_USER_CANCELED = 10
private const val FINGERPRINT_ERROR_NO_FINGERPRINTS = 11
private const val FINGERPRINT_ERROR_HW_NOT_PRESENT = 12
private const val FINGERPRINT_ERROR_NEGATIVE_BUTTON = 13

/**
 * Maps a framework prompt error to the typed step failure every
 * surface renders (Phase 2.6 discipline): step PROMPT, the honest
 * reason, and the framework code kept in the text so a screenshot
 * identifies the platform's verdict exactly.
 */
fun legacyFingerprintPromptFailure(
    errorCode: Int,
    platformMessage: CharSequence?,
): StepResult.Failed {
    val platform = platformMessage?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    val reason = when (errorCode) {
        FINGERPRINT_ERROR_CANCELED,
        FINGERPRINT_ERROR_USER_CANCELED,
        FINGERPRINT_ERROR_NEGATIVE_BUTTON,
        -> "Cancelled"
        FINGERPRINT_ERROR_HW_UNAVAILABLE ->
            "Fingerprint hardware is temporarily unavailable (framework error $errorCode)"
        FINGERPRINT_ERROR_UNABLE_TO_PROCESS ->
            "The fingerprint sensor couldn't process that touch (framework error $errorCode)"
        FINGERPRINT_ERROR_TIMEOUT ->
            "Fingerprint timed out (framework error $errorCode)"
        FINGERPRINT_ERROR_NO_SPACE ->
            "There is no space left for fingerprint data (framework error $errorCode)"
        FINGERPRINT_ERROR_LOCKOUT ->
            "Too many failed attempts — fingerprint is temporarily locked (framework error $errorCode)"
        FINGERPRINT_ERROR_VENDOR ->
            (platform ?: "The fingerprint sensor reported a vendor error") +
                " (framework error $errorCode)"
        FINGERPRINT_ERROR_LOCKOUT_PERMANENT ->
            "Too many failed attempts — fingerprint is locked until a credential is used " +
                "(framework error $errorCode)"
        FINGERPRINT_ERROR_NO_FINGERPRINTS ->
            "No fingerprint is enrolled on this device (framework error $errorCode)"
        FINGERPRINT_ERROR_HW_NOT_PRESENT ->
            "This device does not have a fingerprint sensor (framework error $errorCode)"
        else ->
            (platform ?: "The fingerprint prompt failed") + " (framework error $errorCode)"
    }
    return StepResult.Failed(BiometricStep.PROMPT, reason)
}

/** Maps a framework "acquired" help code to try-again guidance. */
fun legacyFingerprintHelpMessage(helpCode: Int, platformMessage: CharSequence?): String =
    when (helpCode) {
        1 -> "Only part of your fingerprint was read — try again"
        2 -> "Press the fingerprint sensor more firmly"
        3 -> "Clean the fingerprint sensor and try again"
        4 -> "Lift your finger a little more slowly"
        5 -> "Don't lift your finger so quickly"
        else -> platformMessage?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Adjust your finger on the sensor and try again"
    }

/** One in-flight legacy prompt, created by the executor seam. */
class LegacyFingerprintPromptRequest internal constructor(
    val id: Long,
    internal val cipher: Cipher,
    val title: String,
    val subtitle: String?,
    val negativeButtonText: String,
    internal val onAuthenticated: (Cipher) -> Unit,
    internal val onError: (StepResult.Failed) -> Unit,
) {
    internal val stateMachine = LegacyFingerprintPromptStateMachine()
    internal var authorisedCipher: Cipher? = null
    internal var cancelAction: (() -> Unit)? = null
    internal var finished: Boolean = false
}

/**
 * Owns the single active legacy prompt. The executor seam deposits
 * a request here; [LegacyFingerprintPromptHost] (composed at the
 * MainActivity root) renders it and drives the framework session.
 * Callbacks fire exactly once per request: [completeSuccess] /
 * [dismissError] / [cancel] are the only exits, each guarded by the
 * request's finished flag, and stale framework callbacks for a
 * completed request are ignored by identity.
 */
object LegacyFingerprintPromptCoordinator {
    private val _activeRequest = MutableStateFlow<LegacyFingerprintPromptRequest?>(null)
    val activeRequest: StateFlow<LegacyFingerprintPromptRequest?> = _activeRequest.asStateFlow()

    private val _uiState = MutableStateFlow(LegacyFingerprintPromptUiState())
    val uiState: StateFlow<LegacyFingerprintPromptUiState> = _uiState.asStateFlow()

    private var nextId: Long = 1L

    @Synchronized
    fun begin(
        cipher: Cipher,
        title: String,
        subtitle: String?,
        negativeButtonText: String,
        onAuthenticated: (Cipher) -> Unit,
        onError: (StepResult.Failed) -> Unit,
    ) {
        val current = _activeRequest.value
        if (current != null && !current.finished) {
            onError(
                StepResult.Failed(
                    BiometricStep.PROMPT,
                    "Another fingerprint prompt is already open",
                ),
            )
            return
        }
        val request = LegacyFingerprintPromptRequest(
            id = nextId++,
            cipher = cipher,
            title = title,
            subtitle = subtitle,
            negativeButtonText = negativeButtonText,
            onAuthenticated = onAuthenticated,
            onError = onError,
        )
        _activeRequest.value = request
        _uiState.value = request.stateMachine.state
    }

    private fun isCurrent(request: LegacyFingerprintPromptRequest): Boolean =
        _activeRequest.value === request && !request.finished

    private fun publish(request: LegacyFingerprintPromptRequest) {
        _uiState.value = request.stateMachine.state
    }

    internal fun attachSession(request: LegacyFingerprintPromptRequest, cancelAction: () -> Unit) {
        if (isCurrent(request)) request.cancelAction = cancelAction
    }

    fun authenticationFailed(request: LegacyFingerprintPromptRequest) {
        if (!isCurrent(request)) return
        request.stateMachine.authenticationFailed()
        publish(request)
    }

    fun authenticationHelp(request: LegacyFingerprintPromptRequest, message: String) {
        if (!isCurrent(request)) return
        request.stateMachine.authenticationHelp(message)
        publish(request)
    }

    fun retry(request: LegacyFingerprintPromptRequest) {
        if (!isCurrent(request)) return
        request.stateMachine.retry()
        publish(request)
    }

    fun authenticationSucceeded(request: LegacyFingerprintPromptRequest, authorisedCipher: Cipher) {
        if (!isCurrent(request)) return
        request.authorisedCipher = authorisedCipher
        request.stateMachine.succeeded()
        publish(request)
    }

    fun authenticationError(request: LegacyFingerprintPromptRequest, failure: StepResult.Failed) {
        if (!isCurrent(request)) return
        if (failure.reason == "Cancelled") {
            // The framework ended the session itself (system cancel):
            // finish exactly like a user cancel.
            finishWithError(request, failure)
            return
        }
        request.stateMachine.error(failure)
        publish(request)
    }

    /** SUCCESS presentation elapsed: hand the authorised cipher back. */
    fun completeSuccess(request: LegacyFingerprintPromptRequest) {
        if (!isCurrent(request)) return
        val cipher = request.authorisedCipher ?: return
        request.finished = true
        _activeRequest.value = null
        _uiState.value = LegacyFingerprintPromptUiState()
        request.onAuthenticated(cipher)
    }

    /** The user closed the terminal-error dialog: deliver the failure. */
    fun dismissError(request: LegacyFingerprintPromptRequest) {
        if (!isCurrent(request)) return
        val failure = request.stateMachine.state.failure ?: return
        finishWithError(request, failure)
    }

    /** Cancel button / dismiss: cancel the real session, then report. */
    fun cancel(request: LegacyFingerprintPromptRequest) {
        if (!isCurrent(request)) return
        val failure = StepResult.Failed(BiometricStep.PROMPT, "Cancelled")
        request.stateMachine.cancelled(failure)
        val action = request.cancelAction
        request.finished = true
        _activeRequest.value = null
        _uiState.value = LegacyFingerprintPromptUiState()
        // Mark finished BEFORE cancelling the session: the framework
        // will answer with its own CANCELED error, which must not
        // deliver the callback a second time.
        action?.invoke()
        request.onError(failure)
    }

    private fun finishWithError(request: LegacyFingerprintPromptRequest, failure: StepResult.Failed) {
        request.finished = true
        if (_activeRequest.value === request) _activeRequest.value = null
        _uiState.value = LegacyFingerprintPromptUiState()
        request.onError(failure)
    }

    /** Test hygiene: drop any active request without firing callbacks. */
    internal fun resetForTest() {
        _activeRequest.value?.finished = true
        _activeRequest.value = null
        _uiState.value = LegacyFingerprintPromptUiState()
    }
}

/**
 * One framework authentication session for one request. The actual
 * framework call lives in the :legacyfingerprint module
 * ([LegacyFingerprint]) — a direct FingerprintManager.authenticate
 * with a CryptoObject wrapping the request's cipher.
 *
 * Why not androidx.core's FingerprintManagerCompat (the first
 * implementation of this session)? Codex's P1 on PR #12, verified
 * by disassembling the published Core 1.18.0 AAR — the version this
 * project's dependency set resolves: both compat authenticate
 * overloads are single-`return` no-ops and the Api23Impl delegation
 * class no longer exists, so a compat session would never start a
 * scan. The direct framework call is the only working route.
 */
internal class LegacyFingerprintAuthenticationSession(
    private val request: LegacyFingerprintPromptRequest,
) {
    private var cancellationSignal: CancellationSignal? = null

    fun start(context: Context) {
        // The request may already have been cancelled/finished
        // before this effect ran; never start hardware for it.
        if (request.finished) return
        try {
            val signal = LegacyFingerprint.authenticate(
                context,
                request.cipher,
                object : LegacyFingerprint.Callback {
                    override fun onError(errorCode: Int, message: CharSequence) {
                        LegacyFingerprintPromptCoordinator.authenticationError(
                            request,
                            legacyFingerprintPromptFailure(errorCode, message),
                        )
                    }

                    override fun onHelp(helpCode: Int, message: CharSequence) {
                        if (helpCode == 0) return
                        LegacyFingerprintPromptCoordinator.authenticationHelp(
                            request,
                            legacyFingerprintHelpMessage(helpCode, message),
                        )
                    }

                    override fun onSucceeded(cipher: Cipher?) {
                        if (cipher != null) {
                            LegacyFingerprintPromptCoordinator.authenticationSucceeded(
                                request,
                                cipher,
                            )
                        } else {
                            LegacyFingerprintPromptCoordinator.authenticationError(
                                request,
                                StepResult.Failed(
                                    BiometricStep.PROMPT,
                                    "Fingerprint result carried no crypto proof",
                                ),
                            )
                        }
                    }

                    override fun onFailed() {
                        LegacyFingerprintPromptCoordinator.authenticationFailed(request)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            if (signal == null) {
                LegacyFingerprintPromptCoordinator.authenticationError(
                    request,
                    legacyFingerprintPromptFailure(FINGERPRINT_ERROR_HW_NOT_PRESENT, null),
                )
            } else {
                cancellationSignal = signal
                LegacyFingerprintPromptCoordinator.attachSession(request) {
                    signal.cancel()
                }
            }
        } catch (e: Exception) {
            LegacyFingerprintPromptCoordinator.authenticationError(
                request,
                StepResult.Failed(BiometricStep.PROMPT, stepReason(e)),
            )
        }
    }
}

/**
 * The legacy prompt's visual surface, composed ONCE at the
 * MainActivity root so it can present over the lock screen, the
 * nav shell, Settings and the per-note dialogs alike. Renders
 * nothing unless a legacy request is active (always the case on
 * API 28+, where the AndroidX executor is used instead).
 */
@Composable
fun LegacyFingerprintPromptHost() {
    val request by LegacyFingerprintPromptCoordinator.activeRequest.collectAsState()
    val uiState by LegacyFingerprintPromptCoordinator.uiState.collectAsState()
    val active = request ?: return
    val context = LocalContext.current
    val session = remember(active.id) { LegacyFingerprintAuthenticationSession(active) }

    LaunchedEffect(active.id) {
        session.start(context.applicationContext)
    }
    LaunchedEffect(active.id, uiState.phase) {
        when (uiState.phase) {
            LegacyFingerprintPromptPhase.FAILED -> {
                delay(1_400)
                LegacyFingerprintPromptCoordinator.retry(active)
            }
            LegacyFingerprintPromptPhase.SUCCESS -> {
                delay(450)
                LegacyFingerprintPromptCoordinator.completeSuccess(active)
            }
            else -> Unit
        }
    }

    val phase = uiState.phase
    val isError = phase == LegacyFingerprintPromptPhase.ERROR
    val isSuccess = phase == LegacyFingerprintPromptPhase.SUCCESS
    AlertDialog(
        onDismissRequest = {
            when (phase) {
                LegacyFingerprintPromptPhase.ERROR ->
                    LegacyFingerprintPromptCoordinator.dismissError(active)
                LegacyFingerprintPromptPhase.SUCCESS,
                LegacyFingerprintPromptPhase.CANCELLED,
                -> Unit
                else -> LegacyFingerprintPromptCoordinator.cancel(active)
            }
        },
        title = {
            Column {
                NotaBrandLockup(markSize = 24.dp)
                Spacer(Modifier.height(NotaSpacing.sm))
                Text(active.title, style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(
                        if (isSuccess) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                Spacer(Modifier.height(NotaSpacing.md))
                if (!active.subtitle.isNullOrBlank() &&
                    phase == LegacyFingerprintPromptPhase.LISTENING
                ) {
                    Text(
                        active.subtitle!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(NotaSpacing.xs))
                }
                Text(
                    uiState.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isError || phase == LegacyFingerprintPromptPhase.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center,
                )
                if (phase == LegacyFingerprintPromptPhase.FAILED) {
                    Spacer(Modifier.height(NotaSpacing.xs))
                    Text(
                        "The sensor is still listening — adjust your finger and touch again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            if (isError) {
                TextButton(onClick = {
                    LegacyFingerprintPromptCoordinator.dismissError(active)
                }) { Text("Close") }
            }
        },
        dismissButton = {
            if (!isError && !isSuccess) {
                TextButton(onClick = {
                    LegacyFingerprintPromptCoordinator.cancel(active)
                }) { Text(active.negativeButtonText) }
            }
        },
    )
}
