package app.nota.data.repository

import app.nota.data.db.DatabaseProvider
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reading stats (v0.37.0): loads the active account's stats
 * projection and runs the pure calculator over it. DAOs resolve
 * through [DatabaseProvider] at call time (the Phase 3a rule),
 * so the numbers always describe the library on screen. [zone]
 * is the device zone in production; tests pin it (and "today",
 * via [loadStats]'s parameter) for determinism.
 */
class WritingStatsRepository(
    private val provider: DatabaseProvider,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun loadStats(
        today: LocalDate = LocalDate.now(zone),
        zone: ZoneId = this.zone,
    ): WritingStats {
        val rows = provider.current.noteDao().getStatsRows()
        return WritingStatsCalculator.compute(rows, today, zone)
    }
}
