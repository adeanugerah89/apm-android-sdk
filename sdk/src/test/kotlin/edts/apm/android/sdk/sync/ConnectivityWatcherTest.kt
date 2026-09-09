package edts.apm.android.sdk.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executors

/**
 * Pre-existing A5 component that had no test coverage yet — added alongside A7's breadcrumb
 * connectivity source, which uses the same Robolectric NetworkCallback-inspection pattern.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectivityWatcherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        // WorkManager must be initialized before SyncScheduler.triggerNow() can enqueue work.
        val config = Configuration.Builder()
            .setExecutor(Executors.newSingleThreadExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun lastRegisteredCallback(): ConnectivityManager.NetworkCallback {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return shadowOf(cm).networkCallbacks.last()
    }

    @Test fun `onAvailable triggers a sync via SyncScheduler`() {
        ConnectivityWatcher(context).start()

        lastRegisteredCallback().onAvailable(mockk<Network>())

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, workInfos.single().state)
    }

    @Test fun `start is idempotent and stop never crashes even without starting`() {
        val watcher = ConnectivityWatcher(context)
        watcher.start()
        watcher.start()
        watcher.stop()
        watcher.stop()

        ConnectivityWatcher(context).stop()  // stop() before start() must not throw
    }
}
