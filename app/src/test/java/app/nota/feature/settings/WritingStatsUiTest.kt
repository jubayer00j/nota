package app.nota.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import app.nota.data.backup.BackupRepository
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteEntity
import app.nota.data.repository.WritingStatsRepository
import app.nota.ui.theme.NotaTheme
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Reading stats (v0.37.0): the "Your writing" screen at the
 * composition level, over an in-memory database with a pinned
 * zone. Fixture days are relative to the real today (the
 * screen's own clock), so streak and day assertions hold on any
 * weekday: a note today, one yesterday, one forty days ago,
 * and a locked note today.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WritingStatsUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val zone: ZoneId = ZoneId.of("UTC")

    private lateinit var db: NotaDatabase
    private lateinit var repository: WritingStatsRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WritingStatsRepository(
            DatabaseProvider.forDatabase(context, db),
            zone,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun millis(day: LocalDate): Long =
        day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun insert(
        id: String,
        day: LocalDate,
        plainText: String = "",
        locked: Boolean = false,
    ) {
        runBlocking {
            db.noteDao().upsert(
                NoteEntity(
                    id = id,
                    title = id,
                    contentHtml = "<p>$plainText</p>",
                    plainText = plainText,
                    // Fix round 2: fixtures state the stored
                    // count, as a real writer would.
                    wordCount = BackupRepository.wordCount(plainText),
                    createdAt = millis(day),
                    updatedAt = millis(day),
                    locked = locked,
                    lockRecordJson = if (locked) "{}" else null,
                ),
            )
        }
    }

    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (runCatching { condition() }.getOrDefault(false)) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for: $description")
    }

    private fun exists(text: String, substring: Boolean = false): Boolean =
        composeRule.onAllNodesWithText(text, substring = substring)
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun screen_rendersSectionsWithData() {
        val today = LocalDate.now(zone)
        insert("a", today, plainText = "alpha beta gamma")
        insert("b", today.minusDays(1), plainText = "delta epsilon")
        insert("c", today.minusDays(40), plainText = "zeta")
        // Locked notes count as notes and writing days, never
        // as words — and earn the footnote.
        insert("sealed", today, locked = true)

        composeRule.setContent {
            NotaTheme {
                WritingStatsScreen(onBack = {}, statsRepository = repository)
            }
        }

        awaitTrue("stats composed") { exists("Writing days") }
        composeRule.onNodeWithText("Your writing").assertExists()
        // Headline trio: 4 notes, 6 words, 3 writing days.
        // (Existence, not single-node: a bare number can repeat
        // between the headline and a period count.)
        composeRule.onNodeWithText("Notes").assertExists()
        composeRule.onNodeWithText("Words").assertExists()
        assertTrue(exists("4"))
        assertTrue(exists("6"))
        assertTrue(exists("3"))
        // Streak card: today + yesterday is a two-day streak,
        // current and longest alike.
        composeRule.onNodeWithText("Streak").assertExists()
        composeRule.onNodeWithText("Current streak").assertExists()
        composeRule.onNodeWithText("Longest streak").assertExists()
        composeRule.onAllNodesWithText("2 days").assertCountEquals(2)
        // Activity strip + the period rows.
        composeRule.onNodeWithText("Activity").assertExists()
        composeRule.onNodeWithText("Last 12 weeks").assertExists()
        composeRule.onNodeWithText("Started this week").assertExists()
        composeRule.onNodeWithText("Edited this week").assertExists()
        composeRule.onNodeWithText("Started this month").assertExists()
        composeRule.onNodeWithText("Edited this month").assertExists()
        // Footer + the locked footnote.
        composeRule.onNodeWithText("Writing since", substring = true).assertExists()
        composeRule.onNodeWithText("Locked notes aren't counted in word totals.")
            .assertExists()
        // The empty state stays away when there is data.
        composeRule.onNodeWithText(
            "Your writing story starts with your first note.",
        ).assertDoesNotExist()
    }

    /**
     * Fix round 2 (R2-F1): a failed load used to swallow the
     * error and show a blank screen. The closed database below
     * makes the load throw; the screen must show its error
     * state with a retry instead of nothing.
     */
    @Test
    fun screen_failedLoadShowsErrorStateWithRetry() {
        db.close()

        composeRule.setContent {
            NotaTheme {
                WritingStatsScreen(onBack = {}, statsRepository = repository)
            }
        }

        awaitTrue("error state composed") {
            exists("Couldn't load your writing stats.")
        }
        composeRule.onNodeWithText("Your writing").assertExists()
        composeRule.onNodeWithText("Try again").assertExists()
        // No data sections, no empty state — the failure is
        // shown as a failure, not as an empty library.
        composeRule.onNodeWithText("Writing days").assertDoesNotExist()
        composeRule.onNodeWithText(
            "Your writing story starts with your first note.",
        ).assertDoesNotExist()
    }

    @Test
    fun screen_emptyLibraryShowsTheCalmEmptyState() {
        composeRule.setContent {
            NotaTheme {
                WritingStatsScreen(onBack = {}, statsRepository = repository)
            }
        }

        awaitTrue("empty state composed") {
            exists("Your writing story starts with your first note.")
        }
        composeRule.onNodeWithText("Your writing").assertExists()
        // No fake zeroes grid: none of the data sections render.
        composeRule.onNodeWithText("Writing days").assertDoesNotExist()
        composeRule.onNodeWithText("Streak").assertDoesNotExist()
        composeRule.onNodeWithText("Last 12 weeks").assertDoesNotExist()
    }
}
