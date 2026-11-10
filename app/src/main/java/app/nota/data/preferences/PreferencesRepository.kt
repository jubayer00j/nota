package app.nota.data.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.nota.data.security.AppLockCredential
import app.nota.data.security.AppLockMethod
import app.nota.data.security.AppLockSettings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Phase 3.5 (D3): what a horizontal swipe on a note in the main notes
 * list does. Stored by name in preferences; an unknown stored value
 * degrades to the direction's default rather than failing the read.
 */
enum class SwipeAction { ARCHIVE, TRASH, PIN, OFF }

/**
 * The single preferences DataStore delegate for the app. It must live at
 * top level and be instantiated once — multiple DataStore instances for
 * the same file throw at runtime.
 */
/**
 * Phase 6 polish (residual (a)): what a corruption repair installs.
 * NOT a bare empty file: the replacement carries the
 * [PreferencesRepository.KEY_STORE_REPAIRED] marker, so the App Lock
 * flow can tell "this store was repaired after damage" apart from
 * "a fresh, never-locked install" — the two used to be
 * indistinguishable, and a corrupted file therefore silently read
 * as App Lock OFF. The marker is removed by the write that sets up
 * App Lock again (or turns it off) — the moment a post-repair truth
 * exists, the repair flag has done its job.
 */
internal fun repairedStorePreferences(): Preferences =
    emptyPreferences().toMutablePreferences().apply {
        this[PreferencesRepository.KEY_STORE_REPAIRED] = true
    }.toPreferences()

/**
 * The app store's corruption handler. Extracted as a factory so the
 * corruption test can build a scratch store with the EXACT
 * production handler instead of a copy of it.
 */
internal fun notaStoreCorruptionHandler(): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler(produceNewData = { repairedStorePreferences() })

/**
 * The App Lock reading of a preferences snapshot — extracted from
 * [PreferencesRepository.appLockSettings] so the fail-closed rule is
 * directly testable. Null ("unknown", which the lock manager treats
 * as stay-locked) for a missing snapshot (an IO error upstream) AND
 * for a corruption-repaired one ([PreferencesRepository.KEY_STORE_REPAIRED]):
 * a repaired store has lost whatever credential the damaged file
 * held, so reading it as a settings object with no credentials —
 * "App Lock off" — would be a lie.
 */
internal fun appLockSettingsOrNull(prefs: Preferences?): AppLockSettings? {
    if (prefs == null || prefs[PreferencesRepository.KEY_STORE_REPAIRED] == true) return null
    fun credential(
        saltKey: Preferences.Key<String>,
        hashKey: Preferences.Key<String>,
    ): AppLockCredential? {
        val salt = prefs[saltKey]
        val hash = prefs[hashKey]
        return if (salt != null && hash != null) AppLockCredential(salt, hash) else null
    }
    return AppLockSettings(
        password = credential(
            PreferencesRepository.KEY_LOCK_PASSWORD_SALT,
            PreferencesRepository.KEY_LOCK_PASSWORD_HASH,
        ),
        pin = credential(
            PreferencesRepository.KEY_LOCK_PIN_SALT,
            PreferencesRepository.KEY_LOCK_PIN_HASH,
        ),
        pattern = credential(
            PreferencesRepository.KEY_LOCK_PATTERN_SALT,
            PreferencesRepository.KEY_LOCK_PATTERN_HASH,
        ),
        biometricEnabled = prefs[PreferencesRepository.KEY_LOCK_BIOMETRIC] ?: false,
        failedAttempts = prefs[PreferencesRepository.KEY_LOCK_FAILED_ATTEMPTS] ?: 0,
        lockoutUntil = prefs[PreferencesRepository.KEY_LOCK_LOCKOUT_UNTIL] ?: 0L,
        lockTimeoutSeconds = prefs[PreferencesRepository.KEY_LOCK_TIMEOUT_SECONDS] ?: 0,
    )
}

/**
 * The App Lock credential write — the edit
 * [PreferencesRepository.setAppLockCredential] performs — extracted
 * so its failure contract is directly testable against a scratch
 * store, the same reason [appLockSettingsOrNull] exists for reads.
 *
 * Unlike ordinary preference writes (safeEdit in
 * [PreferencesRepository], which swallows [IOException] by design —
 * "the value simply does not persist"), this write must REPORT
 * failure: the flows that set a credential (Settings setup, and the
 * settings-unreadable recovery screen) tell the user the credential
 * was saved, so a silently dropped write is a false success in a
 * security flow (Codex review, PR #33). The store's [IOException]
 * therefore propagates to the caller instead of being caught here.
 */
