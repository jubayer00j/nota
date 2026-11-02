package app.nota.feature.editor

import app.nota.data.attachments.PendingInsertKind
import app.nota.data.attachments.PendingInsertMode
import app.nota.data.attachments.PendingInsertRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 3.6 (G2): the return-routing decisions and the caret-target
 * resolution, unit-proven (the nav shell's wiring is a thin
 * LaunchedEffect over these). Mirrors the UnlockRestoreResumer test
 * contract from Phase 2.9: never while locked, never for another
 * account, never when already in the note, exactly once per record
 * per unlock.
 */
class PendingInsertResumerTest {

    private fun record(
        id: String = "rec-1",
        accountId: String = "guest",
        noteId: String = "note-1",
        segmentIndex: Int = -1,
        segmentAnchor: String = "",
    ) = PendingInsertRecord(
        id = id,
        accountId = accountId,
        noteId = noteId,
        kind = PendingInsertKind.FILE,
        mode = PendingInsertMode.INSERT_AT_CARET,
        segmentIndex = segmentIndex,
        segmentAnchor = segmentAnchor,
        caretOffset = 0,
        stagedAtMillis = 1L,
    )

    @Test
    fun routesToTheRecordNote_whenUnlockedAndElsewhere() {
        val resumer = PendingInsertResumer()
        assertEquals(
            "note-1",
            resumer.onState(false, record(), "guest", currentNoteId = null),
        )
    }

    @Test
    fun neverRoutesWhileLocked_andRearmsOnUnlock() {
        val resumer = PendingInsertResumer()
        assertNull(resumer.onState(true, record(), "guest", currentNoteId = null))
        assertEquals(
            "note-1",
            resumer.onState(false, record(), "guest", currentNoteId = null),
        )
    }

    @Test
    fun routesExactlyOncePerRecordPerUnlock() {
        val resumer = PendingInsertResumer()
        assertEquals(
            "note-1",
            resumer.onState(false, record(), "guest", currentNoteId = null),
        )
        // The user backed out without a result arriving: no re-yank.
        assertNull(resumer.onState(false, record(), "guest", currentNoteId = null))
        // A NEW record routes immediately.
        assertEquals(
            "note-1",
            resumer.onState(false, record(id = "rec-2"), "guest", currentNoteId = null),
        )
        // A fresh lock cycle re-arms the same record.
        assertNull(resumer.onState(true, record(id = "rec-2"), "guest", null))
        assertEquals(
            "note-1",
            resumer.onState(false, record(id = "rec-2"), "guest", currentNoteId = null),
        )
    }

    @Test
    fun neverRoutesWhenAlreadyInsideThatNote() {
        val resumer = PendingInsertResumer()
        assertNull(
            resumer.onState(false, record(), "guest", currentNoteId = "note-1"),
        )
    }

    @Test
    fun routesWhenInsideADifferentNote() {
        val resumer = PendingInsertResumer()
        assertEquals(
            "note-1",
            resumer.onState(false, record(), "guest", currentNoteId = "note-9"),
        )
    }

    @Test
    fun neverRoutesAnotherAccountsRecord() {
        val resumer = PendingInsertResumer()
        assertNull(
            resumer.onState(
                false,
                record(accountId = "uid-other"),
                "guest",
                currentNoteId = null,
            ),
        )
    }

    @Test
    fun noRecord_noRoute() {
        val resumer = PendingInsertResumer()
        assertNull(resumer.onState(false, null, "guest", currentNoteId = null))
    }

    // --- resolveInsertTargetIndex ------------------------------------

    private val candidates = listOf(
        true to "Alpha opening",
        false to "",
        true to "Bravo middle paragraph",
        false to "",
        true to "Charlie closing",
    )

    @Test
    fun target_indexAndAnchorAgree() {
        val target = resolveInsertTargetIndex(
            record(segmentIndex = 2, segmentAnchor = "Bravo middle paragraph"),
            candidates,
        )
        assertEquals(2, target)
    }

    @Test
    fun target_anchorFindsSegmentThatMoved() {
        // Content inserted above the target since staging: the index
        // is stale, the anchor is the identity.
        val shifted = listOf(
            true to "New paragraph at top",
            true to "Alpha opening",
            false to "",
            true to "Bravo middle paragraph",
        )
        val target = resolveInsertTargetIndex(
            record(segmentIndex = 2, segmentAnchor = "Bravo middle paragraph"),
            shifted,
        )
        assertEquals(3, target)
    }

    @Test
    fun target_anchorDrift_fallsBackToIndex() {
        // The user edited the target paragraph's opening words after
        // staging (anchor mismatch), but nothing moved: the index is
        // still the best information.
        val edited = listOf(
            true to "Alpha opening",
            false to "",
            true to "Bravo EDITED paragraph",
        )
        val target = resolveInsertTargetIndex(
            record(segmentIndex = 2, segmentAnchor = "Bravo middle paragraph"),
            edited,
        )
        assertEquals(2, target)
    }

    @Test
    fun target_indexOnABlock_neverSplitsIt() {
        // The staged index now holds a block (document changed
        // structurally); the anchor finds nothing either — unresolved,
        // the caller falls back to the ordinary caret insert.
        val target = resolveInsertTargetIndex(
            record(segmentIndex = 1, segmentAnchor = "Bravo middle paragraph"),
            candidates,
        )
        assertNull(target)
    }

    @Test
    fun target_importOnlyRecord_isUnresolved() {
        val target = resolveInsertTargetIndex(
            record(segmentIndex = -1, segmentAnchor = ""),
            candidates,
        )
        assertNull(target)
    }
}
