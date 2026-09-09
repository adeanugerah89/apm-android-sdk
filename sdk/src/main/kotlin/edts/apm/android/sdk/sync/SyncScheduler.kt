package edts.apm.android.sdk.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules [SyncWorker] runs (MOB-08).
 *
 * WorkManager's `PeriodicWorkRequest` has a hard 15-minute floor, too coarse for the spec's
 * default 30s cadence (`01` §9). Instead this is a self-rescheduling chain of one-off requests:
 * each [SyncWorker] run ends by calling [scheduleNext] again for its own next tick.
 * [ExistingWorkPolicy.REPLACE] on a single unique work name means a manual [triggerNow] call
 * (connectivity recovery, background transition) always collapses onto the earliest pending run
 * rather than stacking duplicate work.
 */
object SyncScheduler {
    const val UNIQUE_WORK_NAME = "edts_apm_sync"

    fun scheduleNext(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** MOB-08: sync sooner than the current schedule — connectivity recovery, background transition. */
    fun triggerNow(context: Context) = scheduleNext(context, delayMs = 0L)
}
