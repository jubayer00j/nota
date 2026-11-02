package app.nota.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nota.notes.R
import app.nota.ui.theme.NotaSpacing

/**
 * The Nota brand lockup (Phase 2.5, F5): the vector N mark +
 * "Nota" wordmark, extracted from the Phase 2a Notes header so the
 * header and the lock experience share ONE brand element instead of
 * hand-rolled copies. Defaults match the Notes header exactly
 * (30dp mark, headlineMedium wordmark); the lock screen asks for a
 * larger mark. NotesScreen still draws its own copy — swapping it
 * to this composable is a safe follow-up, not done here (file not
 * owned by the lock redesign).
 */
@Composable
fun NotaBrandLockup(
    modifier: Modifier = Modifier,
    markSize: Dp = 30.dp,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.ic_nota_mark),
            contentDescription = null,
            modifier = Modifier.size(markSize),
        )
        Spacer(Modifier.size(NotaSpacing.sm))
        Text("Nota", style = MaterialTheme.typography.headlineMedium)
    }
}
