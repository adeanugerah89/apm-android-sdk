package edts.apm.android.sdk.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

/**
 * A31 — a remote `upload_interval_s` must take effect on the run that fetched it.
 *
 * The worker used to read the interval first and refresh remote config second, so a value the
 * server had just changed was applied one whole cycle late — for a 30-minute interval, half an
 * hour of the old cadence after the operator thought they had changed it.
 */
@RunWith(RobolectricTestRunner::class)
class SyncWorkerRefreshOrderTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).build()
        )
    }

    private val idleEngine = SyncEngine(
        queue = object : EventQueue {
            override fun enqueue(event: Event) = true
            override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
            override fun delete(ids: List<String>) {}
            override fun stats() = QueueStats(0, 0)
        },
        uploader = object : Uploader {
            override fun upload(envelope: edts.apm.android.sdk.core.Envelope) = UploadResult.HttpResponse(202, null)
        },
        health = SdkHealth(),
        contextProvider = {
            EnvelopeStaticContext(
                sdkName = "apm-android-sdk", sdkVersion = "0.1.0",
                app = AppInfo("edts.apm.android.sdk", "1.0", "100"),
                device = DeviceInfo(osVersion = "14", model = "Pixel 9", locale = "id_ID", timezone = "Asia/Jakarta"),
                integrity = IntegrityInfo(), installId = "i", sessionId = "s", userId = "u",
                userIdSource = UserIdSource.HOST
            )
        }
    )

    @Test fun `the interval is read after the config refresh, not before`() {
        val calls = mutableListOf<String>()
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context, workerClassName: String, workerParameters: WorkerParameters
            ): ListenableWorker = SyncWorker(
                appContext, workerParameters,
                engineProvider = { idleEngine },
                intervalProvider = { calls += "interval"; 60_000L },
                configRefresher = { calls += "refresh" }
            )
        }

        TestListenableWorkerBuilder<SyncWorker>(context).setWorkerFactory(factory).build().startWork().get()

        assertEquals(listOf("refresh", "interval"), calls)
    }
}
