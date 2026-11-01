package app.nota.feature.lock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * Fingerprint mirrors 1.x: the BiometricPrompt runs FIRST; only on its
 * success does the caller release the device copy via
 * NoteLockRepository.unlockWithBiometric (which performs no
 * authentication itself). During a lockout the inputs are disabled and
 * a live countdown shows, driven by the record's persisted deadline.
 */
@Composable
fun NoteLockGate(
    noteTitle: String,
    record: NoteLockRecord,
    error: String?,
    onBack: () -> Unit,
    onSubmitCredential: (AppLockMethod, String) -> Unit,
    onBiometricSuccess: () -> Unit,
    modifier: Modifier = Modifier,
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

    // Lockout countdown, ticked locally from the persisted deadline.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(record.lockoutUntil) {
        nowMillis = System.currentTimeMillis()
        val until = record.lockoutUntil ?: return@LaunchedEffect
        while (System.currentTimeMillis() < until) {
            delay(250)
            nowMillis = System.currentTimeMillis()
        }
    }
    val lockoutUntil = record.lockoutUntil ?: 0L
    val lockedOut = lockoutUntil > nowMillis
    val lockoutSeconds = ceil((lockoutUntil - nowMillis) / 1000.0).toInt().coerceAtLeast(0)

    val biometricReady = record.biometric && activity != null && isBiometricAvailable(context)

    Column(modifier.fillMaxSize().padding(NotaSpacing.lg)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(
            Modifier.fillMaxSize(),
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
                )
                if (error != null) {
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (biometricReady && !lockedOut) {
                Spacer(Modifier.height(NotaSpacing.lg))
                OutlinedButton(
                    onClick = {
                        activity?.let {
                            showBiometricPrompt(
                                it,
                                title = "Unlock note",
                                subtitle = noteTitle.ifBlank { "Untitled" },
                                onSuccess = onBiometricSuccess,
                                onError = { /* credential form stays available */ },
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
}
