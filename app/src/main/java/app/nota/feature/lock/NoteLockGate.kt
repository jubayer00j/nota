package app.nota.feature.lock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.nota.data.security.AppLockMethod
import app.nota.data.security.NoteLockRecord
import app.nota.ui.theme.NotaSpacing
import kotlin.math.ceil
import kotlinx.coroutines.delay

/** Method preference order for the gate's initial tab (mirrors LockScreen). */
private val GATE_METHOD_ORDER = listOf(AppLockMethod.PATTERN, AppLockMethod.PIN, AppLockMethod.PASSWORD)

/**
 * The per-note lock gate (Phase 2b) — shown INSTEAD of any editor content
 * when a locked note is opened. It renders the note's title, a lock
 * affordance and a credential prompt built from PR-A's shared credential
 * components, with tabs for exactly the methods present in the record's
 * slots. No body text, no snippet and no attachment preview exists in
 * this composition.
 *
 * Fingerprint (Phase 2.5, crypto-bound): the caller supplies a
 * DECRYPT cipher over the sealed NEK copy via [biometricCipherProvider];
 * that cipher IS the prompt's CryptoObject, and on success the SAME
 * authorised cipher is handed to [onBiometricUnlock] - the repository
 * releases the NEK only when the cipher actually decrypts the copy.
 * There is no bare-boolean path. During a lockout the inputs are disabled and
 * a live countdown shows, driven by the record's persisted deadline.
 *
 * v0.8.1: the gate mirrors LockScreen's two reachability behaviours
 * — it AUTO-PROMPTS fingerprint once per appearance when ready, and
 * its content column scrolls. Before this, the button below the form
 * was the only fingerprint affordance, and on smaller screens (the
 * pattern pad alone is 264.dp, under the editor's top bar) it sat
 * below the visible area with no way to scroll to it — a
 * fingerprint-enabled note could never actually be unlocked by
 * fingerprint on those devices.
 */
