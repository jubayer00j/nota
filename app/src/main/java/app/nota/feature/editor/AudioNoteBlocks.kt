package app.nota.feature.editor

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.nota.data.db.AttachmentEntity
import app.nota.data.html.AudioSegment
import app.nota.ui.theme.NotaSpacing
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PR #51 (audio notes): the in-note voice recording — recorder
 * sheet, player row and the one-active-playback controller, plus
 * the pure seams both are built on ([RecordingTimer],
 * [formatAudioDuration]). Noma 1.x never stored audio (voice was
 * transcript-only); this is the block that makes a recording a
 * first-class note citizen: it rides an attachment like
 * [app.nota.data.html.FileSegment], renders in read AND edit mode,
 * and deletes through the same block rules as every other block.
 */

/**
 * The `m:ss` label every audio surface shows (player total,
 * player position, recorder elapsed). Minutes are unbounded and
 * unpadded; seconds are zero-padded; a negative input reads 0:00.
 */
fun formatAudioDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/** The display name minted at Stop: "Recording · <date, time>". */
fun recordingDisplayName(now: Date = Date()): String =
    "Recording · " + SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(now)

/**
 * The recorder's elapsed-time bookkeeping, pure and clock-injected
 * so the pause/resume arithmetic is testable without a microphone:
 * elapsed accrues only while RECORDING; pausing banks the live
 * segment; resuming starts a new one. [stop] reports the final
 * elapsed (the duration the saved [AudioSegment] carries — "the
 * recorder's own elapsed time") and resets to IDLE.
 */
class RecordingTimer {
    enum class State { IDLE, RECORDING, PAUSED }

    var state: State = State.IDLE
        private set

    private var bankedMs = 0L
    private var segmentStartMs: Long? = null

    fun start(nowMs: Long) {
        if (state != State.IDLE) return
        bankedMs = 0
        segmentStartMs = nowMs
        state = State.RECORDING
    }

    fun pause(nowMs: Long) {
        if (state != State.RECORDING) return
        bankedMs += nowMs - (segmentStartMs ?: nowMs)
        segmentStartMs = null
        state = State.PAUSED
    }

    fun resume(nowMs: Long) {
        if (state != State.PAUSED) return
        segmentStartMs = nowMs
        state = State.RECORDING
    }

    fun elapsedAt(nowMs: Long): Long =
        bankedMs + if (state == State.RECORDING) {
            nowMs - (segmentStartMs ?: nowMs)
        } else {
            0
        }

    /** Final elapsed; the timer returns to IDLE, fully reset. */
    fun stop(nowMs: Long): Long {
        val elapsed = elapsedAt(nowMs)
        bankedMs = 0
        segmentStartMs = null
        state = State.IDLE
        return elapsed
    }

    /** Abandon without a result (Cancel): back to IDLE, reset. */
    fun cancel() {
        bankedMs = 0
        segmentStartMs = null
        state = State.IDLE
    }
}

/**
 * One active playback per note surface (PR #51): starting another
 * block's recording releases the current player; completion resets
 * to the start; the owning row's disposal stops its own playback
 * (see [AudioBlock]) and the screen's disposal [release]s the
 * whole controller. All state is Compose snapshot state — rows
 * read it directly and recompose on change. MediaPlayer callbacks
 * land on the main thread (the player is created there), as does
 * the progress ticker.
 */
class AudioPlaybackController {
    var activeId by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0)
        private set

    /** The loaded player's real duration; 0 until prepared. Rows
     *  fall back to the segment's recorded duration until then. */
    var durationMs by mutableStateOf(0)
        private set

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun toggle(id: String, file: File) {
        if (player != null && activeId == id) {
            if (isPlaying) pause() else resume()
            return
        }
        start(id, file)
    }

    private fun start(id: String, file: File) {
        releasePlayer()
        activeId = id
        positionMs = 0
        durationMs = 0
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(file.path)
            mp.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                durationMs = prepared.duration.coerceAtLeast(0)
                prepared.start()
                isPlaying = true
                startTicker()
            }
            mp.setOnCompletionListener { completed ->
                if (player !== completed) return@setOnCompletionListener
                stopTicker()
                isPlaying = false
                runCatching { completed.seekTo(0) }
                positionMs = 0
            }
            mp.setOnErrorListener { errored, _, _ ->
                if (player === errored) reset()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            reset()
        }
    }

    fun pause() {
        val mp = player ?: return
        if (!isPlaying) return
        runCatching { mp.pause() }
        isPlaying = false
        stopTicker()
        positionMs = runCatching { mp.currentPosition }.getOrDefault(positionMs)
    }

    private fun resume() {
        val mp = player ?: return
        runCatching { mp.start() }
        isPlaying = true
        startTicker()
    }

    fun seekTo(ms: Int) {
        val mp = player ?: return
        val target = ms.coerceIn(0, durationMs.coerceAtLeast(0))
        runCatching { mp.seekTo(target) }
        positionMs = target
    }

    /** Stops playback when the row owning [id] leaves composition. */
    fun stopIfActive(id: String) {
        if (activeId == id) reset()
    }

    /** Terminal: the owning surface (note screen, preview) is gone. */
    fun release() {
        reset()
        scope.cancel()
    }

    private fun reset() {
        releasePlayer()
        activeId = null
        isPlaying = false
        positionMs = 0
        durationMs = 0
    }

    private fun releasePlayer() {
        stopTicker()
        player?.let { runCatching { it.release() } }
        player = null
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (true) {
                delay(250)
                val mp = player ?: break
                if (!isPlaying) break
                positionMs = runCatching { mp.currentPosition }.getOrDefault(positionMs)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }
}

