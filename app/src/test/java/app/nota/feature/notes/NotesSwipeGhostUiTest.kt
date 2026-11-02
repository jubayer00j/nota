package app.nota.feature.notes

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteEntity
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.preferences.SwipeAction
import app.nota.data.repository.LibraryRepository
import app.nota.data.repository.NoteRepository
import app.nota.ui.theme.NotaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Phase 3.5 (D1) — UI-level regression tests for the ghost rows.
 *
 * The device recording showed: swipe-archive one note, swipe-trash
 * another, and the coloured swipe-reveal BACKGROUNDS stayed in the
 * list as empty tappable rows after the snackbars expired — tapping
 * one opened the (archived) note's editor. Phase 2.9 proved the data
 * layer and the snackbar batching exact; the defect lives in the
 * composition layer, so these tests drive the REAL [NotesScreen]
 * with a real ViewModel/repository over real (in-memory) Room.
 *
 * Root mechanism under test: [NotesViewModel] built its uiState ONCE
 * at construction from `repository.observe*()` flows, which bind to
 * the [DatabaseProvider]'s then-current database instance — while
 * every WRITE resolves `provider.current` per call. A row leaves the
 * list ONLY when the observed flow re-emits. Anything that strands
 * the collector on a superseded instance (the provider closes the
 * previous database on [DatabaseProvider.switchTo]; Jubayer's
 * recording session included the guest→sign-in switch with the guest
 * library left un-moved) splits reads from writes: the swipe lands
 * in the live database, the emission the row's removal depends on
 * never arrives, and the dismissed item stays composed forever at
 * its dismissed offset — card parked off-row, reveal background
 * filling the slot, taps still wired to the note.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotesSwipeGhostUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var provider: DatabaseProvider
    private lateinit var repository: NoteRepository
    private lateinit var preferences: PreferencesRepository
    private lateinit var viewModel: NotesViewModel
    private val openedIds = mutableListOf<String>()

    private fun note(id: String, title: String, updatedAt: Long) = NoteEntity(
        id = id,
        title = title,
        contentHtml = "<p>$title body</p>",
        plainText = "$title body",
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        provider = DatabaseProvider.forDatabase(context, db)
        repository = NoteRepository(provider)
        preferences = PreferencesRepository(context)
        viewModel = NotesViewModel(
            repository,
            LibraryRepository(provider),
            AttachmentStore(context, provider),
            provider,
            preferences,
        )
        runBlocking {
            db.noteDao().upsert(note("alpha", "Ghost alpha", updatedAt = 2_000))
            db.noteDao().upsert(note("beta", "Ghost beta", updatedAt = 1_000))
        }
        composeScreen()
        // The first Room emission rides the (paused) main looper; a
        // bare waitForIdle can return before it lands, so poll.
        awaitTrue("initial notes displayed") {
            try {
                composeRule.onNodeWithText("Ghost alpha").assertIsDisplayed()
                composeRule.onNodeWithText("Ghost beta").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }
    }

    private fun composeScreen() {
        composeRule.setContent {
            NotaTheme {
                NotesScreen(
                    onOpenNote = { openedIds += it },
                    viewModel = viewModel,
                )
            }
        }
    }

    /** Pumps the (paused) main looper while waiting for a Db-side fact,
     *  the same idiom the Phase 2.9 fidelity test uses. */
    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            composeRule.waitForIdle()
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for: $description")
    }

    /** Control: in an undisturbed session a swipe-archive must remove
     *  the row once the note is archived. */
    @Test
    fun swipeArchive_healthySession_rowLeavesList() {
        composeRule.onNodeWithText("Ghost alpha").performTouchInput { swipeRight() }
        awaitTrue("alpha archived in db") {
            runBlocking { repository.getById("alpha")?.archived == true }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ghost alpha").assertDoesNotExist()
        composeRule.onNodeWithText("Ghost beta").assertIsDisplayed()
    }

    /** The reproduction: the ViewModel's flows were bound to the guest
     *  database at construction; signing in switches the provider
     *  (closing that instance) and the note ids land in the account
     *  database. The list still displays the frozen snapshot — and a
     *  swipe then writes into the ACCOUNT database while the row's
     *  removal depends on an emission from the CLOSED one. The row
     *  must still leave the list completely, and its freed slot must
     *  not open the archived note. */
    @Test
    fun swipeArchive_afterAccountSwitch_rowLeavesListAndCannotBeTappedOpen() {
        val alpha = runBlocking { repository.getById("alpha") }!!
        val beta = runBlocking { repository.getById("beta") }!!
        assertTrue(provider.switchTo("uid-ghost-test"))
        // The guest→account copy preserves ids (Phase 3a): the whole
        // guest library now also exists in the account database.
        runBlocking {
            provider.current.noteDao().upsert(alpha)
            provider.current.noteDao().upsert(beta)
        }

        composeRule.waitForIdle()
        // Split-brain precondition: the pre-switch list still renders.
        composeRule.onNodeWithText("Ghost alpha").assertIsDisplayed()
        val ghostCentre = composeRule.onNodeWithText("Ghost alpha")
            .fetchSemanticsNode().boundsInRoot.center

        composeRule.onNodeWithText("Ghost alpha").performTouchInput { swipeRight() }
        awaitTrue("alpha archived in the ACCOUNT database") {
            runBlocking { repository.getById("alpha")?.archived == true }
        }
        composeRule.waitForIdle()

        // The ghost assertion: the dismissed row must be GONE from the
        // composition, not parked at its dismissed offset.
        composeRule.onNodeWithText("Ghost alpha").assertDoesNotExist()
        composeRule.onNodeWithText("Ghost beta").assertIsDisplayed()

        // And the freed slot must not open the archived note.
        composeRule.onRoot().performTouchInput { click(ghostCentre) }
        composeRule.waitForIdle()
        assertTrue(
            "tapping the freed slot opened the archived note: $openedIds",
            "alpha" !in openedIds,
        )
    }

    /** Undo must bring the note back as a VISIBLE, tappable row — not
     *  as a restored dismiss state sitting at the dismissed offset. */
    @Test
    fun undoAfterSwipe_noteReturnsVisibleAndTappable() {
        composeRule.onNodeWithText("Ghost alpha").performTouchInput { swipeRight() }
        awaitTrue("alpha archived in db") {
            runBlocking { repository.getById("alpha")?.archived == true }
        }
        awaitTrue("undo action clicked") {
            val undoExists = try {
                composeRule.onNodeWithText("Undo").fetchSemanticsNode()
                true
            } catch (e: AssertionError) {
                false
            }
            if (undoExists) {
                composeRule.onNodeWithText("Undo").performClick()
                true
            } else {
                false
            }
        }
        awaitTrue("alpha un-archived by undo") {
            runBlocking { repository.getById("alpha")?.archived == false }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ghost alpha").assertIsDisplayed()
        composeRule.onNodeWithText("Ghost alpha").performClick()
        assertEquals(listOf("alpha"), openedIds)
    }

    /** Codex round (PR #18): a PIN swipe keeps the note in the list,
     *  so its dismiss state must swing back to Settled — left
     *  dismissed, the row stays parked at the dismissed offset with
     *  gestures disabled (the box only enables them while Settled):
     *  a ghost of the D1 family on the pin path. The row must come
     *  back to rest, fully displayed, and stay swipeable. */
    @Test
    fun pinSwipe_rowSwingsBackAndStaysSwipeable() {
        // Flip the mapping live, after composition: D3 promises the
        // new configuration applies immediately, no restart — and the
        // Activity compose rule allows only one setContent anyway.
        runBlocking { preferences.setSwipeRightAction(SwipeAction.PIN) }
        try {
            awaitTrue("pin mapping live") {
                viewModel.swipePreferences.value.right == SwipeAction.PIN
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Ghost alpha").performTouchInput { swipeRight() }
            awaitTrue("alpha pinned") {
                runBlocking { repository.getById("alpha")?.pinned == true }
            }
            awaitTrue("row back at rest after pin") {
                try {
                    composeRule.onNodeWithText("Ghost alpha").assertIsDisplayed()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }
            composeRule.onNodeWithText("Ghost alpha").performTouchInput { swipeRight() }
            awaitTrue("alpha unpinned by a second swipe") {
                runBlocking { repository.getById("alpha")?.pinned == false }
            }
        } finally {
            runBlocking { preferences.setSwipeRightAction(SwipeAction.ARCHIVE) }
        }
    }
}