@Composable
fun NoteLockGate(
    noteTitle: String,
    record: NoteLockRecord,
    error: String?,
    onBack: () -> Unit,
    onSubmitCredential: (AppLockMethod, String) -> Unit,
    biometricCipherProvider: suspend () -> app.nota.data.security.StepResult<javax.crypto.Cipher>,
    onBiometricUnlock: (javax.crypto.Cipher) -> Unit,
    modifier: Modifier = Modifier,
    // Phase 2.5 (F3): the lockout deadline as last reported by the
    // repository for THIS session's attempts (epoch millis), and
    // whether an attempt is currently being verified. The persisted
    // record remains the primary deadline source; the explicit state
    // covers the window before the refreshed record reaches this
    // composition, so a lockout imposed by the latest attempt can never
    // render as a live form with a static error line again. While an
    // attempt is in flight the inputs are disabled — one user action,
    // one recorded attempt.
    lockoutUntilMillis: Long? = null,
    unlockInProgress: Boolean = false,
) {
    val context = LocalContext.current
    val activity = remember(context) { context as? FragmentActivity }
    val methods = remember(record) {
        record.slots.keys.map { it.appLockMethod }.toSet()
    }
    var selectedMethod by remember(methods) {
        mutableStateOf(
            GATE_METHOD_ORDER.firstOrNull { it in methods } ?: AppLockMethod.PASSWORD,
        )
    }
    var formResetKey by remember { mutableIntStateOf(0) }
    var patternError by remember { mutableStateOf(false) }
    LaunchedEffect(error) {
        if (error != null) {
            formResetKey++
            patternError = true
        }
    }

    // Lockout countdown, ticked locally from the effective deadline:
    // the later of the persisted record's deadline and the deadline the
    // ViewModel reported for the latest attempt (Phase 2.5, F3).
    val effectiveLockoutUntil = maxOf(record.lockoutUntil ?: 0L, lockoutUntilMillis ?: 0L)
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(effectiveLockoutUntil) {
        nowMillis = System.currentTimeMillis()
        if (effectiveLockoutUntil <= 0L) return@LaunchedEffect
        while (System.currentTimeMillis() < effectiveLockoutUntil) {
            delay(250)
            nowMillis = System.currentTimeMillis()
        }
    }
    val lockoutUntil = effectiveLockoutUntil
    val lockedOut = lockoutUntil > nowMillis
    val lockoutSeconds = ceil((lockoutUntil - nowMillis) / 1000.0).toInt().coerceAtLeast(0)

    val biometricReady = record.biometric && activity != null && isBiometricAvailable(context)

    // Prefetch the DECRYPT cipher for the prompt (suspend: it reads
    // the sealed copy's IV from DataStore). A fresh cipher is needed
    // per prompt; if the user cancels and retries, the effect key
    // (formResetKey) re-runs and fetches a new one.
    // Phase 2.6 (Fix A): the prefetch is a typed StepResult — when it
    // failed (dead key, missing copy), the fingerprint button stays
    // visible and tapping it SHOWS the reason instead of doing
    // nothing at all.
    var biometricCipherResult by remember(record, formResetKey) {
        mutableStateOf<app.nota.data.security.StepResult<javax.crypto.Cipher>?>(null)
    }
    var bioError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(record, formResetKey) {
        if (record.biometric) biometricCipherResult = biometricCipherProvider()
    }

    // The ONE fingerprint prompt invocation for this gate (v0.8.1) —
    // the auto-prompt effect below and the button both go through
    // here so the call and its error handling cannot drift apart.
    // Crypto-bound, exactly as the button has always been: the
    // prefetched cipher IS the prompt's CryptoObject, and on success
    // the SAME authorised cipher is handed to onBiometricUnlock.
    fun showNoteBiometricPrompt(cipher: javax.crypto.Cipher) {
        val host = activity ?: return
        bioError = null
        showBiometricCryptoPrompt(
            host,
            cipher,
            title = "Unlock note",
            subtitle = noteTitle.ifBlank { "Untitled" },
            onAuthenticated = onBiometricUnlock,
            onError = { failure ->
                if (failure.reason != "Cancelled") {
                    bioError = failure.userMessage
                }
            },
        )
    }

    // Auto-prompt ONCE per gate appearance (v0.8.1, mirrors
    // LockScreen). The prompted flag is a plain remember — NOT keyed
    // on record/formResetKey — so a cancelled prompt or a wrong
    // credential attempt never re-fires it. The effect is keyed on
    // lockedOut so a gate opened mid-lockout still prompts once when
    // the countdown ends, and on the cipher's arrival so a slow
    // prefetch prompts when it lands. A Failed prefetch never
    // auto-prompts: the button stays the manual path and surfaces
    // the typed reason on tap. The flag is set BEFORE firing, and
    // the cipher is single-use per prompt, so the once-only rule is
    // also what keeps the auto-prompt from reusing a spent cipher.
    var biometricPrompted by remember { mutableStateOf(false) }
    val biometricCipherReady =
        biometricCipherResult is app.nota.data.security.StepResult.Ok
    LaunchedEffect(biometricReady, lockedOut, biometricCipherReady) {
        if (shouldAutoPromptNoteUnlock(
                biometricReady = biometricReady,
                lockedOut = lockedOut,
                alreadyPrompted = biometricPrompted,
                cipherReady = biometricCipherReady,
            )
        ) {
            biometricPrompted = true
            (biometricCipherResult as? app.nota.data.security.StepResult.Ok)
                ?.let { showNoteBiometricPrompt(it.value) }
        }
    }

    Column(modifier.fillMaxSize().padding(NotaSpacing.lg)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        // v0.8.1: scrollable (mirrors LockScreen) so the fingerprint
        // button below the form is always reachable on smaller
        // screens instead of clipped below the visible area.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(NotaSpacing.md))
            Text(
                noteTitle.ifBlank { "Untitled" },
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(NotaSpacing.xs))
            Text(
                "This note is locked",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(NotaSpacing.lg))
            if (lockedOut) {
                Text(
                    "Too many attempts. Try again in ${lockoutSeconds}s.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                CredentialEntryForm(
                    methods = methods,
                    selectedMethod = selectedMethod,
                    onMethodSelected = {
                        selectedMethod = it
                        patternError = false
                    },
                    onSubmit = { method, secret -> onSubmitCredential(method, secret) },
                    submitLabel = "Unlock",
                    resetKey = formResetKey,
                    patternError = patternError,
                    onPatternTooShort = { patternError = true },
                    // Phase 2.5 (F3): no new submissions while a verify
                    // is running (the ViewModel also drops them; this
                    // keeps the pad/field from accepting a second entry
                    // that would look like it was ignored).
                    enabled = !unlockInProgress,
                )
                val shownError = error ?: bioError
                if (shownError != null) {
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Text(
                        shownError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (biometricReady && !lockedOut) {
                Spacer(Modifier.height(NotaSpacing.lg))
                OutlinedButton(
                    enabled = !unlockInProgress,
                    onClick = {
                        when (val result = biometricCipherResult) {
                            is app.nota.data.security.StepResult.Failed ->
                                bioError = result.userMessage
                            is app.nota.data.security.StepResult.Ok ->
                                showNoteBiometricPrompt(result.value)
                            null -> bioError = "Fingerprint is still preparing — try again in a moment."
                        }
                    },
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null)
                    Spacer(Modifier.size(NotaSpacing.sm))
                    Text("Unlock with fingerprint")
                }
            }
        }
    }
}

/**
 * Pure auto-prompt decision for the gate (v0.8.1, unit-tested):
 * fire only when biometric unlock is ready, the gate is not in a
 * lockout, this appearance has not prompted yet, and the prefetched
 * DECRYPT cipher actually arrived. A Failed or still-loading
 * prefetch never auto-prompts — the button remains the manual path
 * and surfaces the typed reason on tap.
 */
internal fun shouldAutoPromptNoteUnlock(
    biometricReady: Boolean,
    lockedOut: Boolean,
    alreadyPrompted: Boolean,
    cipherReady: Boolean,
): Boolean = biometricReady && !lockedOut && !alreadyPrompted && cipherReady
