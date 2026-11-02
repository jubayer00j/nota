package app.nota.feature.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.nota.data.security.AppLockBiometricStore
import app.nota.data.security.AppLockManager
import app.nota.data.security.AppLockMethod
import app.nota.data.security.StepResult
import app.nota.data.security.UnlockResult
import app.nota.ui.components.NotaBrandLockup
import app.nota.ui.theme.NotaSpacing
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Method preference order for the lock screen's initial tab. */
private val METHOD_ORDER = listOf(AppLockMethod.PATTERN, AppLockMethod.PIN, AppLockMethod.PASSWORD)

/**
 * The lock screen (Phase 2a, redesigned Phase 2.5/F5) — shown by
 * MainActivity INSTEAD of the nav shell while locked, so no note
 * content exists in the composition at all. Unlock-only:
 * setup/change/disable live in Settings.
 *
 * Phase 2.5 experience: the shared brand lockup up top, the
 * credential form on a raised paper card, biometric-first (the
 * prompt auto-presents once per appearance; a large fingerprint
 * target re-triggers it), wrong attempts shake the card with a
 * clear error + attempts-remaining hint, and a lockout replaces
 * the form with a visible mm:ss countdown — never a silent
 * refusal. FLAG_SECURE and the ready gate live in MainActivity
 * and are untouched.
 */
