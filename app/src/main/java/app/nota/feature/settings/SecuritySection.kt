package app.nota.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import app.nota.data.security.AppLockBiometricStore
import app.nota.data.security.AppLockManager
import app.nota.data.security.AppLockMethod
import app.nota.data.security.AppLockSettings
import app.nota.data.security.StepResult
import app.nota.feature.lock.CredentialEntryForm
import app.nota.feature.lock.CredentialSetupForm
import app.nota.feature.lock.biometricAvailability
import app.nota.feature.lock.showBiometricCryptoPrompt
import app.nota.ui.theme.NotaSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings → Security (Phase 2a): App Lock setup and management.
 *
 * EVERY mutation while App Lock is on (set/change/remove a method,
 * biometric toggle, timeout change, turn off) first requires verifying
 * a current credential — 1.x's `requestAuthorizedChange` — and a
 * biometric prompt may satisfy that verification. Setup while off needs
 * no verification (there is nothing to verify against), and a setup
 * only persists at its confirm step, so abandoning it leaves App Lock
 * exactly as it was.
 */
@Composable
fun SecuritySection(
    appLockManager: AppLockManager = koinInject(),
    biometricStore: AppLockBiometricStore = koinInject(),
) {
    val settings by appLockManager.settings.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context as? FragmentActivity }
    val scope = rememberCoroutineScope()
    // Phase 2.5: the ONE authoritative availability check (enum, so
    // Settings copy reflects the real sensor state on API 27 too).
    val bioAvailability = remember(context) { biometricAvailability(context) }
    val biometricAvailable = bioAvailability.isAvailable

    var verifyOpen by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var verifyFeedback by remember { mutableStateOf<String?>(null) }
    var verifyResetKey by remember { mutableStateOf(0) }
    // Phase 2.9 (J1): a credential save / re-auth verify runs a
    // full-cost PBKDF2 derivation — seconds on a J7-class device.
    // These flags cover that window: the dialogs show progress and
    // refuse further submits, so exactly ONE derivation runs per
    // step. In 0.3.4 the window was silent and re-tappable, which
    // read as a rejection and stacked duplicate derivations.
    var setupBusy by remember { mutableStateOf(false) }
    var verifyBusy by remember { mutableStateOf(false) }
    // A failed save re-arms the setup form (fresh instance via
    // setupEpoch) with the failure shown — never a dead dialog.
    var setupError by remember { mutableStateOf<String?>(null) }
    var setupEpoch by remember { mutableStateOf(0) }
    var verifyMethod by remember {
        mutableStateOf(AppLockMethod.PASSWORD)
    }
    var chooseMethodOpen by remember { mutableStateOf(false) }
    var setupMethod by remember { mutableStateOf<AppLockMethod?>(null) }
    var offerBiometricAfterSetup by remember { mutableStateOf(false) }
    var timeoutMenuOpen by remember { mutableStateOf(false) }

    fun requestAuthorized(action: () -> Unit) {
        if (!settings.isEnabled) {
            action()
        } else {
            verifyMethod = settings.enabledMethods.firstOrNull() ?: AppLockMethod.PASSWORD
            verifyFeedback = null
            pendingAction = action
            verifyOpen = true
        }
    }

    fun onVerified() {
        verifyOpen = false
        val action = pendingAction
        pendingAction = null
        action?.invoke()
    }

    // Phase 2.6 (Fix A): EVERY failure in the fingerprint flows lands
    // in bioError and is rendered under the Fingerprint row — key
    // generation, cipher setup, sealing, saving, prompt errors. In
    // 0.3.1 these were silent nulls / unchecked booleans, and the
    // toggle simply never turned on, with nothing to screenshot.
    var bioError by remember { mutableStateOf<String?>(null) }
    // An ON toggle backed by a token that no longer opens (device
    // key invalidated, record corrupt) must SAY so, not sit green.
    var bioTokenBroken by remember { mutableStateOf(false) }
    LaunchedEffect(settings.biometricEnabled) {
        bioTokenBroken = settings.biometricEnabled && !biometricStore.tokenUsable()
    }

    // Phase 2.5 crypto-bound flows: ENABLING seals a fresh token
    // through the prompt (encrypt cipher); re-authorisation verifies
    // by decrypting that token (decrypt cipher). No bare booleans.
    // Phase 2.6: typed StepResults end to end — see bioError above.
    fun promptBiometricSeal(onSealed: () -> Unit) {
        val host = activity
        if (host == null) {
            bioError = "Fingerprint prompt isn't available on this screen."
            return
        }
        when (val cipherResult = biometricStore.sealCipherResult()) {
            is StepResult.Failed -> bioError = cipherResult.userMessage
            is StepResult.Ok -> showBiometricCryptoPrompt(
                host,
                cipherResult.value,
                title = "Confirm it's you",
                negativeButtonText = "Cancel",
                onAuthenticated = { authorised ->
                    scope.launch {
                        when (val sealed = biometricStore.sealToken(authorised)) {
                            is StepResult.Failed -> bioError = sealed.userMessage
                            is StepResult.Ok -> {
                                bioError = null
                                onSealed()
                            }
                        }
                    }
                },
                onError = { failure -> bioError = failure.userMessage },
            )
        }
    }

    fun promptBiometricVerify(onSuccess: () -> Unit) {
        val host = activity
        if (host == null) {
            verifyFeedback = "Fingerprint prompt isn't available on this screen."
            return
        }
        scope.launch {
            when (val cipherResult = biometricStore.openCipherResult()) {
                is StepResult.Failed -> verifyFeedback = cipherResult.userMessage
                is StepResult.Ok -> showBiometricCryptoPrompt(
                    host,
                    cipherResult.value,
                    title = "Verify it's you",
                    negativeButtonText = "Cancel",
                    onAuthenticated = { authorised ->
                        when (val verified = appLockManager.verifyBiometricForSettings(authorised)) {
                            is StepResult.Failed -> verifyFeedback = verified.userMessage
                            is StepResult.Ok -> onSuccess()
                        }
                    },
                    onError = { failure -> verifyFeedback = failure.userMessage },
                )
            }
        }
    }

    Column {
        Text(
            "Security",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = NotaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("App Lock", style = MaterialTheme.typography.titleMedium)
                Text(
                    appLockSubtitle(settings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!settings.isEnabled) {
                TextButton(onClick = { chooseMethodOpen = true }) { Text("Set up") }
            }
        }
        Text(
            "App Lock is a privacy barrier, not encryption. It stops casual " +
                "access to the app; your notes themselves are not encrypted " +
                "by it. For real encryption, lock an individual note.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (settings.isEnabled) {
            Spacer(Modifier.height(NotaSpacing.sm))
            AppLockMethod.entries.forEach { method ->
                val isSet = method in settings.enabledMethods
                Row(
                    Modifier.fillMaxWidth().padding(vertical = NotaSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(method.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (isSet) "Set" else "Not set",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isSet) {
                        TextButton(onClick = {
                            requestAuthorized { setupMethod = method }
                        }) { Text("Change") }
                        TextButton(onClick = {
                            requestAuthorized {
                                scope.launch { appLockManager.removeCredential(method) }
                            }
                        }) { Text("Remove") }
                    } else {
                        TextButton(onClick = {
                            requestAuthorized { setupMethod = method }
                        }) { Text("Set") }
                    }
                }
            }

            // Biometric convenience gate.
            Row(
                Modifier.fillMaxWidth().padding(vertical = NotaSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Fingerprint unlock", style = MaterialTheme.typography.titleMedium)
                    Text(
                        bioAvailability.settingsSubtitle(settings.biometricEnabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.biometricEnabled,
                    enabled = biometricAvailable,
                    onCheckedChange = { wantEnabled ->
                        bioError = null
                        if (wantEnabled) {
                            // Enabling requires an OS biometric success
                            // (1.x posture), on top of re-authorisation.
                            requestAuthorized {
                                promptBiometricSeal {
                                    scope.launch { appLockManager.setBiometricEnabled(true) }
                                }
                            }
                        } else {
                            requestAuthorized {
                                scope.launch { appLockManager.setBiometricEnabled(false) }
                            }
                        }
                    },
                )
            }

            // Phase 2.6 (Fix A): the fingerprint row's failure
            // surface. A dead token behind an ON toggle and any
            // failed enable step render HERE, in words — the 0.3.1
            // behaviour (toggle off, zero explanation) is banned.
            if (settings.biometricEnabled && bioTokenBroken) {
                Text(
                    "Fingerprint is on, but its sealed key can't be read on this " +
                        "device anymore — turn it off and on again to re-enable it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = NotaSpacing.sm),
                )
            }
            if (bioError != null) {
                Text(
                    bioError!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = NotaSpacing.sm),
                )
            }

            // Lock-after timeout (the exact 1.x option set).
            Row(
                Modifier.fillMaxWidth().padding(vertical = NotaSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Lock after", style = MaterialTheme.typography.titleMedium)
                    Text(
                        AppLockSettings.TIMEOUT_OPTIONS
                            .firstOrNull { it.first == settings.lockTimeoutSeconds }?.second
                            ?: "Immediately",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { timeoutMenuOpen = true }) { Text("Change") }
                    DropdownMenu(expanded = timeoutMenuOpen, onDismissRequest = { timeoutMenuOpen = false }) {
                    AppLockSettings.TIMEOUT_OPTIONS.forEach { (seconds, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    (if (seconds == settings.lockTimeoutSeconds) "✓ " else "") + label,
                                )
                            },
                            onClick = {
                                timeoutMenuOpen = false
                                requestAuthorized {
                                    scope.launch { appLockManager.setLockTimeoutSeconds(seconds) }
                                }
                            },
                        )
                    }
                    }
                }
            }

            TextButton(onClick = {
                requestAuthorized { scope.launch { appLockManager.disableAll() } }
            }) { Text("Turn off App Lock") }
        }
    }

    // ---- Method chooser (initial setup) -------------------------------
    if (chooseMethodOpen) {
        AlertDialog(
            onDismissRequest = { chooseMethodOpen = false },
            title = { Text("Set up App Lock") },
            text = {
                Column {
                    Text("Choose how you'll unlock Nota. You can add the other methods later.")
                    AppLockMethod.entries.forEach { method ->
                        TextButton(
                            onClick = {
                                chooseMethodOpen = false
                                setupMethod = method
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(method.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { chooseMethodOpen = false }) { Text("Cancel") }
            },
        )
    }

    // ---- Setup / change dialog ----------------------------------------
    val activeSetupMethod = setupMethod
    if (activeSetupMethod != null) {
        Dialog(onDismissRequest = { if (!setupBusy) setupMethod = null }) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Column {
                    androidx.compose.runtime.key(setupEpoch) {
                        CredentialSetupForm(
                            method = activeSetupMethod,
                            busy = setupBusy,
                            onComplete = { canonicalSecret ->
                                // Phase 2.9 (J1): one save at a time. The form
                                // already fires completion once; this guard is
                                // the second lock on the same door, and
                                // setupBusy drives the form's progress state
                                // for the whole derivation.
                                if (!setupBusy) {
                                    setupBusy = true
                                    setupError = null
                                    val wasEnabled = settings.isEnabled
                                    scope.launch {
                                        try {
                                            appLockManager.setCredential(activeSetupMethod, canonicalSecret)
                                            setupMethod = null
                                            if (!wasEnabled && biometricAvailable && !settings.biometricEnabled) {
                                                offerBiometricAfterSetup = true
                                            }
                                        } catch (e: Exception) {
                                            // The save failed: say so and
                                            // re-arm a fresh form instead of
                                            // leaving a completed-but-unsaved
                                            // dialog on screen.
                                            setupError = "Couldn't save the ${activeSetupMethod.label.lowercase()} — please try again."
                                            setupEpoch++
                                        } finally {
                                            setupBusy = false
                                        }
                                    }
                                }
                            },
                            onCancel = { setupMethod = null },
                        )
                    }
                    if (setupError != null) {
                        Text(
                            setupError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(
                                start = NotaSpacing.md,
                                end = NotaSpacing.md,
                                bottom = NotaSpacing.md,
                            ),
                        )
                    }
                }
            }
        }
    }

    // ---- Post-setup fingerprint offer ----------------------------------
    if (offerBiometricAfterSetup) {
        AlertDialog(
            onDismissRequest = { offerBiometricAfterSetup = false },
            title = { Text("Add fingerprint unlock?") },
            text = {
                Text(
                    "You can also unlock Nota with your fingerprint. Your " +
                        "credential stays as the fallback.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    offerBiometricAfterSetup = false
                    promptBiometricSeal {
                        scope.launch { appLockManager.setBiometricEnabled(true) }
                    }
                }) { Text("Add fingerprint") }
            },
            dismissButton = {
                TextButton(onClick = { offerBiometricAfterSetup = false }) { Text("Not now") }
            },
        )
    }

    // ---- Re-authorisation dialog ---------------------------------------
    if (verifyOpen) {
        AlertDialog(
            onDismissRequest = {
                verifyOpen = false
                pendingAction = null
            },
            title = { Text("Verify it's you") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CredentialEntryForm(
                        methods = settings.enabledMethods,
                        selectedMethod = verifyMethod,
                        onMethodSelected = {
                            verifyMethod = it
                            verifyFeedback = null
                        },
                        onSubmit = { method, secret ->
                            // Phase 2.9 (J1): one verification at a
                            // time — repeat taps during the derivation
                            // used to stack further derivations and
                            // reset the inputs mid-flight.
                            if (!verifyBusy) {
                                verifyBusy = true
                                scope.launch {
                                    try {
                                        if (appLockManager.verifyForSettings(method, secret)) {
                                            onVerified()
                                        } else {
                                            verifyFeedback = "Incorrect — try again"
                                            verifyResetKey++
                                        }
                                    } finally {
                                        verifyBusy = false
                                    }
                                }
                            }
                        },
                        submitLabel = "Verify",
                        resetKey = verifyResetKey,
                        enabled = !verifyBusy,
                        onPatternTooShort = {
                            verifyFeedback = "Pattern too short — connect at least 4 dots"
                        },
                    )
                    if (verifyBusy) {
                        Spacer(Modifier.height(NotaSpacing.sm))
                        CircularProgressIndicator()
                    }
                    if (verifyFeedback != null) {
                        Spacer(Modifier.height(NotaSpacing.sm))
                        Text(
                            verifyFeedback!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (settings.biometricEnabled && biometricAvailable && activity != null) {
                        Spacer(Modifier.height(NotaSpacing.sm))
                        TextButton(
                            onClick = {
                                promptBiometricVerify { onVerified() }
                            },
                            enabled = !verifyBusy,
                        ) { Text("Use fingerprint instead") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    verifyOpen = false
                    pendingAction = null
                }) { Text("Cancel") }
            },
        )
    }
}

private fun appLockSubtitle(settings: AppLockSettings): String {
    if (!settings.isEnabled) return "Off"
    val parts = AppLockMethod.entries
        .filter { it in settings.enabledMethods }
        .map { it.label }
        .toMutableList()
    if (settings.biometricEnabled) parts.add("Fingerprint")
    return "On — " + parts.joinToString(" · ")
}
