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
import edts.apm.android.sdk.core.DeviceContextBuilder
import edts.apm.android.sdk.core.EventSequencer
import edts.apm.android.sdk.core.InstallIdProvider
import edts.apm.android.sdk.core.SdkHealth
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
import edts.apm.android.sdk.queue.GatedEventQueue
import edts.apm.android.sdk.queue.JsonEventCodec
import edts.apm.android.sdk.queue.LoggingEventQueue
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
import java.io.File

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
    private const val PREFS_NAME = "edts_apm_prefs"
    private const val QUEUE_DIR = "apm-queue"
    private const val CONFIG_CACHE = "apm-remote-config.json"

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
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val health = SdkHealth()
        val sdkHeader = "apm-android-sdk/${BuildConfig.LIBRARY_VERSION}"

        // ─── transport (A11 + A15) ──────────────────────────────────────────
        val (pinnedClient, unpinnedClient) = ApmHttpClientFactory.createPinnedPair(config.pinning)
        val callFactory: Call.Factory =
            if (config.pinning == null) unpinnedClient
            else PinnedCallFactory(pinnedClient, unpinnedClient) {
                !remoteConfigOrDefault().isDisabled(RemoteConfig.Feature.PINNING)
            }

        // ─── remote config (A10) — cache first, network later ───────────────
        val remoteConfig = RemoteConfigProvider(
            store = RemoteConfigStore(File(app.filesDir, CONFIG_CACHE)),
            fetcher = RemoteConfigFetcher(callFactory, config.configUrl, config.appKey, sdkHeader)
        )
        remoteConfig.loadFromCache()
        installedRemoteConfig = remoteConfig

        // ─── queue (A2 + A4 + A10 + A14) ────────────────────────────────────
        val disk = DiskEventQueue(File(app.filesDir, QUEUE_DIR), health, codec())

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
        val queue = AsyncEventQueue(syncQueue, health)


        // ─── identity & context (A1 + A6 + A7 + A8) ─────────────────────────
        val sequencer = EventSequencer()
        val sessions = SessionManager()
        val userIds = UserIdProvider(prefs)
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

        val manualApi = ManualApi(queue, userIds, breadcrumbs, sequencer::next, volatileContext::current)

        // ─── sync (A5) ──────────────────────────────────────────────────────
        val syncEngine = SyncEngine(
            queue = syncQueue,   // drains disk directly; no reason to route reads through a worker
            uploader = IngestUploader(callFactory, config.ingestUrl, config.appKey, sdkHeader),
            health = health,
            contextProvider = {
                EnvelopeStaticContext(
                    sdkName = "apm-android-sdk",
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
            isEnabled = { remoteConfig.isEnabled }
        )

        // ─── stability (A9 + A10) ───────────────────────────────────────────
        val launchElapsed = SystemClock.elapsedRealtime()
        JvmCrashHandler(
            queue = syncQueue,   // synchronous by necessity — the process is already dying
            seqProvider = sequencer::next,
            contextProvider = volatileContext::current,
            appPackagePrefix = app.packageName,
            launchElapsedRealtimeMs = launchElapsed
        ).install()

        TerminationDetector(app, prefs).check()?.let { reason ->
            // Reported once at startup about the *previous* process; write it through before
            // anything else can evict it.
            syncQueue.enqueue(buildTerminationEvent(reason, sequencer.next(), volatileContext.current()))
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
            processStartUptimeMs = Process.getStartUptimeMillis(),
            isEnabled = { !remoteConfig.config.isDisabled(RemoteConfig.Feature.COLD_START) }
        )

        // ─── lifecycle registration (A7 + A10) ──────────────────────────────
        (app as? Application)?.let { application ->
            application.registerActivityLifecycleCallbacks(volatileContext)
            application.registerActivityLifecycleCallbacks(ScreenTrackingLifecycleCallbacks(breadcrumbs))
            application.registerActivityLifecycleCallbacks(ColdStartFirstFrameObserver(coldStart))
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
                uploadIntervalMs = config.uploadIntervalMs,
                awaitCaptureDrain = queue::awaitDrain,
                sequencer = sequencer::next,
                eventContext = volatileContext::current
            )
        )
        edts.apm.android.sdk.APM.recordIngestHost(config.ingestUrl)

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
                    queue = syncQueue,
                    seqProvider = sequencer::next,
                    contextProvider = volatileContext::current
                ).reportPending()
            } catch (e: Throwable) {
                Log.w(TAG, "native crash reporting failed", e)
            }
        }, "apm-native-crash").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()

        // Scheduled last, so a worker that starts immediately finds a fully published graph.
        SyncScheduler.scheduleNext(app, delayMs = 0L)
        return true
    }

    /**
     * At-rest encryption (SEC-08). A Keystore that will not produce a key degrades to plaintext
     * rather than to no telemetry at all — see CONSTITUTION 2026-09-07 (A14) for that trade-off.
     */
    private fun codec(): EventCodec =
        KeystoreKeyProvider().getOrCreateKey()
            ?.let { EncryptedEventCodec(AesGcmQueueCipher(it)) }
            ?: JsonEventCodec()

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
