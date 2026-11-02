package app.nota.data.security

import android.util.Log

/**
 * Lock-path timing instrumentation (Phase 2.6, Fix B).
 *
 * Every credential verification on the App Lock and per-note paths
 * logs its wall time (and the PBKDF2 iteration count, which is not
 * secret) under the `NotaLockPerf` logcat tag, so a slow unlock on
 * a device can be attributed — KDF vs storage vs UI — from a bug
 * report instead of guessed at. ONLY timings and iteration counts
 * are ever logged: no secrets, no hashes, no note data.
 *
 * These calls live in the Android layer (manager / repository), not
 * inside the pure-JVM crypto objects, so plain JUnit tests of the
 * crypto never touch `android.util.Log`.
 */
object LockPerf {

    @PublishedApi
    internal const val TAG = "NotaLockPerf"

    /** Runs [block], logs its wall time under [operation], returns its result. */
    inline fun <T> measure(operation: String, iterations: Int, block: () -> T): T {
        val started = System.nanoTime()
        val result = block()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        Log.d(TAG, "$operation: ${elapsedMs}ms (pbkdf2 iterations=$iterations)")
        return result
    }
}
