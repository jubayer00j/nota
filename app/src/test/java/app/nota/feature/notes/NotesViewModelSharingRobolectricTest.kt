package app.nota.feature.notes

import android.content.Context
import androidx.room.Room
import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.LibraryRepository
import app.nota.data.repository.NoteRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Performance chapter 2 (F5): the notes list's structural
 * sharing pins. A selection toggle must not rebuild row items —
 * the items LIST instance itself survives — and a one-note
 * update must rebuild exactly that note's item, with every
 * neighbour's instance shared (assertSame, not equality: the
 * point is that no work happened, not that the output matches).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotesViewModelSharingRobolectricTest {

    private lateinit var db: NotaDatabase
    private lateinit var repository: NoteRepository
    private lateinit var viewModel: NotesViewModel

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val provider = DatabaseProvider.forDatabase(context, db)
        repository = NoteRepository(provider)
        viewModel = NotesViewModel(
            repository,
            LibraryRepository(provider),
            AttachmentStore(context, provider),
            provider,
            PreferencesRepository(context),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for: $description")
    }

    @Test
    fun selectionToggleSharesTheItemsListInstance() = runBlocking {
        val a = repository.createRichNote("Alpha", "<p>alpha body</p>")
        repository.createRichNote("Beta", "<p>beta body</p>")
        repository.createRichNote("Gamma", "<p>gamma body</p>")

        val collectJob = launch { viewModel.uiState.collect {} }
        try {
            awaitTrue("three items listed") { viewModel.uiState.value.items.size == 3 }
            val before = viewModel.uiState.value

            viewModel.toggleSelect(a.id)
            awaitTrue("selection applied") {
                viewModel.uiState.value.selection == setOf(a.id)
            }
            // THE pin: the toggle changed only the selection —
            // the items list is the identical instance, so no row
            // item was rebuilt and no re-sort ran.
            assertSame(before.items, viewModel.uiState.value.items)
            assertEquals(setOf(a.id), viewModel.uiState.value.selection)
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun oneNoteUpdateRebuildsOnlyThatNotesItem() = runBlocking {
        val a = repository.createRichNote("Alpha", "<p>alpha body</p>")
        val b = repository.createRichNote("Beta", "<p>beta body</p>")
        val c = repository.createRichNote("Gamma", "<p>gamma body</p>")

        val collectJob = launch { viewModel.uiState.collect {} }
        try {
            awaitTrue("three items listed") { viewModel.uiState.value.items.size == 3 }
            val beforeById = viewModel.uiState.value.items.associateBy { it.note.id }

            val storedB = db.noteDao().getById(b.id)!!
            repository.updateRichBody(storedB, "Beta", "<p>beta body edited</p>")
            awaitTrue("edit visible in the list") {
                viewModel.uiState.value.items
                    .firstOrNull { it.note.id == b.id }
                    ?.note?.excerpt?.contains("edited") == true
            }
            val afterById = viewModel.uiState.value.items.associateBy { it.note.id }

            // Neighbours: identical instances — no rebuild.
            assertSame(beforeById[a.id], afterById[a.id])
            assertSame(beforeById[c.id], afterById[c.id])
            // The edited note: a fresh item carrying the new row.
            assertNotSame(beforeById[b.id], afterById[b.id])
        } finally {
            collectJob.cancel()
        }
    }
}