/**
 * The audio block row, shared by the read view and the segment
 * editor (PR #51): name and duration resolve like the file block's
 * (the attachments table is the source of truth, the segment's
 * snapshot is the fallback, a missing row is called out instead of
 * rendering a dead block). Playback is live in BOTH modes —
 * listening never requires entering edit mode; [onRemove] is
 * non-null only in edit mode, where the row wears the standard
 * [BlockShell] remove affordance.
 */
@Composable
fun AudioBlock(
    audio: AudioSegment,
    attachments: List<AttachmentEntity>,
    fileFor: (AttachmentEntity) -> File,
    playback: AudioPlaybackController,
    onRemove: (() -> Unit)? = null,
) {
    val attachment = attachments.firstOrNull { it.id == audio.attachmentId }
    val isActive = playback.activeId == audio.attachmentId
    val totalMs = if (isActive && playback.durationMs > 0) {
        playback.durationMs.toLong()
    } else {
        audio.durationMillis
    }

    // The row leaving composition stops its own playback (a
    // scrolled-off or removed block must never keep sounding);
    // the controller's screen-level release covers the rest.
    DisposableEffect(audio.attachmentId) {
        onDispose { playback.stopIfActive(audio.attachmentId) }
    }

    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (attachment != null) {
                IconButton(
                    onClick = { playback.toggle(audio.attachmentId, fileFor(attachment)) },
                ) {
                    Icon(
                        if (isActive && playback.isPlaying) {
                            Icons.Filled.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        contentDescription = if (isActive && playback.isPlaying) {
                            "Pause recording"
                        } else {
                            "Play recording"
                        },
                    )
                }
            } else {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.padding(NotaSpacing.sm),
                )
            }
            Spacer(Modifier.width(NotaSpacing.xs))
            Column(Modifier.weight(1f)) {
                Text(
                    attachment?.fileName ?: audio.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                when {
                    attachment == null -> Text(
                        "Recording not found on this device",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    isActive -> Text(
                        "${formatAudioDuration(playback.positionMs.toLong())} / " +
                            formatAudioDuration(totalMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(
                        formatAudioDuration(totalMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isActive && totalMs > 0) {
                    Slider(
                        value = playback.positionMs.toFloat() / totalMs.toFloat(),
                        onValueChange = { fraction ->
                            playback.seekTo((fraction * totalMs).toInt())
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
    if (onRemove != null) {
        BlockShell(onRemove = onRemove) { content() }
    } else {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(NotaSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content()
            }
        }
    }
}

/**
 * The recorder (PR #51): a modal sheet opened from the editor's
 * insert menu ("Record audio…"). The take records into a temp file
 * in cacheDir and exists only there until Stop — Stop hands the
 * file to [onSave] (the screen imports it into attachment storage
 * and inserts the [AudioSegment] at the caret, the insert funnel
 * every block uses); Cancel, a swipe-dismiss, or the sheet leaving
 * composition for any other reason discards the take (the
 * DisposableEffect below is the guarantee: a recorder can never
 * run behind the app). RECORD_AUDIO is requested in context when
 * the sheet opens if not already granted — denial shows the honest
 * panel and records nothing.
 *
 * Format: AAC-LC in .m4a, mono, 96 kbps / 44.1 kHz (~0.7 MB/min).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioRecorderSheet(
    onDismiss: () -> Unit,
    onSave: (file: File, displayName: String, durationMillis: Long) -> Unit,
) {
    val context = LocalContext.current
    val timer = remember { RecordingTimer() }
    val tempFile = remember {
        File(
            File(context.cacheDir, "nota-recording"),
            "take-${UUID.randomUUID()}.m4a",
        )
    }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    // RecordingTimer is deliberately NOT snapshot state (it is a
    // pure, clock-injected class); the sheet mirrors its phase
    // here so the pause/resume UI and the ticker effect have a
    // Compose-observable to key on. Every transition below writes
    // both together.
    var phase by remember { mutableStateOf(RecordingTimer.State.IDLE) }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var tick by remember { mutableLongStateOf(0L) }

    fun releaseRecorder() {
        mediaRecorder?.let { recorder ->
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
        }
        mediaRecorder = null
    }

    fun discardTake() {
        mediaRecorder?.let { recorder ->
            if (timer.state != RecordingTimer.State.IDLE) {
                runCatching { recorder.stop() }
            }
        }
        releaseRecorder()
        timer.cancel()
        phase = RecordingTimer.State.IDLE
        tempFile.delete()
    }

    fun startRecording() {
        tempFile.parentFile?.mkdirs()
        tempFile.delete()
        val recorder = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
        } catch (e: Exception) {
            error = "Couldn't start recording. The microphone may be in use by another app."
            return
        }
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(96_000)
            recorder.setOutputFile(tempFile.absolutePath)
            recorder.prepare()
            recorder.start()
        } catch (e: Exception) {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            tempFile.delete()
            error = "Couldn't start recording. The microphone may be in use by another app."
            return
        }
        mediaRecorder = recorder
        timer.start(System.currentTimeMillis())
        phase = RecordingTimer.State.RECORDING
        tick = System.currentTimeMillis()
        error = null
    }

    fun stopAndSave() {
        val recorder = mediaRecorder ?: return
        val durationMs = timer.stop(System.currentTimeMillis())
        phase = RecordingTimer.State.IDLE
        val stopped = runCatching { recorder.stop() }.isSuccess
        releaseRecorder()
        if (!stopped || !tempFile.exists() || tempFile.length() == 0L) {
            tempFile.delete()
            error = "That recording didn't save — nothing was captured."
            return
        }
        saving = true
        onSave(tempFile, recordingDisplayName(), durationMs)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionGranted = true
        } else {
            denied = true
        }
    }
    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // Recording starts on its own once permission is in hand (the
    // voice sheet's pattern); the error guard keeps a failed start
    // from retry-looping — Try again is the deliberate retry.
    LaunchedEffect(permissionGranted) {
        if (permissionGranted && mediaRecorder == null && !saving && error == null) {
            startRecording()
        }
    }
    // Elapsed ticker while recording (paused time stands still:
    // RecordingTimer simply stops accruing).
    LaunchedEffect(phase) {
        while (phase == RecordingTimer.State.RECORDING) {
            tick = System.currentTimeMillis()
            delay(200)
        }
    }
    // The discard guarantee: leaving the sheet by ANY path other
    // than a completed Stop (which nulled the recorder and stopped
    // the timer first) throws the take away.
    DisposableEffect(Unit) {
        onDispose {
            if (mediaRecorder != null || tempFile.exists()) discardTake()
        }
    }

    val elapsedMs = timer.elapsedAt(
        if (phase == RecordingTimer.State.RECORDING) {
            tick
        } else {
            System.currentTimeMillis()
        },
    )

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = NotaSpacing.md)
                .padding(bottom = NotaSpacing.lg),
        ) {
            Text("Record audio", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(NotaSpacing.sm))
            when {
                denied -> {
                    Text(
                        "Nota needs microphone permission to record audio. " +
                            "Nothing was recorded.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(NotaSpacing.md))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                }
                error != null -> {
                    Text(
                        error!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(NotaSpacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                        TextButton(onClick = onDismiss) { Text("Close") }
                        OutlinedButton(onClick = { startRecording() }) {
                            Text("Try again")
                        }
                    }
                }
                !permissionGranted -> {
                    Text(
                        "Preparing the microphone…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(NotaSpacing.md))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                }
                else -> {
                    Text(
                        formatAudioDuration(elapsedMs),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        if (phase == RecordingTimer.State.PAUSED) {
                            "Paused"
                        } else {
                            "Recording…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(NotaSpacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                        TextButton(onClick = onDismiss, enabled = !saving) {
                            Text("Cancel")
                        }
                        if (phase == RecordingTimer.State.PAUSED) {
                            OutlinedButton(
                                enabled = !saving,
                                onClick = {
                                    runCatching { mediaRecorder?.resume() }
                                    timer.resume(System.currentTimeMillis())
                                    phase = RecordingTimer.State.RECORDING
                                },
                            ) { Text("Resume") }
                        } else {
                            OutlinedButton(
                                enabled = !saving,
                                onClick = {
                                    runCatching { mediaRecorder?.pause() }
                                    timer.pause(System.currentTimeMillis())
                                    phase = RecordingTimer.State.PAUSED
                                },
                            ) { Text("Pause") }
                        }
                        Button(onClick = { stopAndSave() }, enabled = !saving) {
                            Text(if (saving) "Saving…" else "Stop & insert")
                        }
                    }
                }
            }
        }
    }
}


