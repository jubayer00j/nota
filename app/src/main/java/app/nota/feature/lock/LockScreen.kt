package app.nota.feature.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.nota.data.security.AppLockManager
import app.nota.data.security.AppLockMethod
import app.nota.data.security.UnlockResult
import app.nota.notes.R
import app.nota.ui.theme.NotaSpacing
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Method preference order for the lock screen's initial tab. */
private val METHOD_ORDER = listOf(AppLockMethod.PATTERN, AppLockMethod.PIN, AppLockMethod.PASSWORD)

/**
 * The lock screen (Phase 2a) — shown by MainActivity INSTEAD of the nav
 * shell while locked, so no note content exists in the composition at
 * all. Unlock-only: setup/change/disable live in Settings.
 *
 * Wrong attempts shake the credential area (the feel of 1.x's
 * noma-shake) and show "Incorrect — try again"; during a lockout the
 * input is disabled and a live countdown is shown.
 */
@Composable
fun LockScreen(
    manager: AppLockManager = koinInject(),
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

    // Shake animation on a wrong attempt.
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(shakeKey) {
        if (shakeKey == 0) return@LaunchedEffect
        shakeOffset.snapTo(0f)
        for (target in listOf(-14f, 12f, -9f, 7f, -4f, 0f)) {
            shakeOffset.animateTo(target, tween(durationMillis = 45))
        }
    }

    val biometricReady = settings.biometricEnabled && settings.isEnabled &&
        activity != null && isBiometricAvailable(context)

    // Auto-prompt ONCE per lock-screen appearance when biometric is on.
    var biometricPrompted by remember { mutableStateOf(false) }
    LaunchedEffect(biometricReady) {
        if (biometricReady && !biometricPrompted && activity != null) {
            biometricPrompted = true
            showBiometricPrompt(
                activity,
                onSuccess = { manager.unlockWithBiometric() },
                onError = { /* credential form stays available */ },
            )
        }
    }

    fun submit(method: AppLockMethod, canonicalSecret: String) {
        scope.launch {
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
        }
    }

    Column(
        Modifier.fillMaxSize().padding(NotaSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Image(
            painterResource(R.drawable.ic_nota_mark),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(NotaSpacing.md))
        Text("Nota is locked", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(NotaSpacing.xs))
        Text(
            "Unlock to see your notes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NotaSpacing.lg))

        Column(
            Modifier.offset { IntOffset(shakeOffset.value.roundToInt(), 0) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (lockedOut) {
                Text(
                    "Too many attempts. Try again in ${lockoutSeconds}s.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
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
                    onPatternTooShort = {
                        feedback = "Pattern too short — connect at least 4 dots"
                        patternError = true
                        shakeKey++
                    },
                )
                if (feedback != null) {
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Text(
                        feedback!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (biometricReady && !lockedOut) {
            Spacer(Modifier.height(NotaSpacing.lg))
            OutlinedButton(
                onClick = {
                    activity?.let {
                        showBiometricPrompt(
                            it,
                            onSuccess = { manager.unlockWithBiometric() },
                            onError = { /* stay on the credential form */ },
                        )
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
