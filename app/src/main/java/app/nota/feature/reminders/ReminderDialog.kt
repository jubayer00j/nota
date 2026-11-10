package app.nota.feature.reminders

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.nota.data.db.ReminderEntity
import app.nota.data.reminders.AlarmToneMode
import app.nota.data.reminders.AlarmToneSettings
import app.nota.data.reminders.ReminderAlertType
import app.nota.data.reminders.ReminderNotificationText
import app.nota.ui.theme.NotaSpacing
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 1.x's default for a new reminder: today, one hour from now,
 *  rounded to the hour. */
private fun defaultTriggerAt(nowMillis: Long): Long {
    val zone = ZoneId.systemDefault()
    val inAnHour = Instant.ofEpochMilli(nowMillis).atZone(zone).plusHours(1)
    return inAnHour.withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
}

/**
 * Phase 5.1: the set/edit reminder dialog, mirroring 1.x's
 * reminder-dialog (src/components/noma/reminder-dialog.tsx): a date
 * picker + time picker (1.x has no presets), the two alert styles
 * ("Notification" / "Clock alarm"), Save / Delete / Cancel, past
 * times rejected with 1.x's plain message, and honest notices when
 * notifications are blocked or exact alarms aren't allowed — the
 * reminder always saves; only the promise changes.
 *
 * Platform work lives here, not in the repository: the POST_NOTIFICATIONS
 * request (API 33+, asked in context at first save), the Clock-app
 * hand-off (checked BEFORE saving as a phone-alarm — with no Clock
 * app the reminder is kept as a notification reminder and the user is
 * told), and the exact-alarm settings route.
 *
 * Phase 6 extras (PR #44): for Clock-alarm reminders the dialog also
 * carries 1.x's tone controls — the system ringtone picker, a custom
 * audio file (persistable read permission), a system-default reset,
 * a preview play/stop, and the vibrate switch. The tone fields are
 * per-reminder and device-local ([AlarmToneSettings]); a stored tone
 * that can no longer be read is reset to the system default with a
 * notice, on open and again at save time, exactly like 1.x.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderDialog(
    noteTitle: String,
    existing: ReminderEntity?,
    onDismiss: () -> Unit,
    onSave: (triggerAtMillis: Long, alertType: String, tone: AlarmToneSettings) -> Unit,
    onRemove: (() -> Unit)?,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val initialTrigger = existing?.triggerAt ?: defaultTriggerAt(System.currentTimeMillis())
    val initialZoned = Instant.ofEpochMilli(initialTrigger).atZone(zone)

    var alertType by remember {
        mutableStateOf(existing?.alertType ?: ReminderAlertType.NOTIFICATION)
    }
    // Tone state (phone-alarm only; kept on the row either way so
    // switching alert styles back and forth loses nothing).
    var toneMode by remember {
        mutableStateOf(
            existing?.let { AlarmToneMode.resolve(it.alarmToneMode) }
                ?: AlarmToneMode.SYSTEM,
        )
    }
    var toneUri by remember { mutableStateOf(existing?.alarmToneUri) }
    var toneName by remember { mutableStateOf(existing?.alarmToneName) }
    var toneVibrate by remember { mutableStateOf(existing?.alarmVibrate ?: true) }
    var toneNotice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // DatePicker works in UTC midnights; the picked day is combined
    // with the picked local time below.
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialZoned.toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    var pickedHour by remember { mutableIntStateOf(initialZoned.hour) }
    var pickedMinute by remember { mutableIntStateOf(initialZoned.minute) }
    val timePickerState = rememberTimePickerState(
        initialHour = initialZoned.hour,
        initialMinute = initialZoned.minute,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )

    // --- Tone preview + pickers (PR #44) --------------------------------
    val previewRingtone = remember { mutableStateOf<Ringtone?>(null) }
    var previewPlaying by remember { mutableStateOf(false) }

    fun stopTonePreview() {
        previewRingtone.value?.let { runCatching { it.stop() } }
        previewPlaying = false
    }

    fun toggleTonePreview() {
        if (previewPlaying) {
            stopTonePreview()
            return
        }
        val uri = toneUri ?: return
        val ringtone = runCatching {
            RingtoneManager.getRingtone(context, Uri.parse(uri))
        }.getOrNull() ?: return
        previewRingtone.value = ringtone
        if (runCatching { ringtone.play() }.isSuccess) {
            previewPlaying = true
        }
    }

    // The preview never outlives the dialog or the tone it plays.
    DisposableEffect(Unit) {
        onDispose {
            previewRingtone.value?.let { runCatching { it.stop() } }
        }
    }
    LaunchedEffect(toneUri) { stopTonePreview() }

    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked: Uri? =
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (picked != null) {
                toneMode = AlarmToneMode.SYSTEM_PICKER
                toneUri = picked.toString()
                toneName = AlarmTonePlatform.toneDisplayName(context, picked.toString())
                    ?: "Custom tone"
                toneNotice = null
            } else {
                // 1.x: picking "Silent" / nothing falls back to the
                // system default.
                toneMode = AlarmToneMode.SYSTEM
                toneUri = null
                toneName = null
            }
        }
    }

    val customToneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            toneMode = AlarmToneMode.CUSTOM
            toneUri = uri.toString()
            toneName = AlarmTonePlatform.toneDisplayName(context, uri.toString())
                ?: "Custom tone"
            toneNotice = null
        }
    }

    // 1.x parity: on open, a stored tone that can no longer be read
    // resets to the system default with a plain notice.
    LaunchedEffect(Unit) {
        val stored = existing ?: return@LaunchedEffect
        val uri = stored.alarmToneUri ?: return@LaunchedEffect
        if (AlarmToneMode.resolve(stored.alarmToneMode) == AlarmToneMode.SYSTEM) {
            return@LaunchedEffect
        }
        if (!AlarmTonePlatform.isToneAccessible(context, uri)) {
            toneMode = AlarmToneMode.SYSTEM
            toneUri = null
            toneName = null
            toneNotice = "The previously selected alarm tone is no longer " +
                "available, so the system default will be used."
        }
    }

    /** The tone as currently picked in the dialog (unvalidated). */
    fun pickedTone(): AlarmToneSettings = AlarmToneSettings(
        mode = toneMode, uri = toneUri, name = toneName, vibrate = toneVibrate,
    )

    fun computeTriggerAt(): Long {
        val dateMillis = datePickerState.selectedDateMillis ?: return initialTrigger
        val date = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        // Fix round (Jules P3): the DST rules are explicit in
        // ReminderTriggerTimes — a picked time inside a spring-forward
        // gap resolves to the moment the clocks land, not an hour on.
        return ReminderTriggerTimes.resolveEpochMillis(
            date, LocalTime.of(pickedHour, pickedMinute), zone,
        )
    }

    fun performSave(finalAlertType: String) {
        val triggerAt = computeTriggerAt()
        if (triggerAt <= System.currentTimeMillis()) {
            error = "Please choose a future date and time."
            return
        }
        if (finalAlertType == ReminderAlertType.PHONE_ALARM) {
            // 1.x re-validates the tone right before the hand-off: a
            // tone that went missing since the dialog opened falls
            // back to the system default, with a warning.
            var tone = pickedTone()
            val pickedUri = tone.uri
            if (pickedUri != null &&
                !AlarmTonePlatform.isToneAccessible(context, pickedUri)
            ) {
                Toast.makeText(
                    context,
                    "The selected alarm tone is no longer available, so the " +
                        "system default will be used.",
                    Toast.LENGTH_LONG,
                ).show()
                tone = AlarmToneSettings(vibrate = tone.vibrate)
                toneMode = AlarmToneMode.SYSTEM
                toneUri = null
                toneName = null
            }
            onSave(triggerAt, ReminderAlertType.PHONE_ALARM, tone)
            val intent = PhoneAlarmIntents.buildSetAlarmIntent(
                noteTitle, triggerAt, zone,
                vibrate = tone.vibrate,
                ringtoneUri = tone.uri,
            )
            val opened = runCatching { context.startActivity(intent) }.isSuccess
            Toast.makeText(
                context,
                if (opened) {
                    "Reminder saved — confirm the alarm in your Clock app."
                } else {
                    "Reminder saved, but the Clock app couldn't be opened."
                },
                Toast.LENGTH_LONG,
            ).show()
        } else {
            onSave(triggerAt, ReminderAlertType.NOTIFICATION, pickedTone())
        }
        onDismiss()
    }

    // The permission result saves either way (1.x: the reminder is
    // saved regardless; only the alert promise depends on it).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { performSave(ReminderAlertType.NOTIFICATION) }

    fun requestPermissionThenSaveNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !ReminderPermissions.notificationsGranted(context)
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            performSave(ReminderAlertType.NOTIFICATION)
        }
    }

    fun onSaveClicked() {
        val triggerAt = computeTriggerAt()
        if (triggerAt <= System.currentTimeMillis()) {
            error = "Please choose a future date and time."
            return
        }
        error = null
        if (alertType == ReminderAlertType.PHONE_ALARM &&
            !ClockAlarmDates.isRepresentable(
                triggerAt, System.currentTimeMillis(), zone,
            )
        ) {
            // Fix round (Codex P2): the Clock app would ring at the
            // next occurrence of this time, not on the picked date —
            // never save a phone-alarm reminder that lies about its
            // day. The dialog shows the same warning above the fold.
            error = "A Clock alarm can't ring on that date — it would ring at " +
                "the next occurrence of the time instead. Choose Notification " +
                "for that date, or pick the nearer date for a Clock alarm."
            return
        }
        if (alertType == ReminderAlertType.PHONE_ALARM) {
            val intent = PhoneAlarmIntents.buildSetAlarmIntent(noteTitle, triggerAt, zone)
            if (PhoneAlarmIntents.canHandle(context, intent)) {
                performSave(ReminderAlertType.PHONE_ALARM)
            } else {
                // Brief rule: no Clock app → say so plainly and keep
                // the reminder as a notification reminder.
                Toast.makeText(
                    context,
                    "No alarm-clock app found — saved as a notification reminder instead.",
                    Toast.LENGTH_LONG,
                ).show()
                requestPermissionThenSaveNotification()
            }
        } else {
            requestPermissionThenSaveNotification()
        }
    }

    val notificationsGranted = ReminderPermissions.notificationsGranted(context)
    val exactAllowed = ReminderPermissions.exactAlarmsAllowed(context)
    val dateLabel = Instant.ofEpochMilli(
        datePickerState.selectedDateMillis ?: initialTrigger,
    ).atZone(ZoneOffset.UTC).toLocalDate()
        .format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()))
    val timeLabel = LocalTime.of(pickedHour, pickedMinute)
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))

    // Fix round (Codex P2): when "Clock alarm" is picked for a date
    // the Clock app cannot represent — it rings at the next
    // occurrence of the time (today if still ahead, else tomorrow),
    // as flagged by ClockAlarmDates — warn here in the dialog, and
    // block the phone-alarm save in onSaveClicked.
    val nowForClockCheck = System.currentTimeMillis()
    val pickedTriggerAt = computeTriggerAt()
    val clockAlarmMisdated = alertType == ReminderAlertType.PHONE_ALARM &&
        pickedTriggerAt > nowForClockCheck &&
        !ClockAlarmDates.isRepresentable(pickedTriggerAt, nowForClockCheck, zone)
    val clockAlarmNextLabel = if (clockAlarmMisdated) {
        Instant.ofEpochMilli(
            ClockAlarmDates.nextOccurrenceMillis(
                LocalTime.of(pickedHour, pickedMinute), nowForClockCheck, zone,
            ),
        ).atZone(zone).format(
            DateTimeFormatter.ofPattern("EEE, d MMM 'at' h:mm a", Locale.getDefault()),
        )
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Set Reminder" else "Edit Reminder") },
        text = {
            Column {
                Text(
                    "For note: \"${noteTitle.ifBlank { ReminderNotificationText.UNTITLED }}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(NotaSpacing.md))
                Row {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1f),
                    ) { Text(dateLabel) }
                    Spacer(Modifier.padding(horizontal = NotaSpacing.sm))
                    OutlinedButton(
                        onClick = {
                            timePickerState.hour = pickedHour
                            timePickerState.minute = pickedMinute
                            showTimePicker = true
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(timeLabel) }
                }
                Spacer(Modifier.height(NotaSpacing.md))
                Text("Alert style", style = MaterialTheme.typography.titleSmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { alertType = ReminderAlertType.NOTIFICATION },
                ) {
                    RadioButton(
                        selected = alertType == ReminderAlertType.NOTIFICATION,
                        onClick = { alertType = ReminderAlertType.NOTIFICATION },
                    )
                    Text("Notification")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { alertType = ReminderAlertType.PHONE_ALARM },
                ) {
                    RadioButton(
                        selected = alertType == ReminderAlertType.PHONE_ALARM,
                        onClick = { alertType = ReminderAlertType.PHONE_ALARM },
                    )
                    Text("Clock alarm")
                }
                if (alertType == ReminderAlertType.PHONE_ALARM) {
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Text(
                        "Your Clock app will open so you can confirm the alarm. " +
                            "Ringing, snooze and dismiss are handled there — " +
                            "Nota can't change or cancel alarms once they're in your Clock app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(NotaSpacing.md))
                    Text("Alarm sound", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (toneMode == AlarmToneMode.SYSTEM) {
                            "System default"
                        } else {
                            toneName ?: "Custom tone"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(
                            onClick = {
                                val pickerIntent =
                                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                        putExtra(
                                            RingtoneManager.EXTRA_RINGTONE_TYPE,
                                            RingtoneManager.TYPE_ALARM,
                                        )
                                        putExtra(
                                            RingtoneManager.EXTRA_RINGTONE_TITLE,
                                            "Choose alarm sound",
                                        )
                                        putExtra(
                                            RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,
                                            false,
                                        )
                                        putExtra(
                                            RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT,
                                            true,
                                        )
                                        val current = toneUri
                                        if (toneMode == AlarmToneMode.SYSTEM_PICKER &&
                                            current != null
                                        ) {
                                            putExtra(
                                                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                                Uri.parse(current),
                                            )
                                        }
                                    }
                                runCatching { ringtonePickerLauncher.launch(pickerIntent) }
                            },
                        ) { Text("Choose tone…") }
                        TextButton(
                            onClick = {
                                runCatching {
                                    customToneLauncher.launch(arrayOf("audio/*"))
                                }
                            },
                        ) { Text("Custom file…") }
                    }
                    if (toneMode != AlarmToneMode.SYSTEM) {
                        Row {
                            TextButton(
                                onClick = {
                                    stopTonePreview()
                                    toneMode = AlarmToneMode.SYSTEM
                                    toneUri = null
                                    toneName = null
                                    toneNotice = null
                                },
                            ) { Text("System default") }
                            TextButton(onClick = { toggleTonePreview() }) {
                                Text(if (previewPlaying) "Stop" else "Preview")
                            }
                        }
                    }
                    if (toneNotice != null) {
                        Text(
                            toneNotice!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Vibrate", modifier = Modifier.weight(1f))
                        Switch(
                            checked = toneVibrate,
                            onCheckedChange = { toneVibrate = it },
                        )
                    }
                    if (existing?.alertType == ReminderAlertType.PHONE_ALARM) {
                        Spacer(Modifier.height(NotaSpacing.sm))
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text(
                                "This note already has an alarm in your Clock app. " +
                                    "Changing the time here creates a new one; the old " +
                                    "alarm stays in the Clock app until you remove it there.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(NotaSpacing.sm),
                            )
                        }
                    }
                    if (clockAlarmMisdated && clockAlarmNextLabel != null) {
                        Spacer(Modifier.height(NotaSpacing.sm))
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text(
                                "A Clock alarm rings at the next occurrence of " +
                                    "the time — $clockAlarmNextLabel — not on the " +
                                    "date you picked. Save as a Notification for " +
                                    "that date, or pick the nearer date above " +
                                    "for a Clock alarm.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(NotaSpacing.sm),
                            )
                        }
                    }
                }
                if (alertType == ReminderAlertType.NOTIFICATION && !notificationsGranted) {
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(
                            "Notifications are currently blocked by system settings. " +
                                "The reminder will be saved in Nota, but alerts will not fire.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(NotaSpacing.sm),
                        )
                    }
                }
                if (alertType == ReminderAlertType.NOTIFICATION && !exactAllowed) {
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Text(
                        "Exact alarms are off for Nota, so this reminder may " +
                            "arrive a few minutes late.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    ReminderPermissions.exactAlarmSettingsIntent(context),
                                )
                            }
                        },
                    ) { Text("Allow exact alarms") }
                }
                if (error != null) {
                    Spacer(Modifier.height(NotaSpacing.sm))
                    Text(
                        error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSaveClicked() }) { Text("Save Reminder") }
        },
        dismissButton = {
            Row {
                if (existing != null && onRemove != null) {
                    TextButton(
                        onClick = { onRemove(); onDismiss() },
                    ) {
                        Text(
                            "Delete Reminder",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState, showModeToggle = false)
        }
    }
    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickedHour = timePickerState.hour
                        pickedMinute = timePickerState.minute
                        showTimePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
            text = { TimePicker(state = timePickerState) },
        )
    }
}
