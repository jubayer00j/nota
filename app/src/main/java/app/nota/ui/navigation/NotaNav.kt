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
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.nota.data.backup.PendingRestoreStore
import app.nota.data.attachments.PendingInsertStore
import app.nota.data.db.DatabaseProvider
import app.nota.data.repository.NoteRepository
import app.nota.feature.editor.PendingInsertResumer
import app.nota.data.security.AppLockManager
import app.nota.feature.ask.AskScreen
import app.nota.feature.settings.UnlockRestoreResumer
import app.nota.feature.editor.RichNoteEditorScreen
import app.nota.feature.graph.LinkGraphScreen
import app.nota.feature.notes.NotesScreen
import app.nota.feature.reminders.RemindersScreen
import app.nota.feature.search.SearchScreen
import app.nota.feature.settings.SettingsAboutScreen
import app.nota.feature.settings.SettingsAccountScreen
import app.nota.feature.settings.SettingsAiScreen
import app.nota.feature.settings.SettingsAppearanceScreen
import app.nota.feature.settings.SettingsCategory
import app.nota.feature.settings.SettingsNotesScreen
import app.nota.feature.settings.SettingsScreen
import app.nota.feature.settings.SettingsSecurityScreen
import app.nota.feature.settings.SettingsSyncScreen
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
    // Phase 6 polish: the Phase 0 review routes (design-system,
    // editor-spike) and their destinations are deleted — developer
    // surfaces, never user settings, gone from the shipped graph.
    const val TEMPLATES = "templates"
    const val TODAY = "today"
    const val SEARCH = "search"
    const val LINK_GRAPH = "link-graph"
    const val SIGN_IN = "sign-in"
    const val NOTE_EDITOR = "note/{noteId}"
    fun noteEditor(noteId: String): String = "note/$noteId"

    // Phase 6 presentation redesign: one sub-route per Settings
    // category. The Settings tab destination itself is the ROOT
    // (the category list); a sub-screen's back returns to it.
    const val SETTINGS_ACCOUNT = "settings/account"
    const val SETTINGS_SYNC = "settings/sync"
    const val SETTINGS_SECURITY = "settings/security"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_NOTES = "settings/notes"
    const val SETTINGS_AI = "settings/ai"
    const val SETTINGS_ABOUT = "settings/about"
}

/** The sub-route for a Settings category (root row taps + deep landings). */
private fun settingsRouteFor(category: SettingsCategory): String =
    when (category) {
        SettingsCategory.ACCOUNT -> NotaRoutes.SETTINGS_ACCOUNT
        SettingsCategory.SYNC -> NotaRoutes.SETTINGS_SYNC
        SettingsCategory.SECURITY -> NotaRoutes.SETTINGS_SECURITY
        SettingsCategory.APPEARANCE -> NotaRoutes.SETTINGS_APPEARANCE
        SettingsCategory.NOTES -> NotaRoutes.SETTINGS_NOTES
        SettingsCategory.AI -> NotaRoutes.SETTINGS_AI
        SettingsCategory.ABOUT -> NotaRoutes.SETTINGS_ABOUT
    }

/**
 * Opens [noteId] in the editor without ever stacking a second
 * copy of the same note's editor (Phase 4.4 fix round). Every
 * open-note site funnels through here. If an editor for this
 * exact note is already on the back stack — the graph opened
 * note A, A's link panel chained on to B, B's panel points back
 * at A — navigating "to" A surfaces that existing editor
 * (everything above it pops) instead of pushing a duplicate, so
 * backing out of it leaves the stack exactly as it was before A
 * was first opened. A note that is not already open navigates
 * normally, so ordinary chains (Notes → A → B) still back
 * through B, then A; launchSingleTop covers re-opening the note
 * already on top.
 */
private fun NavController.openNoteEditor(noteId: String) {
    val existing = currentBackStack.value.lastOrNull { entry ->
        entry.destination.route == NotaRoutes.NOTE_EDITOR &&
            entry.arguments?.getString("noteId") == noteId
    }
    if (existing == null) {
        navigate(NotaRoutes.noteEditor(noteId)) { launchSingleTop = true }
    } else {
        while (currentBackStackEntry != null && currentBackStackEntry !== existing) {
            if (!popBackStack()) break
        }
    }
}

