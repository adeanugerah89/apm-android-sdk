package edts.apm.android.sdk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import edts.apm.android.sdk.breadcrumb.BreadcrumbCategory
import edts.apm.android.sdk.internal.ApmRuntime
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Executors

/**
 * A16 — the public surface, and specifically its behaviour when the SDK is **not** running.
 *
 * "An SDK that is off must be indistinguishable from an SDK that is absent" is the whole of
 * CONSTITUTION rule #1 at the API boundary, and it is the case a host app hits first: called
 * before `start()`, or after a `start()` that quietly failed.
 */
@RunWith(RobolectricTestRunner::class)
class ApmPublicApiTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).build()
        )
    }

    @After fun tearDown() {
        ApmRuntime.reset()
        File(app.filesDir, "apm-queue").deleteRecursively()
        File(app.filesDir, "apm-remote-config.json").delete()
    }

    private fun config(ingestUrl: String = "https://ingest.example.com/v1/ingest") = ApmConfig(
        appId = "edts.apm.android.sdk",
        appVersion = "1.0",
        appBuild = "100",
        ingestUrl = ingestUrl,
        appKey = "app-key-123"
    )

    @Test fun `every call before start is a silent no-op, not a crash`() {
        assertFalse(APM.isStarted)

        APM.logError(IllegalStateException("before start"))
        APM.setUser("user-1")
        APM.breadcrumb("hello", BreadcrumbCategory.NAVIGATION)
        assertNull(APM.networkEventListener())
        // No exception is the assertion.
    }

    @Test fun `a refused start leaves every call a no-op`() {
        APM.start(app, config(ingestUrl = "http://ingest.example.com/v1/ingest"))
        APM.awaitStarted(2_000)   // a refused start never becomes ready; just give it a moment

        assertFalse(APM.isStarted)
        APM.logError(IllegalStateException("after failed start"))
        APM.breadcrumb("hello", BreadcrumbCategory.NAVIGATION)
        // No exception is the assertion.
    }

    @Test fun `after a successful start logError reaches the queue`() {
        APM.start(app, config()); APM.awaitStarted(10_000)
        assertTrue(APM.isStarted)

        APM.logError(IllegalStateException("boom"))
        assertTrue(ApmRuntime.current!!.awaitCaptureDrain(5_000))

        assertEquals(1, ApmRuntime.current!!.queue.stats().eventCount)
    }

    @Test fun `setUser reaches the graph and flips user_id_source to host`() {
        APM.start(app, config()); APM.awaitStarted(10_000)
        val identity = ApmRuntime.current!!.userIds
        assertEquals(edts.apm.android.sdk.core.UserIdSource.GENERATED, identity.userIdSource)

        APM.setUser("+628123456789")

        assertEquals("+628123456789", identity.userId)
        assertEquals(edts.apm.android.sdk.core.UserIdSource.HOST, identity.userIdSource)
    }

    @Test fun `breadcrumbs are attached to a later error, oldest first`() {
        APM.start(app, config()); APM.awaitStarted(10_000)

        APM.breadcrumb("opened cart", BreadcrumbCategory.NAVIGATION)
        APM.breadcrumb("tapped pay", BreadcrumbCategory.NAVIGATION)
        APM.logError(IllegalStateException("payment failed"))
        assertTrue(ApmRuntime.current!!.awaitCaptureDrain(5_000))

        val attrs = ApmRuntime.current!!.queue.peek(10).single().event.attrs.toString()
        assertTrue(attrs.contains("opened cart"))
        assertTrue(attrs.contains("tapped pay"))
        assertTrue(attrs.indexOf("opened cart") < attrs.indexOf("tapped pay"))
    }

    @Test fun `networkEventListener is available once started and excludes the ingest host`() {
        APM.start(app, config()); APM.awaitStarted(10_000)

        assertNotNull(APM.networkEventListener())
    }
}
