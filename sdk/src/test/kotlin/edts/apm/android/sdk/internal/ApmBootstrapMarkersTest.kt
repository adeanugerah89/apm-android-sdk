package edts.apm.android.sdk.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import edts.apm.android.sdk.APM
import edts.apm.android.sdk.ApmConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Executors

/**
 * Review finding 7 — the "previous process" markers (session, app version, OS) are written only after a start
 * has fully succeeded. A start that fails part-way, then is retried in the same process, must not read its own
 * markers as the real previous process's.
 */
@RunWith(RobolectricTestRunner::class)
class ApmBootstrapMarkersTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).build())
    }

    @After fun tearDown() {
        ApmRuntime.current?.userIds?.awaitPersisted()
        ApmRuntime.reset()
        NoBackupPrefs.awaitPendingWrites()
        SdkStorage.queueDir(app).deleteRecursively()
        SdkStorage.configCache(app).delete()
        File(SdkStorage.root(app), SdkStorage.STATE_FILE_NAME).delete()
    }

    private fun config(url: String = "https://ingest.example.com/v1/ingest") = ApmConfig(
        appId = "edts.apm.android.sdk", appVersion = "2.0", appBuild = "200", ingestUrl = url, appKey = "k"
    )

    private fun stateText(): String = File(SdkStorage.root(app), SdkStorage.STATE_FILE_NAME).takeIf { it.exists() }?.readText().orEmpty()

    @Test fun `a refused start writes none of this run's markers`() {
        APM.start(app, config("http://ingest.example.com/v1/ingest"))   // cleartext: refused
        APM.awaitStarted(2_000)

        assertFalse(APM.isStarted)
        assertFalse(stateText().contains("apm_session_id"))
        assertFalse(stateText().contains("apm_app_version"))
    }

    @Test fun `a successful start writes them, so the NEXT launch can recover this one`() {
        APM.start(app, config()); APM.awaitStarted(10_000)
        assertTrue(APM.isStarted)

        val deadline = System.currentTimeMillis() + 3_000
        while (!stateText().contains("apm_app_version") && System.currentTimeMillis() < deadline) Thread.sleep(25)
        val state = SdkStorage.openState(app)
        assertEquals("2.0", state.getString("apm_app_version", null))
        assertEquals("200", state.getString("apm_app_build", null))
        assertEquals(ApmRuntime.current!!.sessionManager.sessionId, state.getString("apm_session_id", null))
    }

    /** Makes `WorkManager.getInstance` throw, so `SyncScheduler.scheduleNext` — the last step of start — fails. */
    private fun breakWorkManager() {
        val impl = Class.forName("androidx.work.impl.WorkManagerImpl")
        for (field in listOf("sDelegatedInstance", "sDefaultInstance")) {
            impl.getDeclaredField(field).apply { isAccessible = true }.set(null, null)
        }
    }

    @Test fun `a start that fails at its very last step leaves the previous process's markers untouched`() {
        // What the real previous process left behind.
        SdkStorage.openState(app).edit()
            .putString("apm_session_id", "session-of-the-real-previous-process")
            .putString("apm_app_version", "1.0").putString("apm_app_build", "100").commit()

        breakWorkManager()
        APM.start(app, config()); APM.awaitStarted(3_000)
        assertFalse("the start must have failed for this test to mean anything", APM.isStarted)
        NoBackupPrefs.awaitPendingWrites()

        val state = SdkStorage.openState(app)
        assertEquals("a failed start must not overwrite them", "session-of-the-real-previous-process", state.getString("apm_session_id", null))
        assertEquals("1.0", state.getString("apm_app_version", null))
        assertEquals("100", state.getString("apm_app_build", null))
    }

    @Test fun `a retry after that failure still sees the real previous process, not the failed attempt (review finding 7)`() {
        SdkStorage.openState(app).edit()
            .putString("apm_session_id", "session-of-the-real-previous-process")
            .putString("apm_app_version", "1.0").putString("apm_app_build", "100").commit()
        breakWorkManager()
        APM.start(app, config()); APM.awaitStarted(3_000)
        assertFalse(APM.isStarted)

        // WorkManager recovers (e.g. the host initialised it late) and the host calls start again.
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).build())
        APM.start(app, config()); APM.awaitStarted(10_000)
        assertTrue(APM.isStarted)

        // The retry's recovered termination/crash would be filed under what the retry read as "previous".
        // It must be the real previous process, which only holds if the failed attempt wrote nothing.
        val state = SdkStorage.openState(app)
        val deadline = System.currentTimeMillis() + 3_000
        while (state.getString("apm_app_version", null) != "2.0" && System.currentTimeMillis() < deadline) {
            Thread.sleep(25); NoBackupPrefs.awaitPendingWrites()
        }
        assertEquals("only now, after a successful start, is this run recorded", "2.0", SdkStorage.openState(app).getString("apm_app_version", null))
    }
}
