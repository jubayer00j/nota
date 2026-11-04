package app.nota.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.nota.data.ai.AiProviderId
import app.nota.data.ai.AiService
import app.nota.data.ai.AiSettingsStore
import app.nota.data.db.DatabaseProvider
import app.nota.ui.theme.NotaSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings → AI features (Phase 4.1): the BYOK provider setup.
 *
 * Bring your own key: calls go straight from this device to the
 * chosen provider, only inside user-initiated flows (Summarize,
 * Suggest tags, Test connection) — nothing ambient. Each provider
 * keeps its OWN key + model; keys are sealed with a device
 * Keystore key, live outside the databases, and never enter a
 * backup.
 *
 * Test connection exercises the values CURRENTLY IN THE FORM
 * (typed key included) — saving is a separate explicit action
 * with a visible dirty state. That split is deliberate: it is
 * the fix for the 1.x wart where Test silently used saved values.
 */
@Composable
fun AiSection(
    store: AiSettingsStore = koinInject(),
    service: AiService = koinInject(),
    databaseProvider: DatabaseProvider = koinInject(),
) {
    val form = remember {
        AiSettingsForm(store, service) { databaseProvider.accountId.value }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { form.load() }

    Text(
        "AI features",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        "Bring your own API key. Calls go straight from this device to the " +
            "provider you pick, and only when you tap Summarize, Suggest tags, " +
            "or Test connection. Keys are encrypted on this device, are never " +
            "included in backups, and are removed when you sign out.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(NotaSpacing.sm))

    if (!form.loaded) {
        CircularProgressIndicator(Modifier.size(24.dp))
        return
    }

    // Provider picker (the SwipeActionRow pattern: a row + menu).
    var providerMenuOpen by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth().clickable { providerMenuOpen = true }
                .padding(vertical = NotaSpacing.sm),
        ) {
            Text("Provider", style = MaterialTheme.typography.titleMedium)
            Text(
                form.activeProvider.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = providerMenuOpen, onDismissRequest = { providerMenuOpen = false }) {
            AiProviderId.entries.forEach { provider ->
                DropdownMenuItem(
                    text = {
                        Text((if (provider == form.activeProvider) "✓ " else "") + provider.label)
                    },
                    onClick = {
                        providerMenuOpen = false
                        scope.launch { form.selectProvider(provider) }
                    },
                )
            }
        }
    }

    val draft = form.draft
    var keyVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = draft.key,
        onValueChange = { draft.key = it; form.onEdited() },
        label = { Text("API key") },
        placeholder = { Text("Paste ${form.activeProvider.label} API key") },
        singleLine = true,
        visualTransformation = if (keyVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(
                    if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (keyVisible) "Hide key" else "Show key",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(NotaSpacing.sm))
    OutlinedTextField(
        value = draft.model,
        onValueChange = { draft.model = it; form.onEdited() },
        label = { Text("Model") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(NotaSpacing.sm))
    if (form.activeProvider == AiProviderId.CUSTOM) {
        OutlinedTextField(
            value = draft.baseUrl,
            onValueChange = { draft.baseUrl = it; form.onEdited() },
            label = { Text("Endpoint URL") },
            placeholder = { Text("https://…/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(NotaSpacing.sm))
    } else if (form.activeProvider != AiProviderId.GEMINI) {
        Text(
            "Endpoint: ${form.activeProvider.presetBaseUrl}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NotaSpacing.sm))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { scope.launch { form.test() } },
            enabled = form.testState != AiSettingsForm.TestState.Testing,
        ) {
            Text("Test connection")
        }
        Spacer(Modifier.size(NotaSpacing.sm))
        Button(
            onClick = { scope.launch { form.save() } },
            enabled = form.isDirty,
        ) {
            Text("Save")
        }
        Spacer(Modifier.size(NotaSpacing.sm))
        when {
            form.testState == AiSettingsForm.TestState.Testing ->
                CircularProgressIndicator(Modifier.size(20.dp))
            form.justSaved -> Text(
                "Saved",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            form.isDirty -> Text(
                "Unsaved changes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    when (val state = form.testState) {
        is AiSettingsForm.TestState.Success -> Text(
            "Connection works — ${form.activeProvider.label} answered.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = NotaSpacing.xs),
        )
        is AiSettingsForm.TestState.Failed -> Text(
            state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = NotaSpacing.xs),
        )
        else -> Unit
    }
}
