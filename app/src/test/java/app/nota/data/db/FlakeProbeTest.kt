package app.nota.data.db

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TEMPORARY diagnostic probe for PR #65 (flake-hardening) — NOT part
 * of the final PR. Answers two questions about the
 * BackupRestoreSemanticsTest SQLITE_BUSY flake empirically:
 *  (a) which journal mode + busy_timeout a NotaDatabase connection
 *      actually runs with under Robolectric;
 *  (b) when a second instance opens the same file while the first
 *      holds a write transaction, does the opener's installLibraryMeta
 *      INSERT wait on the busy handler (and for how long) or fail
 *      immediately (busy-handler bypass)?
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlakeProbeTest {

    @Test
    fun probeJournalModeAndBusyBehaviour() {
        val context = RuntimeEnvironment.getApplication()
        val account = "flake-probe"
        context.deleteDatabase(NotaDatabase.databaseName(account))
        val dbA = NotaDatabase.build(context, account)
        val connA = dbA.openHelper.writableDatabase
        val journalMode = connA.query("PRAGMA journal_mode").use { c ->
            if (c.moveToFirst()) c.getString(0) else "?"
        }
        val busyTimeout = connA.query("PRAGMA busy_timeout").use { c ->
            if (c.moveToFirst()) c.getInt(0) else -1
        }
        println("PROBE journal_mode=$journalMode busy_timeout=$busyTimeout")

        // A takes and HOLDS a write transaction on its connection.
        connA.beginTransaction()
        connA.execSQL(
            "INSERT OR IGNORE INTO `library_meta` (`id`, `notesWriteGeneration`) VALUES (1, 0)",
        )
        println("PROBE A inTransaction=${connA.inTransaction()}")

        val bOutcome = AtomicReference("not-run")
        val bElapsedMs = AtomicLong(-1)
        val bDone = CountDownLatch(1)
        val t = Thread {
            val start = System.nanoTime()
            try {
                val dbB = NotaDatabase.build(context, account)
                dbB.openHelper.writableDatabase
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
        // Hold A's write transaction until B resolves or 9 s pass
        // (busy_timeout is 5000, so a handler-bound B fails ~5 s).
        val finished = bDone.await(9, TimeUnit.SECONDS)
        println(
            "PROBE B finishedWithin9s=$finished outcome=${bOutcome.get()} " +
                "elapsedMs=${bElapsedMs.get()}",
        )
        connA.endTransaction()
        t.join(5_000)
        dbA.close()
        println("PROBE done")
    }
}
