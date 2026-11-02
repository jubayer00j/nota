package app.nota.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.nota.data.auth.AuthManager
import app.nota.data.auth.GuestLibraryTransfer
import app.nota.data.auth.Session
import app.nota.data.backup.PendingRestoreState
import app.nota.data.backup.PendingRestoreStore
import app.nota.ui.theme.NotaSpacing
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings → Account (Phase 3a). Guest mode is a first-class state,
 * not an error: the row says plainly that notes live only on this
 * device and offers sign-in. Signed in, it shows who's signed in and
 * offers sign-out — which returns to the guest library exactly as it
 * was left (the account's database file stays on disk, untouched,
 * for the next sign-in).
 *
 * Switching is disabled while a restore is mid-flight: a restore
 * writes into the ACTIVE database, and swapping the database under
 * it would split the restore across two libraries. The restore
 * always ends in a visible state (Phase 2.5), so the wait is short
 * and explained.
 */
@Composable
fun AccountSection(
    onOpenSignIn: () -> Unit,
    authManager: AuthManager = koinInject(),
    transfer: GuestLibraryTransfer = koinInject(),
    pendingRestoreStore: PendingRestoreStore = PendingRestoreStore.shared,
) {
    val session by authManager.session.collectAsState()
    val pending by pendingRestoreStore.state.collectAsState()
    val scope = rememberCoroutineScope()

    // The guest library's size decides whether the manual
    // "bring guest notes in" row exists (the automatic offer is the
    // AuthManager's job; this row is the user's own second chance).
    var guestNoteCount by remember { mutableStateOf(0) }
    LaunchedEffect(session) {
        guestNoteCount = if (session is Session.SignedIn) {
            runCatching { transfer.guestNoteCount() }.getOrDefault(0)
        } else {
            0
        }
    }

    val restoreInFlight = pending is PendingRestoreState.Copying ||
        pending is PendingRestoreState.Reading ||
        pending is PendingRestoreState.Applying

    Column {
        Text(
            "Account",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        when (val current = session) {
            is Session.Guest -> {
                Text(
                    "Guest mode — your notes live only on this device, " +
                        "in their own guest library. Signing in opens " +
                        "your account's library instead; nothing is " +
                        "uploaded by signing in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onOpenSignIn,
                    enabled = !restoreInFlight,
                ) { Text("Sign in") }
            }
            is Session.SignedIn -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = NotaSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AccountAvatar(current)
                    Spacer(Modifier.width(NotaSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            current.displayName ?: current.email ?: "Signed in",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (current.displayName != null && current.email != null) {
                            Text(
                                current.email,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { scope.launch { authManager.signOut() } },
                    enabled = !restoreInFlight,
                ) { Text("Sign out") }
                if (guestNoteCount > 0) {
                    TextButton(
                        onClick = { scope.launch { authManager.requestGuestMove() } },
                        enabled = !restoreInFlight,
                    ) { Text("Bring guest notes into this account") }
                    Text(
                        "Copies the $guestNoteCount notes kept as a guest " +
                            "into this account. The guest library stays as it is.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (restoreInFlight) {
            Text(
                "Account switching is paused while a restore finishes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(NotaSpacing.xs))
    }
}

@Composable
private fun AccountAvatar(session: Session.SignedIn) {
    val initial = (session.displayName ?: session.email ?: "?")
        .firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    if (session.photoUrl != null) {
        AsyncImage(
            model = session.photoUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
    } else {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    initial,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
