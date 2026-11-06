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
 *
 * Two PR #25 review rounds hardened the thread's edges:
 * - The thread is KEYED to the active account. It can outlive
 *   an account switch (the nav graph's saved state keeps the
 *   Ask destination's ViewModel), so an identity change clears
 *   it — the previous account's questions, answers and source
 *   cards never stay on screen, and the previous-turn context
 *   (derived from the thread) can never leak into the next
 *   account's prompt. An answer still in flight when the
 *   switch lands is dropped, never appended.
 * - A generated answer is REVALIDATED before it renders:
 *   citations whose note stopped being quotable during the
 *   generation call are stripped, and an answer left with no
 *   valid citation at all degrades to the refusal state.
 */
class AskViewModel(
    private val aiService: AiService,
    private val indexer: SemanticIndexer,
    private val accountIdFlow: StateFlow<String>,
) : ViewModel() {

    /** The active account, read fresh at every call — the
     *  same discipline as the data layer's DAO resolution. */
    private val accountId: String get() = accountIdFlow.value

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

    /** The account the current thread belongs to. Compared
     *  against every [accountIdFlow] emission after the
     *  initial one: a change means the thread on screen
     *  belongs to a different library and is cleared (see the
     *  class doc). */
    private var threadAccountId: String = accountIdFlow.value

    init {
        viewModelScope.launch {
            accountIdFlow.collect { active ->
                if (active != threadAccountId) {
                    threadAccountId = active
                    _entries.value = emptyList()
                }
            }
        }
        refreshGate()
    }

    /** Re-reads the gate inputs: whether a chat key is saved,
     *  and the index's live status. Called on entry to the
     *  tab and after every index action. */
    fun refreshGate() {
        viewModelScope.launch {
            val hasKey = aiService.currentConfig() != null
            val status = try {
                indexer.status(accountId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            _indexStatus.value = status
            _gate.value = deriveAskGate(hasKey, status)
        }
    }

    /** The gate's Build action — the USER-INITIATED build:
     *  it embeds what's missing and drops what's foreign or no
     *  longer eligible, and a provider failure surfaces as
     *  calm error copy on the gate (mirroring [rebuildIndex])
     *  instead of the Build button silently re-appearing as
     *  if nothing had happened (Codex PR #25 P2). Background
     *  maintenance keeps the indexer's swallow-by-design
     *  reconcile; this path deliberately does not use it. */
    fun buildIndex() {
        if (_indexBusy.value) return
        _indexBusy.value = true
        _indexError.value = null
        viewModelScope.launch {
            try {
                indexer.buildIndex(accountId)
            } catch (e: AiException) {
                _indexError.value = e.displayMessage
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
                indexer.rebuild(accountId)
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
        // The account this exchange belongs to, pinned at ask
        // time: if the active identity changes while it is in
        // flight, the thread is cleared by the init collector
        // and this exchange's outcome must never land in the
        // new account's thread.
        val askedAccount = accountId
        val previousTurn = previousTurnOf(_entries.value)
        _entries.value = _entries.value + AskEntry.Question(nextId++, question)
        _busy.value = true
        viewModelScope.launch {
            var disclosure: String? = null
            try {
                val excerpts = indexer.retrieveAskExcerpts(askedAccount, question)
                if (accountId != askedAccount) return@launch
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
                    is AskAnswerResult.Answer -> {
                        if (accountId != askedAccount) return@launch
                        // Post-generation revalidation (Jules
                        // PR #25 P1): the cited notes were valid
                        // when retrieved, but the generation call
                        // took seconds — a note locked, archived,
                        // trashed, deleted or edited in that
                        // window must not render as quoted.
                        // Citations to now-invalid notes are
                        // stripped (markers included, surviving
                        // numbers unchanged); an answer left with
                        // no valid citation at all degrades to
                        // the refusal, the parser's own rule.
                        val validNoteIds = indexer.revalidateAskNotes(
                            askedAccount,
                            result.citations.map { it.noteId }.toSet(),
                        )
                        if (accountId != askedAccount) return@launch
                        val kept = result.citations.filter { it.noteId in validNoteIds }
                        when {
                            kept.isEmpty() -> appendAnswer(
                                AskAnswerStatus.REFUSAL, "", emptyList(),
                                sent, disclosure, null,
                            )
                            kept.size == result.citations.size -> appendAnswer(
                                AskAnswerStatus.ANSWER, result.text, result.citations,
                                sent, disclosure, null,
                            )
                            else -> {
                                val removedNumbers =
                                    result.citations.map { it.n }.toSet() -
                                        kept.map { it.n }.toSet()
                                appendAnswer(
                                    AskAnswerStatus.ANSWER,
                                    stripCitationMarkers(result.text, removedNumbers),
                                    kept, sent, disclosure, null,
                                )
                            }
                        }
                    }
                    AskAnswerResult.Refusal -> {
                        if (accountId != askedAccount) return@launch
                        appendAnswer(
                            AskAnswerStatus.REFUSAL, "", emptyList(),
                            sent, disclosure, null,
                        )
                    }
                    AskAnswerResult.NoEvidence -> {
                        if (accountId != askedAccount) return@launch
                        appendAnswer(
                            AskAnswerStatus.NO_EVIDENCE, "", emptyList(),
                            emptyList(), null, null,
                        )
                    }
                }
            } catch (e: AiException) {
                if (accountId != askedAccount) return@launch
                appendAnswer(
                    AskAnswerStatus.ERROR, "", emptyList(),
                    emptyList(), disclosure, e.displayMessage,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (accountId != askedAccount) return@launch
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
