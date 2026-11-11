package app.nota.feature.ask

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nota.data.ai.SemanticIndexStatus
import app.nota.notes.R
import app.nota.ui.theme.LocalNotaDarkTheme
import app.nota.ui.theme.NotaPalette
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
 *
 * Ask Aurora (v0.26.0): a presentation-only reskin of this
 * surface over the approved "Aurora" direction — an aurora
 * backdrop, Nota's own N glyph re-presented as the hero mark,
 * glassy answer cards and a gradient input pill. The engine,
 * the ViewModel contract, the gate states, the refusal copy
 * and the citation wiring are exactly as Phase 4.3 shipped.
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

    Box(Modifier.fillMaxSize().auroraBackdrop()) {
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
}

/**
 * The aurora backdrop: layered radial-gradient washes over the
 * scheme background — pine → teal with a blue and a violet
 * shoulder in dark mode; the same composition as soft mint/pine
 * washes on paper in light mode. Drawn behind the content only;
 * every surface above it keeps its own colour roles.
 */
@Composable
private fun Modifier.auroraBackdrop(): Modifier {
    val dark = LocalNotaDarkTheme.current
    val background = MaterialTheme.colorScheme.background
    return this
        .background(background)
        .drawBehind {
            val w = size.width
            val h = size.height
            if (dark) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NotaPalette.AuroraGreen.copy(alpha = 0.50f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.5f, -h * 0.08f),
                        radius = w * 0.9f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NotaPalette.AuroraBlue.copy(alpha = 0.28f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.82f, h * 0.16f),
                        radius = w * 0.6f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NotaPalette.AuroraViolet.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.08f, h * 0.26f),
                        radius = w * 0.55f,
                    ),
                )
            } else {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NotaPalette.Pine500.copy(alpha = 0.16f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.5f, -h * 0.08f),
                        radius = w * 0.9f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NotaPalette.AuroraBlue.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.82f, h * 0.16f),
                        radius = w * 0.6f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NotaPalette.AuroraViolet.copy(alpha = 0.06f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.08f, h * 0.26f),
                        radius = w * 0.55f,
                    ),
                )
            }
        }
}

/**
 * The hero mark: Nota's own N glyph (the traced path data in
 * R.drawable.ic_nota_glyph — the same geometry as the launcher
 * mark) re-presented for Ask per Jubayer's amendment: the glyph
 * swept with an aurora/cream gradient, riding a glowing aurora
 * disc with a soft halo. Recognisably the same N; clearly a
 * feature treatment, not the app icon pasted in.
 */
@Composable
private fun AskHeroMark(modifier: Modifier = Modifier) {
    val dark = LocalNotaDarkTheme.current
    Box(modifier.size(136.dp), contentAlignment = Alignment.Center) {
        // Halo.
        Box(
            Modifier.size(136.dp).drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            if (dark) {
                                NotaPalette.AuroraGreen.copy(alpha = 0.45f)
                            } else {
                                NotaPalette.Pine300.copy(alpha = 0.55f)
                            },
                            Color.Transparent,
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension / 2f,
                    ),
                )
            },
        )
        // Disc.
        Box(
            Modifier
                .size(92.dp)
                .clip(CircleShape)
                .drawBehind {
                    val brush = if (dark) {
                        Brush.radialGradient(
                            colors = listOf(
                                NotaPalette.AuroraGlowCore,
                                NotaPalette.AuroraDiscMid,
                                NotaPalette.AuroraDiscDeep,
                            ),
                            center = Offset(size.width * 0.34f, size.height * 0.30f),
                            radius = size.maxDimension * 0.85f,
                        )
                    } else {
                        Brush.radialGradient(
                            colors = listOf(
                                NotaPalette.Pine500,
                                NotaPalette.Pine700,
                                NotaPalette.Pine900,
                            ),
                            center = Offset(size.width * 0.34f, size.height * 0.30f),
                            radius = size.maxDimension * 0.85f,
                        )
                    }
                    drawRect(brush = brush)
                },
        )
        // The glyph itself, gradient-swept (kept only where the
        // glyph's own alpha is — BlendMode.SrcIn over the drawn
        // painter).
        Icon(
            painter = painterResource(R.drawable.ic_nota_glyph),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier
                .size(width = 58.dp, height = 50.dp)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                NotaPalette.LogoCream,
                                if (dark) {
                                    NotaPalette.AuroraGlowCore
                                } else {
                                    NotaPalette.Pine100
                                },
                            ),
                        ),
                        blendMode = BlendMode.SrcIn,
                    )
                },
        )
    }
}

/** The three starter questions on the hero — each submits as-is. */
private val ASK_SUGGESTIONS = listOf(
    "Summarise my newest notes",
    "What's due this week?",
    "What did I decide recently?",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskHero(onAsk: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NotaSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AskHeroMark()
        Spacer(Modifier.height(NotaSpacing.md))
        Text(
            "Ask your notes anything.",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(NotaSpacing.sm))
        Text(
            "Answers come from your notes only — and every answer " +
                "shows the note it came from.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(NotaSpacing.lg))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(
                NotaSpacing.sm,
                Alignment.CenterHorizontally,
            ),
            verticalArrangement = Arrangement.spacedBy(NotaSpacing.sm),
        ) {
            ASK_SUGGESTIONS.forEach { question ->
                SuggestionChip(text = question) { onAsk(question) }
            }
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    val dark = LocalNotaDarkTheme.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (dark) {
            Color(0xFF14281E).copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
        },
        border = BorderStroke(
            1.dp,
            if (dark) {
                NotaPalette.AuroraMint.copy(alpha = 0.35f)
            } else {
                NotaPalette.Pine300
            },
        ),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (dark) {
                NotaPalette.Pine100
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(
                horizontal = NotaSpacing.md,
                vertical = NotaSpacing.sm,
            ),
        )
    }
}