@Composable
fun LockScreen(
    manager: AppLockManager = koinInject(),
    biometricStore: AppLockBiometricStore = koinInject(),
) {
    val settings by manager.settings.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context as? FragmentActivity }
    val scope = rememberCoroutineScope()

    var selectedMethod by remember(settings.enabledMethods) {
        mutableStateOf(
            METHOD_ORDER.firstOrNull { it in settings.enabledMethods }
                ?: AppLockMethod.PASSWORD,
        )
    }
    var feedback by remember { mutableStateOf<String?>(null) }
    var patternError by remember { mutableStateOf(false) }
    var shakeKey by remember { mutableIntStateOf(0) }
    var formResetKey by remember { mutableIntStateOf(0) }
    // Phase 2.6 (Fix B): one verification in flight at a time. A
    // repeat submission while a verify is running is DROPPED here,
    // never queued behind the manager's mutex to become a second
    // derivation — and the form disables + shows progress so the
    // wait is visible instead of feeling like a hang.
    var verifying by remember { mutableStateOf(false) }

    // Lockout countdown, ticked locally from the persisted deadline.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(settings.lockoutUntil) {
        nowMillis = System.currentTimeMillis()
        while (System.currentTimeMillis() < settings.lockoutUntil) {
            delay(250)
            nowMillis = System.currentTimeMillis()
        }
    }
    val lockedOut = settings.lockoutUntil > nowMillis
    val lockoutSeconds = ceil((settings.lockoutUntil - nowMillis) / 1000.0).toInt().coerceAtLeast(0)

    val biometricReady = settings.biometricEnabled && settings.isEnabled &&
        activity != null && isBiometricAvailable(context)

    // Phase 2.5 (F4, coordinator re-point of the F5 design): the ONE
    // biometric entry point for this screen — the auto-prompt effect
    // and the fingerprint disc both go through here. Crypto-bound:
    // the cipher comes from AppLockBiometricStore.openCipherResult()
    // (suspend); the prompt authorises it via CryptoObject, and
    // AppLockManager unlocks ONLY when that cipher decrypts the
    // sealed token. There is no bare-boolean path.
    // Phase 2.6 (Fix A): both steps are typed — a failure (dead key,
    // corrupt token, failed decrypt) lands in `feedback` with its
    // step + reason instead of a generic or silent refusal.
    fun showCryptoBiometric() {
        val host = activity ?: return
        scope.launch {
            when (val opened = biometricStore.openCipherResult()) {
                is StepResult.Failed -> feedback = opened.userMessage
                is StepResult.Ok -> showBiometricCryptoPrompt(
                    host,
                    opened.value,
                    onAuthenticated = { authorised ->
                        when (val unlocked = manager.unlockWithBiometric(authorised)) {
                            is StepResult.Failed -> feedback = unlocked.userMessage
                            is StepResult.Ok -> Unit // MainActivity swaps to the nav shell.
                        }
                    },
                    onError = { failure ->
                        // A user cancel just returns to the credential
                        // form; every other prompt failure is shown
                        // with its step + reason (Phase 2.7: framework
                        // errors arrive typed the same way).
                        if (failure.reason != "Cancelled") {
                            feedback = failure.userMessage
                        }
                    },
                )
            }
        }
    }

    // Auto-prompt ONCE per lock-screen appearance when biometric is
    // on. Never during a lockout: unlockWithBiometric refuses while
    // locked out, so a prompt there would "succeed" yet leave the
    // app locked. Keyed on lockedOut so a screen opened mid-lockout
    // still prompts once the countdown ends.
    var biometricPrompted by remember { mutableStateOf(false) }
    LaunchedEffect(biometricReady, lockedOut) {
        if (biometricReady && !lockedOut && !biometricPrompted && activity != null) {
            biometricPrompted = true
            showCryptoBiometric()
        }
    }

    fun submit(method: AppLockMethod, canonicalSecret: String) {
        if (verifying) return
        verifying = true
        scope.launch {
            try {
                when (manager.attemptUnlock(method, canonicalSecret)) {
                    UnlockResult.Success -> Unit // MainActivity swaps to the nav shell.
                    UnlockResult.Wrong -> {
                        feedback = "Incorrect — try again"
                        patternError = true
                        shakeKey++
                        formResetKey++
                    }
                    is UnlockResult.LockedOut -> {
                        feedback = null
                        patternError = true
                        shakeKey++
                        formResetKey++
                    }
                }
            } finally {
                verifying = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(NotaSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(NotaSpacing.xxl))
        // Same mark + wordmark as the Notes header (shared composable).
        NotaBrandLockup(markSize = 40.dp)
        Spacer(Modifier.height(NotaSpacing.lg))
        Text("Nota is locked", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(NotaSpacing.xs))
        Text(
            "Unlock to see your notes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NotaSpacing.lg))

        ShakeBox(shakeKey = shakeKey, modifier = Modifier.fillMaxWidth()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = NotaSpacing.xs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(NotaSpacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (lockedOut) {
                        LockoutCountdown(remainingSeconds = lockoutSeconds)
                    } else {
                        CredentialEntryForm(
                            methods = settings.enabledMethods,
                            selectedMethod = selectedMethod,
                            onMethodSelected = {
                                selectedMethod = it
                                feedback = null
                                patternError = false
                            },
                            onSubmit = { method, secret -> submit(method, secret) },
                            submitLabel = "Unlock",
                            resetKey = formResetKey,
                            patternError = patternError,
                            error = feedback != null || patternError,
                            enabled = !verifying,
                            onPatternTooShort = {
                                feedback = "Pattern too short — connect at least 4 dots"
                                patternError = true
                                shakeKey++
                            },
                        )
                        if (verifying) {
                            Spacer(Modifier.height(NotaSpacing.sm))
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        if (feedback != null) {
                            Spacer(Modifier.height(NotaSpacing.sm))
                            Text(
                                feedback!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                            )
                        }
                        val attemptsLeft = LOCKOUT_THRESHOLD - settings.failedAttempts
                        if (settings.failedAttempts > 0 && attemptsLeft > 0) {
                            Spacer(Modifier.height(NotaSpacing.xs))
                            Text(
                                if (attemptsLeft == 1) {
                                    "1 attempt left before a temporary pause"
                                } else {
                                    "$attemptsLeft attempts left before a temporary pause"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }

        if (biometricReady && !lockedOut) {
            Spacer(Modifier.height(NotaSpacing.lg))
            // Large fingerprint target: the modern-app pattern — one
            // obvious disc to tap, labelled, re-triggering the prompt
            // through the same wrapper as the auto-prompt.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { showCryptoBiometric() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Fingerprint,
                        contentDescription = "Unlock with fingerprint",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.height(NotaSpacing.xs))
                Text(
                    "Use fingerprint",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(NotaSpacing.lg))
    }
}
