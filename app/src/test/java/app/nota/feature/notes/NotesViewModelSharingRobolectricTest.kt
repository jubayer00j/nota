package app.nota.feature.notes

import android.content.Context
import androidx.room.Room
import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.LibraryRepository
import app.nota.data.repository.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * The third pin covers the cache's pruning contract (Codex R6):
 * a hard delete evicts the note's entry on the next pass, while
 * passes composed under a filtered view never evict notes that
 * merely sit outside that view.
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

    private val collectScope = CoroutineScope(Dispatchers.Main + Job())

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
    fun selectionToggleSharesTheItemsListInstance() {
        val a = runBlocking { repository.createRichNote("Alpha", "<p>alpha body</p>") }
        runBlocking {
            repository.createRichNote("Beta", "<p>beta body</p>")
            repository.createRichNote("Gamma", "<p>gamma body</p>")
        }

        val collectJob = collectScope.launch { viewModel.uiState.collect {} }
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
    fun oneNoteUpdateRebuildsOnlyThatNotesItem() {
        val ids = runBlocking {
            val a = repository.createRichNote("Alpha", "<p>alpha body</p>")
            val b = repository.createRichNote("Beta", "<p>beta body</p>")
            val c = repository.createRichNote("Gamma", "<p>gamma body</p>")
            Triple(a.id, b.id, c.id)
        }
        val (aId, bId, cId) = ids

        val collectJob = collectScope.launch { viewModel.uiState.collect {} }
        try {
            awaitTrue("three items listed") { viewModel.uiState.value.items.size == 3 }
            val beforeById = viewModel.uiState.value.items.associateBy { it.note.id }

            val storedB = runBlocking { db.noteDao().getById(bId)!! }
            runBlocking {
                repository.updateRichBody(storedB, "Beta", "<p>beta body edited</p>")
            }
            awaitTrue("edit visible in the list") {
                viewModel.uiState.value.items
                    .firstOrNull { it.note.id == bId }
                    ?.note?.excerpt?.contains("edited") == true
            }
            val afterById = viewModel.uiState.value.items.associateBy { it.note.id }

            // Neighbours: identical instances — no rebuild.
            assertSame(beforeById[aId], afterById[aId])
            assertSame(beforeById[cId], afterById[cId])
            // The edited note: a fresh item carrying the new row.
            assertNotSame(beforeById[bId], afterById[bId])
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun hardDeletePrunesTheCachedItemAndFilteredViewsKeepSurvivorsShared() {
        val (a, b) = runBlocking {
            val a = repository.createRichNote("Alpha", "<p>alpha body</p>")
            val b = repository.createRichNote("Beta", "<p>beta body</p>")
            a to b
        }
        // Kept aside so the SAME row can be resurrected after the
        // hard delete, with byte-identical enrichment inputs.
        val entityB = runBlocking { db.noteDao().getById(b.id)!! }

        val collectJob = collectScope.launch { viewModel.uiState.collect {} }
        try {
            awaitTrue("two items listed") { viewModel.uiState.value.items.size == 2 }
            val beforeById = viewModel.uiState.value.items.associateBy { it.note.id }

            // Move to a view whose visible slice contains NEITHER
            // note. Pruning scoped to the visible slice (instead
            // of the full snapshot) would evict both live notes on
            // the passes that run under this filter.
            viewModel.setFilter(NotesFilter.Trash)
            awaitTrue("trash view shown empty") {
                viewModel.uiState.value.filter == NotesFilter.Trash &&
                    viewModel.uiState.value.items.isEmpty()
            }

            runBlocking { repository.deletePermanently(entityB) }
            // Force a full pass over the post-delete snapshot
            // while still under the trash filter, so that pass's
            // prune — and nothing else — decides what survives.
            viewModel.setSort(SortOrder.TitleAsc)
            awaitTrue("post-delete pass ran") {
                viewModel.uiState.value.sort == SortOrder.TitleAsc
            }

            viewModel.setFilter(NotesFilter.All)
            awaitTrue("only Alpha remains") {
                viewModel.uiState.value.items.map { it.note.id } == listOf(a.id)
            }
            // Alpha sat outside every visible slice while the
            // delete pass ran, yet its cached item survived
            // untouched: the prune used the full snapshot
            // (active + archived + trashed), not the filtered view.
            assertSame(beforeById[a.id], viewModel.uiState.value.items.single())

            // Resurrect Beta with the very row that was deleted.
            // Had its cache entry survived the hard delete, the
            // identical input tuple would hit it and hand back the
            // pre-delete instance; a pruned cache must build fresh.
            runBlocking { repository.upsert(entityB) }
            awaitTrue("Beta listed again") { viewModel.uiState.value.items.size == 2 }
            val afterById = viewModel.uiState.value.items.associateBy { it.note.id }
            assertNotSame(beforeById[b.id], afterById[b.id])
            assertSame(beforeById[a.id], afterById[a.id])
        } finally {
            collectJob.cancel()
        }
    }
}
