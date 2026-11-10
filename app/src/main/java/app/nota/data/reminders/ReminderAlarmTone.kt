package app.nota.data.reminders

import app.nota.data.db.ReminderEntity

/**
 * Phase 6 extras (PR #45): the phone-alarm tone choices on a
 * reminder, mirroring 1.x's phone-alarm.ts alarmToneMode. The mode
 * says WHERE the tone came from; the URI + display name are set
 * only for the two picked modes.
 */
object AlarmToneMode {
    /** The Clock app's own default tone — no ringtone extra is sent. */
    const val SYSTEM = "system"

    /** Picked from the system ringtone picker. */
    const val SYSTEM_PICKER = "system-picker"

    /** A user-picked audio file (persistable read permission held). */
    const val CUSTOM = "custom"

    /** Unknown/missing values degrade to [SYSTEM], like 1.x's
     *  resolveToneMode. */
    fun resolve(mode: String?): String =
        if (mode == SYSTEM_PICKER || mode == CUSTOM) mode else SYSTEM
}

/**
 * The tone half of a reminder save: everything
 * [ReminderRepository.setReminder] needs beyond the trigger and
 * alert type. Defaults are the 1.x defaults — system tone, vibrate
 * on — so callers that do not offer tone controls keep today's
 * behaviour by not passing one.
 *
 * Device-local by design (the alertType discipline): these values
 * never enter the backup wire format or a sync payload.
 */
data class AlarmToneSettings(
    val mode: String = AlarmToneMode.SYSTEM,
    val uri: String? = null,
    val name: String? = null,
    val vibrate: Boolean = true,
) {
    /**
     * Normalised for storage / intent building: the mode is
     * resolved, and a system-mode tone never carries a stale URI
     * or name along (1.x clears both when the mode returns to
     * system).
     */
    fun normalised(): AlarmToneSettings {
        val resolved = AlarmToneMode.resolve(mode)
        return if (resolved == AlarmToneMode.SYSTEM) {
            AlarmToneSettings(mode = AlarmToneMode.SYSTEM, vibrate = vibrate)
        } else {
            copy(mode = resolved)
        }
    }

    companion object {
        /** The tone a stored reminder row carries. */
        fun fromEntity(reminder: ReminderEntity): AlarmToneSettings =
            AlarmToneSettings(
                mode = reminder.alarmToneMode,
                uri = reminder.alarmToneUri,
                name = reminder.alarmToneName,
                vibrate = reminder.alarmVibrate,
            ).normalised()
    }
}
