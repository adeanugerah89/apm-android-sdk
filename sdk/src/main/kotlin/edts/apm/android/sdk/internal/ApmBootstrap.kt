package edts.apm.android.sdk.internal

import android.app.Application
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import edts.apm.android.sdk.ApmConfig
import edts.apm.android.sdk.BuildConfig
import edts.apm.android.sdk.breadcrumb.BreadcrumbRingBuffer
import edts.apm.android.sdk.breadcrumb.ConnectivityBreadcrumbSource
import edts.apm.android.sdk.breadcrumb.LifecycleBreadcrumbSource
import edts.apm.android.sdk.breadcrumb.ScreenTrackingLifecycleCallbacks
import edts.apm.android.sdk.config.RemoteConfig
import edts.apm.android.sdk.config.RemoteConfigFetcher
import edts.apm.android.sdk.config.RemoteConfigProvider
import edts.apm.android.sdk.config.RemoteConfigStore
import edts.apm.android.sdk.core.AppStamp
import edts.apm.android.sdk.core.DeviceContextBuilder
import edts.apm.android.sdk.core.EventSequencer
import edts.apm.android.sdk.core.InstallIdProvider
import edts.apm.android.sdk.core.SdkHealth
import edts.apm.android.sdk.core.SdkIdentity
import edts.apm.android.sdk.core.SessionManager
import edts.apm.android.sdk.core.UserIdProvider
import edts.apm.android.sdk.crash.JvmCrashHandler
import edts.apm.android.sdk.crash.NativeCrashReporter
import edts.apm.android.sdk.crash.TerminationDetector
import edts.apm.android.sdk.crash.buildTerminationEvent
import edts.apm.android.sdk.crypto.AesGcmQueueCipher
import edts.apm.android.sdk.crypto.KeystoreKeyProvider
import edts.apm.android.sdk.integrity.DeviceIntegrityCollector
import edts.apm.android.sdk.manual.ManualApi
import edts.apm.android.sdk.queue.AsyncEventQueue
import edts.apm.android.sdk.queue.DiskEventQueue
import edts.apm.android.sdk.queue.EncryptedEventCodec
import edts.apm.android.sdk.queue.EventCodec
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.GatedEventQueue
import edts.apm.android.sdk.queue.JsonEventCodec
import edts.apm.android.sdk.queue.LoggingEventQueue
import edts.apm.android.sdk.queue.SessionStampingEventQueue
import edts.apm.android.sdk.scrub.ScrubbingEventQueue
import edts.apm.android.sdk.stability.AnrWatchdog
import edts.apm.android.sdk.stability.ColdStartFirstFrameObserver
import edts.apm.android.sdk.stability.ColdStartTracker
import edts.apm.android.sdk.stability.HandlerMainThreadProbe
import edts.apm.android.sdk.sync.EnvelopeStaticContext
import edts.apm.android.sdk.sync.IngestUploader
import edts.apm.android.sdk.sync.SyncEngine
import edts.apm.android.sdk.sync.SyncScheduler
import okhttp3.Call

/**
 * Assembles the whole SDK (A16, MOB-25). Everything A2–A15 deferred with the words
 * "composition root" happens here, and only here.
 *
 * Two orderings in [start] are load-bearing and must not be rearranged:
 *
 *  1. **`GatedEventQueue(ScrubbingEventQueue(DiskEventQueue))`** — the gate is outermost, so an
 *     event rejected by the kill switch is dropped before it is scrubbed or serialized, and the
 *     fixed pipeline Capture → Scrub → Disk → Sync still runs in that order for everything else.
 *  2. **`remoteConfig.loadFromCache()` before any capture component is installed** — otherwise the
 *     SDK spends the window between launch and the first HTTP round-trip believing it is enabled
 *     when the last known answer was that it is not (MOB-21).
 *
 * Every step is inside [start]'s single try/catch, and [start] returns false rather than throwing.
 * A failed assembly must leave the SDK dead and the host app untouched — CONSTITUTION rule #1
 * applies most sharply here, because this runs inside `Application.onCreate`, where an exception
 * kills the app before it draws anything.
 */
internal object ApmBootstrap {

    private const val TAG = "APM"
    private const val KEY_SESSION_ID = "apm_session_id"
    private const val KEY_APP_VERSION = "apm_app_version"
    private const val KEY_APP_BUILD = "apm_app_build"
    private const val KEY_OS_VERSION = "apm_os_version"

