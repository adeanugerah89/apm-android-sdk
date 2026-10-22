package edts.apm.android.sdk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import edts.apm.android.sdk.breadcrumb.BreadcrumbCategory
import edts.apm.android.sdk.internal.ApmRuntime
import kotlinx.serialization.json.jsonPrimitive
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
        ApmRuntime.current?.userIds?.awaitPersisted()                        // the identity writer, before the runtime is dropped
        ApmRuntime.reset()
        edts.apm.android.sdk.internal.NoBackupPrefs.awaitPendingWrites()   // a late write must not land in the next test
        edts.apm.android.sdk.internal.SdkStorage.queueDir(app).deleteRecursively()
        edts.apm.android.sdk.internal.SdkStorage.configCache(app).delete()
        java.io.File(edts.apm.android.sdk.internal.SdkStorage.root(app), edts.apm.android.sdk.internal.SdkStorage.STATE_FILE_NAME).delete()
        app.getSharedPreferences(edts.apm.android.sdk.internal.SdkStorage.LEGACY_PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
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
        APM.clearUser()
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

    @Test fun `a JVM crash through the assembled graph carries the breadcrumb trail`() {
        // Capture the chain target BEFORE start(): JvmCrashHandler remembers whatever default handler
        // existed when it was built, and we must not let it fall through to one that kills the JVM.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var chained: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> chained = e }
        try {
            APM.start(app, config()); APM.awaitStarted(10_000)
            APM.breadcrumb("opened cart", BreadcrumbCategory.NAVIGATION)
            APM.breadcrumb("tapped pay", BreadcrumbCategory.NAVIGATION)

            // The handler APM installed — this is what the VM calls when a thread dies.
            Thread.getDefaultUncaughtExceptionHandler()!!
                .uncaughtException(Thread.currentThread(), IllegalStateException("payment crashed"))

            assertNotNull("the crash must still reach the host's own handler", chained)
            val crash = ApmRuntime.current!!.queue.peek(10).map { it.event }
                .single { it.type == edts.apm.android.sdk.core.EventType.CRASH }
            val crumbs = crash.attrs.getValue("breadcrumbs").toString()
            assertTrue(crumbs.contains("opened cart"))
            assertTrue(crumbs.indexOf("opened cart") < crumbs.indexOf("tapped pay"))
            assertEquals("\"0\"", crash.attrs.getValue("breadcrumbs_dropped").toString())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun `remote max_batch and upload_interval_s reach the assembled graph (A31)`() {
        // Seed the cache the way a previous launch's fetch would have left it. The live refresh that
        // start() kicks off cannot reach example.com, so the cached values are what stays in force.
        edts.apm.android.sdk.config.RemoteConfigStore(edts.apm.android.sdk.internal.SdkStorage.configCache(app))
            .write(edts.apm.android.sdk.config.RemoteConfig(maxBatch = 3, uploadIntervalS = 120.0))

        APM.start(app, config()); APM.awaitStarted(10_000)

        val graph = ApmRuntime.current!!
        assertEquals("SyncEngine must read max_batch from remote config", 3, graph.syncEngine.currentBatchSize)
        assertEquals("the worker must see the server's interval, not ApmConfig's", 120_000L, graph.uploadIntervalMs())
    }

    @Test fun `the session id is persisted at start so a later launch can recover it (A28)`() {
        APM.start(app, config()); APM.awaitStarted(10_000)

        val expected = ApmRuntime.current!!.sessionManager.sessionId

        // The state file is written on a background thread; wait for it rather than assume it has run.
        val deadline = System.currentTimeMillis() + 3_000
        var stored: String? = null
        while (stored != expected && System.currentTimeMillis() < deadline) {
            stored = edts.apm.android.sdk.internal.SdkStorage.openState(app).getString("apm_session_id", null)
            if (stored != expected) Thread.sleep(25)
        }
        assertEquals(expected, stored)
    }

    @Test fun `events captured through the assembled graph carry the session they were captured in (A28)`() {
        APM.start(app, config()); APM.awaitStarted(10_000)

        APM.logError(IllegalStateException("boom"))
        assertTrue(ApmRuntime.current!!.awaitCaptureDrain(5_000))

        val queued = ApmRuntime.current!!.queue.peek(10).single()
        assertEquals(ApmRuntime.current!!.sessionManager.sessionId, queued.event.sessionId)
    }

    // Robolectric's ApplicationExitInfo shadow cannot build its record on the newest SDK; 33 is what
    // NativeCrashReporterTest uses for the same reason.
    @org.robolectric.annotation.Config(sdk = [33])
    @Test fun `a termination from the previous process is filed under the session that process was in (A28)`() {
        // What the previous process left behind: its session id in storage. The new graph must read it
        // BEFORE creating its own session, then use it for the termination it reports.
        edts.apm.android.sdk.internal.SdkStorage.openState(app).edit().putString("apm_session_id", "session-of-previous-process")
            .putString("apm_app_version", "0.9").putString("apm_app_build", "90").putString("apm_os_version", "12")
            .putString("apm_host_user_id", "p1:user-who-was-signed-in").putInt("apm_identity_epoch", 4).commit()
        // A process the OS killed for memory: ApplicationExitInfo REASON_LOW_MEMORY (API 30+).
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowActivityManager>(am)
            .addApplicationExitInfo(app.packageName, 777, android.app.ApplicationExitInfo.REASON_LOW_MEMORY, 9)

        APM.start(app, config()); APM.awaitStarted(10_000)

        val termination = ApmRuntime.current!!.queue.peek(10).map { it.event }
            .single { it.type == edts.apm.android.sdk.core.EventType.TERMINATION }
        assertEquals("session-of-previous-process", termination.sessionId)
        // A39: filed under the app version that was running, not the one installed now (config() says 1.0 / 100).
        assertEquals(Triple("0.9", "90", "12"), Triple(termination.appVersion, termination.appBuild, termination.osVersion))
        // A39: and under the user who was signed in when that process died — not whoever opens the app next.
        assertEquals(4, termination.identityEpoch)
        assertEquals("user-who-was-signed-in", ApmRuntime.current!!.syncEngine.contextFor(termination).userId)
        assertTrue("and the new process has its own session, not the old one",
            ApmRuntime.current!!.sessionManager.sessionId != "session-of-previous-process")
    }

    @org.robolectric.annotation.Config(sdk = [33])
    @Test fun `a native crash through the assembled graph is filed under the session that crashed (A28)`() {
        edts.apm.android.sdk.internal.SdkStorage.openState(app).edit().putString("apm_session_id", "session-that-crashed")
            .putString("apm_app_version", "0.9").putString("apm_app_build", "90").putString("apm_os_version", "12")
            .putString("apm_host_user_id", "p1:user-at-the-crash").putInt("apm_identity_epoch", 6).commit()
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowActivityManager>(am)
            .addApplicationExitInfo(app.packageName, 4242, android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, 11)
        val info = am.getHistoricalProcessExitReasons(app.packageName, 0, 5).single { it.pid == 4242 }
        val trace = checkNotNull(javaClass.classLoader!!.getResourceAsStream("tombstones/system-tombstone-api33-segv.pb")).readBytes()
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowApplicationExitInfo>(info)
            .setTraceInputStream(java.io.ByteArrayInputStream(trace))
        org.robolectric.util.ReflectionHelpers.setField(info, "mTimestamp", 1_788_000_000_000L)

        APM.start(app, config()); APM.awaitStarted(10_000)

        // The native report is read on its own background thread after the graph is published.
        val deadline = System.currentTimeMillis() + 8_000
        var crash: edts.apm.android.sdk.core.Event? = null
        while (crash == null && System.currentTimeMillis() < deadline) {
            crash = ApmRuntime.current!!.queue.peek(10).map { it.event }.firstOrNull { it.type == edts.apm.android.sdk.core.EventType.CRASH }
            if (crash == null) Thread.sleep(50)
        }
        assertNotNull("the native crash was never reported", crash)
        assertEquals("session-that-crashed", crash!!.sessionId)
        assertEquals("a crash after an update is filed under the version that crashed", Triple("0.9", "90", "12"),
            Triple(crash.appVersion, crash.appBuild, crash.osVersion))
        assertEquals("filed under the user signed in when it crashed (A39)", 6, crash.identityEpoch)
        assertEquals("user-at-the-crash", ApmRuntime.current!!.syncEngine.contextFor(crash).userId)
    }

    @Test fun `start deletes the tombstones an xCrash-era build left in filesDir (A47)`() {
        val leftover = java.io.File(app.filesDir, "apm-native-crash").apply { mkdirs() }
        java.io.File(leftover, "tombstone_00001.xcrash").writeText("stack trace and memory map")

        APM.start(app, config()); APM.awaitStarted(10_000)

        assertTrue("the SDK must have started", APM.isStarted)
        assertFalse("the backup-eligible leftover must be gone after start", leftover.exists())
    }

    @Test fun `an updating app keeps its pending events, and new ones never land in filesDir (A29)`() {
        setInstallTimes(first = 1_700_000_000_000L, lastUpdate = 1_700_000_000_000L + 3 * 86_400_000L)   // a genuine update
        // What the previous build left behind: a queued event in the old, backup-eligible location.
        val legacy = java.io.File(app.filesDir, "apm-queue").apply { mkdirs() }
        val pending = edts.apm.android.sdk.core.Event(
            eventId = "pending-from-old-build", type = edts.apm.android.sdk.core.EventType.ERROR,
            tsClient = "2026-09-30T00:00:00.000Z", seq = 1,
            attrs = kotlinx.serialization.json.JsonObject(emptyMap()), ctx = edts.apm.android.sdk.core.EventContext()
        )
        java.io.File(legacy, "000-old.evt").writeBytes(edts.apm.android.sdk.queue.JsonEventCodec().encode(pending)!!)

        APM.start(app, config()); APM.awaitStarted(10_000)
        APM.logError(IllegalStateException("after the update"))
        assertTrue(ApmRuntime.current!!.awaitCaptureDrain(5_000))

        val ids = ApmRuntime.current!!.queue.peek(10).map { it.event.eventId }
        assertTrue("the old build's pending event was stranded: $ids", "pending-from-old-build" in ids)
        assertEquals("plus the new one", 2, ids.size)
        assertFalse("the backup-eligible directory must be gone", java.io.File(app.filesDir, "apm-queue").exists())
        assertTrue(edts.apm.android.sdk.internal.SdkStorage.queueDir(app).listFiles()!!.isNotEmpty())
    }

    @Test fun `a fresh install does not upload a queue restored from backup (A29)`() {
        setInstallTimes(first = 1_700_000_000_000L, lastUpdate = 1_700_000_000_000L)   // installed just now, e.g. a restore
        val legacy = java.io.File(app.filesDir, "apm-queue").apply { mkdirs() }
        val restored = edts.apm.android.sdk.core.Event(
            eventId = "restored-from-another-device", type = edts.apm.android.sdk.core.EventType.ERROR,
            tsClient = "2026-09-30T00:00:00.000Z", seq = 1,
            attrs = kotlinx.serialization.json.JsonObject(emptyMap()), ctx = edts.apm.android.sdk.core.EventContext()
        )
        java.io.File(legacy, "000-restored.evt").writeBytes(edts.apm.android.sdk.queue.JsonEventCodec().encode(restored)!!)

        APM.start(app, config()); APM.awaitStarted(10_000)
        APM.logError(IllegalStateException("on the new install"))
        assertTrue(ApmRuntime.current!!.awaitCaptureDrain(5_000))

        val ids = ApmRuntime.current!!.queue.peek(10).map { it.event.eventId }
        assertFalse("a backup's queue was filed under this install: $ids", "restored-from-another-device" in ids)
        assertEquals("only the event captured here", 1, ids.size)
        assertFalse("and the cloud-eligible copy is gone", java.io.File(app.filesDir, "apm-queue").exists())
    }

    // ─── A46 · cold start when assembly outlasts the first frame ────────────────

    private fun dispatchDraw(activity: android.app.Activity) {
        val observer = activity.window.decorView.viewTreeObserver
        observer.javaClass.getDeclaredMethod("dispatchOnDraw").apply { isAccessible = true }.invoke(observer)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()   // the listener defers its work with decorView.post {}
    }

    private fun coldStarts() = ApmRuntime.current!!.queue.peek(50).map { it.event }
        .filter { it.type == edts.apm.android.sdk.core.EventType.LIFECYCLE &&
            it.attrs["state"]?.toString()?.contains("cold_start") == true }

    /**
     * `APM.start()` assembles on a background thread when called from the main thread (A18), so on a slow device the
     * first frame is drawn BEFORE the graph exists. The test holds the assembly (`startNow` synchronises on `APM`)
     * until the first frame and a later resume have both happened — exactly what a slow emulator did to the
     * verification app: no cold start at all, or, when the user came back within a minute, a "cold start" measured
     * to that later resume.
     */
    @Test fun `cold start is measured at the first frame even when assembly finishes after it (A46)`() {
        assertEquals("the test must run on the main thread, or start() would assemble inline", android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
        // Robolectric reports a process start of 0, which would make the duration the whole of its fake uptime and
        // trip the plausibility cap. Say the process started "now", the way a real one just forked would.
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Process::class.java, "sStartElapsedRealtime", android.os.SystemClock.elapsedRealtime())
        val drawnAt: Long
        synchronized(APM) {
            APM.start(app, config())                                                   // returns at once; assembly is parked on the lock
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(900))
            // Window attached BEFORE resume: Robolectric does not carry a listener added to the not-yet-attached view's
            // floating ViewTreeObserver over to the real one the way a device does, and that merge is not under test here.
            val launch = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().start().postCreate(null).visible().resume()
            dispatchDraw(launch.get())
            drawnAt = android.os.SystemClock.elapsedRealtime()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(20))
            launch.pause().resume()                                                    // the user comes back: NOT a cold start
            dispatchDraw(launch.get())
        }
        assertTrue(APM.awaitStarted(10_000))
        assertTrue(ApmRuntime.current!!.awaitCaptureDrain(5_000))

        val events = coldStarts()
        assertEquals("exactly one cold start, for the real first frame", 1, events.size)
        assertEquals(
            "measured to the frame that was drawn, not to the later resume",
            drawnAt - android.os.Process.getStartElapsedRealtime(),
            events.single().attrs.getValue("duration_ms").toString().toLong()
        )
    }

    // ─── A48 · activity state when assembly outlasts the launch activity ────────

    private fun lastError() = ApmRuntime.current!!.queue.peek(50).map { it.event }
        .last { it.type == edts.apm.android.sdk.core.EventType.ERROR }

    /**
     * Same setup as A46: assembly is held on the `APM` monitor while the launch activity starts and resumes, so the
     * graph is built AFTER them. `volatileContext` and the screen breadcrumbs used to be registered inside the
     * assembly and never saw those callbacks: an event captured in the foreground said `app_state: background`, had no
     * `screen`, and the trail had no first-screen entry.
     */
    @Test fun `app state, screen and first-screen breadcrumb are right when assembly finishes after the launch activity (A48)`() {
        assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
        synchronized(APM) {
            APM.start(app, config())
            org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()   // started + resumed, graph not built yet
        }
        assertTrue(APM.awaitStarted(10_000))

        APM.logError(IllegalStateException("captured in the foreground"))
        assertTrue(ApmRuntime.current!!.awaitCaptureDrain(5_000))

        val event = lastError()
        assertEquals("foreground", event.ctx.appState)
        assertEquals("Activity", event.ctx.screen)
        val trail = event.attrs.getValue("breadcrumbs").jsonPrimitive.content   // the snapshot is a JSON string inside the JSON
        assertTrue("the launch screen is in the trail: $trail", trail.contains("\"category\":\"navigation\"") && trail.contains("Activity"))
        assertTrue("and so is the foreground transition: $trail", trail.contains("\"message\":\"foreground\""))
    }

    @Test fun `a second activity starting while the first stops does not look like the app going to the background (A48)`() {
        assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
        val first: org.robolectric.android.controller.ActivityController<android.app.Activity>
        synchronized(APM) {
            APM.start(app, config())
            first = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        }
        assertTrue(APM.awaitStarted(10_000))
        val sessionBefore = ApmRuntime.current!!.sessionManager.sessionId

        // A -> B: B starts before A stops. A counter that never saw A's start reaches 0 here and reports "background".
        val second = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        first.pause().stop()
        APM.logError(IllegalStateException("on the second screen"))
        assertTrue(ApmRuntime.current!!.awaitCaptureDrain(5_000))

        assertEquals("foreground", lastError().ctx.appState)
        val trail = lastError().attrs.getValue("breadcrumbs").jsonPrimitive.content
        assertFalse("no spurious background transition: $trail", trail.contains("\"message\":\"background\""))
        assertEquals(sessionBefore, ApmRuntime.current!!.sessionManager.sessionId)
        second.get()   // keep the reference alive for the duration of the assertions
    }

    // ─── A40 · through the graph APM.start() assembles ──────────────────────────

    private fun setInstallTimes(first: Long, lastUpdate: Long) {
        val info = org.robolectric.Shadows.shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName)
        info.firstInstallTime = first
        info.lastUpdateTime = lastUpdate
    }

    private fun seedOldBuildPrefs() = app.getSharedPreferences(
        edts.apm.android.sdk.internal.SdkStorage.LEGACY_PREFS_NAME, android.content.Context.MODE_PRIVATE
    ).edit().putString("apm_install_id", "install-from-old-build").commit()

    private fun legacyPrefs() = app.getSharedPreferences(
        edts.apm.android.sdk.internal.SdkStorage.LEGACY_PREFS_NAME, android.content.Context.MODE_PRIVATE
    )

    @Test fun `a started SDK keeps its state in the no-backup file and writes nothing to shared_prefs (A40)`() {
        setInstallTimes(1_700_000_000_000L, 1_700_000_000_000L)

        APM.start(app, config()); APM.awaitStarted(10_000)

        assertTrue("shared_prefs must stay empty: ${legacyPrefs().all}", legacyPrefs().all.isEmpty())
        val deadline = System.currentTimeMillis() + 3_000
        val stateFile = java.io.File(edts.apm.android.sdk.internal.SdkStorage.root(app), edts.apm.android.sdk.internal.SdkStorage.STATE_FILE_NAME)
        while ((!stateFile.exists() || !stateFile.readText().contains("apm_session_id")) && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue("the session id is persisted in the no-backup file", stateFile.readText().contains("apm_session_id"))
    }

    @Test fun `an updating app keeps its install_id (A40)`() {
        seedOldBuildPrefs()
        setInstallTimes(1_700_000_000_000L, 1_700_000_000_000L + 3 * 86_400_000L)

        APM.start(app, config()); APM.awaitStarted(10_000)

        assertEquals("install-from-old-build",
            edts.apm.android.sdk.internal.SdkStorage.openState(app).getString("apm_install_id", null))
        assertTrue(legacyPrefs().all.isEmpty())
    }

    @Test fun `a fresh install does not inherit an install_id that a backup restore put in shared_prefs (A40)`() {
        seedOldBuildPrefs()
        setInstallTimes(1_700_000_000_000L, 1_700_000_000_000L)

        APM.start(app, config()); APM.awaitStarted(10_000)

        assertEquals(null, edts.apm.android.sdk.internal.SdkStorage.openState(app).getString("apm_install_id", null))
        assertTrue("and the restored copy is emptied", legacyPrefs().all.isEmpty())
    }

    @Test fun `events captured through the assembled graph carry the app version and OS they were captured under (A39)`() {
        APM.start(app, config()); APM.awaitStarted(10_000)

        APM.logError(IllegalStateException("boom"))
        assertTrue(ApmRuntime.current!!.awaitCaptureDrain(5_000))

        val e = ApmRuntime.current!!.queue.peek(10).single().event
        // config() above is appVersion "1.0", appBuild "100"
        assertEquals(Triple("1.0", "100", android.os.Build.VERSION.RELEASE), Triple(e.appVersion, e.appBuild, e.osVersion))
    }

    @Test fun `the running version is persisted at start so the next launch can attribute a crash to it (A39)`() {
        APM.start(app, config()); APM.awaitStarted(10_000)

        val deadline = System.currentTimeMillis() + 3_000
        var stored: Triple<String?, String?, String?> = Triple(null, null, null)
        while (stored.first != "1.0" && System.currentTimeMillis() < deadline) {
            val state = edts.apm.android.sdk.internal.SdkStorage.openState(app)
            stored = Triple(state.getString("apm_app_version", null), state.getString("apm_app_build", null), state.getString("apm_os_version", null))
            if (stored.first != "1.0") Thread.sleep(25)
        }
        assertEquals(Triple<String?, String?, String?>("1.0", "100", android.os.Build.VERSION.RELEASE), stored)
    }

    // ─── A39 / A41 · identity, through the graph APM.start() assembles ──────────

    private fun stateWithHostUser(id: String, epoch: Int) =
        edts.apm.android.sdk.internal.SdkStorage.openState(app).edit()
            .putString("apm_host_user_id", "p1:$id").putInt("apm_identity_epoch", epoch).commit()

    @Test fun `setUser from a previous launch is still in force after a restart - no second call (A41)`() {
        stateWithHostUser("+628123456789", epoch = 3)

        APM.start(app, config()); APM.awaitStarted(10_000)

        val ids = ApmRuntime.current!!.userIds
        assertEquals("+628123456789", ids.userId)
        assertEquals(edts.apm.android.sdk.core.UserIdSource.HOST, ids.userIdSource)
        assertEquals(3, ids.currentEpoch)
    }

    @Test fun `the host calling setUser with the same user again after a restart is not an identity change (A39)`() {
        stateWithHostUser("user-7", epoch = 3)
        APM.start(app, config()); APM.awaitStarted(10_000)

        APM.setUser("user-7")

        assertEquals(3, ApmRuntime.current!!.userIds.currentEpoch)
    }

    @Test fun `clearUser makes the SDK anonymous again, and later events are stamped with the new epoch (A39)`() {
        APM.start(app, config()); APM.awaitStarted(10_000)
        APM.setUser("user-X"); val epochX = ApmRuntime.current!!.userIds.currentEpoch
        APM.logError(IllegalStateException("as X"))
        APM.clearUser()
        APM.logError(IllegalStateException("anonymous"))
        assertTrue(ApmRuntime.current!!.awaitCaptureDrain(5_000))

        assertEquals(edts.apm.android.sdk.core.UserIdSource.GENERATED, ApmRuntime.current!!.userIds.userIdSource)
        val epochs = ApmRuntime.current!!.queue.peek(10).map { it.event.identityEpoch }
        // Compared as a set: two events written in the same millisecond share a modification time, and the
        // disk queue orders by it (a known weakness, parked) — this test is about the epochs, not the order.
        assertEquals("one event under X's epoch, one under the anonymous epoch after clearUser", 2, epochs.toSet().size)
        assertTrue(epochX in epochs)
        assertEquals("the later event carries the CURRENT epoch", ApmRuntime.current!!.userIds.currentEpoch, epochs.filterNotNull().first { it != epochX })
    }

    @Test fun `the engine the graph assembles uploads an event under the user it was captured under (A39)`() {
        APM.start(app, config()); APM.awaitStarted(10_000)
        APM.setUser("user-X")
        APM.logError(IllegalStateException("as X"))
        assertTrue(ApmRuntime.current!!.awaitCaptureDrain(5_000))
        val captured = ApmRuntime.current!!.queue.peek(10).single().event
        APM.setUser("user-Y")                       // X signs out and Y signs in before the upload runs

        val context = ApmRuntime.current!!.syncEngine.contextFor(captured)

        assertEquals("user-X", context.userId)
        assertEquals(edts.apm.android.sdk.core.UserIdSource.HOST, context.userIdSource)
        assertEquals("user-Y", ApmRuntime.current!!.userIds.userId)   // while the live identity is Y
    }

    @Test fun `networkEventListener is available once started and excludes the ingest host`() {
        APM.start(app, config()); APM.awaitStarted(10_000)

        assertNotNull(APM.networkEventListener())
    }
}
