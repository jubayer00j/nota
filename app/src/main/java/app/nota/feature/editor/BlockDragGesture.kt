package app.nota.feature.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Editor-surface block drag (Phase 3.8 — the drag SESSION).
 *
 * Phases 3.6/3.7 hung the drag gesture on each block and reordered
 * the document on every mid-drag swap: the whole segment list —
 * tables are dozens of text fields — re-composed and re-laid-out
 * under the finger dozens of times per drag, which on Jubayer's
 * J7 Prime rendered as the dragged block trembling and ghosting
 * (0.4.3 device verdict). 3.8 inverts the ownership: ONE gesture
 * lives on the editor surface (the box wrapping the segment list),
 * so the node that starts a gesture is the node that finishes it,
 * however the list underneath re-renders.
 *
 * The protocol has the same shape as the 3.6/3.7 watch, moved up
 * one level:
 *
 *  - Every down is hit-tested by the screen ([hitTest]): only a
 *    down on a draggable block is a drag candidate. Anything else
 *    (text segments — their long press is text selection — title,
 *    meta, attachments, the tail area) is ignored entirely and the
 *    gesture never touches it.
 *  - The candidate press must stay (nearly) still for the platform
 *    long-press timeout — twice the touch slop of drift is allowed
 *    (3.7: a finger planted on a large block card rolls more than
 *    one slop during the window, and the narrower watch aborted
 *    body grabs before they could start). A moving press remains
 *    a scroll, a quick one a tap; a second finger aborts the watch
 *    (pinch territory).
 *  - The watch observes in the INITIAL pass WITHOUT consuming, so
 *    children see the press exactly as before until the claim.
 *    At the claim, the current change is consumed and from then on
 *    EVERY change is consumed in the Initial pass: neither the
 *    list's scroll nor a child's selection drag nor a second
 *    finger's pinch can fight the session. A second finger landing
 *    mid-session is therefore simply ignored for the session's
 *    duration — its events never reach any other handler.
 *
 * While a session is live the screen owns everything visual: the
 * ghost overlay, the placeholder slot, the landing line, the
 * insertion target. This gesture only reports the claim
 * ([onSessionStart], with the original down position), the
 * pointer's vertical position in surface coordinates
 * ([onSessionMove]) and the release/cancel ([onSessionEnd] /
 * [onSessionCancel]). The document itself is never touched here —
 * the screen commits exactly one reorder when the finger lifts.
 */
fun Modifier.blockDragSurface(
    key: Any?,
    hitTest: (Offset) -> Boolean,
    onSessionStart: (downPosition: Offset) -> Unit,
    onSessionMove: (pointerY: Float) -> Unit,
    onSessionEnd: () -> Unit,
    onSessionCancel: () -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        val hit = hitTest(down.position)
        DragProbe.log("DOWN pos=${down.position} hit=$hit")
        if (!hit) return@awaitEachGesture
        var current: PointerInputChange = down
        var aborted = false
        try {
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1) {
                        DragProbe.log("WATCH abort: second finger")
                        aborted = true
                        return@withTimeoutOrNull
                    }
                    val change = event.changes.firstOrNull { it.id == down.id }
                    // A framework cancel surfaces as an up in
                    // this Compose version — either way the press
                    // ended without becoming a drag.
                    if (change == null || change.changedToUp()) {
                        DragProbe.log(
                            "WATCH abort: changeNull=${change == null} " +
                                "up=${change?.changedToUp()}",
                        )
                        aborted = true
                        return@withTimeoutOrNull
                    }
                    if ((change.position - down.position).getDistance() >
                        viewConfiguration.touchSlop * 2f
                    ) {
                        DragProbe.log(
                            "WATCH abort: drift=" +
                                (change.position - down.position).getDistance() +
                                " limit=" + viewConfiguration.touchSlop * 2f,
                        )
                        aborted = true
                        return@withTimeoutOrNull
                    }
                    current = change
                    // Event-clock path: an injected (test) or
                    // otherwise batched event stream may deliver the
                    // long press as timestamps rather than as elapsed
                    // wall time on a still finger.
                    if (change.uptimeMillis - down.uptimeMillis >=
                        viewConfiguration.longPressTimeoutMillis
                    ) {
                        DragProbe.log(
                            "WATCH claim: event clock dt=" +
                                (change.uptimeMillis - down.uptimeMillis) +
                                " timeout=" +
                                viewConfiguration.longPressTimeoutMillis,
                        )
                        return@withTimeoutOrNull
                    }
                }
            }
        } catch (e: CancellationException) {
            DragProbe.log("WATCH cancelled: $e")
            throw e
        }
        // The block returned early only on an abort condition; a
        // timeout (null result) means the finger simply held still
        // for the full long-press window — the session is on.
        DragProbe.log("WATCH exit aborted=$aborted")
        if (aborted) return@awaitEachGesture
        current.consume()
        DragProbe.log("CLAIM pos=${current.position}")
        onSessionStart(down.position)
        onSessionMove(current.position.y)
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                DragProbe.log(
                    "SESSION ev changes=${event.changes.size} " +
                        "trackedPressed=${change?.pressed} " +
                        "trackedUp=${change?.changedToUp()} " +
                        "trackedPos=${change?.position}",
                )
                // Claimed: consume every change in the Initial
                // pass, the tracked pointer's and any other's —
                // a second finger is ignored for the whole
                // session (see the class doc).
                event.changes.forEach { it.consume() }
                if (change == null) continue
                if (change.changedToUp()) {
                    DragProbe.log("SESSION up -> onSessionEnd")
                    // The lift commits. This Compose version
                    // delivers a framework cancel as the same up
                    // change (no isCanceled signal exists), so a
                    // system takeover mid-session also lands here —
                    // the commit is a single atomic reorder, so the
                    // document can never be left half-moved; a
                    // genuine coroutine cancellation (the surface
                    // leaving composition) is the abort path below.
                    onSessionEnd()
                    break
                }
                onSessionMove(change.position.y)
            }
        } catch (e: CancellationException) {
            DragProbe.log("SESSION cancelled: $e")
            onSessionCancel()
            throw e
        }
    }
}
