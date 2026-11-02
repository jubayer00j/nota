package app.nota.feature.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.nota.data.security.AppLockCrypto
import app.nota.data.security.AppLockMethod
import app.nota.ui.theme.NotaSpacing
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Shared credential components (Phase 2a, redesigned Phase 2.5/F5) —
 * 1.x has ONE shared credential prompt used by the lock screen,
 * re-auth and setup; these composables are that shared layer for
 * Nota. The lock screen is unlock-only; setup/change lives in
 * Settings and reuses the same building blocks through
 * [CredentialSetupForm].
 *
 * Phase 2.5 visual language (pine & paper roles only, no new
 * palette): larger touch targets (72dp PIN keys on tonal circles,
 * a bigger pattern grid presence), an icon-led method switcher,
 * error-tinted inputs on a wrong attempt, a shared shake
 * ([ShakeBox]) and a shared visible lockout countdown
 * ([LockoutCountdown]) so every surface speaks the same way.
 */

internal const val PATTERN_MIN_POINTS = 4
internal const val PIN_MIN_LENGTH = 4
internal const val PIN_MAX_LENGTH = 6
internal const val PASSWORD_MIN_LENGTH = 8

/** Attempts allowed before the first lockout pause (AppLockCrypto). */
internal const val LOCKOUT_THRESHOLD = 5

/** "mm:ss" for a remaining-seconds count (65 -> "1:05"). */
internal fun formatLockoutCountdown(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}

/**
 * Horizontal shake for a wrong attempt — the feel of 1.x's
 * noma-shake, shared by the lock screen card and the setup form.
 * [shakeKey] is a monotonically increasing counter; 0 = never.
 */
@Composable
internal fun ShakeBox(
    shakeKey: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(shakeKey) {
        if (shakeKey == 0) return@LaunchedEffect
        shakeOffset.snapTo(0f)
        for (target in listOf(-14f, 12f, -9f, 7f, -4f, 0f)) {
            shakeOffset.animateTo(target, tween(durationMillis = 45))
        }
    }
    Box(modifier.offset { IntOffset(shakeOffset.value.roundToInt(), 0) }) {
        content()
    }
}

/**
 * The visible lockout state (Phase 2.5): lock badge, the remaining
 * time as a large mm:ss countdown (polite live region so TalkBack
 * announces changes) and an explicit statement that input is
 * paused — a lockout is never a silent refusal. Callers drive
 * [remainingSeconds] from the persisted deadline and tick it.
 */
