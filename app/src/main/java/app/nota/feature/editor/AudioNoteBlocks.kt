package app.nota.feature.editor

import android.Manifest
import android.content.Context
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
 * One in-progress recording take, owned by [RichNoteEditorViewModel]
 * so it survives the activity recreations a configuration change
 * (rotation) causes (PR #51 fix, Codex R2): the recorder, its temp
 * file, the [RecordingTimer] and the phase live HERE, not in
 * composition — the recorder sheet only renders this state, so the
 * sheet being disposed and recomposed never touches the take.
 *
 * The discard rule is the point. A take is thrown away ONLY by an
 * explicit [cancel] (the sheet's Cancel, or the error panel's
 * Close) or by [release] when the owning ViewModel is cleared.
 * [dismissSheet] — a swipe-dismiss, the denied panel's Close, the
 * sheet leaving composition for any reason — hides the sheet and
 * touches nothing else, because an un-stopped recorder's file has
 * no finalised moov atom and is unplayable: merely keeping the file
 * while the recorder died with the composition would preserve a
 * corpse, not a take. Here the [MediaRecorder] that started the
 * take is the one that later stops it, so [stop] after any number
 * of recreations still finalises a playable file.
 */
class AudioRecordingSession {

    /** A stopped take, handed to the screen's import + caret insert. */
    class FinishedTake(
        val file: File,
        val displayName: String,
        val durationMillis: Long,
    )

    val timer = RecordingTimer()

    /**
     * Snapshot mirror of [timer]'s phase — the sheet's UI and its
     * ticker key on this; every transition writes both together
     * (the [RecordingTimer] itself stays a pure, non-snapshot
     * class, exactly as before).
     */
    var phase by mutableStateOf(RecordingTimer.State.IDLE)
        private set

    /**
     * Whether the recorder sheet is up. ViewModel-held so a
     * configuration change reopens the sheet on the live session
     * instead of stranding the take behind a closed UI.
     */
    var sheetVisible by mutableStateOf(false)
        private set

    /**
     * The current start/stop failure, rendered by the sheet's
     * error panel; null while the session is healthy. A failed
     * start is retried only through [retry] (the panel's
     * deliberate Try again), never by the auto-start effect.
     */
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * True between a successful [stop] and the screen's import
     * completing ([onSaveConsumed]): the auto-start guard and the
     * sheet's dismissal gate both read it.
     */
    var saving by mutableStateOf(false)
        private set

    /**
     * The take's temp file in cacheDir, minted by [begin]; null
     * when no take exists. The file is created up front, so its
     * presence IS the take's footprint: discarding deletes it,
     * [stop] hands it to the importer.
     */
    var tempFile: File? = null
        private set

    private var recorder: MediaRecorder? = null

    /** The insert menu's entry: show the sheet on this session. */
    fun showSheet() {
        sheetVisible = true
    }

    /**
     * Hides the sheet WITHOUT touching the take (a dismissal is
     * not a decision about the recording). A live take keeps
     * recording; the sheet reopens on it from the insert menu, or
     * reappears on its own after a configuration change.
     */
    fun dismissSheet() {
        sheetVisible = false
    }

    /**
     * Starts recording into a fresh temp file — unless a take is
     * already live, a save is in flight, or a failure is showing
     * (the error panel owns the retry). Those guards are what
     * make the sheet's auto-start effect safe to re-run after a
     * recreation: on a live session this is a no-op and the SAME
     * recorder, file and timer carry on.
     */
    fun begin(context: Context) {
        if (recorder != null || saving || error != null) return
        val appContext = context.applicationContext
        val file = File(
            File(appContext.cacheDir, "nota-recording"),
            "take-${UUID.randomUUID()}.m4a",
        )
        file.parentFile?.mkdirs()
        // The file exists from the moment recording starts (the
        // recorder truncates and writes it as it runs): creating
        // it here makes the take's footprint observable to the
        // discard paths without consulting the recorder.
        runCatching { file.createNewFile() }
        val newRecorder = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
        } catch (e: Exception) {
            file.delete()
            error = "Couldn't start recording. The microphone may be in use by another app."
            return
        }
        try {
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioChannels(1)
            newRecorder.setAudioSamplingRate(44_100)
            newRecorder.setAudioEncodingBitRate(96_000)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
        } catch (e: Exception) {
            runCatching { newRecorder.reset() }
            runCatching { newRecorder.release() }
            file.delete()
            error = "Couldn't start recording. The microphone may be in use by another app."
            return
        }
        recorder = newRecorder
        tempFile = file
        timer.start(System.currentTimeMillis())
        phase = RecordingTimer.State.RECORDING
    }

    /** The error panel's deliberate retry: clear the failure, start again. */
    fun retry(context: Context) {
        error = null
        begin(context)
    }

    fun pause() {
        if (phase != RecordingTimer.State.RECORDING) return
        runCatching { recorder?.pause() }
        timer.pause(System.currentTimeMillis())
        phase = RecordingTimer.State.PAUSED
    }

    fun resume() {
        if (phase != RecordingTimer.State.PAUSED) return
        runCatching { recorder?.resume() }
        timer.resume(System.currentTimeMillis())
        phase = RecordingTimer.State.RECORDING
    }

    /**
     * Finalises the take: the recorder that started it stops it
     * (which is what writes a playable file), and the finished
     * take is returned for the screen's import + caret insert;
     * [saving] gates the sheet until [onSaveConsumed]. A stop
     * that captured nothing reports the honest error and returns
     * null — nothing is handed over.
     */
    fun stop(): FinishedTake? {
        val active = recorder ?: return null
        val durationMs = timer.stop(System.currentTimeMillis())
        phase = RecordingTimer.State.IDLE
        val stopped = runCatching { active.stop() }.isSuccess
        releaseRecorder()
        val file = tempFile
        if (!stopped || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            tempFile = null
            error = "That recording didn't save — nothing was captured."
            return null
        }
        saving = true
        return FinishedTake(file, recordingDisplayName(), durationMs)
    }

    /**
     * The screen's import + insert has run (saved, or honestly
     * failed — either way the take is consumed and the temp file
     * was the importer's to copy and the screen's to delete):
     * close the session out.
     */
    fun onSaveConsumed() {
        saving = false
        tempFile = null
        sheetVisible = false
    }

    /**
     * Explicit Cancel (and the error panel's Close — an explicit
     * abandon): the take is thrown away and the sheet closes.
     * The ONLY user action that discards.
     */
    fun cancel() {
        discardTake()
        sheetVisible = false
    }

    /**
     * The owning ViewModel is gone (the editor closed for real):
     * a recorder must never outlive it, so the take is discarded
     * with it.
     */
    fun release() {
        discardTake()
    }

    private fun discardTake() {
        recorder?.let { active ->
            if (timer.state != RecordingTimer.State.IDLE) {
                runCatching { active.stop() }
            }
        }
        releaseRecorder()
        timer.cancel()
        phase = RecordingTimer.State.IDLE
        error = null
        saving = false
        tempFile?.delete()
        tempFile = null
    }

    private fun releaseRecorder() {
        recorder?.let { active ->
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        recorder = null
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
 * insert menu ("Record audio…") that RENDERS the ViewModel-owned
 * [AudioRecordingSession] and forwards it the user's intents. The
 * take itself — recorder, temp file, timer, phase — lives in the
 * session, so this sheet can be disposed and recomposed freely (a
 * rotation recreates the activity mid-recording) without the take
 * noticing; there is deliberately NO dispose-time discard here
 * (PR #51 fix, Codex R2 — the pre-fix sheet held the take in
 * `remember` and threw it away on ANY disposal, rotation
 * included, and the sheet never reopened). The take is discarded
 * only through the session's explicit cancel ([onCancel]) or
 * when the owning ViewModel is cleared.
 *
 * Recording auto-starts once permission is in hand; Stop hands
 * the finished take to [onSave] (the screen imports it into
 * attachment storage and inserts the [AudioSegment] at the caret,
 * the insert funnel every block uses). A dismissal ([onDismiss])
 * hides the sheet and leaves a live take recording in the
 * session — it reopens from the insert menu. RECORD_AUDIO is
 * requested in context when the sheet opens if not already
 * granted — denial shows the honest panel and records nothing.
 *
 * Format: AAC-LC in .m4a, mono, 96 kbps / 44.1 kHz (~0.7 MB/min).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioRecorderSheet(
    session: AudioRecordingSession,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onSave: (file: File, displayName: String, durationMillis: Long) -> Unit,
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    var tick by remember { mutableLongStateOf(0L) }

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
    // voice sheet's pattern). The session's own guards make this
    // safe to re-run when the sheet is recomposed after a
    // recreation: a live take, an in-flight save and a showing
    // error each make begin a no-op — a failed start never
    // retry-loops, and Try again stays the deliberate retry.
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            session.begin(context)
        }
    }
    // Elapsed ticker while recording (paused time stands still:
    // RecordingTimer simply stops accruing).
    val phase = session.phase
    LaunchedEffect(phase) {
        while (phase == RecordingTimer.State.RECORDING) {
            tick = System.currentTimeMillis()
            delay(200)
        }
    }

    val saving = session.saving
    val error = session.error
    val elapsedMs = session.timer.elapsedAt(
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
                        error,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(NotaSpacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                        TextButton(onClick = onCancel) { Text("Close") }
                        OutlinedButton(onClick = { session.retry(context) }) {
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
                        TextButton(onClick = onCancel) { Text("Cancel") }
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
                        TextButton(onClick = onCancel, enabled = !saving) {
                            Text("Cancel")
                        }
                        if (phase == RecordingTimer.State.PAUSED) {
                            OutlinedButton(
                                enabled = !saving,
                                onClick = { session.resume() },
                            ) { Text("Resume") }
                        } else {
                            OutlinedButton(
                                enabled = !saving,
                                onClick = { session.pause() },
                            ) { Text("Pause") }
                        }
                        Button(
                            enabled = !saving,
                            onClick = {
                                session.stop()?.let { take ->
                                    onSave(take.file, take.displayName, take.durationMillis)
                                }
                            },
                        ) {
                            Text(if (saving) "Saving…" else "Stop & insert")
                        }
                    }
                }
            }
        }
    }
}
