package app.nota.data.db

import app.nota.data.attachments.AttachmentStore
import app.nota.data.backup.BackupRepository
import app.nota.data.backup.RestoreMode
import app.nota.data.templates.TemplateRepository
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TEMPORARY diagnostic probe for PR #65 (flake-hardening) — NOT part
 * of the final PR. Round 3:
 *  - probeTimedRelease: holder releases its write transaction at a
 *    fixed 3 s (on the holding thread — Android transactions are
 *    thread-bound); does the second instance's DAO-path open then
 *    succeed, and when?
 *  - probeRealActors: the production orphan-sweep actor
 *    (DatabaseProvider.allAttachmentLocalPaths, which opens a
 *    dedicated handle on EVERY account file) hammering in a loop
 *    while a BackupRestoreSemanticsTest-shaped stack repeatedly
 *    creates + restores fresh account databases. Identifies the
 *    in-situ lock holder if SQLITE_BUSY reproduces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlakeProbeTest {

    @Test
    fun probeTimedRelease() {
        val context = RuntimeEnvironment.getApplication()
        val account = "flake-probe-timed"
        context.deleteDatabase(NotaDatabase.databaseName(account))
        val holding = CountDownLatch(1)
        val released = CountDownLatch(1)
        val holder = Thread {
            val dbA = NotaDatabase.build(context, account)
            val connA = dbA.openHelper.writableDatabase
            connA.beginTransaction()
            connA.execSQL(
                "INSERT OR IGNORE INTO `library_meta` (`id`, `notesWriteGeneration`) VALUES (1, 0)",
            )
            holding.countDown()
            Thread.sleep(3_000)
            connA.endTransaction()
            released.countDown()
            dbA.close()
        }
        holder.start()
        check(holding.await(15, TimeUnit.SECONDS)) { "holder never took the lock" }

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
                bOutcome.set(
                    "threw ${e.javaClass.name}: ${e.message}\n" +
                        e.stackTrace.take(14).joinToString("\n") { "    at $it" },
                )
            } finally {
                bDone.countDown()
            }
        }
        t.start()
        val finished = bDone.await(25, TimeUnit.SECONDS)
        println(
            "PROBE[timed] finished=$finished outcome=${bOutcome.get()} " +
                "elapsedMs=${bElapsedMs.get()}",
        )
        released.await(10, TimeUnit.SECONDS)
        holder.join(10_000)
        println("PROBE[timed] done")
    }

    private fun payloadZip(): File {
        fun manifestJson(noteCount: Int): String =
            """{"backupFormatVersion": 2, "ownerId": null, "libraryId": "nota_guest", "nomaVersion": "1.2.0", "createdAt": "2024-10-26T00:00:00.000Z", "noteCount": $noteCount, "folderCount": 0, "tagCount": 0, "attachmentCount": 0, "reminderCount": 0, "deviceId": null}"""
        fun noteJson(id: String, title: String, updatedAt: Long): String =
            """{"id": "$id", "title": "$title", "content": "<p>body</p>", "contentFormat": "tiptap-html", "createdAt": 1000, "updatedAt": $updatedAt, "folderId": null, "tagIds": [], "pinned": false, "favorite": false, "archived": false, "deleted": false, "deletedAt": null, "reminderAt": null, "wordCount": 1}"""
        val notes = listOf(
            noteJson("../../evil-note", "Evil", 1000),
            noteJson("good-note", "Good", 1000),
        )
        val entries = linkedMapOf(
            "manifest.json" to manifestJson(notes.size),
            "folders.json" to "[]",
            "tags.json" to "[]",
            "reminders.json" to "[]",
            "settings.json" to "null",
            "sync.json" to """{"deviceId": null, "syncedAt": "2024-10-26T00:00:00.000Z", "tombstones": []}""",
        )
        for (note in notes) {
            val id = Regex(""""id": "([^"]+)"""").find(note)!!.groupValues[1]
            entries["notes/$id.json"] = note
        }
        val file = File.createTempFile("probe-backup", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun probeRealActors() {
        val context = RuntimeEnvironment.getApplication()
        val sweepProvider = DatabaseProvider(context, NotaDatabase.GUEST_ACCOUNT)
        val stop = AtomicBoolean(false)
        val sweeper = Thread {
            while (!stop.get()) {
                try {
                    runBlocking { sweepProvider.allAttachmentLocalPaths() }
                } catch (e: Exception) {
                    // The production sweep swallows everything too.
                }
            }
        }
        sweeper.start()
        var failures = 0
        for (i in 1..25) {
            val account = "flake-actors-$i"
            context.deleteDatabase(NotaDatabase.databaseName(account))
            var db: NotaDatabase? = null
            try {
                db = NotaDatabase.build(context, account)
                val provider = DatabaseProvider.forDatabase(context, db)
                val store = AttachmentStore(context, provider)
                val repo = BackupRepository(
                    context, provider, TemplateRepository(context), store,
                )
                runBlocking { repo.restore(payloadZip(), null, RestoreMode.REPLACE) }
                val n = runBlocking { db.noteDao().getAll().size }
                println("PROBE[actors] iter $i ok notes=$n")
            } catch (e: Exception) {
                failures++
                println(
                    "PROBE[actors] iter $i FAILED ${e.javaClass.name}: ${e.message}\n" +
                        e.stackTrace.take(12).joinToString("\n") { "    at $it" },
                )
            } finally {
                db?.close()
            }
        }
        stop.set(true)
        sweeper.join(15_000)
        println("PROBE[actors] failures=$failures/25 sweeperAlive=${sweeper.isAlive}")
    }
}