@Composable
fun LockoutCountdown(
    remainingSeconds: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Spacer(Modifier.height(NotaSpacing.sm))
        Text("Too many attempts", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(NotaSpacing.xs))
        Text(
            formatLockoutCountdown(remainingSeconds),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            "Unlock pauses until the timer ends — fingerprint too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------
// Pattern grid: 3x3, Canvas-drawn dots + connecting lines, pointer
// hit-testing. A point can be selected at most once; lines clear on
// release and the completed selection is reported via onComplete.
// ---------------------------------------------------------------------
@Composable
fun PatternGridInput(
    onComplete: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: Boolean = false,
    resetKey: Int = 0,
) {
    val selected = remember(resetKey) { mutableStateListOf<Int>() }
    var dragPosition by remember(resetKey) { mutableStateOf<Offset?>(null) }
    val lineColor = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier
            .size(264.dp)
            .pointerInput(resetKey, enabled) {
                if (!enabled) return@pointerInput
                val cellW = size.width / 3f
                val cellH = size.height / 3f
                fun center(index: Int) = Offset(
                    (index % 3 + 0.5f) * cellW,
                    (index / 3 + 0.5f) * cellH,
                )
                fun hit(position: Offset): Int? {
                    val radius = minOf(cellW, cellH) * 0.42f
                    for (i in 0..8) {
                        val c = center(i)
                        if (hypot((position.x - c.x).toDouble(), (position.y - c.y).toDouble()) <= radius) {
                            return i
                        }
                    }
                    return null
                }
                detectDragGestures(
                    onDragStart = { offset ->
                        dragPosition = offset
                        hit(offset)?.let { if (it !in selected) selected.add(it) }
                    },
                    onDrag = { change, _ ->
                        dragPosition = change.position
                        hit(change.position)?.let { if (it !in selected) selected.add(it) }
                    },
                    onDragEnd = {
                        if (selected.isNotEmpty()) onComplete(selected.toList())
                        selected.clear()
                        dragPosition = null
                    },
                    onDragCancel = {
                        selected.clear()
                        dragPosition = null
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cellW = size.width / 3f
            val cellH = size.height / 3f
            fun center(index: Int) = Offset(
                (index % 3 + 0.5f) * cellW,
                (index / 3 + 0.5f) * cellH,
            )
            for (i in 0 until selected.size - 1) {
                drawLine(lineColor, center(selected[i]), center(selected[i + 1]), strokeWidth = 6f)
            }
            val last = selected.lastOrNull()
            val drag = dragPosition
            if (last != null && drag != null) {
                drawLine(lineColor, center(last), drag, strokeWidth = 6f)
            }
            for (i in 0..8) {
                val isSelected = i in selected
                val c = center(i)
                if (isSelected) {
                    // Soft halo so the active dot reads as a target,
                    // not a speck (Phase 2.5: radii scale with the
                    // cell instead of fixed px).
                    drawCircle(
                        color = lineColor.copy(alpha = 0.18f),
                        radius = minOf(cellW, cellH) * 0.20f,
                        center = c,
                    )
                }
                drawCircle(
                    color = if (isSelected) lineColor else idleColor,
                    radius = minOf(cellW, cellH) * if (isSelected) 0.10f else 0.055f,
                    center = c,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// PIN pad: custom numeric pad, dot indicators, 4-6 digits, backspace.
// Auto-submits at 6 digits; otherwise the submit button (enabled at 4+)
// confirms — the stored length is not known at unlock time, so an
// explicit confirm is the only honest way to accept 4- and 5-digit PINs.
// ---------------------------------------------------------------------
@Composable
fun PinPadInput(
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    submitLabel: String = "Continue",
    resetKey: Int = 0,
    error: Boolean = false,
) {
    var entered by remember(resetKey) { mutableStateOf("") }
    LaunchedEffect(entered) {
        if (entered.length == PIN_MAX_LENGTH) {
            onSubmit(entered)
            entered = ""
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm),
            modifier = Modifier.height(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(entered.length) {
                Box(
                    Modifier.size(12.dp).clip(CircleShape)
                        .background(
                            if (error) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        ),
                )
            }
        }
        Spacer(Modifier.height(NotaSpacing.xs))
        Text(
            "$PIN_MIN_LENGTH–$PIN_MAX_LENGTH digits",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NotaSpacing.sm))
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "⌫"),
        )
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                row.forEach { label ->
                    Box(
                        Modifier.size(72.dp).clip(CircleShape)
                            .background(
                                if (label.isEmpty()) {
                                    Color.Transparent
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            )
                            .clickable(enabled = enabled && label.isNotEmpty()) {
                                when (label) {
                                    "⌫" -> entered = entered.dropLast(1)
                                    else -> if (entered.length < PIN_MAX_LENGTH) entered += label
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (label == "⌫") {
                            Icon(Icons.Filled.Backspace, contentDescription = "Backspace")
                        } else if (label.isNotEmpty()) {
                            Text(label, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(NotaSpacing.sm))
        Button(
            onClick = {
                onSubmit(entered)
                entered = ""
            },
            enabled = enabled && entered.length >= PIN_MIN_LENGTH,
        ) { Text(submitLabel) }
    }
}

// ---------------------------------------------------------------------
// Password field with visibility toggle.
// ---------------------------------------------------------------------
@Composable
fun PasswordInput(
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    submitLabel: String = "Continue",
    resetKey: Int = 0,
    label: String = "Password",
    error: Boolean = false,
) {
    var text by remember(resetKey) { mutableStateOf("") }
    var visible by remember(resetKey) { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            isError = error,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "Hide password" else "Show password",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(NotaSpacing.sm))
        Button(
            onClick = {
                onSubmit(text)
                text = ""
            },
            enabled = enabled && text.isNotEmpty(),
        ) { Text(submitLabel) }
    }
}

/** Icon for the method switcher — one glyph per credential kind. */
private fun methodIcon(method: AppLockMethod): ImageVector = when (method) {
    AppLockMethod.PASSWORD -> Icons.Filled.Password
    AppLockMethod.PIN -> Icons.Filled.Dialpad
    AppLockMethod.PATTERN -> Icons.Filled.Gesture
}

// ---------------------------------------------------------------------
// Unlock/verify entry form: method switcher + the selected method's
// input. Reports CANONICAL secrets (1.x forms) to the caller.
// ---------------------------------------------------------------------
@Composable
fun CredentialEntryForm(
    methods: Set<AppLockMethod>,
    selectedMethod: AppLockMethod,
    onMethodSelected: (AppLockMethod) -> Unit,
    onSubmit: (AppLockMethod, String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    submitLabel: String = "Unlock",
    resetKey: Int = 0,
    patternError: Boolean = false,
    onPatternTooShort: () -> Unit = {},
    /** Phase 2.5: tints the active input (password outline, PIN dots,
     *  pattern line) when the last attempt was wrong. Defaults to the
     *  pattern flag so existing callers keep their behaviour. */
    error: Boolean = patternError,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (methods.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                AppLockMethod.entries.filter { it in methods }.forEach { method ->
                    FilterChip(
                        selected = method == selectedMethod,
                        onClick = { onMethodSelected(method) },
                        label = { Text(method.label) },
                        leadingIcon = {
                            Icon(
                                methodIcon(method),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        enabled = enabled,
                    )
                }
            }
            Spacer(Modifier.height(NotaSpacing.md))
        }
        key(selectedMethod, resetKey) {
            when (selectedMethod) {
                AppLockMethod.PASSWORD -> PasswordInput(
                    onSubmit = { onSubmit(AppLockMethod.PASSWORD, AppLockCrypto.canonicalPassword(it)) },
                    enabled = enabled,
                    submitLabel = submitLabel,
                    resetKey = resetKey,
                    error = error,
                )
                AppLockMethod.PIN -> PinPadInput(
                    onSubmit = { onSubmit(AppLockMethod.PIN, AppLockCrypto.canonicalPin(it)) },
                    enabled = enabled,
                    submitLabel = submitLabel,
                    resetKey = resetKey,
                    error = error,
                )
                AppLockMethod.PATTERN -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PatternGridInput(
                        onComplete = { points ->
                            if (points.size >= PATTERN_MIN_POINTS) {
                                onSubmit(AppLockMethod.PATTERN, AppLockCrypto.canonicalPattern(points))
                            } else {
                                onPatternTooShort()
                            }
                        },
                        enabled = enabled,
                        error = patternError || error,
                        resetKey = resetKey,
                    )
                    Text(
                        "Connect at least $PATTERN_MIN_POINTS dots",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Setup/change form: enter → confirm, per method. Nothing is persisted
// until the confirm step matches, so abandoning the flow mid-way can
// never leave App Lock half-enabled. Reports the CANONICAL secret.
//
// Phase 2.9 (J1): the enter/confirm decisions live in
// [CredentialSetupFlow] (unit-tested) — a wrong confirmation now keeps
// the first entry and asks only for the confirmation again, instead
// of restarting the whole flow. And once a match completes, [busy]
// (owned by the caller, who runs the save derivation) disables every
// input and shows progress: in 0.3.4 the save's derivation window had
// NO feedback at all — the inputs had already cleared themselves on
// submit, so a correct setup looked exactly like a silent rejection
// and invited re-entries that stacked duplicate derivations.
// ---------------------------------------------------------------------
@Composable
fun CredentialSetupForm(
    method: AppLockMethod,
    onComplete: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val flow = remember(method) { CredentialSetupFlow(method) }
    var step by remember(method) { mutableStateOf(flow.step) }
    var error by remember(method) { mutableStateOf<String?>(null) }
    var resetKey by remember(method) { mutableStateOf(0) }
    // Completion fires exactly once: after a match the caller needs a
    // derivation's worth of time to save, and a second submit in that
    // window must be impossible, not merely ignored downstream.
    var completionFired by remember(method) { mutableStateOf(false) }
    val inputEnabled = !busy && !completionFired
    // Phase 2.5: the shared shake, fired whenever a fresh error lands
    // (mismatch / too short), matching the lock screen's feedback.
    var shakeKey by remember(method) { mutableIntStateOf(0) }
    LaunchedEffect(error) {
        if (error != null) shakeKey++
    }

    fun handleOutcome(outcome: CredentialSetupFlow.Outcome) {
        when (outcome) {
            is CredentialSetupFlow.Outcome.AdvancedToConfirm -> {
                error = null
                step = flow.step
                resetKey++
            }
            is CredentialSetupFlow.Outcome.Completed -> {
                if (!completionFired) {
                    completionFired = true
                    error = null
                    step = flow.step
                    onComplete(outcome.canonicalSecret)
                }
            }
            is CredentialSetupFlow.Outcome.Rejected -> {
                // The flow kept its step (and, on CONFIRM, the first
                // entry): clear only the current input and SAY why.
                error = outcome.message
                resetKey++
            }
        }
    }

    Column(
        modifier.padding(NotaSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when (method) {
                AppLockMethod.PASSWORD -> "Set a password"
                AppLockMethod.PIN -> "Set a PIN"
                AppLockMethod.PATTERN -> "Draw a pattern"
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(NotaSpacing.sm))
        ShakeBox(shakeKey = shakeKey) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (method) {
                    AppLockMethod.PASSWORD -> {
                        if (step == CredentialSetupFlow.Step.ENTER_FIRST) {
                            Text(
                                "At least $PASSWORD_MIN_LENGTH characters.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(NotaSpacing.sm))
                            PasswordInput(
                                onSubmit = { value -> handleOutcome(flow.submitPassword(value)) },
                                submitLabel = "Continue",
                                resetKey = resetKey,
                                label = "New password",
                                error = error != null,
                                enabled = inputEnabled,
                            )
                        } else {
                            Text(
                                "Enter the password again to confirm.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(NotaSpacing.sm))
                            PasswordInput(
                                onSubmit = { value -> handleOutcome(flow.submitPassword(value)) },
                                submitLabel = "Save password",
                                resetKey = resetKey,
                                label = "Confirm password",
                                error = error != null,
                                enabled = inputEnabled,
                            )
                        }
                    }
                    AppLockMethod.PIN -> {
                        Text(
                            if (step == CredentialSetupFlow.Step.ENTER_FIRST) {
                                "Choose a $PIN_MIN_LENGTH–$PIN_MAX_LENGTH digit PIN."
                            } else {
                                "Enter the PIN again to confirm."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(NotaSpacing.sm))
                        PinPadInput(
                            onSubmit = { value -> handleOutcome(flow.submitPin(value)) },
                            submitLabel = if (step == CredentialSetupFlow.Step.ENTER_FIRST) {
                                "Continue"
                            } else {
                                "Save PIN"
                            },
                            resetKey = resetKey,
                            error = error != null,
                            enabled = inputEnabled,
                        )
                    }
                    AppLockMethod.PATTERN -> {
                        Text(
                            if (step == CredentialSetupFlow.Step.ENTER_FIRST) {
                                "Connect at least $PATTERN_MIN_POINTS dots."
                            } else {
                                "Draw the pattern again to confirm."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(NotaSpacing.sm))
                        PatternGridInput(
                            onComplete = { points -> handleOutcome(flow.submitPattern(points)) },
                            error = error != null,
                            resetKey = resetKey,
                            enabled = inputEnabled,
                        )
                    }
                }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(NotaSpacing.sm))
            Text(
                error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (busy) {
            // Phase 2.9 (J1): the save IS happening — say so, with a
            // spinner, for exactly as long as the derivation takes.
            Spacer(Modifier.height(NotaSpacing.sm))
            CircularProgressIndicator()
            Spacer(Modifier.height(NotaSpacing.xs))
            Text(
                "Saving…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(NotaSpacing.sm))
        TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
    }
}
