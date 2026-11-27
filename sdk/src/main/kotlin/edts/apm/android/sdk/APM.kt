package edts.apm.android.sdk

import android.content.Context
import android.os.Looper
import edts.apm.android.sdk.breadcrumb.BreadcrumbCategory
import edts.apm.android.sdk.breadcrumb.BreadcrumbLevel
import edts.apm.android.sdk.internal.ApmBootstrap
import edts.apm.android.sdk.internal.ApmRuntime
import edts.apm.android.sdk.network.ApmEventListener
import edts.apm.android.sdk.network.ManualNetworkReporter
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Public entry point.
 *
 * Every method here is safe to call from any thread, at any time, including before [start] and
 * after a [start] that failed — in that case they do nothing. That is CONSTITUTION rule #1 at the
 * surface: an SDK that is off must be indistinguishable from an SDK that is absent, never a source
 * of exceptions in the host app's code.
 */
public object APM {

    /**
     * Initializes the SDK. Call from `Application.onCreate()`. Thread-safe and idempotent.
     *
     * Returns nothing and throws nothing: assembly failure (an unusable Keystore, a cleartext
     * ingest URL, a missing system service) leaves the SDK off. Check [isStarted] to find out.
     *
     * "Already started" is decided by [ApmRuntime] alone rather than by a flag kept here. A second
     * flag would be a second source of truth for one fact, and the two can disagree — a failed
     * assembly that still marked the SDK initialized would make every later call a permanent
     * no-op with no way to tell why. A start that failed can therefore be retried; a start that
     * succeeded is a no-op.
     */
    @JvmStatic
    public fun start(context: Context, config: ApmConfig) {
        if (ApmRuntime.isStarted) return
        val app = context.applicationContext

        // A46: begin watching for the launch activity's first frame NOW, on this thread, before assembly is handed
        // to a background thread that may finish after the frame was drawn. Registering a callback is not I/O.
        try { ApmBootstrap.observeLaunch(app) } catch (e: Throwable) { Unit }

        // Assembly reads SharedPreferences, the config cache, the Keystore and the queue
        // directory — all blocking disk I/O, and `Application.onCreate()` runs on the main thread,
        // where `02` §5 allows none of it. So when called from the main thread the work is moved
        // to a background thread and `start()` returns immediately; called from anywhere else it
        // runs inline, because there is nothing to protect.
        //
        // The cost is a short window after `start()` where the SDK is not yet live and every API
        // is a no-op — including the crash handler. That window is milliseconds of
        // `Application.onCreate`, against blocking the main thread on every single launch.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread({ startNow(app, config) }, "apm-start").apply {
                isDaemon = true
                start()
            }
        } else {
            startNow(app, config)
        }
    }

    private fun startNow(app: Context, config: ApmConfig) {
        synchronized(this) {
            if (ApmRuntime.isStarted) return
            try {
                ApmBootstrap.start(app, config)
            } catch (e: Throwable) {
                Unit
            }
        }
    }

    /**
     * Blocks until a [start] issued from the main thread has finished. For tests and for a host
     * app that genuinely needs the SDK live before continuing — not needed in normal use, where
     * the APIs simply no-op until assembly completes.
     */
    @JvmStatic
    public fun awaitStarted(timeoutMs: Long): Boolean {
        // Polls [ApmRuntime] rather than waiting on a latch. A latch here would be one-shot for the
        // life of the process, so a second start after a teardown would see it already open and
        // report success before assembly had run.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ApmRuntime.isStarted) return true
            Thread.sleep(POLL_MS)
        }
        return ApmRuntime.isStarted
    }

    private const val POLL_MS = 10L

    /** Whether [start] assembled a working SDK. False also means every call below is a no-op. */
    @JvmStatic
    public val isStarted: Boolean get() = ApmRuntime.isStarted

    /**
     * The listener the host app attaches to **its own** OkHttpClient to capture network
     * events (MOB-01/02):
     *
     * ```kotlin
     * val client = OkHttpClient.Builder()
     *     .eventListener(APM.networkEventListener()!!)
     *     .build()
     * ```
     *
     * This is a manual step by necessity, not by omission: the SDK does not own the host app's
     * client and cannot attach itself to it. `null` before [start] succeeds.
     *
     * The returned listener excludes the ingest host from capture, so the SDK cannot instrument
     * its own uploads even if the host app points this at a client that talks to both (MOB-10).
     */
    @JvmStatic
    public fun networkEventListener(): EventListener? {
        val components = ApmRuntime.current ?: return null
        val host = lastIngestHost ?: return null
        return ApmEventListener(
            queue = components.queue,
            sessionProvider = { components.sessionManager.sessionId },
            seqProvider = { components.sequencer() },
            contextProvider = { components.eventContext() },
            ingestHost = host
        )
    }

    /**
     * Report a network request made with a stack the SDK cannot observe on its own — `HttpURLConnection`,
     * Cronet, Volley's default stack, Ktor on another engine, or an `OkHttpClient` you did not attach
     * [networkEventListener] to. (A51, MOB-01; iOS: `reportNetworkTask`)
     *
     * Requests made through an `OkHttpClient` that **does** carry [networkEventListener] are captured
     * automatically — do not also report those here, they would be counted twice.
     *
     * - Pass [statusCode] for a request that got a response. A status of 400 or above produces both a `network`
     *   and a `network_failure` event, exactly as the automatic capture does.
     * - Pass [error] (and no [statusCode]) for a request that failed before a response. It is classified as for
     *   OkHttp: timeout, dns, TLS, cancelled, and so on.
     * - [url] is the full URL; only its host and normalised path are kept. The query string, fragment and
     *   credentials are discarded, and no header or body can be passed. A non-http(s) URL, or a call with
     *   neither a status nor an error, records nothing.
     * - Phase timings (DNS, TCP, TLS, time-to-first-byte) are not recorded — a stack of your own does not expose
     *   them to the SDK.
     *
     * Safe to call from any thread; it never blocks and never throws. A no-op before [start] succeeds.
     */
    @JvmStatic
    @JvmOverloads
    public fun reportNetworkRequest(
        url: String,
        method: String,
        durationMs: Long,
        statusCode: Int? = null,
        error: Throwable? = null,
        requestBytes: Long? = null,
        responseBytes: Long? = null
    ) {
        safely { components ->
            ManualNetworkReporter(
                queue = components.queue,
                seqProvider = { components.sequencer() },
                contextProvider = { components.eventContext() },
                ingestHost = lastIngestHost
            ).report(url, method, durationMs, statusCode, error, requestBytes, responseBytes)
        }
    }

    /** Report a handled error, with call-site location filled automatically. (MOB-11b) */
    @JvmStatic
    @JvmOverloads
    public fun logError(throwable: Throwable, extra: Map<String, String> = emptyMap()) {
        safely { it.manualApi.logError(throwable, extra) }
    }

    /**
     * Set the user identifier. Sent raw over TLS; the SDK never hashes it. (MOB-28, SEC-06)
     *
     * An empty or whitespace-only [id] is **ignored** — it is not an identity, and the previous user (or the
     * generated id) stays in place. Call this after login and when a saved session is restored; do not call it
     * with a placeholder such as `"0"` or `"guest"` for "not signed in" — use [clearUser] on logout instead.
     */
    @JvmStatic
    public fun setUser(id: String) {
        safely { it.manualApi.setUser(id) }
    }

    /**
     * The user signed out: events from now on are no longer attributed to them. Call it on logout — without it
     * the SDK cannot know the session ended, and anonymous activity would keep going out under the last user.
     * Events captured *before* the call stay with the user they were captured under.
     */
    @JvmStatic
    public fun clearUser() {
        safely { it.manualApi.clearUser() }
    }

    /** Add a manual breadcrumb. (MOB-11) */
    @JvmStatic
    @JvmOverloads
    public fun breadcrumb(
        message: String,
        category: BreadcrumbCategory,
        level: BreadcrumbLevel = BreadcrumbLevel.INFO
    ) {
        safely { it.manualApi.breadcrumb(message, category, level) }
    }

    internal var lastIngestHost: String? = null

    /** Set by [ApmBootstrap] so [networkEventListener] knows what to exclude. */
    internal fun recordIngestHost(ingestUrl: String) {
        lastIngestHost = ingestUrl.toHttpUrlOrNull()?.host
    }

    /**
     * Runs [block] against the live graph, or does nothing. Swallows anything the block throws:
     * a bug inside the SDK must surface as missing telemetry, never as an exception on the host
     * app's thread.
     */
    private inline fun safely(block: (edts.apm.android.sdk.internal.ApmComponents) -> Unit) {
        val components = ApmRuntime.current ?: return
        try {
            block(components)
        } catch (e: Throwable) {
            Unit
        }
    }
}
