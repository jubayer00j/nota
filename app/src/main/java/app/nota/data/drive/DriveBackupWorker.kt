package app.nota.data.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.nota.data.preferences.PreferencesRepository
import java.util.concurrent.TimeUnit
import org.koin.core.context.GlobalContext

/**
 * Scheduled Drive backup (Phase 3b): a thin WorkManager adapter over
 * [DriveRepository.runScheduledBackup] — all policy (signed-in? grant?
 * due? encryption locked?) lives in the repository, so the worker
 * only translates outcomes: done/not-due/paused → success; transient
 * failure → retry with linear backoff. WorkManager's own persistence
 * is the resilience: the schedule survives reboots and process death
 * (the same role 1.x's alarm/worker pair plays).
 */
class DriveBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = runCatching {
            GlobalContext.get().get<DriveRepository>()
        }.getOrNull() ?: return Result.success()
        val prefs = runCatching {
            GlobalContext.get().get<PreferencesRepository>()
        }.getOrNull() ?: return Result.success()
        // Phase 3c: in sync mode the tick runs a sync cycle instead —
        // WorkManager's 15-minute floor is the coarsest sync trigger
        // (app-start, edit-debounce and "Sync now" are the fast ones;
        // the spec's 5-minute cadence is below WorkManager's floor).
        val ok = if (prefs.driveBackupModeNow() == PreferencesRepository.DRIVE_MODE_SYNC) {
            runCatching { repository.runScheduledSync() }.getOrDefault(false)
        } else {
            runCatching { repository.runScheduledBackup() }.getOrDefault(false)
        }
        return if (ok) Result.success() else Result.retry()
    }
}

object DriveBackupScheduler {
    private const val WORK_NAME = "nota-drive-backup"

    /** Reconciles the unique periodic work with the current mode:
     *  scheduled → (re)enqueued at the chosen interval; sync →
     *  enqueued at WorkManager's 15-minute floor (the tick runs sync
     *  cycles — see the worker); off → cancelled. Called at startup
     *  and whenever the setting changes in the UI. */
    fun sync(context: Context, mode: String, intervalHours: Int) {
        val manager = WorkManager.getInstance(context)
        if (mode == PreferencesRepository.DRIVE_MODE_SYNC) {
            val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            return
        }
        if (mode != PreferencesRepository.DRIVE_MODE_SCHEDULED) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(
            intervalHours.toLong().coerceIn(1, 720),
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
