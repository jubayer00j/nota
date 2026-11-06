package app.nota.feature.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.nota.data.text.PlainTextCodec
import app.nota.ui.theme.NotaSpacing
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Phase 5.4: the voice quick-capture sheet — a port of Noma 1.x's
 * voice-capture-sheet.tsx. It is where the voice_note shortcut and
 * the voice QS tile land (through the 5.2 capture seam: the staged
 * record is consumed by the shell, which presents this sheet —
 * nothing is created or shown behind App Lock).
 *
 * The flow is 1.x's, choice for choice:
 *  1. Probe first. Unavailable → an honest panel (a disabled speech
 *     service is NAMED) with Close / Type instead — never a dead
 *     mic. The keyboard's own voice typing is named as the
 *     fallback, because it keeps working on devices whose system
 *     recogniser is dead.
 *  2. Available → listening starts on its own. Live transcript:
 *     committed paragraphs, the in-progress utterance in italic.
 *  3. Done stops and saves WHEN THE SESSION SETTLES (finals that
 *     land during "stopping" count; an error settle saves whatever
 *     committed). Nothing is written before Done — Cancel discards
 *     everything, and closing the sheet cancels the session, so a
 *     recogniser can never run behind the app.
 *  4. An empty session creates nothing: a notice offers Try again
 *     or Type instead (a blank note).
 *
 * The note lands exactly as 1.x's does: title = first spoken
 * segment, body = one paragraph per segment, in the current
 * account's library (the caller creates it via NoteRepository).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCaptureSheet(
    onDismiss: () -> Unit,
    onSave: (title: String, contentHtml: String) -> Unit,
    onTypeInstead: () -> Unit,
) {
    val context = LocalContext.current
    val engine = remember { AndroidSpeechEngine(context) }
    rememberSpeechPermissionRequester(engine)
    val scope = rememberCoroutineScope()
    val controller = remember(engine) { SpeechSessionController(engine, scope) }
    val snapshot by controller.snapshot.collectAsState()

    var availability by remember { mutableStateOf<VoiceAvailability?>(null) }
    var started by remember { mutableStateOf(false) }
    var savePending by remember { mutableStateOf(false) }
    var emptyNotice by remember { mutableStateOf(false) }
    var startedAtMs by remember { mutableStateOf<Long?>(null) }
    var elapsedLabel by remember { mutableStateOf("0:00") }

    fun attemptSave() {
        val segments = controller.snapshot.value.committed
        val title = SpeechText.voiceNoteTitle(segments)
        if (title.isEmpty()) {
            emptyNotice = true
            return
        }
        onSave(
            title,
            PlainTextCodec.textToHtml(SpeechText.segmentsToParagraphs(segments)),
        )
    }

    // Probe on open; auto-start listening once availability is known.
    LaunchedEffect(controller) {
        val probe = runCatching { engine.probe() }.getOrNull()
        val mapped = VoiceProbeUi.fromProbe(probe)
        availability = mapped
        if (mapped is VoiceAvailability.Available && !started) {
            started = true
            controller.start(Locale.getDefault().toLanguageTag())
        }
    }

    // Elapsed timer while listening.
    LaunchedEffect(snapshot.state) {
        if (snapshot.state == SpeechSessionState.LISTENING) {
            if (startedAtMs == null) startedAtMs = System.currentTimeMillis()
            while (true) {
                val startedAt = startedAtMs ?: System.currentTimeMillis()
                val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                elapsedLabel = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                delay(500)
            }
        } else if (snapshot.state == SpeechSessionState.IDLE ||
            snapshot.state == SpeechSessionState.ERROR
        ) {
            startedAtMs = null
            elapsedLabel = "0:00"
        }
    }

    // Done pressed while listening: save once the session settles —
    // idle OR error (finals that land during "stopping" count, and
    // an error settle keeps whatever committed). 1.x's rule.
    LaunchedEffect(snapshot.state, savePending) {
        if (savePending &&
            (snapshot.state == SpeechSessionState.IDLE ||
                snapshot.state == SpeechSessionState.ERROR)
        ) {
            savePending = false
            attemptSave()
        }
    }

    DisposableEffect(controller) {
        onDispose { controller.cancel() }
    }

    val listening = snapshot.state == SpeechSessionState.LISTENING
    val stopping = snapshot.state == SpeechSessionState.STOPPING
    val requesting = snapshot.state == SpeechSessionState.REQUESTING_PERMISSION
    val errorKind = if (snapshot.state == SpeechSessionState.ERROR) {
        snapshot.errorKind
    } else {
        null
    }
    val unavailable = availability as? VoiceAvailability.Unavailable
    val advisoryApp = (availability as? VoiceAvailability.Available)?.disabledServiceApp
    val onDevice = (availability as? VoiceAvailability.Available)?.onDevice

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = NotaSpacing.md)
                .padding(bottom = NotaSpacing.lg),
        ) {
            Text("Voice note", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(NotaSpacing.sm))

            when {
                availability == null -> {
                    Text(
                        "Preparing the microphone…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(NotaSpacing.md))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                }
                unavailable != null -> {
                    Text(
                        VoiceProbeUi.unavailableMessage(unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Text(
                        VoiceProbeUi.KEYBOARD_HINT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(NotaSpacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                        TextButton(onClick = onDismiss) { Text("Close") }
                        OutlinedButton(onClick = onTypeInstead) { Text("Type instead") }
                    }
                }
                else -> {
                    val status = when {
                        requesting -> "Preparing the microphone…"
                        listening -> "Listening… $elapsedLabel"
                        stopping -> "Finishing…"
                        errorKind != null -> SpeechErrorLines.line(errorKind)
                        else -> "Voice capture"
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (errorKind != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp, max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        val committedText =
                            SpeechText.segmentsToParagraphs(snapshot.committed)
                        if (committedText.isEmpty() && snapshot.partial.isEmpty()) {
                            Text(
                                "Say something — your note appears here as you speak.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            if (committedText.isNotEmpty()) {
                                Text(committedText, style = MaterialTheme.typography.bodyLarge)
                            }
                            if (snapshot.partial.isNotEmpty()) {
                                Text(
                                    snapshot.partial,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontStyle = FontStyle.Italic,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (emptyNotice) {
                        Spacer(Modifier.height(NotaSpacing.sm))
                        Text(
                            "Nothing was captured — the note would be empty.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Text(
                        when {
                            onDevice == true ->
                                "Recognition runs on this device. Nota never stores audio."
                            advisoryApp != null ->
                                "$advisoryApp is disabled, so dictation runs on-device here. " +
                                    "Nota never stores audio."
                            else ->
                                "Speech is recognised by your device's speech service, " +
                                    "which may work online. Nota never stores audio."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(NotaSpacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        if (emptyNotice) {
                            OutlinedButton(
                                onClick = {
                                    emptyNotice = false
                                    controller.reset()
                                    controller.start(Locale.getDefault().toLanguageTag())
                                },
                            ) { Text("Try again") }
                            OutlinedButton(onClick = onTypeInstead) { Text("Type instead") }
                        } else {
                            OutlinedButton(
                                enabled = !stopping && !requesting,
                                onClick = {
                                    when (snapshot.state) {
                                        SpeechSessionState.LISTENING -> {
                                            savePending = true
                                            controller.stop()
                                        }
                                        SpeechSessionState.STOPPING -> Unit
                                        else -> attemptSave()
                                    }
                                },
                            ) { Text(if (stopping) "One moment…" else "Done") }
                            if (errorKind != null) {
                                OutlinedButton(onClick = onTypeInstead) {
                                    Text("Type instead")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
