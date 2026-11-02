package app.nota.feature.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToCanceled
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Long-press-drag for a document block (Phase 3.6, G1).
 *
 * Phase 3.5 shipped drag on a thin 24 dp handle rail only, and
 * Jubayer's verdict was that placing a block precisely was still a
 * fight. G1 makes the WHOLE block the drag surface: the press starts
 * anywhere on the block, and only a press that stays (nearly) still
 * for the platform long-press timeout becomes a drag — a moving
 * press remains a scroll, a quick one a tap, so the gesture never
 * steals the list's scrolling or a child's tap.
 *
 * The watch runs in the INITIAL pointer pass, observing without
 * consuming: children (text fields in tables / task lists / code
 * blocks, the image's tap and pinch handlers) see every event
 * exactly as before until the long press actually fires. At that
 * moment the current change is consumed and every later move is
 * consumed in the Initial pass, so neither the list's scroll nor a
 * child's selection drag can fight the block drag.
 *
 * Two deliberate bail-outs: a second finger (pinch territory — image
 * resizing must never turn into a block drag) and any movement beyond
 * touch slop before the timeout (a scroll, not a press).
 *
 * [onDrag] receives vertical deltas in this modifier's local
 * coordinate space — the block's LAYOUT position never changes while
 * it is dragged (the float is a graphicsLayer translation), so the
 * deltas stay meaningful however far the finger travels.
 */
fun Modifier.longPressBlockDrag(
    key: Any?,
    onDragStart: () -> Unit,
    onDrag: (dragAmountY: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        var current: PointerInputChange = down
        var lastY = down.position.y
        var aborted = false
        try {
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1) {
                        aborted = true
                        return@withTimeoutOrNull
                    }
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || change.changedToUp() || change.changedToCanceled()) {
                        aborted = true
                        return@withTimeoutOrNull
                    }
                    if ((change.position - down.position).getDistance() >
                        viewConfiguration.touchSlop
                    ) {
                        aborted = true
                        return@withTimeoutOrNull
                    }
                    current = change
                    lastY = change.position.y
                    // Event-clock path: an injected (test) or
                    // otherwise batched event stream may deliver the
                    // long press as timestamps rather than as elapsed
                    // wall time on a still finger.
                    if (change.uptimeMillis - down.uptimeMillis >=
                        viewConfiguration.longPressTimeoutMillis
                    ) {
                        return@withTimeoutOrNull
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
        // The block returned early only on an abort condition; a
        // timeout (null result) means the finger simply held still
        // for the full long-press window — the drag is on.
        if (aborted) return@awaitEachGesture
        current.consume()
        onDragStart()
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                when {
                    change.changedToUp() -> {
                        change.consume()
                        onDragEnd()
                        break
                    }
                    change.changedToCanceled() -> {
                        onDragCancel()
                        break
                    }
                    else -> {
                        val dy = change.position.y - lastY
                        lastY = change.position.y
                        if (dy != 0f) {
                            change.consume()
                            onDrag(dy)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            onDragCancel()
            throw e
        }
    }
}

