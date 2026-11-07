package app.nota.feature.notes

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import app.nota.data.repository.LibraryRepository
import app.nota.data.repository.NoteRepository
import app.nota.ui.theme.NotaTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Fix round (Codex, PR #32) — regression for the swipe-undo race the
 * Phase 6.1 lightweight list rows opened.
 *
 * 6.1's swipe ops fetch the pre-op entity asynchronously
 * ([NotesViewModel] swipeOp: getById → snapshot → write) while
 * [NotesScreen.swipeWithUndo] offers Undo in parallel. If the user
 * taps Undo before the read lands, the snackbar resolves against an
 * EMPTY batch: the resolution closes the window, the late snapshot
 * is dropped by the window guard — but the write itself used to land
 * anyway, archiving the note after Undo appeared to succeed.
 *
 * This test makes the read deterministically slow by holding Room's
 * query executor behind a latch (the stand-in for a by-id read stuck
 * behind sync/backup load): the fetch the swipe fires CANNOT
 * complete until the test releases it, so Undo always resolves
 * first. The note must come through untouched and its row must be
 * handed back to the list. On the pre-fix code the write lands after
 * the release: the note ends archived and the row never returns, so
 * the "row handed back" wait times out and the test fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SwipeUndoRaceUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** A [java.util.concurrent.Executor] that, once [hold] is called,
     *  parks every submitted task behind a latch until [release].
     *  Tasks submitted while no gate is armed run immediately. The
     *  generous latch timeout only exists so a broken test cannot
     *  wedge the worker JVM forever. */
    private class GatedExecutor : Executor {
        private val delegate = Executors.newCachedThreadPool()

        @Volatile
        private var gate: CountDownLatch? = null

        fun hold() {
            gate = CountDownLatch(1)
        }

        fun release() {
            val g = gate
            gate = null
            g?.countDown()
        }

        override fun execute(command: Runnable) {
            val g = gate
            delegate.execute {
                g?.await(60, TimeUnit.SECONDS)
                command.run()
            }
        }
    }

    private lateinit var provider: DatabaseProvider
    private lateinit var repository: NoteRepository
    private lateinit var viewModel: NotesViewModel
    private lateinit var queryGate: GatedExecutor

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
        queryGate = GatedExecutor()
        val db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(queryGate)
            .build()
        provider = DatabaseProvider.forDatabase(context, db)
        repository = NoteRepository(provider)
        viewModel = NotesViewModel(
            repository,
            LibraryRepository(provider),
            AttachmentStore(context, provider),
            provider,
            PreferencesRepository(context),
        )
        runBlocking {
            db.noteDao().upsert(note("alpha", "Race alpha", updatedAt = 2_000))
            db.noteDao().upsert(note("beta", "Race beta", updatedAt = 1_000))
        }
        composeRule.setContent {
            NotaTheme {
                NotesScreen(
                    onOpenNote = {},
                    viewModel = viewModel,
                )
            }
        }
        awaitTrue("initial notes displayed") {
            try {
                composeRule.onNodeWithText("Race alpha").assertIsDisplayed()
                composeRule.onNodeWithText("Race beta").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }
    }

    /** Pumps the (paused) main looper while waiting for a fact — the
     *  same idiom [NotesSwipeGhostUiTest] uses. */
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

    @Test
    fun undoTappedBeforeSwipeFetchLands_writeIsVetoedAndRowReturns() {
        // Shut the gate: from here the swipe's by-id fetch is stuck
        // behind the latch. (The snackbar is main-thread work and is
        // unaffected; nothing else needs the query executor until the
        // fetch is released.)
        queryGate.hold()

        composeRule.onNodeWithText("Race alpha").performTouchInput { swipeRight() }

        // Undo is offered while the fetch is still in flight.
        awaitTrue("undo action offered while fetch held") {
            try {
                composeRule.onNodeWithText("Undo").fetchSemanticsNode()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithText("Undo").performClick()

        // Pump the main looper so the resolution block actually runs:
        // the batch is empty, the window closes. Pre-fix, this is the
        // moment Undo has "succeeded" — vacuously.
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()

        // Let the fetch land. The sink must now veto the write and
        // hand the row back.
        queryGate.release()
        awaitTrue("alpha row handed back after the veto") {
            try {
                composeRule.onNodeWithText("Race alpha").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        val alpha = runBlocking { repository.getById("alpha") }
        assertNotNull("note vanished after an honoured Undo", alpha)
        assertFalse(
            "note archived even though Undo resolved before the swipe fetch landed",
            alpha!!.archived,
        )
        assertFalse(
            "note trashed even though Undo resolved before the swipe fetch landed",
            alpha.trashedAt != null,
        )
        composeRule.onNodeWithText("Race beta").assertIsDisplayed()
    }
}
