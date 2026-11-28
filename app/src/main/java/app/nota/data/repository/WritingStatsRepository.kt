package app.nota.data.repository

import app.nota.data.db.DatabaseProvider
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reading stats (v0.37.0): loads the active account's stats
 * projection and runs the pure calculator over it. DAOs resolve
 * through [DatabaseProvider] at call time (the Phase 3a rule),
 * so the numbers always describe the library on screen.
 *
 * [zone] pins the zone for tests; when it is null (the
 * production shape — the Koin singleton is built without one),
 * the current system zone is resolved on every [loadStats]
 * call, so a device timezone change while the process is alive
 * is honoured instead of grouping days under the zone the
 * repository happened to be constructed in (fix round 5).
 * Callers may also pass a zone to [loadStats] directly; that
 * wins over both.
 */
class WritingStatsRepository(
    private val provider: DatabaseProvider,
    private val zone: ZoneId? = null,
) {
    suspend fun loadStats(
        zone: ZoneId = this.zone ?: ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): WritingStats {
        val rows = provider.current.noteDao().getStatsRows()
        return WritingStatsCalculator.compute(rows, today, zone)
    }
}
