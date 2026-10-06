package edts.apm.android.sdk.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import edts.apm.android.sdk.internal.ApmRuntime

/**
 * Drives [SyncEngine] to drain the disk queue, then asks [SyncScheduler] to schedule the next
 * run (MOB-08). WorkManager already guarantees `doWork()` runs off the main thread, satisfying
 * the "no blocking I/O on main thread" invariant without extra dispatching here.
 *
 * `@JvmOverloads` is load-bearing: it emits a real `(Context, WorkerParameters)` constructor, which
 * is the signature WorkManager's **default** reflective factory looks for. That is what lets the
 * SDK schedule work without the host app registering a `WorkerFactory` through
 * `Configuration.Provider` — see [edts.apm.android.sdk.internal.ApmRuntime] for why that matters.
 * The remaining parameters stay injectable so tests never touch the process-wide graph.
 */
class SyncWorker @JvmOverloads constructor(
    context: Context,
    params: WorkerParameters,
    private val engineProvider: () -> SyncEngine? = { ApmRuntime.current?.syncEngine },
    private val intervalProvider: () -> Long = { ApmRuntime.current?.uploadIntervalMs?.invoke() ?: DEFAULT_INTERVAL_MS },
    private val configRefresher: () -> Unit = { ApmRuntime.current?.remoteConfig?.refresh() }
) : Worker(context, params) {

    override fun doWork(): Result {
        // Work can outlive the process that scheduled it: WorkManager may run this in a fresh
        // process before (or without) APM.start() ever being called. Nothing to drain, no
        // reschedule — a later start() will schedule again.
        val engine = engineProvider() ?: return Result.success()

        // Refresh remote config before draining (MOB-20/21). Doing it here — rather than only at
        // startup — is what makes the kill switch remote control rather than a launch-time setting:
        // an app left running for hours would otherwise never see it. Already off the main thread,
        // and a failure just leaves the previous config in force.
        try {
            configRefresher()
        } catch (e: Throwable) {
            // A config fetch failure must never stop the queue from draining.
        }

        // Read after the refresh, not before: this run's refresh may have just changed the interval,
        // and reading it first would apply the new value one full cycle late.
        val uploadIntervalMs = intervalProvider()

        var outcome: SyncOutcome = SyncOutcome.Idle
        var iterations = 0
        do {
            outcome = engine.syncOnce()
            iterations++
        } while (outcome.shouldContinueImmediately && iterations < MAX_ITERATIONS_PER_RUN)

        SyncScheduler.scheduleNext(applicationContext, nextDelayFor(outcome, uploadIntervalMs))
        return Result.success()
    }

    companion object {
        // One disk queue holds at most MAX_EVENTS (5,000) events at MAX_BATCH (200) per attempt
        // -> 25 batches covers a full drain; leaves headroom without ever spinning unbounded.
        private const val MAX_ITERATIONS_PER_RUN = 50

        /** Only used if work runs before the graph exists; `01` §9's documented default. */
        const val DEFAULT_INTERVAL_MS = 30_000L

        /**
         * Pure decision, kept out of doWork() so it's testable without WorkManager/Robolectric.
         * A [SyncOutcome.RetryAfter] wins over the normal cadence — that's the whole point of
         * backoff; every other outcome (including a stale [SyncOutcome.SplitApplied] left over
         * from hitting the iteration cap) just falls back to the configured interval.
         */
        fun nextDelayFor(outcome: SyncOutcome, uploadIntervalMs: Long): Long =
            if (outcome is SyncOutcome.RetryAfter) outcome.delayMs else uploadIntervalMs
    }
}

/**
 * Explicit factory for [SyncWorker].
 *
 * **Not used in production any more** — [SyncWorker]'s two-argument constructor lets WorkManager's
 * default factory build it, which is what keeps integration free of a `Configuration.Provider`
 * requirement on the host app. Kept because it is the only way a test can drive real WorkManager
 * with an injected engine instead of the process-wide [ApmRuntime].
 */
class SyncWorkerFactory(
    private val engineProvider: () -> SyncEngine,
    private val uploadIntervalMs: Long
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? =
        if (workerClassName == SyncWorker::class.java.name) {
            SyncWorker(appContext, workerParameters, { engineProvider() }, { uploadIntervalMs })
        } else {
            null
        }
}