internal suspend fun writeAppLockCredential(
    store: DataStore<Preferences>,
    method: AppLockMethod,
    credential: AppLockCredential,
) {
    // The transform is declared with its receiver type explicitly —
    // the same shape safeEdit uses — rather than as a trailing
    // lambda on edit.
    val transform: suspend MutablePreferences.() -> Unit = {
        // A fresh credential is a post-repair truth: any
        // corruption-repair marker stops applying from here.
        remove(PreferencesRepository.KEY_STORE_REPAIRED)
        when (method) {
            AppLockMethod.PASSWORD -> {
                this[PreferencesRepository.KEY_LOCK_PASSWORD_SALT] = credential.salt
                this[PreferencesRepository.KEY_LOCK_PASSWORD_HASH] = credential.hash
            }
            AppLockMethod.PIN -> {
                this[PreferencesRepository.KEY_LOCK_PIN_SALT] = credential.salt
                this[PreferencesRepository.KEY_LOCK_PIN_HASH] = credential.hash
            }
            AppLockMethod.PATTERN -> {
                this[PreferencesRepository.KEY_LOCK_PATTERN_SALT] = credential.salt
                this[PreferencesRepository.KEY_LOCK_PATTERN_HASH] = credential.hash
            }
        }
    }
    store.edit(transform)
}

private val Context.notaDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nota_preferences",
    // A corrupt file is replaced (see repairedStorePreferences)
    // instead of failing every read AND write: without a handler,
    // dataStore.edit throws CorruptionException on a corrupt file,
    // so the read-side catch in PreferencesRepository could never
    // actually recover via a Settings write.
    corruptionHandler = notaStoreCorruptionHandler(),
)

/**
 * App preferences (Phase 1a) — the DataStore settings store spec §2 calls
 * for, starting with the two Appearance switches:
 *
 * - dynamicColour: Material You dynamic colour. Default FALSE — static
 *   pine & paper is Nota's default theme (Phase 1a decision; dynamic
 *   colour is what made the device UI diverge from the mock-ups).
 * - oledBlack: OLED true-black dark theme. Default false.
 *
 * Registered as a Koin single; observed by MainActivity and Settings.
 */
class PreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /**
     * The app-wide repository, over the [Context.notaDataStore]
     * singleton. The store-taking constructor above exists for the
     * same reason as the [writeAppLockCredential] /
     * [appLockSettingsOrNull] seams: so tests can drive the real
     * repository logic against a scratch store (e.g. one whose reads
     * fail transiently) instead of the process-wide app store.
     */
    constructor(context: Context) : this(context.applicationContext.notaDataStore)

    val dynamicColour: Flow<Boolean> = dataStore.safeData.map { prefs ->
        prefs[KEY_DYNAMIC_COLOUR] ?: false
    }

    val oledBlack: Flow<Boolean> = dataStore.safeData.map { prefs ->
        prefs[KEY_OLED_BLACK] ?: false
    }

    /** Phase 1b: open on the Today surface instead of Notes. Default false;
     *  read once at launch by MainActivity — changes apply on next launch. */
    val startOnToday: Flow<Boolean> = dataStore.safeData.map { prefs ->
        prefs[KEY_START_ON_TODAY] ?: false
    }

    suspend fun setDynamicColour(enabled: Boolean) {
        safeEdit { this[KEY_DYNAMIC_COLOUR] = enabled }
    }

    suspend fun setOledBlack(enabled: Boolean) {
        safeEdit { this[KEY_OLED_BLACK] = enabled }
    }

    /**
     * A preference write must never crash the app: if the store file is
     * unreadable for a plain [IOException] (not corruption, so the
     * corruption handler above does not repair it), `dataStore.edit`
     * throws, while reads via [safeData] already degrade to defaults.
     * Swallow and log the IO failure only — the value simply does not
     * persist. Any other exception (e.g. a bug in the edit lambda)
     * still surfaces.
     */
    private suspend fun safeEdit(transform: suspend MutablePreferences.() -> Unit) {
        try {
            dataStore.edit(transform)
        } catch (exception: IOException) {
            Log.w(TAG, "Preference write failed; value will not persist", exception)
        }
    }

    suspend fun setStartOnToday(enabled: Boolean) {
        safeEdit { this[KEY_START_ON_TODAY] = enabled }
    }

    // ---- Writing typography (Phase 6, PR #43) ----------------------
    // Noma 1.x's Writing settings: the font size + line height the
    // note body renders at, in the editor and the read view alike.
    // Reads clamp into the slider ranges: a stored value outside
    // them (hand-edited file, future range change) degrades to the
    // nearest in-range value instead of rendering absurd type.

    val writingFontSize: Flow<Int> = dataStore.safeData.map { prefs ->
        (prefs[KEY_WRITING_FONT_SIZE] ?: WRITING_FONT_SIZE_DEFAULT)
            .coerceIn(WRITING_FONT_SIZE_MIN, WRITING_FONT_SIZE_MAX)
    }

    val writingLineHeight: Flow<Float> = dataStore.safeData.map { prefs ->
        (prefs[KEY_WRITING_LINE_HEIGHT] ?: WRITING_LINE_HEIGHT_DEFAULT)
            .coerceIn(WRITING_LINE_HEIGHT_MIN, WRITING_LINE_HEIGHT_MAX)
    }

    suspend fun setWritingFontSize(size: Int) {
        safeEdit {
            this[KEY_WRITING_FONT_SIZE] =
                size.coerceIn(WRITING_FONT_SIZE_MIN, WRITING_FONT_SIZE_MAX)
        }
    }

    suspend fun setWritingLineHeight(lineHeight: Float) {
        safeEdit {
            this[KEY_WRITING_LINE_HEIGHT] =
                lineHeight.coerceIn(WRITING_LINE_HEIGHT_MIN, WRITING_LINE_HEIGHT_MAX)
        }
    }

    suspend fun writingFontSizeNow(): Int = writingFontSize.first()

    suspend fun writingLineHeightNow(): Float = writingLineHeight.first()

    // ---- Theme choice (Phase 6 extras, PR #44) ---------------------
    // Until PR #44 the app always followed the system dark setting;
    // the user can now pin Light or Dark. Unknown stored values
    // degrade to "system", the same posture as the swipe-action
    // mapping below. Device-local, like every preference in this
    // store: it never enters a backup.

    val themeMode: Flow<String> = dataStore.safeData.map { prefs ->
        when (prefs[KEY_THEME_MODE]) {
            THEME_MODE_LIGHT -> THEME_MODE_LIGHT
            THEME_MODE_DARK -> THEME_MODE_DARK
            else -> THEME_MODE_SYSTEM
        }
    }

    suspend fun setThemeMode(mode: String) {
        safeEdit {
            this[KEY_THEME_MODE] = when (mode) {
                THEME_MODE_LIGHT -> THEME_MODE_LIGHT
                THEME_MODE_DARK -> THEME_MODE_DARK
                else -> THEME_MODE_SYSTEM
            }
        }
    }

    suspend fun themeModeNow(): String = themeMode.first()

    // ---- Dictation language (Phase 6 extras, PR #44) ---------------
    // 1.x's speechLanguage: which language the speech recogniser is
    // asked to listen in, for the editor mic and voice notes alike.
    // "system" (the default) adds no override — the engine keeps
    // asking for the device default. Unknown values degrade to
    // "system".

    val speechLanguage: Flow<String> = dataStore.safeData.map { prefs ->
        when (prefs[KEY_SPEECH_LANGUAGE]) {
            SPEECH_LANGUAGE_ENGLISH -> SPEECH_LANGUAGE_ENGLISH
            SPEECH_LANGUAGE_BANGLA -> SPEECH_LANGUAGE_BANGLA
            else -> SPEECH_LANGUAGE_SYSTEM
        }
    }

    suspend fun setSpeechLanguage(language: String) {
        safeEdit {
            this[KEY_SPEECH_LANGUAGE] = when (language) {
                SPEECH_LANGUAGE_ENGLISH -> SPEECH_LANGUAGE_ENGLISH
                SPEECH_LANGUAGE_BANGLA -> SPEECH_LANGUAGE_BANGLA
                else -> SPEECH_LANGUAGE_SYSTEM
            }
        }
    }

    suspend fun speechLanguageNow(): String = speechLanguage.first()

    // ---- Swipe actions (Phase 3.5, D3) -------------------------------
    // Per-direction actions for the main notes list, chosen in
    // Settings. Defaults are the pre-3.5 behaviour: right (start-to-
    // end) archives, left (end-to-start) moves to trash. Device-local
    // UI preference — like the appearance switches it never enters a
    // backup (backups read Room, not this store).

    val swipeRightAction: Flow<SwipeAction> = dataStore.safeData.map { prefs ->
        prefs[KEY_SWIPE_RIGHT_ACTION].toSwipeAction(SwipeAction.ARCHIVE)
    }

    val swipeLeftAction: Flow<SwipeAction> = dataStore.safeData.map { prefs ->
        prefs[KEY_SWIPE_LEFT_ACTION].toSwipeAction(SwipeAction.TRASH)
    }

    suspend fun setSwipeRightAction(action: SwipeAction) {
        safeEdit { this[KEY_SWIPE_RIGHT_ACTION] = action.name }
    }

    suspend fun setSwipeLeftAction(action: SwipeAction) {
        safeEdit { this[KEY_SWIPE_LEFT_ACTION] = action.name }
    }

    suspend fun swipeRightActionNow(): SwipeAction = swipeRightAction.first()

    suspend fun swipeLeftActionNow(): SwipeAction = swipeLeftAction.first()

    // ---- App Lock (Phase 2a) -------------------------------------------
    // Per-method salt+hash slots (like 1.x's separate password/pattern
    // fields), a biometric convenience flag, the failed-attempt counter
    // and lockout deadline, and the re-lock timeout. All keys are new in
    // 2a and cannot collide with the Phase 1a keys above. A credential
    // slot exists only when BOTH its salt and hash are stored.

    /**
     * The App Lock slice of the store. Unlike the appearance flows above
     * (which deliberately degrade to defaults via [safeData]), this flow
     * FAILS CLOSED: an [IOException] reading the store emits null —
     * "unknown" — never an empty settings object, because empty would
     * read as "App Lock off" and open the notes shell on a locked
     * install (Codex review, PR #7). [AppLockManager] treats null as
     * stay-locked (its settingsUnreadable posture).
     *
     * Corruption lands here as null too (Phase 6 polish, residual
     * (a)): the store's ReplaceFileCorruptionHandler repairs a
     * damaged file before this flow reads it, but the replacement it
     * installs carries [KEY_STORE_REPAIRED] — and a repaired store
     * has LOST whatever credential was in the damaged file, so the
     * only honest reading is "unknown", never "off". The user sets
     * App Lock up again from that state; the setup write removes
     * the marker (see [setAppLockCredential]).
     */
    val appLockSettings: Flow<AppLockSettings?> = dataStore.data
        .map<Preferences, Preferences?> { prefs -> prefs }
        .catch { exception ->
            if (exception is IOException) emit(null) else throw exception
        }
        .map { prefs -> appLockSettingsOrNull(prefs) }

    /**
     * Sets (or replaces) one method's credential. Deliberately NOT
     * safeEdit: if the store rejects the write, the [IOException]
     * propagates so the caller can tell the user the credential was
     * not saved — the setup flows close their form on success, and a
     * swallowed failure there reads as "saved" while nothing
     * persisted (Codex review, PR #33). See [writeAppLockCredential].
     */
    suspend fun setAppLockCredential(method: AppLockMethod, credential: AppLockCredential) {
        writeAppLockCredential(dataStore, method, credential)
    }

    suspend fun removeAppLockCredential(method: AppLockMethod) {
        safeEdit {
            when (method) {
                AppLockMethod.PASSWORD -> {
                    remove(KEY_LOCK_PASSWORD_SALT); remove(KEY_LOCK_PASSWORD_HASH)
                }
                AppLockMethod.PIN -> {
                    remove(KEY_LOCK_PIN_SALT); remove(KEY_LOCK_PIN_HASH)
                }
                AppLockMethod.PATTERN -> {
                    remove(KEY_LOCK_PATTERN_SALT); remove(KEY_LOCK_PATTERN_HASH)
                }
            }
        }
    }

    /** Removes every credential slot + the biometric flag (App Lock off). */
    suspend fun clearAppLock() {
        safeEdit {
            remove(KEY_STORE_REPAIRED)
            remove(KEY_LOCK_PASSWORD_SALT); remove(KEY_LOCK_PASSWORD_HASH)
            remove(KEY_LOCK_PIN_SALT); remove(KEY_LOCK_PIN_HASH)
            remove(KEY_LOCK_PATTERN_SALT); remove(KEY_LOCK_PATTERN_HASH)
            remove(KEY_LOCK_BIOMETRIC)
            remove(KEY_LOCK_BIO_TOKEN)
            remove(KEY_LOCK_FAILED_ATTEMPTS); remove(KEY_LOCK_LOCKOUT_UNTIL)
        }
    }

    suspend fun setAppLockBiometricEnabled(enabled: Boolean) {
        safeEdit { this[KEY_LOCK_BIOMETRIC] = enabled }
    }

    // ---- App Lock biometric token (Phase 2.5) ---------------------------
    // The sealed random token the crypto-bound fingerprint unlock must
    // decrypt (see AppLockBiometricStore). Device-local only; this value
    // is a Keystore-sealed blob, useless off this device, and it never
    // enters a backup (backups read Room, not this store).

    suspend fun getAppLockBiometricToken(): String? =
        dataStore.safeData.first()[KEY_LOCK_BIO_TOKEN]

    suspend fun setAppLockBiometricToken(packed: String) {
        safeEdit { this[KEY_LOCK_BIO_TOKEN] = packed }
    }

    suspend fun removeAppLockBiometricToken() {
        safeEdit {
            remove(KEY_LOCK_BIO_TOKEN)
            remove(KEY_LOCK_BIO_TOKEN_ALIAS)
        }
    }

    // Phase 2.6 (Codex, PR #11): the alias of the device key the
    // stored token was sealed under. A token whose alias no longer
    // matches the provider's current alias was sealed under a
    // rotated-away key: its decrypt cipher still INITIALISES, but
    // the authenticated decrypt can never succeed — the token is
    // stale and Settings must say so (re-enable) instead of showing
    // a green toggle. Tokens sealed before 2.6 have no alias and
    // are stale by the same rule.
    suspend fun getAppLockBiometricTokenAlias(): String? =
        dataStore.safeData.first()[KEY_LOCK_BIO_TOKEN_ALIAS]

    suspend fun setAppLockBiometricTokenAlias(alias: String) {
        safeEdit { this[KEY_LOCK_BIO_TOKEN_ALIAS] = alias }
    }

    suspend fun setAppLockFailedAttempts(failedAttempts: Int, lockoutUntil: Long) {
        safeEdit {
            this[KEY_LOCK_FAILED_ATTEMPTS] = failedAttempts
            this[KEY_LOCK_LOCKOUT_UNTIL] = lockoutUntil
        }
    }

    suspend fun setAppLockTimeoutSeconds(seconds: Int) {
        safeEdit { this[KEY_LOCK_TIMEOUT_SECONDS] = seconds }
    }

    // ---- Per-note lock biometric copies (Phase 2b) ----------------------
    // The on-device NEK copy a fingerprint prompt may release for one
    // locked note, sealed under the Android-Keystore device key (see
    // NoteLockBiometricStore). Device-local only — these values never
    // enter Room or a backup.
    //
    // Phase 3a (Codex P2): keyed per ACCOUNT as well as per note —
    // `notelock_bio_<accountId>_<noteId>`. The guest→account copy
    // preserves note ids and lock records, so an unscoped key would
    // let the two libraries' fingerprint copies clobber each other.
    // Pre-3a copies live under the legacy `notelock_bio_<noteId>`
    // key; those can only belong to the guest library (the only one
    // that existed), so guest reads fall back to it and guest writes
    // migrate it away. Account libraries never touch the legacy key.

    private fun noteLockBioKey(accountId: String, noteId: String) =
        stringPreferencesKey("notelock_bio_${accountId}_$noteId")

    private fun legacyNoteLockBioKey(noteId: String) =
        stringPreferencesKey("notelock_bio_$noteId")

    suspend fun getNoteLockBiometricCopy(accountId: String, noteId: String): String? {
        val prefs = dataStore.safeData.first()
        return prefs[noteLockBioKey(accountId, noteId)]
            ?: if (accountId == GUEST_ACCOUNT_ID) prefs[legacyNoteLockBioKey(noteId)] else null
    }

    suspend fun setNoteLockBiometricCopy(accountId: String, noteId: String, packed: String) {
        safeEdit {
            this[noteLockBioKey(accountId, noteId)] = packed
            if (accountId == GUEST_ACCOUNT_ID) remove(legacyNoteLockBioKey(noteId))
        }
    }

    suspend fun removeNoteLockBiometricCopy(accountId: String, noteId: String) {
        safeEdit {
            remove(noteLockBioKey(accountId, noteId))
            if (accountId == GUEST_ACCOUNT_ID) remove(legacyNoteLockBioKey(noteId))
        }
    }

    // ---- Phase 3a: guest→account move offer outcome, per uid --------
    // Written the first time the user answers the "bring your guest
    // notes into this account?" offer for an account ("copied" or
    // "declined"); its presence is what stops the offer re-appearing
    // on every sign-in for that account. Device-local, never backed
    // up (this store is not part of any backup payload).

    private fun guestMoveKey(uid: String) =
        stringPreferencesKey("guest_move_offer_$uid")

    suspend fun getGuestMoveOutcome(uid: String): String? =
        dataStore.safeData.first()[guestMoveKey(uid)]

    suspend fun setGuestMoveOutcome(uid: String, outcome: String) {
        safeEdit { this[guestMoveKey(uid)] = outcome }
    }

    // ---- Phase 3b: Google Drive --------------------------------------
    // Device-local Drive state. NONE of this is ever backed up or
    // synced (settings never sync — spec §4.4 / 1.x D4): the backup
    // mode + interval are this device's choices; the connection grant
    // flag and the backup-encryption record are keyed per account uid
    // (guest cannot connect Drive at all).

    /** This device's copy of an account's backup-encryption setup
     *  (1.x D3): [salt]/[iterations]/[wrappedKey] are the NOMAENC1
     *  header wrap parameters (presence of the record = encryption
     *  enabled for the account); [deviceKeyBase64] is the BMK this
     *  device holds for unattended backups, or null when the key was
     *  removed from this device (backups then pause until unlock). */
    data class DriveEncryptionRecord(
        val salt: String,
        val iterations: Int,
        val wrappedKey: String,
        val deviceKeyBase64: String?,
    )

    val driveBackupMode: Flow<String> = dataStore.safeData.map { prefs ->
        prefs[KEY_DRIVE_BACKUP_MODE] ?: DRIVE_MODE_OFF
    }

    val driveBackupIntervalHours: Flow<Int> = dataStore.safeData.map { prefs ->
        prefs[KEY_DRIVE_BACKUP_INTERVAL_HOURS] ?: 24
    }

    suspend fun setDriveBackupMode(mode: String) {
        safeEdit { this[KEY_DRIVE_BACKUP_MODE] = mode }
    }

    suspend fun setDriveBackupIntervalHours(hours: Int) {
        safeEdit { this[KEY_DRIVE_BACKUP_INTERVAL_HOURS] = hours }
    }

    suspend fun driveBackupModeNow(): String = driveBackupMode.first()

    suspend fun driveBackupIntervalHoursNow(): Int = driveBackupIntervalHours.first()

    private fun driveGrantKey(uid: String) = booleanPreferencesKey("drive_grant_$uid")
    private fun driveEncSaltKey(uid: String) = stringPreferencesKey("drive_enc_${uid}_salt")
    private fun driveEncIterationsKey(uid: String) = intPreferencesKey("drive_enc_${uid}_iterations")
    private fun driveEncWrappedKey(uid: String) = stringPreferencesKey("drive_enc_${uid}_wrapped")
    private fun driveEncDeviceKeyKey(uid: String) = stringPreferencesKey("drive_enc_${uid}_devicekey")
    private fun driveLastBackupAtKey(uid: String) = longPreferencesKey("drive_last_backup_at_$uid")
    private fun driveLastResultKey(uid: String) = stringPreferencesKey("drive_last_result_$uid")

    suspend fun getDriveGrant(uid: String): Boolean =
        dataStore.safeData.first()[driveGrantKey(uid)] ?: false

    suspend fun setDriveGrant(uid: String, granted: Boolean) {
        safeEdit {
            if (granted) this[driveGrantKey(uid)] = true else remove(driveGrantKey(uid))
        }
    }

    suspend fun getDriveEncryptionRecord(uid: String): DriveEncryptionRecord? {
        val prefs = dataStore.safeData.first()
        val salt = prefs[driveEncSaltKey(uid)] ?: return null
        val wrapped = prefs[driveEncWrappedKey(uid)] ?: return null
        return DriveEncryptionRecord(
            salt = salt,
            iterations = prefs[driveEncIterationsKey(uid)] ?: 600_000,
            wrappedKey = wrapped,
            deviceKeyBase64 = prefs[driveEncDeviceKeyKey(uid)],
        )
    }

    suspend fun setDriveEncryptionRecord(uid: String, record: DriveEncryptionRecord) {
        safeEdit {
            this[driveEncSaltKey(uid)] = record.salt
            this[driveEncIterationsKey(uid)] = record.iterations
            this[driveEncWrappedKey(uid)] = record.wrappedKey
            if (record.deviceKeyBase64 != null) {
                this[driveEncDeviceKeyKey(uid)] = record.deviceKeyBase64
            } else {
                remove(driveEncDeviceKeyKey(uid))
            }
        }
    }

    /** Removes only the held device key (1.x "Remove key from this
     *  device"): the record (and thus encryption) stays, backups pause. */
    suspend fun removeDriveDeviceKey(uid: String) {
        safeEdit { remove(driveEncDeviceKeyKey(uid)) }
    }

    /** Removes the encryption record only (turn-off): grant + status
     *  stay; the current cloud backup remains encrypted until the
     *  next (plaintext) backup replaces it — 1.x's fleet semantics. */
    suspend fun clearDriveEncryptionRecord(uid: String) {
        safeEdit {
            remove(driveEncSaltKey(uid))
            remove(driveEncIterationsKey(uid))
            remove(driveEncWrappedKey(uid))
            remove(driveEncDeviceKeyKey(uid))
        }
    }

    /** Clears the whole Drive footprint for an account: grant flag,
     *  encryption record, status (1.x lesson — no credential material
     *  outlives the connection or the sign-in it belongs to). */
    suspend fun clearDriveState(uid: String) {
        safeEdit {
            remove(driveGrantKey(uid))
            remove(driveEncSaltKey(uid))
            remove(driveEncIterationsKey(uid))
            remove(driveEncWrappedKey(uid))
            remove(driveEncDeviceKeyKey(uid))
            remove(driveLastBackupAtKey(uid))
            remove(driveLastResultKey(uid))
        }
    }

    suspend fun getDriveLastBackup(uid: String): Pair<Long, String?> {
        val prefs = dataStore.safeData.first()
        return (prefs[driveLastBackupAtKey(uid)] ?: 0L) to prefs[driveLastResultKey(uid)]
    }

    suspend fun setDriveLastBackup(uid: String, atMillis: Long, result: String) {
        safeEdit {
            this[driveLastBackupAtKey(uid)] = atMillis
            this[driveLastResultKey(uid)] = result
        }
    }

    // ---- AI provider settings (Phase 4.1) -------------------------
    // Device-local BYOK state, keyed per account like the Drive
    // records above: the active provider plus one field set per
    // provider slot (sealed key, model, custom endpoint), addressed
    // by caller-supplied field names (see AiSettingsStore for the
    // field vocabulary). Keys arrive here SEALED (AiKeyCipher) —
    // this store never holds a plaintext key. And like every other
    // record in this file it lives outside Room, so AI state can
    // never enter a backup ZIP or a sync payload.

    private fun aiFieldKey(accountId: String, field: String) =
        stringPreferencesKey("ai_${accountId}_$field")

    suspend fun getAiField(accountId: String, field: String): String? =
        dataStore.safeData.first()[aiFieldKey(accountId, field)]

    suspend fun setAiField(accountId: String, field: String, value: String?) {
        safeEdit {
            if (value == null) {
                remove(aiFieldKey(accountId, field))
            } else {
                this[aiFieldKey(accountId, field)] = value
            }
        }
    }

    /** Drops every AI field for an account (sign-out: 1.x drops the
     *  account's keys when it signs out — no credential material
     *  outlives the sign-in it belongs to). */
    suspend fun clearAiFields(accountId: String, fields: Collection<String>) {
        safeEdit { fields.forEach { remove(aiFieldKey(accountId, it)) } }
    }

    private fun String?.toSwipeAction(default: SwipeAction): SwipeAction =
        this?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: default

    /** A corrupt/unreadable preferences file degrades to defaults, not a crash. */
    private val DataStore<Preferences>.safeData: Flow<Preferences>
        get() = data.catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }

    companion object {
        /** Mirrors NotaDatabase.GUEST_ACCOUNT (kept literal so the
         *  preferences layer stays free of a db dependency). */
        const val GUEST_ACCOUNT_ID = "guest"
        const val TAG = "PreferencesRepository"
        /** Set ONLY by the corruption handler's replacement data:
         *  "this store was repaired after damage" (Phase 6 polish). */
        val KEY_STORE_REPAIRED = booleanPreferencesKey("store_repaired_from_corruption")
        val KEY_DYNAMIC_COLOUR = booleanPreferencesKey("dynamic_colour")
        val KEY_OLED_BLACK = booleanPreferencesKey("oled_black")
        val KEY_START_ON_TODAY = booleanPreferencesKey("start_on_today")
        val KEY_LOCK_PASSWORD_SALT = stringPreferencesKey("app_lock_password_salt")
        val KEY_LOCK_PASSWORD_HASH = stringPreferencesKey("app_lock_password_hash")
        val KEY_LOCK_PIN_SALT = stringPreferencesKey("app_lock_pin_salt")
        val KEY_LOCK_PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
        val KEY_LOCK_PATTERN_SALT = stringPreferencesKey("app_lock_pattern_salt")
        val KEY_LOCK_PATTERN_HASH = stringPreferencesKey("app_lock_pattern_hash")
        val KEY_LOCK_BIOMETRIC = booleanPreferencesKey("app_lock_biometric")
        val KEY_LOCK_BIO_TOKEN = stringPreferencesKey("app_lock_bio_token")
        val KEY_LOCK_BIO_TOKEN_ALIAS = stringPreferencesKey("app_lock_bio_token_alias")
        val KEY_LOCK_FAILED_ATTEMPTS = intPreferencesKey("app_lock_failed_attempts")
        val KEY_LOCK_LOCKOUT_UNTIL = longPreferencesKey("app_lock_lockout_until")
        val KEY_LOCK_TIMEOUT_SECONDS = intPreferencesKey("app_lock_timeout_seconds")
        val KEY_DRIVE_BACKUP_MODE = stringPreferencesKey("drive_backup_mode")
        val KEY_DRIVE_BACKUP_INTERVAL_HOURS = intPreferencesKey("drive_backup_interval_hours")
        val KEY_SWIPE_RIGHT_ACTION = stringPreferencesKey("swipe_right_action")
        val KEY_SWIPE_LEFT_ACTION = stringPreferencesKey("swipe_left_action")
        val KEY_WRITING_FONT_SIZE = intPreferencesKey("writing_font_size")
        val KEY_WRITING_LINE_HEIGHT = floatPreferencesKey("writing_line_height")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_SPEECH_LANGUAGE = stringPreferencesKey("speech_language")

        // Theme choice (Phase 6 extras, PR #44): "system" (follow
        // the device), "light" or "dark". System is the default —
        // until PR #44 the app always followed the system.
        const val THEME_MODE_SYSTEM = "system"
        const val THEME_MODE_LIGHT = "light"
        const val THEME_MODE_DARK = "dark"

        // Dictation language (Phase 6 extras, PR #44 — Noma 1.x's
        // speechLanguage): "system" (the device default), "en" or
        // "bn". The pure resolver mapping a stored value to the
        // recognition language tag lives with the voice feature
        // (SpeechLanguage.resolveTag).
        const val SPEECH_LANGUAGE_SYSTEM = "system"
        const val SPEECH_LANGUAGE_ENGLISH = "en"
        const val SPEECH_LANGUAGE_BANGLA = "bn"

        // Writing typography (Phase 6, PR #43 — Noma 1.x parity):
        // the reading/editor comfort pair from 1.x's Writing
        // settings. Device-local display choices, like every other
        // preference in this store: they never enter a backup.
        const val WRITING_FONT_SIZE_DEFAULT = 17
        const val WRITING_FONT_SIZE_MIN = 14
        const val WRITING_FONT_SIZE_MAX = 22
        const val WRITING_LINE_HEIGHT_DEFAULT = 1.7f
        const val WRITING_LINE_HEIGHT_MIN = 1.4f
        const val WRITING_LINE_HEIGHT_MAX = 2.2f

        const val DRIVE_MODE_OFF = "off"
        const val DRIVE_MODE_SCHEDULED = "scheduled"
        const val DRIVE_MODE_SYNC = "sync"
    }
}
