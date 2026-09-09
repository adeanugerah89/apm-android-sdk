package edts.apm.android.sdk.internal

import edts.apm.android.sdk.config.RemoteConfigProvider
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.SessionManager
import edts.apm.android.sdk.core.UserIdProvider
import edts.apm.android.sdk.manual.ManualApi
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.stability.AnrWatchdog
import edts.apm.android.sdk.sync.SyncEngine

/**
 * The assembled object graph, published once by `APM.start()`.
 *
 * A process-wide holder is not the shape one would pick freely — it exists because WorkManager
 * instantiates workers reflectively and cannot be handed constructor arguments. The alternative,
 * a custom `WorkerFactory`, must be registered through `Configuration.Provider` on the **host
 * app's** `Application` class. That is a mandatory integration step for every adopting team, it
 * conflicts with any app that already supplies its own factory, and it contradicts MOB-25's
 * 30-minute integration budget and `02` §3.4's claim that screen tracking is the only step that
 * is not zero-effort. Reading dependencies from here instead keeps WorkManager's default factory
 * working and the host app's code untouched.
 *
 * Read through [current], which is null until `APM.start()` succeeds — every consumer must treat
 * "SDK not started" as an ordinary state and do nothing, not fail.
 */
internal object ApmRuntime {

    @Volatile private var components: ApmComponents? = null

    val current: ApmComponents? get() = components

    val isStarted: Boolean get() = components != null

    fun install(newComponents: ApmComponents) {
        components = newComponents
    }

    /**
     * Tears the graph down. Exists for tests, which share one JVM across cases and would otherwise
     * inherit a previous case's graph. Stops the watchdog thread so it cannot outlive its test.
     */
    fun reset() {
        components?.anrWatchdog?.stop()
        components = null
    }
}

internal class ApmComponents(
    /** The outermost queue — gated, then scrubbed, then disk. Everything captures through this. */
    val queue: EventQueue,
    val syncEngine: SyncEngine,
    val manualApi: ManualApi,
    val remoteConfig: RemoteConfigProvider,
    val sessionManager: SessionManager,
    val userIds: UserIdProvider,
    val anrWatchdog: AnrWatchdog?,
    val uploadIntervalMs: Long,
    /**
     * Blocks until queued captures have reached disk. Capture is asynchronous (A18), so anything
     * asserting on the queue's contents must wait for the writer rather than assume it has run.
     */
    val awaitCaptureDrain: (Long) -> Boolean,
    /** Next sequence number — the same counter every other capture path draws from. */
    val sequencer: () -> Long,
    /** Live per-event `ctx` (`01` §4), shared so host-attached capture matches internal capture. */
    val eventContext: () -> EventContext
)
