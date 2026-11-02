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
 * The single preferences DataStore delegate for the app. It must live at
 * top level and be instantiated once — multiple DataStore instances for
 * the same file throw at runtime.
 */
private val Context.notaDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nota_preferences",
    // A corrupt file is replaced with empty preferences (defaults)
    // instead of failing every read AND write: without a handler,
    // dataStore.edit throws CorruptionException on a corrupt file,
    // so the read-side catch in PreferencesRepository could never
    // actually recover via a Settings write.
    corruptionHandler = ReplaceFileCorruptionHandler(
        produceNewData = { emptyPreferences() },
    ),
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
class PreferencesRepository(context: Context) {

    private val dataStore: DataStore<Preferences> = context.applicationContext.notaDataStore

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
     * stay-locked. Corruption is a different path: the store's
     * ReplaceFileCorruptionHandler repairs the file before this flow
     * reads it, so a corrupted file surfaces here as a valid read.
     */
    val appLockSettings: Flow<AppLockSettings?> = dataStore.data
        .map<Preferences, Preferences?> { prefs -> prefs }
        .catch { exception ->
            if (exception is IOException) emit(null) else throw exception
        }
        .map { prefs ->
            if (prefs == null) {
                null
            } else {
                fun credential(
                    saltKey: Preferences.Key<String>,
                    hashKey: Preferences.Key<String>,
                ): AppLockCredential? {
                    val salt = prefs[saltKey]
                    val hash = prefs[hashKey]
                    return if (salt != null && hash != null) {
                        AppLockCredential(salt, hash)
                    } else {
                        null
                    }
                }
                AppLockSettings(
                    password = credential(KEY_LOCK_PASSWORD_SALT, KEY_LOCK_PASSWORD_HASH),
                    pin = credential(KEY_LOCK_PIN_SALT, KEY_LOCK_PIN_HASH),
                    pattern = credential(KEY_LOCK_PATTERN_SALT, KEY_LOCK_PATTERN_HASH),
                    biometricEnabled = prefs[KEY_LOCK_BIOMETRIC] ?: false,
                    failedAttempts = prefs[KEY_LOCK_FAILED_ATTEMPTS] ?: 0,
                    lockoutUntil = prefs[KEY_LOCK_LOCKOUT_UNTIL] ?: 0L,
                    lockTimeoutSeconds = prefs[KEY_LOCK_TIMEOUT_SECONDS] ?: 0,
                )
            }
        }

    suspend fun setAppLockCredential(method: AppLockMethod, credential: AppLockCredential) {
        safeEdit {
            when (method) {
                AppLockMethod.PASSWORD -> {
                    this[KEY_LOCK_PASSWORD_SALT] = credential.salt
                    this[KEY_LOCK_PASSWORD_HASH] = credential.hash
                }
                AppLockMethod.PIN -> {
                    this[KEY_LOCK_PIN_SALT] = credential.salt
                    this[KEY_LOCK_PIN_HASH] = credential.hash
                }
                AppLockMethod.PATTERN -> {
                    this[KEY_LOCK_PATTERN_SALT] = credential.salt
                    this[KEY_LOCK_PATTERN_HASH] = credential.hash
                }
            }
        }
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

    /** A corrupt/unreadable preferences file degrades to defaults, not a crash. */
    private val DataStore<Preferences>.safeData: Flow<Preferences>
        get() = data.catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }

    private companion object {
        /** Mirrors NotaDatabase.GUEST_ACCOUNT (kept literal so the
         *  preferences layer stays free of a db dependency). */
        const val GUEST_ACCOUNT_ID = "guest"
        const val TAG = "PreferencesRepository"
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

        const val DRIVE_MODE_OFF = "off"
        const val DRIVE_MODE_SCHEDULED = "scheduled"
        const val DRIVE_MODE_SYNC = "sync"
    }
}
