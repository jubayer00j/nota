package app.nota.data.db

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TEMPORARY diagnostic probe for PR #65 (flake-hardening) — NOT part
 * of the final PR. Round 2: round 1 found journal_mode=wal and an
 * effective busy_timeout of 2500 (not the 5000 installLibraryMeta
 * sets), and the second instance's openHelper-open never returned
 * within 9 s while the first instance held a write transaction.
 * This round opens the second instance through a DAO call (the
 * flake's real path), records where the blocked thread is parked,
 * and varies the hold time (3 s vs 12 s) to measure the effective
 * busy-wait on the DAO connection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlakeProbeTest {

    private fun stackOf(t: Thread): String =
        t.stackTrace.take(18).joinToString("\n") { "    at $it" }

    @Test
    fun probeDaoPathOpenUnderWriteHold() {
        val context = RuntimeEnvironment.getApplication()
        // Scenario 1: A holds a write transaction for 3 s; B opens
        // via a DAO call. Then scenario 2 with a 12 s hold.
        for ((label, holdMs) in listOf("hold3s" to 3_000L, "hold12s" to 12_000L)) {
            val account = "flake-probe-$label"
            context.deleteDatabase(NotaDatabase.databaseName(account))
            val dbA = NotaDatabase.build(context, account)
            val connA = dbA.openHelper.writableDatabase
            connA.beginTransaction()
            connA.execSQL(
                "INSERT OR IGNORE INTO `library_meta` (`id`, `notesWriteGeneration`) VALUES (1, 0)",
            )
            val bOutcome = AtomicReference("not-run")
            val bElapsedMs = AtomicLong(-1)
            val bDone = CountDownLatch(1)
            val t = Thread {
                val start = System.nanoTime()
                try {
                    val dbB = NotaDatabase.build(context, account)
                    runBlocking { dbB.noteDao().getAll() }
                    bElapsedMs.set((System.nanoTime() - start) / 1_000_000)
                    bOutcome.set("opened-ok")
                    dbB.close()
                } catch (e: Exception) {
                    bElapsedMs.set((System.nanoTime() - start) / 1_000_000)
                    bOutcome.set("threw ${e.javaClass.name}: ${e.message}")
                } finally {
                    bDone.countDown()
                }
            }
            t.start()
            val finished = bDone.await(holdMs + 9_000, TimeUnit.MILLISECONDS)
            println(
                "PROBE[$label] B finished=$finished outcome=${bOutcome.get()} " +
                    "elapsedMs=${bElapsedMs.get()}",
            )
            if (!finished) {
                println("PROBE[$label] B stack:\n${stackOf(t)}")
            }
            connA.endTransaction()
            // Give B a chance to complete after release; report again.
            val finishedAfter = bDone.await(6, TimeUnit.SECONDS)
            println(
                "PROBE[$label] after release: finished=$finishedAfter " +
                    "outcome=${bOutcome.get()} elapsedMs=${bElapsedMs.get()}",
            )
            if (!finishedAfter) {
                println("PROBE[$label] B stack after release:\n${stackOf(t)}")
            }
            dbA.close()
        }
        println("PROBE done")
    }
}
