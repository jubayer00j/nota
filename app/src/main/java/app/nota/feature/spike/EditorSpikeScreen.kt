package app.nota.feature.spike

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import app.nota.ui.theme.NotaSpacing
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor

/**
 * PHASE 0 EDITOR SPIKE — throwaway test bench (spec §5), not the real editor.
 * Exercises Compose Rich Editor 1.2.1 against Noma-shaped HTML so the go/no-go
 * verdict in SPIKE-REPORT.md rests on evidence, not the README.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRichTextApi::class)
@Composable
fun EditorSpikeScreen(onBack: () -> Unit = {}) {
    val state = rememberRichTextState()
    var htmlOutput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editor spike — Phase 0") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = NotaSpacing.md),
        ) {
            // Formatting toolbar — one button per formatting verb under test.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                SpikeButton("B") { state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
                SpikeButton("I") { state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
                SpikeButton("U") { state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }
                SpikeButton("S") { state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) }
                SpikeButton("code") { state.toggleCodeSpan() }
                SpikeButton("H1") { state.setHeadingStyle(HeadingStyle.H1) }
                SpikeButton("H2") { state.setHeadingStyle(HeadingStyle.H2) }
                SpikeButton("H3") { state.setHeadingStyle(HeadingStyle.H3) }
                SpikeButton("¶") { state.setHeadingStyle(HeadingStyle.Normal) }
                SpikeButton("• list") { state.toggleUnorderedList() }
                SpikeButton("1. list") { state.toggleOrderedList() }
                SpikeButton("link") { state.addLink(text = "example", url = "https://example.com") }
                SpikeButton("red") { state.toggleSpanStyle(SpanStyle(color = Color(0xFFC0392B))) }
                SpikeButton("hl") { state.toggleSpanStyle(SpanStyle(background = Color(0xFFFFEB3B))) }
            }
            Spacer(Modifier.height(NotaSpacing.sm))
            RichTextEditor(
                state = state,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(NotaSpacing.sm))
            Row {
                TextButton(onClick = { state.setHtml(SpikeFixtures.FULL_NOTE) }) {
                    Text("Load Noma fixture")
                }
                TextButton(onClick = { htmlOutput = state.toHtml() }) {
                    Text("Export HTML ↓")
                }
                TextButton(onClick = { state.clear() }) { Text("Clear") }
            }
            OutlinedTextField(
                value = htmlOutput,
                onValueChange = {},
                readOnly = true,
                label = { Text("toHtml() output") },
                modifier = Modifier.fillMaxWidth().height(NotaSpacing.xxl * 3)
                    .verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(NotaSpacing.sm))
        }
    }
}

@Composable
private fun SpikeButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