@Composable
fun NotaNavShell(
    startOnToday: Boolean = false,
    appLockManager: AppLockManager = koinInject(),
    authManager: AuthManager = koinInject(),
    pendingRestoreStore: PendingRestoreStore = PendingRestoreStore.shared,
    // Phase 3.6 (G2): the durable picker-insert record and its seams.
    pendingInsertStore: PendingInsertStore = koinInject(),
    // Phase 5.1: the reminder-notification tap handoff.
    pendingNoteOpenStore: app.nota.data.reminders.PendingNoteOpenStore = koinInject(),
    // Phase 5.2: the durable quick-capture queue + its deliverer.
    pendingCaptureStore: app.nota.data.capture.PendingCaptureStore = koinInject(),
    captureDeliverer: app.nota.feature.capture.CaptureDeliverer = koinInject(),
    databaseProvider: DatabaseProvider = koinInject(),
    noteRepository: NoteRepository = koinInject(),
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
    // get overridden. Phase 6 presentation redesign: BackupSection
    // now lives on the Sync & backup sub-screen, so the resumer
    // lands on the Settings ROOT first (tab-style) and then pushes
    // the Sync sub-screen — the restore surfaces exactly as
    // before, and back returns to the root.
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
            navController.navigate(NotaRoutes.SETTINGS_SYNC)
        }
    }
    // Phase 3.6 (G2): a picker insert staged before the app locked /
    // died routes the user back INTO its note once the shell is
    // composed again — the 0.4.1 recording ended at the notes list
    // with the file added but the note unopened. The resumer fires
    // once per record per unlock and never when the user is already
    // inside that note (the normal fast return consumes the record
    // in the editor itself). A record from another account is stale
    // by definition (its note lives in another library) and is
    // retired, not routed; a record whose note no longer exists is
    // retired too — the picked file stays in the library, nothing
    // crashes, nothing is left staged.
    val pendingInsert by pendingInsertStore.state.collectAsState()
    val currentAccountId by databaseProvider.accountId.collectAsState()
    val insertResumer = remember { PendingInsertResumer() }
    val currentNoteId = if (currentRoute == NotaRoutes.NOTE_EDITOR) {
        backStack?.arguments?.getString("noteId")
    } else {
        null
    }
    LaunchedEffect(isLocked, pendingInsert, currentAccountId, currentNoteId) {
        val record = pendingInsert ?: return@LaunchedEffect
        if (!isLocked && record.accountId != currentAccountId) {
            pendingInsertStore.clearIf(record.id)
            return@LaunchedEffect
        }
        val target = insertResumer.onState(
            isLocked, record, currentAccountId, currentNoteId,
        ) ?: return@LaunchedEffect
        if (noteRepository.getById(target) != null) {
            navController.openNoteEditor(target)
        } else {
            pendingInsertStore.clearIf(record.id)
        }
    }
    // Phase 5.1: a reminder notification tap parks its note id in
    // PendingNoteOpenStore (via MainActivity). Consume it here — the
    // shell only composes while unlocked, so the tap can never bypass
    // App Lock, and the note's own lock gate applies inside the
    // editor. A record from another account is retired, not routed
    // (its note lives in another library); so is one whose note no
    // longer exists.
    val pendingNoteOpen by pendingNoteOpenStore.record.collectAsState()
    LaunchedEffect(isLocked, pendingNoteOpen, currentAccountId, currentNoteId) {
        val record = pendingNoteOpen ?: return@LaunchedEffect
        if (isLocked) return@LaunchedEffect
        if (record.accountId != currentAccountId) {
            pendingNoteOpenStore.clearIf(record.noteId)
            return@LaunchedEffect
        }
        if (record.noteId == currentNoteId) {
            pendingNoteOpenStore.clearIf(record.noteId)
            return@LaunchedEffect
        }
        if (noteRepository.getById(record.noteId) != null) {
            navController.openNoteEditor(record.noteId)
        }
        pendingNoteOpenStore.clearIf(record.noteId)
    }
    // Phase 5.2: quick captures (share target, launcher shortcuts,
    // QS tile) staged by MainActivity in PendingCaptureStore. The
    // shell consumes the HEAD record only while unlocked, under the
    // PendingCaptureResumer's decisions: a cross-account record is
    // retired with its staged files; a deliverable record is TAKEN
    // (removed before anything is created — 1.x's consume-before-
    // handle, so a capture can never create two notes) and its note
    // is created on the shell's scope, then opened. The shell only
    // composes while unlocked, so no capture can create or show
    // anything behind App Lock.
    val pendingCaptures by pendingCaptureStore.records.collectAsState()
    val captureResumer = remember { app.nota.feature.capture.PendingCaptureResumer() }
    val captureScope = rememberCoroutineScope()
    var captureDeliveryInFlight by remember { mutableStateOf(false) }
    // Phase 5.4: a taken VoiceNote record opens the listening sheet
    // instead of creating a note through the deliverer — the note
    // only exists once something has been said and Done is tapped.
    var voiceCaptureOpen by remember { mutableStateOf(false) }
    LaunchedEffect(isLocked, pendingCaptures.firstOrNull(), currentAccountId, captureDeliveryInFlight) {
        if (captureDeliveryInFlight) return@LaunchedEffect
        val record = pendingCaptures.firstOrNull() ?: return@LaunchedEffect
        when (captureResumer.decide(isLocked, record, currentAccountId)) {
            app.nota.feature.capture.CaptureDecision.HOLD -> Unit
            app.nota.feature.capture.CaptureDecision.RETIRE ->
                pendingCaptureStore.remove(record.id)
            app.nota.feature.capture.CaptureDecision.DELIVER -> {
                val taken = pendingCaptureStore.take(record.id)
                if (taken != null) {
                    captureDeliveryInFlight = true
                    if (taken.request is app.nota.data.capture.CaptureRequest.VoiceNote) {
                        // Phase 5.4 fix (Codex PR #31): the gate
                        // stays HELD while the voice sheet is open —
                        // the flag is cleared only when the sheet
                        // closes (see its callbacks below), so no
                        // further record is taken or delivered
                        // underneath the sheet.
                        voiceCaptureOpen = true
                    } else {
                        captureScope.launch {
                            try {
                                val noteId = runCatching {
                                    captureDeliverer.deliver(taken)
                                }.getOrNull()
                                if (noteId != null) {
                                    navController.openNoteEditor(noteId)
                                }
                            } finally {
                                captureDeliveryInFlight = false
                            }
                        }
                    }
                }
            }
        }
    }
    if (voiceCaptureOpen) {
        app.nota.feature.voice.VoiceCaptureSheet(
            onDismiss = {
                voiceCaptureOpen = false
                captureDeliveryInFlight = false
            },
            onSave = { title, contentHtml ->
                voiceCaptureOpen = false
                captureDeliveryInFlight = false
                captureScope.launch {
                    val note = runCatching {
                        noteRepository.createRichNote(
                            title = title,
                            contentHtml = contentHtml,
                        )
                    }.getOrNull()
                    if (note != null) {
                        navController.openNoteEditor(note.id)
                    }
                }
            },
            onTypeInstead = {
                voiceCaptureOpen = false
                captureDeliveryInFlight = false
                captureScope.launch {
                    val note = runCatching {
                        noteRepository.createNote()
                    }.getOrNull()
                    if (note != null) {
                        navController.openNoteEditor(note.id)
                    }
                }
            },
        )
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
                    onOpenNote = { id -> navController.openNoteEditor(id) },
                    onOpenSearch = { navController.navigate(NotaRoutes.SEARCH) },
                    onOpenGraph = { navController.navigate(NotaRoutes.LINK_GRAPH) },
                )
            }
            composable(NotaRoutes.TODAY) {
                TodayScreen(
                    onOpenNote = { id -> navController.openNoteEditor(id) },
                )
            }
            composable(NotaRoutes.SEARCH) {
                SearchScreen(
                    onOpenNote = { id -> navController.openNoteEditor(id) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(NotaRoutes.LINK_GRAPH) {
                LinkGraphScreen(
                    onBack = { navController.popBackStack() },
                    onOpenNote = { id -> navController.openNoteEditor(id) },
                )
            }
            composable(NotaRoutes.NOTE_EDITOR) { entry ->
                val noteId = entry.arguments?.getString("noteId").orEmpty()
                RichNoteEditorScreen(
                    noteId = noteId,
                    onBack = { navController.popBackStack() },
                    onOpenNote = { id -> navController.openNoteEditor(id) },
                )
            }
            composable(NotaDestination.Ask.route) {
                AskScreen(
                    onOpenNote = { id -> navController.openNoteEditor(id) },
                    onOpenSettings = {
                        navController.navigate(NotaDestination.Settings.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(NotaDestination.Reminders.route) {
                RemindersScreen(
                    onOpenNote = { id -> navController.openNoteEditor(id) },
                )
            }
            composable(NotaDestination.Settings.route) {
                // Phase 6 presentation redesign: the Settings tab
                // IS the root category list; each row pushes its
                // sub-screen, and back returns here.
                SettingsScreen(
                    onOpenCategory = { category ->
                        navController.navigate(settingsRouteFor(category))
                    },
                )
            }
            composable(NotaRoutes.SETTINGS_ACCOUNT) {
                SettingsAccountScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSignIn = { navController.navigate(NotaRoutes.SIGN_IN) },
                )
            }
            composable(NotaRoutes.SETTINGS_SYNC) {
                SettingsSyncScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(NotaRoutes.SETTINGS_SECURITY) {
                SettingsSecurityScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(NotaRoutes.SETTINGS_APPEARANCE) {
                SettingsAppearanceScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(NotaRoutes.SETTINGS_NOTES) {
                SettingsNotesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTemplates = { navController.navigate(NotaRoutes.TEMPLATES) },
                )
            }
            composable(NotaRoutes.SETTINGS_AI) {
                SettingsAiScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(NotaRoutes.SETTINGS_ABOUT) {
                SettingsAboutScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(NotaRoutes.SIGN_IN) {
                SignInScreen(onDone = { navController.popBackStack() })
            }
            composable(NotaRoutes.TEMPLATES) {
                TemplatesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenNote = { id -> navController.openNoteEditor(id) },
                )
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
