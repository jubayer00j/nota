package app.nota.feature.lock

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.nota.data.security.AppLockCrypto
import app.nota.data.security.AppLockMethod
import app.nota.ui.theme.NotaSpacing
import kotlin.math.hypot

/**
 * Shared credential components (Phase 2a) — 1.x has ONE shared
 * credential prompt used by the lock screen, re-auth and setup; these
 * composables are that shared layer for Nota. The lock screen is
 * unlock-only; setup/change lives in Settings and reuses the same
 * building blocks through [CredentialSetupForm].
 */

private const val PATTERN_MIN_POINTS = 4
private const val PIN_MIN_LENGTH = 4
private const val PIN_MAX_LENGTH = 6
private const val PASSWORD_MIN_LENGTH = 8

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
                drawCircle(
                    color = if (isSelected) lineColor else idleColor,
                    radius = if (isSelected) 15f else 9f,
                    center = center(i),
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
        ) {
            repeat(entered.length) {
                Box(
                    Modifier.size(12.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
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
                        Modifier.size(68.dp).clip(CircleShape).clickable(enabled = enabled) {
                            when (label) {
                                "" -> Unit
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
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (methods.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                AppLockMethod.entries.filter { it in methods }.forEach { method ->
                    FilterChip(
                        selected = method == selectedMethod,
                        onClick = { onMethodSelected(method) },
                        label = { Text(method.label) },
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
                )
                AppLockMethod.PIN -> PinPadInput(
                    onSubmit = { onSubmit(AppLockMethod.PIN, AppLockCrypto.canonicalPin(it)) },
                    enabled = enabled,
                    submitLabel = submitLabel,
                    resetKey = resetKey,
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
                        error = patternError,
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
// ---------------------------------------------------------------------
@Composable
fun CredentialSetupForm(
    method: AppLockMethod,
    onComplete: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var error by remember(method) { mutableStateOf<String?>(null) }
    var resetKey by remember(method) { mutableStateOf(0) }
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
        when (method) {
            AppLockMethod.PASSWORD -> {
                var first by remember(method) { mutableStateOf<String?>(null) }
                if (first == null) {
                    Text(
                        "At least $PASSWORD_MIN_LENGTH characters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(NotaSpacing.sm))
                    PasswordInput(
                        onSubmit = { value ->
                            if (value.length < PASSWORD_MIN_LENGTH) {
                                error = "Password must be at least $PASSWORD_MIN_LENGTH characters."
                            } else {
                                error = null
                                first = value
                            }
                        },
                        submitLabel = "Continue",
                        resetKey = resetKey,
                        label = "New password",
                    )
                } else {
                    Text(
                        "Enter the password again to confirm.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(NotaSpacing.sm))
                    PasswordInput(
                        onSubmit = { value ->
                            if (value == first) {
                                onComplete(AppLockCrypto.canonicalPassword(value))
                            } else {
                                error = "Passwords do not match. Start again."
                                first = null
                                resetKey++
                            }
                        },
                        submitLabel = "Save password",
                        resetKey = resetKey,
                        label = "Confirm password",
                    )
                }
            }
            AppLockMethod.PIN -> {
                var first by remember(method) { mutableStateOf<String?>(null) }
                Text(
                    if (first == null) {
                        "Choose a $PIN_MIN_LENGTH–$PIN_MAX_LENGTH digit PIN."
                    } else {
                        "Enter the PIN again to confirm."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(NotaSpacing.sm))
                PinPadInput(
                    onSubmit = { value ->
                        val firstEntry = first
                        if (firstEntry == null) {
                            error = null
                            first = value
                            resetKey++
                        } else if (value == firstEntry) {
                            onComplete(AppLockCrypto.canonicalPin(value))
                        } else {
                            error = "PINs do not match. Start again."
                            first = null
                            resetKey++
                        }
                    },
                    submitLabel = if (first == null) "Continue" else "Save PIN",
                    resetKey = resetKey,
                )
            }
            AppLockMethod.PATTERN -> {
                var first by remember(method) { mutableStateOf<List<Int>?>(null) }
                Text(
                    if (first == null) {
                        "Connect at least $PATTERN_MIN_POINTS dots."
                    } else {
                        "Draw the pattern again to confirm."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(NotaSpacing.sm))
                PatternGridInput(
                    onComplete = { points ->
                        val firstPoints = first
                        when {
                            points.size < PATTERN_MIN_POINTS -> {
                                error = "Pattern must connect at least $PATTERN_MIN_POINTS dots."
                                resetKey++
                            }
                            firstPoints == null -> {
                                error = null
                                first = points
                                resetKey++
                            }
                            points == firstPoints ->
                                onComplete(AppLockCrypto.canonicalPattern(points))
                            else -> {
                                error = "Patterns do not match. Start again."
                                first = null
                                resetKey++
                            }
                        }
                    },
                    error = error != null,
                    resetKey = resetKey,
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(NotaSpacing.sm))
            Text(
                error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(NotaSpacing.sm))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}
