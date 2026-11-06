package app.nota.feature.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nota.data.ai.AiException
import app.nota.data.ai.AiService
import app.nota.data.ai.AskAnswerResult
import app.nota.data.ai.AskCitation
import app.nota.data.ai.AskExcerpt
import app.nota.data.ai.AskNota
import app.nota.data.ai.SemanticIndexStatus
import app.nota.data.ai.SemanticIndexer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Ask Nota's orchestration (Phase 4.3), following 1.x's view
 * flow: fresh settings at call time (the saved record is the
 * truth, not a render prop), retrieval through the semantic
 * indexer's validated passages, and exactly one generation
 * call through [AiService.ask] — or none at all when
 * retrieval finds nothing to ground on. The thread is
 * memory-only: it lives in this ViewModel and dies with it,
 * exactly as 1.x keeps no Ask history anywhere.
 */
class AskViewModel(
    private val aiService: AiService,
    private val indexer: SemanticIndexer,
    private val accountIdProvider: () -> String,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<AskEntry>>(emptyList())
    val entries: StateFlow<List<AskEntry>> = _entries

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _gate = MutableStateFlow(AskGate.LOADING)
    val gate: StateFlow<AskGate> = _gate

    private val _indexStatus = MutableStateFlow<SemanticIndexStatus?>(null)
    val indexStatus: StateFlow<SemanticIndexStatus?> = _indexStatus

    private val _indexBusy = MutableStateFlow(false)
    val indexBusy: StateFlow<Boolean> = _indexBusy

    private val _indexError = MutableStateFlow<String?>(null)
    val indexError: StateFlow<String?> = _indexError

    /** The indexer's own rebuild progress, passed through so
     *  the gate's Rebuild shows the same honest "n of N" the
     *  Settings section shows. */
    val rebuildProgress: StateFlow<app.nota.data.ai.SemanticRebuildProgress?> =
        indexer.rebuildProgress

    private var nextId = 1

    init {
        refreshGate()
    }

    /** Re-reads the gate inputs: whether a chat key is saved,
     *  and the index's live status. Called on entry to the
     *  tab and after every index action. */
    fun refreshGate() {
        viewModelScope.launch {
            val hasKey = aiService.currentConfig() != null
            val status = try {
                indexer.status(accountIdProvider())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            _indexStatus.value = status
            _gate.value = deriveAskGate(hasKey, status)
        }
    }

    /** The gate's Build action — reconcile is the index's
     *  self-repair: it embeds what's missing and drops what's
     *  foreign or no longer eligible. Never throws. */
    fun buildIndex() {
        if (_indexBusy.value) return
        _indexBusy.value = true
        _indexError.value = null
        viewModelScope.launch {
            try {
                indexer.reconcile(accountIdProvider())
            } finally {
                _indexBusy.value = false
                refreshGate()
            }
        }
    }

    /** The gate's Rebuild action — a full build-then-swap.
     *  A provider failure surfaces as calm copy on the gate;
     *  the previous index keeps serving meanwhile. */
    fun rebuildIndex() {
        if (_indexBusy.value) return
        _indexBusy.value = true
        _indexError.value = null
        viewModelScope.launch {
            try {
                indexer.rebuild(accountIdProvider())
            } catch (e: AiException) {
                _indexError.value = e.displayMessage
            } finally {
                _indexBusy.value = false
                refreshGate()
            }
        }
    }

    fun ask(raw: String) {
        val question = raw.trim()
        // One question in flight at a time — the ViewModel
        // refuses a second submit rather than trusting every
        // caller to disable its button (1.x reducer rule).
        if (question.isEmpty() || _busy.value) return
        val previousTurn = previousTurnOf(_entries.value)
        _entries.value = _entries.value + AskEntry.Question(nextId++, question)
        _busy.value = true
        viewModelScope.launch {
            var disclosure: String? = null
            try {
                val excerpts = indexer.retrieveAskExcerpts(accountIdProvider(), question)
                if (excerpts.isEmpty()) {
                    // Nothing eligible cleared the floor — no
                    // provider call is spent (AiService.ask
                    // would refuse to make one too).
                    appendAnswer(
                        AskAnswerStatus.NO_EVIDENCE, "", emptyList(),
                        emptyList(), null, null,
                    )
                    return@launch
                }
                // The exact excerpts the prompt will carry —
                // AiService.ask rebuilds the same list
                // deterministically from the same inputs, so
                // the disclosure and the source cards describe
                // the real prompt.
                val sent = AskNota.buildAskPrompt(question, excerpts, previousTurn).excerpts
                disclosure = askDisclosureLine(
                    sent,
                    askProviderName(aiService.currentConfig()?.providerId),
                )
                when (val result = aiService.ask(question, excerpts, previousTurn)) {
                    is AskAnswerResult.Answer -> appendAnswer(
                        AskAnswerStatus.ANSWER, result.text, result.citations,
                        sent, disclosure, null,
                    )
                    AskAnswerResult.Refusal -> appendAnswer(
                        AskAnswerStatus.REFUSAL, "", emptyList(),
                        sent, disclosure, null,
                    )
                    AskAnswerResult.NoEvidence -> appendAnswer(
                        AskAnswerStatus.NO_EVIDENCE, "", emptyList(),
                        emptyList(), null, null,
                    )
                }
            } catch (e: AiException) {
                appendAnswer(
                    AskAnswerStatus.ERROR, "", emptyList(),
                    emptyList(), disclosure, e.displayMessage,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                appendAnswer(
                    AskAnswerStatus.ERROR, "", emptyList(),
                    emptyList(), disclosure, "Something went wrong. Try again.",
                )
            } finally {
                _busy.value = false
            }
        }
    }

    /** Clears the thread (and with it the follow-up context —
     *  the previous turn is derived from the thread itself).
     *  Refused while a question is in flight: its answer still
     *  lands in the thread it was asked in. */
    fun clear() {
        if (_busy.value) return
        _entries.value = emptyList()
    }

    private fun appendAnswer(
        status: AskAnswerStatus,
        text: String,
        citations: List<AskCitation>,
        sentExcerpts: List<AskExcerpt>,
        disclosure: String?,
        errorMessage: String?,
    ) {
        _entries.value = _entries.value + AskEntry.Answer(
            id = nextId++,
            status = status,
            text = text,
            citations = citations,
            sentExcerpts = sentExcerpts,
            disclosure = disclosure,
            errorMessage = errorMessage,
        )
    }
}
