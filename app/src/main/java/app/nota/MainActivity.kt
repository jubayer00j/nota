package app.nota

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
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
                    else -> NotaNavShell(startOnToday = startOnToday)
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
