package app.nota.feature.notes

import app.nota.data.preferences.SwipeAction

/**
 * Phase 3.5 (D3): the user's per-direction swipe choices for the main
 * notes list, as one value. Defaults are the pre-3.5 behaviour.
 */
data class SwipePreferences(
    val right: SwipeAction = SwipeAction.ARCHIVE,
    val left: SwipeAction = SwipeAction.TRASH,
)

/** The concrete behaviour a committed swipe performs. */
enum class SwipeKind { ARCHIVE, TRASH, RESTORE, PIN, DELETE_FOREVER }

/**
 * PR #52: whether a committed swipe of [kind] must ask for
 * confirmation before it runs. Only the destructive kinds are
 * gated — archiving, trashing, and deleting forever — and only
 * when the user has turned the Settings → Notes list option on.
 * PIN and RESTORE are never gated; with the option off, nothing
 * is gated and swipes behave exactly as before.
 *
 * [confirmEnabled] is null while the saved value is still loading
 * (the ViewModel's state starts unloaded): unloaded counts as ON
 * for the destructive kinds, so a swipe in the startup window can
 * never bypass a confirmation the user asked for (Codex R1).
 */
fun requiresSwipeConfirmation(kind: SwipeKind, confirmEnabled: Boolean?): Boolean =
    confirmEnabled != false && when (kind) {
        SwipeKind.ARCHIVE, SwipeKind.TRASH, SwipeKind.DELETE_FOREVER -> true
        SwipeKind.RESTORE, SwipeKind.PIN -> false
    }

/** Swipe direction in reading terms: RIGHT = start-to-end. */
enum class SwipeDirection { RIGHT, LEFT }

/** Settings-facing label for a [SwipeAction]. */
fun SwipeAction.label(): String = when (this) {
    SwipeAction.ARCHIVE -> "Archive"
    SwipeAction.TRASH -> "Move to trash"
    SwipeAction.PIN -> "Pin or unpin"
    SwipeAction.OFF -> "Off"
}

/**
 * Resolves a committed swipe to its behaviour (Phase 3.5, D3).
 *
 * The context lists keep their natural fixed actions regardless of
 * the user's choices — Trash restores right / deletes forever left,
 * Archived restores right / trashes left. Every other list (All,
 * folders, tags, smart folders, Favourites) is the "main notes list"
 * family and follows [prefs]. A direction set to
 * [SwipeAction.OFF] resolves to null: that direction is disabled.
 */
fun resolveSwipeKind(
    filter: NotesFilter,
    direction: SwipeDirection,
    prefs: SwipePreferences,
): SwipeKind? = when (filter) {
    NotesFilter.Trash -> when (direction) {
        SwipeDirection.RIGHT -> SwipeKind.RESTORE
        SwipeDirection.LEFT -> SwipeKind.DELETE_FOREVER
    }
    NotesFilter.Archived -> when (direction) {
        SwipeDirection.RIGHT -> SwipeKind.RESTORE
        SwipeDirection.LEFT -> SwipeKind.TRASH
    }
    else -> when (if (direction == SwipeDirection.RIGHT) prefs.right else prefs.left) {
        SwipeAction.ARCHIVE -> SwipeKind.ARCHIVE
        SwipeAction.TRASH -> SwipeKind.TRASH
        SwipeAction.PIN -> SwipeKind.PIN
        SwipeAction.OFF -> null
    }
}
