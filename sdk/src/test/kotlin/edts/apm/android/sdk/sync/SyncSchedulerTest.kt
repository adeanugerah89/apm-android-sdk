package edts.apm.android.sdk.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import edts.apm.android.sdk.core.AppInfo
import edts.apm.android.sdk.core.DeviceInfo
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.IntegrityInfo
import edts.apm.android.sdk.core.SdkHealth
import edts.apm.android.sdk.core.UserIdSource
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * MOB-08 — verifies real WorkManager wiring: enqueueing via [SyncScheduler] actually reaches
 * [SyncWorker] through [SyncWorkerFactory] and runs [SyncEngine] to completion.
 */
@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {

    /**
     * peek() is called exactly once per real syncOnce() invocation regardless of outcome —
     * counting it proves the worker actually ran, without depending on a WorkInfo id that
     * ExistingWorkPolicy.REPLACE may have already pruned by the time we go looking for it
     * (the worker reschedules itself, under the same unique name, before doWork() returns).
     */
    private class EmptyQueue(val peekCalls: AtomicInteger = AtomicInteger(0)) : EventQueue {
        override fun enqueue(event: Event) = true
        override fun peek(maxCount: Int): List<QueuedEvent> {
            peekCalls.incrementAndGet()
            return emptyList()
        }
        override fun delete(ids: List<String>) {}
        override fun stats() = QueueStats(0, 0)
    }

    private lateinit var context: Context
    private lateinit var queue: EmptyQueue

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        queue = EmptyQueue()

        val engine = SyncEngine(
            queue = queue,
            uploader = object : Uploader {
                override fun upload(envelope: edts.apm.android.sdk.core.Envelope) =
                    UploadResult.HttpResponse(202, null)
            },
            health = SdkHealth(),
            contextProvider = {
                EnvelopeStaticContext(
                    sdkName = "apm-android-sdk",
                    sdkVersion = "0.1.0",
                    app = AppInfo("edts.apm.android.sdk", "1.0", "100"),
                    device = DeviceInfo(osVersion = "14", model = "Pixel 9", locale = "id_ID", timezone = "Asia/Jakarta"),
                    integrity = IntegrityInfo(),
                    installId = "install-1",
                    sessionId = "session-1",
                    userId = "user-1",
                    userIdSource = UserIdSource.HOST
                )
            }
        )

        val config = Configuration.Builder()
            .setExecutor(Executors.newSingleThreadExecutor())
            .setWorkerFactory(SyncWorkerFactory({ engine }, uploadIntervalMs = 30_000L))
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test fun `scheduleNext runs SyncWorker through to success and self-reschedules`() {
        SyncScheduler.scheduleNext(context, delayMs = 0L)

        val workManager = WorkManager.getInstance(context)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        val firstRunId = workManager.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK_NAME).get().single().id

        // Initial delay is 0, already met — only the network constraint needs satisfying.
        // That alone is enough for TestScheduler to run the work synchronously on our executor.
        // The worker reschedules itself (same unique name, REPLACE) before doWork() returns, so
        // by the time we look again the original id may already be pruned — don't depend on it.
        driver.setAllConstraintsMet(firstRunId)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("worker must have actually run SyncEngine.syncOnce()", 1, queue.peekCalls.get())

        val allWork = workManager.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK_NAME).get()
        assertTrue(
            "expected a rescheduled run to be enqueued for the next tick",
            allWork.any { it.state == WorkInfo.State.ENQUEUED }
        )
    }

    @Test fun `triggerNow schedules with no delay`() {
        SyncScheduler.triggerNow(context)
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, workInfos.single().state)
    }
}
