package app.nota.feature.notes

import app.nota.data.preferences.SwipeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3.5 (D3): the swipe-action mapping. Pure logic — the main
 * list family follows the user's per-direction preferences, the
 * context lists (Archived, Trash) keep their natural fixed actions
 * whatever the preferences say, and OFF disables a direction.
 */
class SwipeActionsTest {

    @Test
    fun defaults_matchPre35Behaviour() {
        val prefs = SwipePreferences()
        assertEquals(SwipeAction.ARCHIVE, prefs.right)
        assertEquals(SwipeAction.TRASH, prefs.left)
        assertEquals(
            SwipeKind.ARCHIVE,
            resolveSwipeKind(NotesFilter.All, SwipeDirection.RIGHT, prefs),
        )
        assertEquals(
            SwipeKind.TRASH,
            resolveSwipeKind(NotesFilter.All, SwipeDirection.LEFT, prefs),
        )
    }

    @Test
    fun mainListFamily_followsPreferences() {
        val prefs = SwipePreferences(right = SwipeAction.PIN, left = SwipeAction.ARCHIVE)
        val mainFilters = listOf(
            NotesFilter.All,
            NotesFilter.Favourites,
            NotesFilter.Folder("f1"),
            NotesFilter.Tag("t1"),
            NotesFilter.Smart(app.nota.domain.SmartFolderKey.LAST_7_DAYS),
        )
        for (filter in mainFilters) {
            assertEquals(
                "right for $filter",
                SwipeKind.PIN,
                resolveSwipeKind(filter, SwipeDirection.RIGHT, prefs),
            )
            assertEquals(
                "left for $filter",
                SwipeKind.ARCHIVE,
                resolveSwipeKind(filter, SwipeDirection.LEFT, prefs),
            )
        }
    }

    @Test
    fun everyPreferenceValue_maps() {
        fun kind(action: SwipeAction, direction: SwipeDirection): SwipeKind? {
            val prefs = if (direction == SwipeDirection.RIGHT) {
                SwipePreferences(right = action)
            } else {
                SwipePreferences(left = action)
            }
            return resolveSwipeKind(NotesFilter.All, direction, prefs)
        }
        assertEquals(SwipeKind.ARCHIVE, kind(SwipeAction.ARCHIVE, SwipeDirection.RIGHT))
        assertEquals(SwipeKind.TRASH, kind(SwipeAction.TRASH, SwipeDirection.RIGHT))
        assertEquals(SwipeKind.PIN, kind(SwipeAction.PIN, SwipeDirection.RIGHT))
        assertNull(kind(SwipeAction.OFF, SwipeDirection.RIGHT))
        assertEquals(SwipeKind.ARCHIVE, kind(SwipeAction.ARCHIVE, SwipeDirection.LEFT))
        assertEquals(SwipeKind.TRASH, kind(SwipeAction.TRASH, SwipeDirection.LEFT))
        assertEquals(SwipeKind.PIN, kind(SwipeAction.PIN, SwipeDirection.LEFT))
        assertNull(kind(SwipeAction.OFF, SwipeDirection.LEFT))
    }

    /** PR #52: the confirmation gate covers exactly the destructive
     *  kinds, and only when the user turned the option on. */
    @Test
    fun confirmationGate_coversExactlyTheDestructiveKinds() {
        // Option on: archive / trash / delete-forever ask first.
        assertTrue(requiresSwipeConfirmation(SwipeKind.ARCHIVE, confirmEnabled = true))
        assertTrue(requiresSwipeConfirmation(SwipeKind.TRASH, confirmEnabled = true))
        assertTrue(requiresSwipeConfirmation(SwipeKind.DELETE_FOREVER, confirmEnabled = true))
        // Pin and restore are never gated.
        assertFalse(requiresSwipeConfirmation(SwipeKind.PIN, confirmEnabled = true))
        assertFalse(requiresSwipeConfirmation(SwipeKind.RESTORE, confirmEnabled = true))
        // Option off: nothing is gated — swipes behave as before.
        for (kind in SwipeKind.entries) {
            assertFalse(requiresSwipeConfirmation(kind, confirmEnabled = false))
        }
        // Not yet loaded (null, Codex R1): the destructive kinds are
        // gated conservatively until the saved value arrives; pin and
        // restore still are not.
        assertTrue(requiresSwipeConfirmation(SwipeKind.ARCHIVE, confirmEnabled = null))
        assertTrue(requiresSwipeConfirmation(SwipeKind.TRASH, confirmEnabled = null))
        assertTrue(requiresSwipeConfirmation(SwipeKind.DELETE_FOREVER, confirmEnabled = null))
        assertFalse(requiresSwipeConfirmation(SwipeKind.PIN, confirmEnabled = null))
        assertFalse(requiresSwipeConfirmation(SwipeKind.RESTORE, confirmEnabled = null))
    }

    @Test
    fun trashList_fixedActions_ignorePreferences() {
        val prefs = SwipePreferences(right = SwipeAction.OFF, left = SwipeAction.PIN)
        assertEquals(
            SwipeKind.RESTORE,
            resolveSwipeKind(NotesFilter.Trash, SwipeDirection.RIGHT, prefs),
        )
        assertEquals(
            SwipeKind.DELETE_FOREVER,
            resolveSwipeKind(NotesFilter.Trash, SwipeDirection.LEFT, prefs),
        )
    }

    @Test
    fun archivedList_fixedActions_ignorePreferences() {
        val prefs = SwipePreferences(right = SwipeAction.TRASH, left = SwipeAction.OFF)
        assertEquals(
            SwipeKind.RESTORE,
            resolveSwipeKind(NotesFilter.Archived, SwipeDirection.RIGHT, prefs),
        )
        assertEquals(
            SwipeKind.TRASH,
            resolveSwipeKind(NotesFilter.Archived, SwipeDirection.LEFT, prefs),
        )
    }
}
