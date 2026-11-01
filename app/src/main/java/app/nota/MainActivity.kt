package app.nota

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.nota.data.preferences.PreferencesRepository
import app.nota.ui.navigation.NotaNavShell
import app.nota.ui.theme.NotaTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    // Koin single (registered in AppModule). Obtained via Koin's Activity
    // inject — Koin is started in NotaApplication.onCreate, which runs
    // before any activity is created, so this can never resolve too early.
    private val preferencesRepository: PreferencesRepository by inject()

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
                NotaNavShell(startOnToday = startOnToday)
            }
        }
    }
}
