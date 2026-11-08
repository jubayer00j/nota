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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.nota.data.ai.AiProviderId
import app.nota.data.ai.AiService
import app.nota.data.ai.AiSettingsStore
import app.nota.data.ai.SemanticIndexStatus
import app.nota.data.ai.SemanticIndexer
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
    indexer: SemanticIndexer = koinInject(),
    databaseProvider: DatabaseProvider = koinInject(),
) {
    val form = remember {
        AiSettingsForm(store, service) { databaseProvider.accountId.value }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { form.load() }

    // Phase 4.2: the semantic index's status + rebuild. The index
    // itself lives in SemanticIndexer; this block only reports and
    // triggers. Status refreshes on entry, after a provider
    // switch, immediately after a Save — BEFORE the save-triggered
    // reconcile starts (see the Save handler), never only after
    // it — and after a rebuild.
    var indexStatus by remember { mutableStateOf<SemanticIndexStatus?>(null) }
    var rebuildError by remember { mutableStateOf<String?>(null) }
    // A running rebuild reports through the indexer's OWN progress
    // flow, not a local flag: it carries the honest "n of N", and
    // it clears itself however the rebuild ends. Meanwhile the
    // status above keeps showing the previous count — the stored
    // index keeps serving until the rebuild swaps it.
    val rebuildProgress by indexer.rebuildProgress.collectAsState()
    val activeRebuild = rebuildProgress?.takeIf {
        it.accountId == databaseProvider.accountId.value
    }
    val rebuilding = activeRebuild != null
    fun refreshIndexStatus() {
        scope.launch {
            indexStatus = indexer.status(databaseProvider.accountId.value)
        }
    }
    LaunchedEffect(Unit) { refreshIndexStatus() }

    // Phase 6 presentation redesign: no header Text here any
    // more — the AI features sub-screen's header names the
    // category. The form lives in a "Provider & key" card; the
    // semantic-search controls in their own card below. While
    // the form is still loading, the card shows the same
    // explainer + spinner the flat layout showed.
    if (!form.loaded) {
        SettingsCard(title = "Provider & key") {
            AiProviderProse()
            Spacer(Modifier.height(NotaSpacing.sm))
            CircularProgressIndicator(Modifier.size(24.dp))
        }
        return
    }

    SettingsCard(title = "Provider & key") {
        AiProviderProse()
        Spacer(Modifier.height(NotaSpacing.sm))

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
                // Beginner AI Setup #1 (F2): the active provider's
                // one-line plain description, from AiProviderId so
                // the picker and every other surface agree.
                Text(
                    form.activeProvider.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = providerMenuOpen, onDismissRequest = { providerMenuOpen = false }) {
                AiProviderId.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text((if (provider == form.activeProvider) "✓ " else "") + provider.label)
                                Text(
                                    provider.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            providerMenuOpen = false
                            scope.launch {
                                form.selectProvider(provider)
                                // The semantic section reports on the
                                // ACTIVE provider's saved key — refresh
                                // it with the switch, not on next entry.
                                refreshIndexStatus()
                            }
                        },
                    )
                }
            }
        }

        val draft = form.draft
        // Phase 6 polish: the endpoint is a PROVIDER-level field, so it
        // sits directly under the Provider picker — Custom gets the
        // editable field here, presets show their fixed endpoint as a
        // read-only line in the same slot (it used to render below the
        // model fields, as if it belonged to them).
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
        // Beginner AI Setup #1 (F1): "get a key" help for the
        // SELECTED provider, directly under the field it serves —
        // one honest line about where the key comes from (any
        // offer in it is the provider's), the 2–3 short steps,
        // and a button that opens the provider's own key page.
        // Custom has no page Nota can point at: its note stands
        // alone, with no link.
        val provider = form.activeProvider
        Text(
            provider.keyHelpNote,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = NotaSpacing.sm),
        )
        provider.keySteps.forEachIndexed { index, step ->
            Text(
                "${index + 1}. $step",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        provider.keyPageUrl?.let { url ->
            val uriHandler = LocalUriHandler.current
            OutlinedButton(
                onClick = { uriHandler.openUri(url) },
                modifier = Modifier.padding(top = NotaSpacing.xs),
            ) {
                Text("Get a key")
            }
        }
        Spacer(Modifier.height(NotaSpacing.sm))

        // Beginner AI Setup #1 (F3+F4): the two model fields move
        // into a collapsed-by-default "Advanced — models" fold.
        // The header shows the EFFECTIVE model names (the draft's
        // value, or the provider default it falls back to), so
        // the fold hides nothing about what a call will use.
        // Custom's Endpoint URL stays above, outside the fold —
        // it is required, not advanced.
        var advancedOpen by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().clickable { advancedOpen = !advancedOpen }
                .padding(vertical = NotaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Advanced — models", style = MaterialTheme.typography.titleMedium)
                Text(
                    advancedModelsSummary(
                        draft.model.ifBlank { provider.defaultModel },
                        draft.embeddingModel.ifBlank { provider.defaultEmbeddingModel },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (advancedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (advancedOpen) "Hide model fields" else "Show model fields",
            )
        }
        if (advancedOpen) {
            OutlinedTextField(
                value = draft.model,
                onValueChange = { draft.model = it; form.onEdited() },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "The pre-filled model already works — change it only " +
                    "if you want a different model, and type its exact " +
                    "model id.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(NotaSpacing.sm))
            OutlinedTextField(
                value = draft.embeddingModel,
                onValueChange = { draft.embeddingModel = it; form.onEdited() },
                label = { Text("Embedding model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Powers search-by-meaning and Ask Nota. Summarize and " +
                    "Suggest tags work without it.",
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
                onClick = {
                    scope.launch {
                        form.save()
                        // Show the saved key's effect on the semantic
                        // section NOW — inline, before the reconcile
                        // below starts. status() reads the saved
                        // settings live, so this lands at once; the
                        // pre-fix order refreshed only after reconcile,
                        // which can spend tens of seconds embedding
                        // (and holds the indexer mutex a launched
                        // refresh would queue behind), leaving the
                        // section on its pre-save snapshot — "Add an
                        // API key above", Rebuild disabled — long after
                        // the form already showed "Saved".
                        indexStatus = indexer.status(databaseProvider.accountId.value)
                        // Saving a key or an embedding model is a
                        // semantic-index backfill trigger (best-effort;
                        // reconcile never throws).
                        indexer.reconcile(databaseProvider.accountId.value)
                        refreshIndexStatus()
                    }
                },
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
                // Beginner AI Setup #1 (F5): Test exercises the
                // chat model only — when no embedding model
                // resolves for the provider, the success line
                // says what still won't run. Composition only.
                aiTestSuccessMessage(form.activeProvider, draft.embeddingModel),
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
        // Beginner AI Setup #1 (F9): the stale-index note ALSO
        // appears here, in the card where the provider change
        // happens — the Semantic search card below carries the
        // full line with its Rebuild button. This reuses the
        // status state already refreshed on entry, on provider
        // switch and around Save; no new plumbing.
        if (indexStaleNoteVisible(indexStatus)) {
            Text(
                "Your search index was built with a different provider " +
                    "or embedding model — rebuild it in the Semantic " +
                    "search card below to use this one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = NotaSpacing.xs),
            )
        }
    }

    // ---- Semantic search (Phase 4.2): its own card below the
    // provider card. --------------------------------
    SettingsCard(title = "Semantic search") {
        Text(
            "Notes are turned into vectors by the provider above so search " +
                "can find them by meaning. Vectors are stored only on this " +
                "device, are never included in backups, and locked notes are " +
                "never indexed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NotaSpacing.sm))
        val status = indexStatus
        Text(
            when {
                status == null -> "Checking the index…"
                !status.canIndex ->
                    "Add an API key above to use semantic search."
                status.indexedNotes == 0 ->
                    "No notes indexed yet — rebuild, or just search: the first " +
                        "search builds the index."
                !status.spaceIsCurrent ->
                    "${status.indexedNotes} note(s) indexed with a different " +
                        "provider or embedding model — rebuild to use them."
                else -> "${status.indexedNotes} of ${status.eligibleNotes} note(s) indexed."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NotaSpacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    rebuildError = null
                    scope.launch {
                        try {
                            indexer.rebuild(databaseProvider.accountId.value)
                        } catch (e: app.nota.data.ai.AiException) {
                            rebuildError = e.displayMessage
                        }
                        refreshIndexStatus()
                    }
                },
                enabled = status?.canIndex == true && !rebuilding,
            ) {
                Text("Rebuild index")
            }
            if (rebuilding) {
                Spacer(Modifier.size(NotaSpacing.sm))
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.size(NotaSpacing.sm))
                Text(
                    // The indexer's real pass count — and the status
                    // line above keeps the previous indexed count for
                    // the whole rebuild, because the stored index is
                    // only swapped when the new one is complete.
                    if (activeRebuild != null && activeRebuild.total > 0) {
                        "Rebuilding… ${activeRebuild.done} of ${activeRebuild.total}"
                    } else {
                        "Rebuilding…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        rebuildError?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = NotaSpacing.xs),
            )
        }
    }
}

/** Beginner AI Setup #1 (F5): the honest Test-connection success
 *  line. Test exercises the chat model only, so when no embedding
 *  model resolves the line adds the one sentence that keeps a
 *  "Connection works" from over-promising: semantic search and
 *  Ask Nota also need an embedding model. Resolution mirrors
 *  AiEmbeddingConfig.resolvedEmbeddingModel — the form field,
 *  else the provider's default. A preset's blank field still
 *  resolves (and Save persists the resolved value), so the
 *  sentence is added only when even the resolved model is blank,
 *  which today means Custom. Pure composition — the Test itself
 *  is unchanged. */
internal fun aiTestSuccessMessage(provider: AiProviderId, formEmbeddingModel: String): String {
    val effectiveEmbeddingModel =
        formEmbeddingModel.trim().ifBlank { provider.defaultEmbeddingModel }
    return "Connection works — ${provider.label} answered." +
        if (effectiveEmbeddingModel.isBlank()) {
            " Semantic search and Ask Nota also need an embedding " +
                "model — that field is empty right now " +
                "(see Advanced — models)."
        } else {
            ""
        }
}

/** Beginner AI Setup #1 (F3): the Advanced fold's header summary
 *  — the EFFECTIVE model names (the caller passes draft-or-
 *  default), so the collapsed fold hides nothing about what a
 *  call will use. A side that is still blank (a fresh Custom
 *  slot) reads "not set". */
internal fun advancedModelsSummary(model: String, embeddingModel: String): String =
    listOf(model, embeddingModel)
        .map { it.ifBlank { "not set" } }
        .joinToString(" · ")

/** Beginner AI Setup #1 (F9): whether the stale-index one-liner
 *  shows in the Provider card — exactly the states in which the
 *  Semantic search card shows its own stale line: indexing can
 *  run, an index exists, and its vectors belong to a different
 *  provider or embedding-model space. */
internal fun indexStaleNoteVisible(status: SemanticIndexStatus?): Boolean =
    status != null && status.canIndex && status.indexedNotes > 0 && !status.spaceIsCurrent

/** The BYOK explainer that leads the provider card (both states). */
@Composable
private fun AiProviderProse() {
    Text(
        "Bring your own API key. Calls go straight from this device to the " +
            "provider you pick, and only when you tap Summarize, Suggest tags, " +
            "or Test connection. Keys are encrypted on this device, are never " +
            "included in backups, and are removed when you sign out.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
