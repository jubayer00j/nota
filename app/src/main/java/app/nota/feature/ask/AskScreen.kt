package app.nota.feature.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nota.data.ai.SemanticIndexStatus
import app.nota.ui.theme.NotaSpacing
import org.koin.androidx.compose.koinViewModel

/**
 * The Ask Nota screen (Phase 4.3, spec §6): grounded Q&A over
 * the user's own notes. A gate (no key / no embedding model /
 * no index / foreign index) stands before the thread; the
 * thread itself is a memory-only list of question/answer
 * exchanges whose answers carry tappable citation chips and
 * source cards — tap either and the cited note opens. The
 * honest states are the UI's own words, never the model's:
 * a refusal renders as "Not in your notes", a no-evidence
 * exchange as "No matching passages".
 */
@Composable
fun AskScreen(
    onOpenNote: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: AskViewModel = koinViewModel(),
) {
    val gate by viewModel.gate.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val indexStatus by viewModel.indexStatus.collectAsState()
    val indexBusy by viewModel.indexBusy.collectAsState()
    val indexError by viewModel.indexError.collectAsState()
    val rebuildProgress by viewModel.rebuildProgress.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshGate() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = NotaSpacing.md, vertical = NotaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Ask Nota",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (gate == AskGate.READY && entries.isNotEmpty()) {
                TextButton(onClick = { viewModel.clear() }, enabled = !busy) {
                    Text("Clear")
                }
            }
        }
        if (gate == AskGate.READY) {
            ReadyThread(
                entries = entries,
                busy = busy,
                status = indexStatus,
                indexBusy = indexBusy,
                onUpdateIndex = { viewModel.buildIndex() },
                onOpenNote = onOpenNote,
                onAsk = { viewModel.ask(it) },
                modifier = Modifier.weight(1f),
            )
        } else {
            GateContent(
                gate = gate,
                indexBusy = indexBusy,
                indexError = indexError,
                rebuildDone = rebuildProgress?.done,
                rebuildTotal = rebuildProgress?.total,
                onOpenSettings = onOpenSettings,
                onBuild = { viewModel.buildIndex() },
                onRebuild = { viewModel.rebuildIndex() },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GateContent(
    gate: AskGate,
    indexBusy: Boolean,
    indexError: String?,
    rebuildDone: Int?,
    rebuildTotal: Int?,
    onOpenSettings: () -> Unit,
    onBuild: () -> Unit,
    onRebuild: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(NotaSpacing.lg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (gate) {
                AskGate.LOADING -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(NotaSpacing.md))
                    Text(
                        "Checking your index…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AskGate.NO_KEY -> {
                    GateText(
                        title = "Ask needs your API key",
                        body = "Ask Nota answers questions from your notes using " +
                            "your own AI provider. Add an API key in Settings → " +
                            "AI features to switch it on.",
                    )
                    Spacer(Modifier.height(NotaSpacing.md))
                    Button(onClick = onOpenSettings) { Text("Open AI settings") }
                }
                AskGate.NEEDS_EMBEDDING_MODEL -> {
                    GateText(
                        title = "An embedding model is missing",
                        body = "Ask finds the right passages with the embedding " +
                            "model saved for this provider, and that slot is " +
                            "empty. Add one in Settings → AI features to switch " +
                            "Ask on.",
                    )
                    Spacer(Modifier.height(NotaSpacing.md))
                    Button(onClick = onOpenSettings) { Text("Open AI settings") }
                }
                AskGate.NEEDS_INDEX -> {
                    GateText(
                        title = "Your notes aren't indexed yet",
                        body = "Ask uses the same on-device index as semantic " +
                            "search: note text goes to your provider for " +
                            "embedding, the vectors stay on this device, and " +
                            "locked notes are never indexed. Build it once and " +
                            "Ask can find the right passages.",
                    )
                    Spacer(Modifier.height(NotaSpacing.md))
                    Button(onClick = onBuild, enabled = !indexBusy) {
                        Text(if (indexBusy) "Building…" else "Build index")
                    }
                    if (indexBusy) {
                        Spacer(Modifier.height(NotaSpacing.sm))
                        CircularProgressIndicator(Modifier.size(20.dp))
                    }
                }
                AskGate.NEEDS_REBUILD -> {
                    GateText(
                        title = "The index belongs to another provider",
                        body = "The stored index was built with a different " +
                            "provider or embedding model, and vectors from two " +
                            "spaces are never mixed. Rebuild it to ask with the " +
                            "current one — the old index keeps serving search " +
                            "until the new one is ready.",
                    )
                    Spacer(Modifier.height(NotaSpacing.md))
                    Button(onClick = onRebuild, enabled = !indexBusy) {
                        Text(if (indexBusy) "Rebuilding…" else "Rebuild index")
                    }
                    if (indexBusy) {
                        Spacer(Modifier.height(NotaSpacing.sm))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Spacer(Modifier.width(NotaSpacing.sm))
                            Text(
                                if (rebuildDone != null && rebuildTotal != null &&
                                    rebuildTotal > 0
                                ) {
                                    "Rebuilding… $rebuildDone of $rebuildTotal"
                                } else {
                                    "Rebuilding…"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                AskGate.READY -> Unit
            }
            indexError?.let { message ->
                Spacer(Modifier.height(NotaSpacing.sm))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun GateText(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(NotaSpacing.sm))
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReadyThread(
    entries: List<AskEntry>,
    busy: Boolean,
    status: SemanticIndexStatus?,
    indexBusy: Boolean,
    onUpdateIndex: () -> Unit,
    onOpenNote: (String) -> Unit,
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val itemCount = entries.size + if (busy) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    var input by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        if (askIndexStale(status)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NotaSpacing.md, vertical = NotaSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Some notes aren't in the index yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onUpdateIndex, enabled = !indexBusy) {
                    Text(if (indexBusy) "Updating…" else "Update")
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(NotaSpacing.md),
        ) {
            if (entries.isEmpty() && !busy) {
                item {
                    Text(
                        "Ask anything about your notes — what did I decide, " +
                            "when is it due, what did I write about… Answers " +
                            "cite the notes they came from, and tapping a " +
                            "citation opens the note.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(entries, key = { it.id }) { entry ->
                when (entry) {
                    is AskEntry.Question -> QuestionBubble(entry.text)
                    is AskEntry.Answer -> AnswerCard(entry, onOpenNote)
                }
                Spacer(Modifier.height(NotaSpacing.md))
            }
            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Spacer(Modifier.width(NotaSpacing.sm))
                        Text(
                            "Searching your notes…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(NotaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask about your notes…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                enabled = !busy,
            )
            Spacer(Modifier.width(NotaSpacing.sm))
            Button(
                onClick = {
                    onAsk(input)
                    input = ""
                },
                enabled = !busy && input.isNotBlank(),
            ) { Text("Ask") }
        }
    }
}

@Composable
private fun QuestionBubble(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1f))
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(
                    horizontal = NotaSpacing.md,
                    vertical = NotaSpacing.sm,
                ),
            )
        }
    }
}

@Composable
private fun AnswerCard(entry: AskEntry.Answer, onOpenNote: (String) -> Unit) {
    Column {
        when (entry.status) {
            AskAnswerStatus.ANSWER -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(NotaSpacing.md)) {
                        AnswerText(entry, onOpenNote)
                        val cards = sourceCardsFor(entry.sentExcerpts, entry.citations)
                        if (cards.isNotEmpty()) {
                            Spacer(Modifier.height(NotaSpacing.md))
                            cards.forEach { card ->
                                SourceCard(card) { onOpenNote(card.noteId) }
                                Spacer(Modifier.height(NotaSpacing.sm))
                            }
                        }
                    }
                }
            }
            AskAnswerStatus.REFUSAL -> HonestStateCard(
                title = "Not in your notes",
                body = "Ask Nota couldn't ground an answer to that in your " +
                    "notes. Try different words, or a more specific question.",
            )
            AskAnswerStatus.NO_EVIDENCE -> HonestStateCard(
                title = "No matching passages",
                body = "Nothing in your notes is close enough to that " +
                    "question to ground an answer, so no answer was generated.",
            )
            AskAnswerStatus.ERROR -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        entry.errorMessage ?: "Something went wrong. Try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(NotaSpacing.md),
                    )
                }
            }
        }
        entry.disclosure?.let { disclosure ->
            Spacer(Modifier.height(NotaSpacing.xs))
            Text(
                disclosure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HonestStateCard(title: String, body: String) {
    OutlinedCard {
        Column(Modifier.padding(NotaSpacing.md)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(NotaSpacing.xs))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceCard(card: AskSourceCard, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(NotaSpacing.sm)) {
            Text(
                card.title.ifBlank { "(untitled)" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                card.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The answer's text with its validated citations as tappable
 * chips — a small numbered circle in the pine colour, flowed
 * inline with the text ([androidx.compose.foundation.layout
 * .FlowRow], the same pattern as Search's tag chips); tapping
 * opens the cited note. Only numbers the parser validated
 * become chips ([citationSegments] decides); anything else
 * stays literal text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerText(entry: AskEntry.Answer, onOpenNote: (String) -> Unit) {
    val segments = remember(entry.id) { citationSegments(entry.text, entry.citations) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            when (segment) {
                is AskTextSegment.Text ->
                    Text(segment.text, style = MaterialTheme.typography.bodyMedium)
                is AskTextSegment.Citation ->
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable { onOpenNote(segment.noteId) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            segment.n.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
            }
        }
    }
}
