package app.nota

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.nota.data.db.DatabaseProvider
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.security.AppLockManager
import app.nota.feature.lock.LegacyFingerprintPromptHost
import app.nota.feature.lock.LockScreen
import app.nota.notes.R
import app.nota.ui.navigation.NotaNavShell
import app.nota.ui.theme.NotaSpacing
import app.nota.ui.theme.NotaTheme
import org.koin.android.ext.android.inject

/**
 * Phase 2a: FragmentActivity (was ComponentActivity) — BiometricPrompt
 * requires a FragmentActivity host. It extends ComponentActivity, so
 * setContent, enableEdgeToEdge and Koin's Activity inject all behave
 * exactly as before.
 */
class MainActivity : FragmentActivity() {

    // Koin singles (registered in AppModule). Obtained via Koin's Activity
    // inject — Koin is started in NotaApplication.onCreate, which runs
    // before any activity is created, so this can never resolve too early.
    private val preferencesRepository: PreferencesRepository by inject()
    private val appLockManager: AppLockManager by inject()
    private val databaseProvider: DatabaseProvider by inject()
    private val pendingNoteOpenStore: app.nota.data.reminders.PendingNoteOpenStore by inject()
    private val captureIntake: app.nota.data.capture.CaptureIntake by inject()

    /**
     * Phase 5.1: a reminder notification's tap lands here with the
     * note id as an extra. The request is parked in
     * [app.nota.data.reminders.PendingNoteOpenStore] (tagged with the
     * account active right now); the nav shell consumes it once the
     * app is unlocked, so App Lock and the note's own lock gate apply
     * exactly as for any other open.
     */
    private fun captureNoteOpenIntent(intent: Intent?) {
        val noteId = intent?.getStringExtra(
            app.nota.data.reminders.PendingNoteOpenStore.EXTRA_NOTE_ID,
        ) ?: return
        pendingNoteOpenStore.request(noteId, databaseProvider.accountId.value)
    }

    /**
     * Phase 5.2: every system door lands here — the note-open extra
     * above (notification + pinned-note taps), shares
     * (ACTION_SEND / SEND_MULTIPLE) and the launcher-shortcut / QS
     * tile door intents. Doors and shares are STAGED in
     * PendingCaptureStore stamped with the account active right now;
     * the nav shell delivers them only once the app is unlocked, so
     * nothing is created or shown behind App Lock. Share image bytes
     * are copied by [captureIntake] immediately — the sender's URI
     * grant does not survive a relock or a process death.
     */
    private fun captureSystemIntent(intent: Intent?) {
        if (intent == null) return
        captureNoteOpenIntent(intent)
        val accountId = databaseProvider.accountId.value
        if (app.nota.data.capture.ShareIntents.isShare(intent)) {
            captureIntake.stageShare(intent, accountId)
            return
        }
        val door = app.nota.data.capture.CaptureIntents.doorFrom(intent)
        if (door != null) {
            captureIntake.stageDoor(
                door,
                app.nota.data.capture.CaptureIntents.sourceFrom(intent),
                accountId,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureSystemIntent(intent)
    }

    /**
     * Recents masking (Phase 2a, Codex PR #7 P1): the system captures the
     * Recents thumbnail as the app backgrounds — before the re-lock rule
     * can engage on the next start. When App Lock is enabled, mask the
     * window with FLAG_SECURE on the way out so the thumbnail can never
     * retain note content; on return, the mask stays only while locked,
     * so screenshots work normally in an unlocked session.
     */
    override fun onPause() {
        if (appLockManager.settings.value.isEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val manager = appLockManager
        if (manager.settings.value.isEnabled && manager.isLocked.value) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureSystemIntent(intent)
        enableEdgeToEdge()
        setContent {
            // Phase 1a: theme choices are persisted in DataStore and
            // observed here. Both default to false — static pine & paper
            // is the default theme; dynamic colour is opt-in.
            val dynamicColour by preferencesRepository.dynamicColour
                .collectAsState(initial = false)
            val oledBlack by preferencesRepository.oledBlack
                .collectAsState(initial = false)
            val startOnToday by preferencesRepository.startOnToday
                .collectAsState(initial = false)
            NotaTheme(dynamicColor = dynamicColour, oledBlack = oledBlack) {
                // Phase 2a App Lock gate: until the persisted lock state
                // has loaded (ready), show only the brand mark — notes
                // must never flash on a locked cold start. While locked,
                // the nav shell is not composed at all.
                val lockReady by appLockManager.ready.collectAsState()
                val lockSettings by appLockManager.settings.collectAsState()
                val isLocked by appLockManager.isLocked.collectAsState()
                val lockUnreadable by appLockManager.settingsUnreadable.collectAsState()
                when {
                    !lockReady -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painterResource(R.drawable.ic_nota_mark),
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                        )
                    }
                    // Fail closed (Codex PR #7): an unreadable settings
                    // store must never read as "App Lock off".
                    lockUnreadable -> Column(
                        Modifier.fillMaxSize().padding(NotaSpacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(64.dp))
                        Image(
                            painterResource(R.drawable.ic_nota_mark),
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                        )
                        Spacer(Modifier.height(NotaSpacing.md))
                        Text(
                            "Nota couldn't read its settings, so it stays locked.",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(NotaSpacing.xs))
                        Text(
                            "Restart the app. If this keeps happening, the " +
                                "settings file on this device may be damaged.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    lockSettings.isEnabled && isLocked -> LockScreen()
                    else -> {
                        // Phase 3a: the shell is KEYED by account. A
                        // sign-in/out swaps the active database (see
                        // DatabaseProvider); keying disposes every
                        // ViewModel + flow collector in the old shell
                        // and composes a fresh one against the new
                        // account's database, so nothing from the
                        // previous library can linger on screen.
                        val accountId by databaseProvider.accountId
                            .collectAsState()
                        key(accountId) {
                            NotaNavShell(startOnToday = startOnToday)
                        }
                    }
                }
                // Phase 2.7: the legacy (API < 28) fingerprint
                // prompt's Compose host — composed once at the root
                // so its dialog can present over the lock screen,
                // Settings and the per-note surfaces alike. It
                // renders nothing unless a framework prompt request
                // is active (never on API 28+, where AndroidX is
                // the executor).
                LegacyFingerprintPromptHost()
            }
        }
    }
}
