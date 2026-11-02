package app.nota.data.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guest→account offer rule (Phase 3a): offer exactly when the
 *  guest library has notes and this account has never answered. */
class GuestMovePolicyTest {

    @Test
    fun offersWhenGuestHasNotesAndNoOutcomeRecorded() {
        assertTrue(GuestMovePolicy.shouldOffer(guestNoteCount = 3, recordedOutcome = null))
        assertTrue(GuestMovePolicy.shouldOffer(guestNoteCount = 1, recordedOutcome = null))
    }

    @Test
    fun neverOffersForAnEmptyGuestLibrary() {
        assertFalse(GuestMovePolicy.shouldOffer(guestNoteCount = 0, recordedOutcome = null))
    }

    @Test
    fun neverReoffersAfterAnOutcome() {
        assertFalse(
            GuestMovePolicy.shouldOffer(3, GuestMovePolicy.OUTCOME_COPIED),
        )
        assertFalse(
            GuestMovePolicy.shouldOffer(3, GuestMovePolicy.OUTCOME_DECLINED),
        )
    }
}
