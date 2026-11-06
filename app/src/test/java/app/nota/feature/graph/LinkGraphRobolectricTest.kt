package app.nota.feature.graph

import androidx.room.Room
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.repository.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Phase 4.4 seams under Robolectric, against an in-memory Room:
 *
 * - The graph ViewModel turns stored [[backlink]] edges into a
 *   positioned map (nodes, edges, finite coordinates), and reacts
 *   when the library changes underneath it.
 * - The visual model's locked-source rule holds even for a stored
 *   edge row whose source was locked outside the repository write
 *   path (defence in depth at the mapping layer).
 * - The editor's new outgoing "Links" data path
 *   (NoteRepository.observeOutgoing) mirrors the shipped
 *   "Linked from…" conventions: newest first, trashed targets
 *   gone, locked targets still listed by title.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkGraphRobolectricTest {

    private lateinit var db: NotaDatabase
    private lateinit var repository: NoteRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NoteRepository(DatabaseProvider.forDatabase(context, db))
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Pumps the main looper until [condition] holds or the deadline passes. */
    private fun settleUntil(deadlineMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + deadlineMs
        while (!condition()) {
            ShadowLooper.idleMainLooper()
            if (System.currentTimeMillis() > end) break
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun graphShowsCreatedLinkWithPositions() = runBlocking {
        val beta = repository.createNote(title = "Beta", plainBody = "the target")
        val alpha = repository.createNote(title = "Alpha", plainBody = "See [[Beta]] for details")

        val viewModel = LinkGraphViewModel(repository)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val collectJob = scope.launch { viewModel.graph.collect {} }
        try {
            settleUntil { viewModel.graph.value.data.nodes.size == 2 }
            val positioned = viewModel.graph.value
            assertEquals(
                setOf(alpha.id, beta.id),
                positioned.data.nodes.map { it.id }.toSet(),
            )
            assertEquals(
                listOf(LinkGraphEdge(alpha.id, beta.id)),
                positioned.data.edges,
            )
            assertEquals(
                positioned.data.nodes.map { it.id }.toSet(),
                positioned.positions.keys,
            )
            assertTrue(
                positioned.positions.values.all { it.x.isFinite() && it.y.isFinite() },
            )
        } finally {
            collectJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun trashingTheTargetEmptiesTheGraph() = runBlocking {
        val beta = repository.createNote(title = "Beta", plainBody = "the target")
        repository.createNote(title = "Alpha", plainBody = "See [[Beta]] for details")

        val viewModel = LinkGraphViewModel(repository)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val collectJob = scope.launch { viewModel.graph.collect {} }
        try {
            settleUntil { viewModel.graph.value.data.nodes.size == 2 }
            assertEquals(2, viewModel.graph.value.data.nodes.size)

            // The stored edge row survives the trash (relink owns
            // source-side rows only); the mapping must hide it.
            repository.trash(beta)
            settleUntil { viewModel.graph.value.isEmpty }
            assertTrue(viewModel.graph.value.isEmpty)
        } finally {
            collectJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun storedEdgeFromALockedSourceIsExcluded() = runBlocking {
        val beta = repository.createNote(title = "Beta", plainBody = "the target")
        val alpha = repository.createNote(title = "Alpha", plainBody = "See [[Beta]] for details")

        val viewModel = LinkGraphViewModel(repository)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val collectJob = scope.launch { viewModel.graph.collect {} }
        try {
            settleUntil { viewModel.graph.value.data.nodes.size == 2 }
            assertEquals(2, viewModel.graph.value.data.nodes.size)

            // Lock the source OUTSIDE the repository write path, so
            // its stored edge row remains: the graph must still
            // refuse to draw it (the shipped panel's DAO rule).
            db.noteDao().upsert(alpha.copy(locked = true))
            settleUntil { viewModel.graph.value.isEmpty }
            assertTrue(viewModel.graph.value.isEmpty)
        } finally {
            collectJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun outgoingListsTargetsNewestFirstAndMirrorsPanelRules() = runBlocking {
        val older = repository.createNote(title = "Older", plainBody = "x")
        val newer = repository.createNote(title = "Newer", plainBody = "x")
        // Pin distinct updatedAt values so the DESC ordering is
        // deterministic regardless of creation-millisecond ties.
        db.noteDao().upsert(older.copy(updatedAt = 1_000L))
        db.noteDao().upsert(newer.copy(updatedAt = 2_000L))
        val source = repository.createNote(
            title = "Source",
            plainBody = "[[Older]] and [[Newer]]",
        )

        val outgoing = repository.observeOutgoing(source.id).first()
        assertEquals(listOf(newer.id, older.id), outgoing.map { it.id })

        // Locked targets stay listed by title — opening one lands
        // on its lock gate, like opening it from the notes list.
        db.noteDao().upsert(newer.copy(updatedAt = 2_000L, locked = true))
        val withLocked = repository.observeOutgoing(source.id).first()
        assertEquals(listOf(newer.id, older.id), withLocked.map { it.id })

        // Trashed targets disappear.
        repository.trash(db.noteDao().getById(newer.id)!!)
        val afterTrash = repository.observeOutgoing(source.id).first()
        assertEquals(listOf(older.id), afterTrash.map { it.id })
    }
}
