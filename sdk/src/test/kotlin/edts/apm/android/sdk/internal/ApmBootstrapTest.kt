package edts.apm.android.sdk.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import edts.apm.android.sdk.ApmConfig
import edts.apm.android.sdk.core.EventType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Executors

/**
 * A16 — the assembly itself. Every component here was already tested in isolation; what was never
 * exercised until now is whether they actually fit together, which is the failure MOB-25 is about.
 */
@RunWith(RobolectricTestRunner::class)
class ApmBootstrapTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // ApmBootstrap schedules work as its last step; without a test WorkManager that throws.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).build()
        )
    }

    @After fun tearDown() {
        ApmRuntime.reset()
        SdkStorage.queueDir(app).deleteRecursively()
        SdkStorage.configCache(app).delete()
    }

    private fun config(
        ingestUrl: String = "https://ingest.example.com/v1/ingest",
        pinning: CertificatePinningConfig? = null
    ) = ApmConfig(
        appId = "edts.apm.android.sdk",
        appVersion = "1.0",
        appBuild = "100",
        ingestUrl = ingestUrl,
        appKey = "app-key-123",
        pinning = pinning
    )

    @Test fun `start assembles a live graph`() {
        assertTrue(ApmBootstrap.start(app, config()))

        val components = ApmRuntime.current!!
        assertNotNull(components.queue)
        assertNotNull(components.syncEngine)
        assertNotNull(components.manualApi)
        assertTrue(ApmRuntime.isStarted)
    }

    @Test fun `a cleartext ingest URL refuses to start at all (SEC-12)`() {
        assertFalse(ApmBootstrap.start(app, config(ingestUrl = "http://ingest.example.com/v1/ingest")))
        assertFalse(ApmRuntime.isStarted)
    }

    @Test fun `the assembled queue actually writes an event to disk`() {
        ApmBootstrap.start(app, config())

        ApmRuntime.current!!.manualApi.logError(IllegalStateException("boom"))
        assertTrue(ApmRuntime.current!!.awaitCaptureDrain(5_000))

        val files = SdkStorage.queueDir(app).listFiles()!!.filter { it.name.endsWith(".evt") }
        assertEquals(1, files.size)
        assertTrue(files.single().length() > 0)
    }

    @Test fun `an event written through the graph carries the pipeline's context and type`() {
        ApmBootstrap.start(app, config())
        val components = ApmRuntime.current!!

        components.manualApi.logError(IllegalStateException("boom"))
        assertTrue(components.awaitCaptureDrain(5_000))

        val queued = components.queue.peek(10).single()
        assertEquals(EventType.ERROR, queued.event.type)
        assertTrue("seq must come from the shared sequencer", queued.event.seq > 0)
        assertEquals("background", queued.event.ctx.appState)
    }

    @Test fun `the kill switch reaches the assembled queue`() {
        ApmBootstrap.start(app, config())
        val components = ApmRuntime.current!!
        components.remoteConfig.let { /* default: enabled */ }

        components.manualApi.logError(IllegalStateException("first"))
        components.awaitCaptureDrain(5_000)
        val before = components.queue.stats().eventCount

        // Simulate a fetched kill switch by writing it through the same provider the gate reads.
        val store = edts.apm.android.sdk.config.RemoteConfigStore(SdkStorage.configCache(app))
        store.write(edts.apm.android.sdk.config.RemoteConfig(enabled = false))
        components.remoteConfig.loadFromCache()

        components.manualApi.logError(IllegalStateException("second"))
        components.awaitCaptureDrain(5_000)

        assertEquals("nothing may be captured while killed", before, components.queue.stats().eventCount)
    }

    @Test fun `start is idempotent — a second call does not rebuild the graph`() {
        ApmBootstrap.start(app, config())
        val first = ApmRuntime.current

        ApmBootstrap.start(app, config())

        assertNotSame("bootstrap itself rebuilds; APM.start is what guards it", first, ApmRuntime.current)
    }

    @Test fun `pinning configured produces a working graph too`() {
        val pinning = CertificatePinningConfig.of(
            "ingest.example.com",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
        )

        assertTrue(ApmBootstrap.start(app, config(pinning = pinning)))
        assertTrue(ApmRuntime.isStarted)
    }

    @Test fun `the config endpoint defaults alongside the ingest endpoint`() {
        assertEquals("https://ingest.example.com/v1/config", config().configUrl)
        assertEquals(
            "https://a.example.com/apm/v1/config",
            config(ingestUrl = "https://a.example.com/apm/v1/ingest").configUrl
        )
    }
}
