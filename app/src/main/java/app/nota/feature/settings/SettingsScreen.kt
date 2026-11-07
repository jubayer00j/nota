package app.nota.feature.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nota.data.ai.AiSettingsStore
import app.nota.data.auth.AuthManager
import app.nota.data.auth.Session
import app.nota.data.db.DatabaseProvider
import app.nota.data.drive.DriveRepository
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.preferences.SwipeAction
import app.nota.data.security.AppLockManager
import app.nota.feature.notes.label
import app.nota.ui.theme.NotaSpacing
import org.koin.compose.koinInject

/**
 * The seven Settings categories (Phase 6 polish — the order and
 * grouping Jubayer approved in the reorganisation, unchanged by
 * the presentation redesign): identity first, system last. One
 * icon per category, used in BOTH the root row's tile and the
 * sub-screen header's mini tile, so a category looks like itself
 * in both places.
 */
enum class SettingsCategory(val title: String, val icon: ImageVector) {
    ACCOUNT("Account", Icons.Filled.Person),
    SYNC("Sync & backup", Icons.Filled.CloudUpload),
    SECURITY("Security & privacy", Icons.Filled.Shield),
    APPEARANCE("Appearance", Icons.Filled.Palette),
    NOTES("Notes & writing", Icons.Filled.EditNote),
    AI("AI features", Icons.Filled.AutoAwesome),
    ABOUT("About", Icons.Filled.Info),
}

/** Root-list card grouping: cards of two rows, About on its own. */
private val SETTINGS_CATEGORY_GROUPS: List<List<SettingsCategory>> = listOf(
    listOf(SettingsCategory.ACCOUNT, SettingsCategory.SYNC),
    listOf(SettingsCategory.SECURITY, SettingsCategory.APPEARANCE),
    listOf(SettingsCategory.NOTES, SettingsCategory.AI),
    listOf(SettingsCategory.ABOUT),
)

/**
 * Settings root (Phase 6 presentation redesign, mock-up approved
 * by Jubayer 2026-11-08): NOT one long scroll — a category list
 * in the Noma pattern, re-expressed in pine & paper. Categories
 * sit in cards of two rows on the page background; each row is a
 * 48dp pine-tinted icon tile + serif-free title + a LIVE one-line
 * summary + chevron, and tapping opens the category's own
 * sub-screen (see SettingsSubScreens.kt). The summaries are
 * derived from the SAME state the sections themselves observe —
 * the session, the Drive grant + backup mode, App Lock settings,
 * the encryption record flow, the theme/launch preferences, the
 * AI store for the current account — so a summary cannot drift
 * from the truth the sub-screen will show.
 *
 * [appVersionName] is injectable for tests; production reads the
 * REAL installed version from the PackageManager — never a
 * hard-coded string — so the About summary can never drift from
 * the build it describes.
 */
