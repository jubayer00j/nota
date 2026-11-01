package app.nota

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.security.AppLockManager
import app.nota.feature.lock.LockScreen
import app.nota.notes.R
import app.nota.ui.navigation.NotaNavShell
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
                    lockSettings.isEnabled && isLocked -> LockScreen()
                    else -> NotaNavShell(startOnToday = startOnToday)
                }
            }
        }
    }
}
