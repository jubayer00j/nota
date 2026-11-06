package app.nota.feature.capture

import app.nota.data.capture.CaptureRecord

/** What the nav shell should do with the head staged capture. */
enum class CaptureDecision {
    /** Wait — the app is locked, or nothing is staged. */
    HOLD,

    /** Drop the record (and its staged files): it belongs to
     *  another account's library and must never be delivered here. */
    RETIRE,

    /** Take the record and create its note now. */
    DELIVER,
}

/**
 * Decides the fate of the head [CaptureRecord] (Phase 5.2) — the
 * [app.nota.feature.editor.PendingInsertResumer] pattern reduced to
 * its essentials. Unlike the insert resumer this is stateless, and
 * deliberately so: delivery REMOVES the record from the store before
 * creating anything (1.x's consume-before-handle rule), so
 * "exactly once" is structural rather than remembered.
 *
 * - NEVER while locked — a capture creates a note, and nothing is
 *   created behind App Lock;
 * - NEVER for another account's record — retired instead;
 * - otherwise DELIVER.
 */
class PendingCaptureResumer {

    fun decide(
        locked: Boolean,
        record: CaptureRecord?,
        currentAccountId: String,
    ): CaptureDecision = when {
        locked || record == null -> CaptureDecision.HOLD
        record.accountId != currentAccountId -> CaptureDecision.RETIRE
        else -> CaptureDecision.DELIVER
    }
}
