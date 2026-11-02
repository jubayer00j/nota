package app.nota.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
    const val NOTE_EDITOR = "note/{noteId}"
    fun noteEditor(noteId: String): String = "note/$noteId"
}

@Composable
fun NotaNavShell(
    startOnToday: Boolean = false,
    appLockManager: AppLockManager = koinInject(),
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
                )
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
}
