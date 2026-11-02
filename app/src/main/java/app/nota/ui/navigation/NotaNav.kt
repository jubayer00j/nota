package app.nota.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.nota.data.auth.AuthManager
import app.nota.feature.settings.SignInScreen
import kotlinx.coroutines.launch
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.nota.data.backup.PendingRestoreStore
import app.nota.data.security.AppLockManager
import app.nota.feature.ask.AskScreen
import app.nota.feature.settings.UnlockRestoreResumer
import app.nota.feature.designsystem.DesignSystemScreen
import app.nota.feature.editor.RichNoteEditorScreen
import app.nota.feature.notes.NotesScreen
import app.nota.feature.reminders.RemindersScreen
import app.nota.feature.search.SearchScreen
import app.nota.feature.settings.SettingsScreen
import app.nota.feature.spike.EditorSpikeScreen
import app.nota.feature.templates.TemplatesScreen
import app.nota.feature.today.TodayScreen
import org.koin.compose.koinInject

/** The four bottom-navigation destinations (spec §6 — veto-approved defaults). */
enum class NotaDestination(val route: String, val label: String, val icon: ImageVector) {
    Notes("notes", "Notes", Icons.Filled.EditNote),
    Ask("ask", "Ask", Icons.Filled.Forum),
    Reminders("reminders", "Reminders", Icons.Filled.Alarm),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

object NotaRoutes {
    const val DESIGN_SYSTEM = "design-system"
    const val EDITOR_SPIKE = "editor-spike"
    const val TEMPLATES = "templates"
    const val TODAY = "today"
    const val SEARCH = "search"
    const val SIGN_IN = "sign-in"
    const val NOTE_EDITOR = "note/{noteId}"
    fun noteEditor(noteId: String): String = "note/$noteId"
}

@Composable
fun NotaNavShell(
    startOnToday: Boolean = false,
    appLockManager: AppLockManager = koinInject(),
    authManager: AuthManager = koinInject(),
    pendingRestoreStore: PendingRestoreStore = PendingRestoreStore.shared,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Phase 2.9 (J2): surface a restore that is waiting on the user
    // (passphrase / summary / unclaimed read) as soon as the app is
    // unlocked, by routing to Settings where BackupSection renders
    // it. The shell only composes while unlocked, so nothing can
    // surface behind the lock; the resumer (remembered for the
    // shell's lifetime = one unlocked session) fires exactly once
    // per restore per unlock — walking away from Settings does not
    // get overridden.
    val isLocked by appLockManager.isLocked.collectAsState()
    val pendingRestore by pendingRestoreStore.state.collectAsState()
    val restoreResumer = remember { UnlockRestoreResumer() }
    LaunchedEffect(isLocked, pendingRestore) {
        if (restoreResumer.onState(isLocked, pendingRestore)) {
            navController.navigate(NotaDestination.Settings.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    // Today has its own route but lives under the Notes bottom-bar item.
    val showBottomBar = NotaDestination.entries.any { it.route == currentRoute } ||
        currentRoute == NotaRoutes.TODAY

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NotaDestination.entries.forEach { dest ->
                        val selected = currentRoute == dest.route ||
                            (currentRoute == NotaRoutes.TODAY && dest == NotaDestination.Notes)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (startOnToday) NotaRoutes.TODAY else NotaDestination.Notes.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(NotaDestination.Notes.route) {
                NotesScreen(
                    onOpenNote = { id -> navController.navigate(NotaRoutes.noteEditor(id)) },
                    onOpenSearch = { navController.navigate(NotaRoutes.SEARCH) },
                )
            }
            composable(NotaRoutes.TODAY) {
                TodayScreen(
                    onOpenNote = { id -> navController.navigate(NotaRoutes.noteEditor(id)) },
                )
            }
            composable(NotaRoutes.SEARCH) {
                SearchScreen(
                    onOpenNote = { id -> navController.navigate(NotaRoutes.noteEditor(id)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(NotaRoutes.NOTE_EDITOR) { entry ->
                val noteId = entry.arguments?.getString("noteId").orEmpty()
                RichNoteEditorScreen(
                    noteId = noteId,
                    onBack = { navController.popBackStack() },
                    onOpenNote = { id -> navController.navigate(NotaRoutes.noteEditor(id)) },
                )
            }
            composable(NotaDestination.Ask.route) { AskScreen() }
            composable(NotaDestination.Reminders.route) { RemindersScreen() }
            composable(NotaDestination.Settings.route) {
                SettingsScreen(
                    onOpenDesignSystem = { navController.navigate(NotaRoutes.DESIGN_SYSTEM) },
                    onOpenEditorSpike = { navController.navigate(NotaRoutes.EDITOR_SPIKE) },
                    onOpenTemplates = { navController.navigate(NotaRoutes.TEMPLATES) },
                    onOpenSignIn = { navController.navigate(NotaRoutes.SIGN_IN) },
                )
            }
            composable(NotaRoutes.SIGN_IN) {
                SignInScreen(onDone = { navController.popBackStack() })
            }
            composable(NotaRoutes.TEMPLATES) {
                TemplatesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenNote = { id -> navController.navigate(NotaRoutes.noteEditor(id)) },
                )
            }
            composable(NotaRoutes.DESIGN_SYSTEM) {
                DesignSystemScreen(onBack = { navController.popBackStack() })
            }
            composable(NotaRoutes.EDITOR_SPIKE) {
                EditorSpikeScreen(onBack = { navController.popBackStack() })
            }
        }
    }
    GuestMoveDialogs(authManager)
}

/**
 * The guest→account copy offer + its result (Phase 3a). Hosted at the
 * shell level for the same reason the restore resumer lives here:
 * the shell composes only while the app is UNLOCKED, so the offer
 * can never appear behind the App Lock, and it survives navigation
 * between tabs. The offer is computed by [AuthManager] (guest library
 * non-empty + no recorded outcome for this account) and is strictly
 * one tap deep: Copy runs the copy, "No thanks" records the decline
 * so it never asks again for this account.
 */
@Composable
private fun GuestMoveDialogs(authManager: AuthManager) {
    val offerUid by authManager.guestMoveOffer.collectAsState()
    val result by authManager.guestMoveResult.collectAsState()
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    if (offerUid != null) {
        AlertDialog(
            onDismissRequest = {
                if (!working) scope.launch { authManager.declineGuestMove() }
            },
            title = { Text("Bring your guest notes in?") },
            text = {
                Text(
                    "You were keeping notes as a guest. Nota can copy " +
                        "them into this account's library — the guest " +
                        "library itself is left exactly as it is, and " +
                        "anything already in this account is kept.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        working = true
                        scope.launch {
                            authManager.acceptGuestMove()
                            working = false
                        }
                    },
                    enabled = !working,
                ) { Text(if (working) "Copying…" else "Copy my guest notes") }
            },
            dismissButton = {
                TextButton(
                    onClick = { scope.launch { authManager.declineGuestMove() } },
                    enabled = !working,
                ) { Text("No thanks") }
            },
        )
    }
    if (result != null) {
        AlertDialog(
            onDismissRequest = { authManager.acknowledgeGuestMoveResult() },
            title = { Text("Guest notes") },
            text = { Text(result!!) },
            confirmButton = {
                TextButton(onClick = { authManager.acknowledgeGuestMoveResult() }) {
                    Text("Done")
                }
            },
        )
    }
}