    /** What is watching the launch, registered before assembly and bound to the graph during it (A46, A48). */
    internal class LaunchObservers(val coldStart: ColdStartFirstFrameObserver, val activities: ActivityStateRecorder)

    /**
     * Starts watching the launch activity. Called at the very top of `APM.start()`, on the caller's thread,
     * **before** the background assembly begins (A46, A48): registering a lifecycle callback does no I/O, and doing it
     * late is what lost the first frame and every activity callback before it. Idempotent per Application; never throws.
     */
    @Synchronized
    internal fun observeLaunch(context: Context): LaunchObservers {
        val application = context.applicationContext as? Application
        launchObservers?.takeIf { application != null && observedApplication === application }?.let { return it }
        val observers = LaunchObservers(ColdStartFirstFrameObserver(), ActivityStateRecorder())
        try {
            application?.registerActivityLifecycleCallbacks(observers.activities)
            application?.registerActivityLifecycleCallbacks(observers.coldStart)
        } catch (e: Throwable) {
            // Nothing to measure without it; the SDK itself is unaffected.
        }
        launchObservers = observers
        observedApplication = application
        return observers
    }

    private var launchObservers: LaunchObservers? = null
    private var observedApplication: Application? = null

    /** Returns true when the SDK is live. False means it is off and will stay off this process. */
    fun start(context: Context, config: ApmConfig): Boolean = try {
        startInternal(context, config)
    } catch (e: Throwable) {
        // Nothing here is worth an app crash. A dead SDK is a bad day; a dead host app is an outage.
        //
        // But swallowing this silently was worse than it looked: on a real device the SDK failed to
        // start and left no trace anywhere, so the only symptom was telemetry that never arrived —
        // the exact failure this SDK exists to make visible. One warning on total startup failure
        // is not "logging"; it is the difference between a diagnosable problem and an invisible one.
        Log.w(TAG, "APM failed to start; SDK is disabled for this process", e)
        ApmRuntime.reset()
        false
    }