@Composable
fun SettingsScreen(
    onOpenCategory: (SettingsCategory) -> Unit,
    appVersionName: String? = null,
    preferencesRepository: PreferencesRepository = koinInject(),
    authManager: AuthManager = koinInject(),
    driveRepository: DriveRepository = koinInject(),
    appLockManager: AppLockManager = koinInject(),
    aiSettingsStore: AiSettingsStore = koinInject(),
    databaseProvider: DatabaseProvider = koinInject(),
) {
    val context = LocalContext.current
    val versionName = appVersionName ?: remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }

    // ---- Live-summary state: the same flows the sections read.
    val session by authManager.session.collectAsState()
    val signedIn = session as? Session.SignedIn
    val driveState by driveRepository.state.collectAsState()
    // Mirrors DriveBackupSection's own grant probe (keyed on the
    // Drive state so a fresh grant re-reads without a restart).
    val grantKnown by produceState(initialValue = false, signedIn?.uid, driveState) {
        value = driveRepository.hasGrant()
    }
    val driveMode by preferencesRepository.driveBackupMode
        .collectAsState(initial = PreferencesRepository.DRIVE_MODE_OFF)
    val lockSettings by appLockManager.settings.collectAsState()
    val encryptionRecord by driveRepository.encryptionRecordFlow.collectAsState()
    // Mirrors BackupEncryptionBlock: the record flow is refreshed
    // on entry so the summary reads the current account's record.
    LaunchedEffect(signedIn?.uid) { driveRepository.refreshEncryptionRecord() }
    val dynamicColour by preferencesRepository.dynamicColour.collectAsState(initial = false)
    val oledBlack by preferencesRepository.oledBlack.collectAsState(initial = false)
    val startOnToday by preferencesRepository.startOnToday.collectAsState(initial = false)
    val accountId by databaseProvider.accountId.collectAsState()
    // The AI store is suspend-based (per-account slots, sealed
    // keys) — the same reads AiSection's form performs on load.
    val aiSummary by produceState<String?>(initialValue = null, accountId) {
        val provider = aiSettingsStore.activeProvider(accountId)
        val slot = aiSettingsStore.slot(accountId, provider)
        value = if (slot.apiKey.isBlank()) {
            "Not set up"
        } else {
            "${provider.label} · Key saved"
        }
    }

    fun summaryFor(category: SettingsCategory): String = when (category) {
        SettingsCategory.ACCOUNT ->
            signedIn?.let { it.email ?: it.displayName ?: "Signed in" }
                ?: "Guest — notes stay on this device"
        SettingsCategory.SYNC -> when {
            signedIn == null -> "Sign in to use Drive backup"
            !grantKnown -> "Not connected yet"
            else -> "Connected · " + when (driveMode) {
                PreferencesRepository.DRIVE_MODE_SYNC -> "Sync"
                PreferencesRepository.DRIVE_MODE_SCHEDULED -> "Scheduled"
                else -> "Off"
            }
        }
        SettingsCategory.SECURITY ->
            "App lock ${if (lockSettings.isEnabled) "On" else "Off"}" +
                " · Encryption ${if (encryptionRecord != null) "On" else "Off"}"
        SettingsCategory.APPEARANCE -> {
            // NotaTheme only applies dynamic colour on Android 12+,
            // so below that the honest summary is the static theme
            // even when the preference is stored on.
            val materialYou = dynamicColour &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            (if (materialYou) "Material You" else "Pine & paper") +
                " · OLED ${if (oledBlack) "on" else "off"}"
        }
        SettingsCategory.NOTES ->
            if (startOnToday) "Starts on Today" else "Starts on Notes"
        SettingsCategory.AI -> aiSummary ?: "…"
        SettingsCategory.ABOUT ->
            versionName?.let { "Version $it" } ?: "Version unknown"
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(
                horizontal = NotaSpacing.md,
                vertical = NotaSpacing.md,
            ),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NotaSpacing.md, vertical = NotaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(NotaSpacing.md),
        ) {
            SETTINGS_CATEGORY_GROUPS.forEach { group ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        group.forEachIndexed { index, category ->
                            if (index > 0) {
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 20.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            SettingsCategoryRow(
                                category = category,
                                summary = summaryFor(category),
                                onClick = { onOpenCategory(category) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One root-list row: icon tile + title + live summary + chevron. */
@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                category.icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(NotaSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                category.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                ),
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The sub-screen card (Phase 6 presentation redesign): a surface
 * one step above the page background, 20dp radius, a 1dp
 * pine-tinted hairline border, no shadow — the same treatment as
 * the root list's cards, with an optional serif card title.
 * Sections are re-housed inside these; their own content keeps
 * its layout, state and dialogs.
 */
@Composable
internal fun SettingsCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(NotaSpacing.md)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 17.sp,
                        lineHeight = 24.sp,
                    ),
                )
                Spacer(Modifier.height(NotaSpacing.sm))
            }
            content()
        }
    }
}

/**
 * The sub-screen scaffold: a fixed header (back + the category's
 * mini icon tile + serif title, over a hairline) and a scrolling
 * content column that opens with the category's plain-language
 * intro sentence before any control — the Noma signature, in
 * Nota's tokens.
 */
@Composable
internal fun SettingsSubScreen(
    category: SettingsCategory,
    intro: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = NotaSpacing.sm, vertical = NotaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
            Box(
                Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    category.icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(NotaSpacing.sm))
            Text(
                category.title,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NotaSpacing.md, vertical = NotaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(NotaSpacing.md),
        ) {
            Text(
                intro,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = NotaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Phase 3.5 (D3): a swipe-direction row; tapping opens the action menu. */
@Composable
internal fun SwipeActionRow(
    title: String,
    action: SwipeAction,
    onSelect: (SwipeAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsRow(title = title, subtitle = action.label(), onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SwipeAction.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text((if (option == action) "✓ " else "") + option.label()) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(vertical = NotaSpacing.sm),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one navigation-row shape (Phase 6 polish): same text stack as
 * every other row, plus the chevron that says "this goes somewhere"
 * — the settings rows that open another screen were previously
 * indistinguishable from static labels.
 */
@Composable
internal fun SettingsNavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = NotaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A static information row (no tap action): the About version. */
@Composable
internal fun SettingsInfoRow(title: String, value: String) {
    Column(Modifier.padding(vertical = NotaSpacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