/**
 * The glassy card chrome shared by the answer cards and the
 * honest-state cards: a translucent surface with a hairline
 * border, so the aurora backdrop breathes through without the
 * text ever sitting on the raw gradient.
 */
@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dark = LocalNotaDarkTheme.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(
            alpha = if (dark) 0.55f else 0.78f,
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(
                alpha = if (dark) 0.6f else 1f,
            ),
        ),
        modifier = modifier,
    ) {
        content()
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
            if (gate != AskGate.LOADING && gate != AskGate.READY) {
                AskHeroMark()
                Spacer(Modifier.height(NotaSpacing.lg))
            }
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
    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(NotaSpacing.sm))
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@OptIn(ExperimentalLayoutApi::class)
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
    // Scroll-to-bottom fires not only when an item lands but
    // when the layout SETTLES around it (Jules PR #25 P3):
    // the keyboard opening shifts the viewport, and a tall
    // answer/source card can finish laying out after the
    // count changed, either of which could leave the newest
    // content below the fold. The IME's visibility and the
    // last visible item's laid-out size are the settle
    // signals — the size only moves while the tail of the
    // list is on screen, so reading back through history is
    // never fought.
    val imeVisible = WindowInsets.isImeVisible
    val lastVisibleItemSize by remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size ?: 0
        }
    }
    LaunchedEffect(itemCount, imeVisible, lastVisibleItemSize) {
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
        if (entries.isEmpty() && !busy) {
            AskHero(onAsk = onAsk, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(NotaSpacing.md),
            ) {
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
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
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
        }
        AskInputBar(
            input = input,
            onInputChange = { input = it },
            busy = busy,
            onSubmit = {
                onAsk(input)
                input = ""
            },
        )
    }
}

/**
 * The aurora input bar: a pill field with a subtle gradient
 * border (green → blue → violet, from the mock-up) and a round
 * gradient Ask button. The field inside is a plain Material
 * TextField with transparent chrome — it keeps the theme's
 * caret and the same enabled/blank rules as before the reskin.
 */
@Composable
private fun AskInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    busy: Boolean,
    onSubmit: () -> Unit,
) {
    val canAsk = !busy && input.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(NotaSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                )
                .border(
                    1.5.dp,
                    Brush.linearGradient(
                        listOf(
                            NotaPalette.AuroraGreen,
                            NotaPalette.AuroraBlue,
                            NotaPalette.AuroraViolet,
                        ),
                    ),
                    RoundedCornerShape(28.dp),
                )
                .padding(horizontal = NotaSpacing.sm),
        ) {
            TextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = { Text("Ask about your notes…") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
                enabled = !busy,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
        }
        Spacer(Modifier.width(NotaSpacing.sm))
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .then(
                    if (canAsk) {
                        Modifier.background(
                            Brush.linearGradient(
                                listOf(
                                    NotaPalette.AuroraGreen,
                                    NotaPalette.AuroraDeep,
                                ),
                            ),
                        )
                    } else {
                        Modifier.background(
                            MaterialTheme.colorScheme.surfaceVariant,
                        )
                    },
                )
                .clickable(enabled = canAsk) { onSubmit() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.ArrowUpward,
                contentDescription = "Ask",
                tint = if (canAsk) {
                    Color.White
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
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
                GlassCard {
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
                    shape = RoundedCornerShape(16.dp),
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
    GlassCard {
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
    val dark = LocalNotaDarkTheme.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(
            alpha = if (dark) 0.45f else 0.65f,
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(
                alpha = if (dark) 0.6f else 1f,
            ),
        ),
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
 * chips — a small numbered circle (aurora gradient in dark
 * mode, pine in light), flowed inline with the text
 * ([androidx.compose.foundation.layout.FlowRow], the same
 * pattern as Search's tag chips); tapping opens the cited
 * note. Only numbers the parser validated become chips
 * ([citationSegments] decides); anything else stays literal
 * text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerText(entry: AskEntry.Answer, onOpenNote: (String) -> Unit) {
    val dark = LocalNotaDarkTheme.current
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
                            .then(
                                if (dark) {
                                    Modifier.background(
                                        Brush.linearGradient(
                                            listOf(
                                                NotaPalette.AuroraGreen,
                                                NotaPalette.AuroraDeep,
                                            ),
                                        ),
                                    )
                                } else {
                                    Modifier.background(
                                        MaterialTheme.colorScheme.primary,
                                    )
                                },
                            )
                            .clickable { onOpenNote(segment.noteId) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            segment.n.toString(),
                            color = if (dark) {
                                NotaPalette.Pine950
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
            }
        }
    }
}