    private fun startInternal(context: Context, config: ApmConfig): Boolean {
        // SEC-12 fail closed at the earliest possible point: an SDK pointed at a cleartext ingest
        // host never starts, rather than starting and retrying forever against an endpoint it is
        // structurally forbidden from using.
        if (!TlsPolicy.isSecureUrl(config.ingestUrl)) return false

        val app = context.applicationContext
        // A40: the SDK's own key-value state, kept out of Auto Backup (a real SharedPreferences file cannot
        // be). Older builds' values are carried over on an app update — see SdkStorage.openState.
        val prefs = SdkStorage.openState(app)

        // A29 (SEC-07). Files now live in the no-backup directory; bring over anything a previous build
        // left in filesDir BEFORE the config cache and the queue are opened, or an updating app would
        // strand its pending telemetry where nothing reads it.
        SdkStorage.migrateLegacy(app)
        // A47: an xCrash-era build's tombstones are backup-eligible and unreadable by anything now.
        SdkStorage.deleteAbandonedNativeCrashDir(app)
        val health = SdkHealth()
        val sdkHeader = "${SdkIdentity.NAME}/${BuildConfig.LIBRARY_VERSION}"

        // ─── transport (A11 + A15) ──────────────────────────────────────────
        val (pinnedClient, unpinnedClient) = ApmHttpClientFactory.createPinnedPair(config.pinning)
        val callFactory: Call.Factory =
            if (config.pinning == null) unpinnedClient
            else PinnedCallFactory(pinnedClient, unpinnedClient) {
                !remoteConfigOrDefault().isDisabled(RemoteConfig.Feature.PINNING)
            }

        // ─── remote config (A10) — cache first, network later ───────────────
        val remoteConfig = RemoteConfigProvider(
            store = RemoteConfigStore(SdkStorage.configCache(app)),
            fetcher = RemoteConfigFetcher(
                callFactory,
                config.configUrl,
                config.appKey,
                sdkHeader,
                // The unpinned client is the recovery path for a pin that no longer matches —
                // see RemoteConfigFetcher.fallbackClient. Null when pinning is off, because then
                // the first attempt is already unpinned.
                fallbackClient = if (config.pinning != null) unpinnedClient else null
            )
        )
        remoteConfig.loadFromCache()
        installedRemoteConfig = remoteConfig

        // ─── queue (A2 + A4 + A10 + A14) ────────────────────────────────────
        // One cipher for both: the queue files and the stored host user id (A41).
        val cipher = queueCipher()
        val disk = DiskEventQueue(SdkStorage.queueDir(app), health, codec(cipher))

        // Two views of one queue (A18).
        //
        // `syncQueue` writes on the calling thread. Only the crash and termination paths use it:
        // an event that describes a dying process must not be handed to a worker thread that will
        // never be scheduled again.
        //
        // `queue` is the same pipeline with the write moved off the caller's thread, and is what
        // every other capture path uses — `02` §5 allows zero blocking I/O on the main thread, and
        // `APM.logError()` is called from click listeners and catch blocks in UI code.
        // MOB-26. Inside the scrubber on purpose — the logger must only ever see redacted events,
        // because logcat is not a private channel.
        val debugLogging = DebugMode.isEnabled(app, config.debugLogging)
        val syncQueue = GatedEventQueue(
            ScrubbingEventQueue(LoggingEventQueue(disk, isEnabled = { debugLogging }))
        ) { remoteConfig.config }
        val asyncQueue = AsyncEventQueue(syncQueue, health)


        // ─── identity & context (A1 + A6 + A7 + A8) ─────────────────────────
        val sequencer = EventSequencer()

        // A28. Read BEFORE this process creates its own session: it is what the previous process was
        // in when it died, and the only way a crash or termination reported now can be filed under the
        // session that actually had it. `apply()` is asynchronous, so a kill in the instant after a
        // session change can leave the one before it — still closer than the new session, which is
        // what was recorded before this existed.
        val previousSessionId = prefs.getString(KEY_SESSION_ID, null)
        // A39: the same for what the previous process was running. Read before it is overwritten below.
        val previousApp = AppStamp(
            prefs.getString(KEY_APP_VERSION, null), prefs.getString(KEY_APP_BUILD, null), prefs.getString(KEY_OS_VERSION, null)
        ).takeIf { it.version != null || it.build != null || it.osVersion != null }
        // The markers for THIS run are written only once the graph is published (see below), never here: a start that
        // fails part-way and is retried in the same process would otherwise read its own markers as "the previous
        // process" and file the real previous process's crash under the wrong session and version.
        val markersPublished = java.util.concurrent.atomic.AtomicBoolean(false)
        val sessions = SessionManager(onSessionStarted = { id ->
            if (markersPublished.get()) prefs.edit().putString(KEY_SESSION_ID, id).apply()   // a rotation after publish
        })

        // Outermost views (A28): the session is fixed on the caller's thread at capture time, before an
        // async hop that could outlive the session. `queue` is what every capture path uses.
        //
        // A39: the app version/build and OS version are fixed at capture for the same reason as the session —
        // an envelope states them once for all its events, and it is built at upload time.
        val currentApp = { AppStamp(config.appVersion, config.appBuild, DeviceContextBuilder.osVersion()) }
        val userIds = UserIdProvider(prefs, cipher)
        // A39: the identity epoch the previous process ended in — read now, before any setUser can change it.
        val previousIdentityEpoch = userIds.currentEpoch
        val currentEpoch = { userIds.currentEpoch }
        val queue: EventQueue = SessionStampingEventQueue(asyncQueue, currentApp, currentEpoch) { sessions.sessionId }
        val stampedSyncQueue: EventQueue = SessionStampingEventQueue(syncQueue, currentApp, currentEpoch) { sessions.sessionId }
        val installIds = InstallIdProvider(prefs)
        val breadcrumbs = BreadcrumbRingBuffer()
        val lifecycleBreadcrumbs = LifecycleBreadcrumbSource(breadcrumbs)
        // Snapshotted once per session (`01` §2.1) — these probes touch build props and the
        // filesystem, and re-running them per event would be pure cost for a value that cannot
        // change while the process lives.
        val integrity = DeviceIntegrityCollector(app).collect()

        val volatileContext = VolatileContextProvider(
            context = app,
            onEnterForeground = {
                sessions.onForeground()
                lifecycleBreadcrumbs.onForeground()
            },
            onEnterBackground = {
                sessions.onBackground()
                lifecycleBreadcrumbs.onBackground()
                // Draining on the way out is the last chance before the process may be killed.
                SyncScheduler.triggerNow(app)
            }
        )

        val manualApi = ManualApi(
            queue, userIds, breadcrumbs, sequencer::next, volatileContext::current,
            debugLog = { message -> if (debugLogging) android.util.Log.d("APM", message) }
        )

        // ─── sync (A5) ──────────────────────────────────────────────────────
        val syncEngine = SyncEngine(
            queue = syncQueue,   // drains disk directly; no reason to route reads through a worker
            uploader = IngestUploader(callFactory, config.ingestUrl, config.appKey, sdkHeader),
            health = health,
            contextProvider = {
                EnvelopeStaticContext(
                    sdkName = SdkIdentity.NAME,
                    sdkVersion = BuildConfig.LIBRARY_VERSION,
                    app = DeviceContextBuilder.buildAppInfo(app, config.appVersion, config.appBuild),
                    device = DeviceContextBuilder.buildDeviceInfo(),
                    integrity = integrity,
                    installId = installIds.installId,
                    sessionId = sessions.sessionId,
                    userId = userIds.userId,
                    userIdSource = userIds.userIdSource
                )
            },
            isEnabled = { remoteConfig.isEnabled },
            maxBatchProvider = { remoteConfig.config.maxBatch },
            identityFor = { epoch -> userIds.identityFor(epoch) }
        )

        // ─── stability (A9 + A10) ───────────────────────────────────────────
        val launchElapsed = SystemClock.elapsedRealtime()
        JvmCrashHandler(
            queue = stampedSyncQueue,   // synchronous by necessity — the process is already dying
            seqProvider = sequencer::next,
            contextProvider = volatileContext::current,
            appPackagePrefix = app.packageName,
            launchElapsedRealtimeMs = launchElapsed,
            breadcrumbsProvider = breadcrumbs::snapshot
        ).install()

        TerminationDetector(app, prefs).detect()?.let { termination ->
            // Reported once at startup about the *previous* process; write it through before
            // anything else can evict it.
            stampedSyncQueue.enqueue(
                buildTerminationEvent(termination.reason, sequencer.next(), volatileContext.current(), termination.eventId)
                    .copy(   // all null on a first launch: stamped with the current values instead
                        sessionId = previousSessionId,
                        appVersion = previousApp?.version, appBuild = previousApp?.build, osVersion = previousApp?.osVersion
                        // identityEpoch needs no copy here: at this point the current epoch IS the previous one (it is
                        // loaded from state, and no host call can reach a graph that is not published yet).
                    )
            )
        }

        val anrWatchdog = AnrWatchdog(
            queue = queue,
            seqProvider = sequencer::next,
            contextProvider = volatileContext::current,
            appPackagePrefix = app.packageName,
            launchElapsedRealtimeMs = launchElapsed,
            probe = HandlerMainThreadProbe(),
            // The watchdog already runs on its own thread, but it writes while the main thread is
            // blocked — going through the async queue keeps that write off any contended path.
            isAnrEnabled = { !remoteConfig.config.isDisabled(RemoteConfig.Feature.ANR) }
        ).also { it.start() }

        val coldStart = ColdStartTracker(
            queue = queue,
            seqProvider = sequencer::next,
            contextProvider = volatileContext::current,
            processStartElapsedMs = Process.getStartElapsedRealtime(),
            isEnabled = { !remoteConfig.config.isDisabled(RemoteConfig.Feature.COLD_START) }
        )

        // ─── lifecycle registration (A7 + A10) ──────────────────────────────
        (app as? Application)?.let { application ->
            // A48: not registered with the Application here — by now the launch activity has usually come and gone
            // past it. The recorder, registered in observeLaunch(), replays what these missed, then forwards live.
            val launch = observeLaunch(app)
            launch.activities.attach(volatileContext)
            launch.activities.attach(ScreenTrackingLifecycleCallbacks(breadcrumbs))
            // Registered earlier, by observeLaunch(); this only binds the tracker (A46). Calling it here too makes a
            // direct caller of start() — one that did not come through APM.start() — work the way it always did.
            launch.coldStart.attach(coldStart)
        }

        ConnectivityBreadcrumbSource(app, breadcrumbs).start()
        edts.apm.android.sdk.sync.ConnectivityWatcher(app).start()

        ApmRuntime.install(
            ApmComponents(
                queue = queue,
                syncEngine = syncEngine,
                manualApi = manualApi,
                remoteConfig = remoteConfig,
                sessionManager = sessions,
                userIds = userIds,
                anrWatchdog = anrWatchdog,
                uploadIntervalMs = { remoteConfig.uploadIntervalMs(config.uploadIntervalMs) },
                awaitCaptureDrain = asyncQueue::awaitDrain,
                sequencer = sequencer::next,
                eventContext = volatileContext::current
            )
        )
        edts.apm.android.sdk.APM.recordIngestHost(config.ingestUrl)

        // MOB-20: actually fetch the remote config. `loadFromCache()` above only seeds the last
        // known answer; without this the SDK never contacts `/v1/config` at all, so a kill switch
        // can never arrive and MOB-21 is unimplementable. That was the state until a device test
        // flipped a kill switch and nothing happened.
        //
        // On a background thread and after the graph is published, for the same reason the
        // tombstone scan is: a network round-trip has no business on the startup path.
        Thread({
            try {
                remoteConfig.refresh()
            } catch (e: Throwable) {
                Log.w(TAG, "remote config refresh failed", e)
            }
        }, "apm-config").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()

        // Native crashes from the previous process (MOB-15 native, MOB-16). Android already wrote
        // the tombstone; this only reads it, so no signal handler is installed anywhere.
        //
        // Deliberately **after** the graph is published and on its own thread: each tombstone is a
        // ~250 KB protobuf with a couple of thousand memory mappings, and doing this inline pushed
        // cold start from 15 ms to 50 ms and idle CPU from 0.2% to 3% — both over `02` §5's budget,
        // caught by the budget suite rather than by review. Nothing about a crash from a previous
        // process is urgent enough to sit on the startup path.
        Thread({
            try {
                NativeCrashReporter(
                    context = app,
                    prefs = prefs,
                    queue = stampedSyncQueue,
                    seqProvider = sequencer::next,
                    contextProvider = volatileContext::current,
                    crashedSessionId = previousSessionId,
                    crashedApp = previousApp,
                    crashedIdentityEpoch = previousIdentityEpoch
                ).reportPending()
            } catch (e: Throwable) {
                Log.w(TAG, "native crash reporting failed", e)
            }
        }, "apm-native-crash").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()

        // Scheduled last, so a worker that starts immediately finds a fully published graph.
        SyncScheduler.scheduleNext(app, delayMs = 0L)

        // Only now — after the LAST step that can throw — does this run become "the previous process" for the
        // next one. A start that fails anywhere above (including this scheduling step, which resets the runtime)
        // must leave the stored markers as the real previous process wrote them.
        markersPublished.set(true)
        prefs.edit()
            .putString(KEY_SESSION_ID, sessions.sessionId)
            .putString(KEY_APP_VERSION, config.appVersion)
            .putString(KEY_APP_BUILD, config.appBuild)
            .putString(KEY_OS_VERSION, DeviceContextBuilder.osVersion())
            .apply()
        return true
    }

    /**
     * At-rest encryption (SEC-08): the Keystore-backed cipher, or `null` when the Keystore will not produce a key.
     * The queue then degrades to plaintext rather than to no telemetry at all — see CONSTITUTION 2026-09-07 (A14)
     * for that trade-off. (The stored host user id does NOT degrade: A41 fails closed.)
     */
    private fun queueCipher(): AesGcmQueueCipher? = KeystoreKeyProvider().getOrCreateKey()?.let { AesGcmQueueCipher(it) }

    private fun codec(cipher: AesGcmQueueCipher?): EventCodec =
        cipher?.let { EncryptedEventCodec(it) } ?: JsonEventCodec()

    /**
     * The pinning kill switch is read inside a lambda that the transport needs *before* the config
     * provider exists — the provider's own fetcher is built on that transport. This breaks the
     * cycle by resolving late; until the provider is published, pinning stays on, which is the
     * safe direction (SEC-11: the app asked for pinning on purpose).
     */
    @Volatile private var installedRemoteConfig: RemoteConfigProvider? = null

    private fun remoteConfigOrDefault(): RemoteConfig =
        installedRemoteConfig?.config ?: RemoteConfig.DEFAULT
}
