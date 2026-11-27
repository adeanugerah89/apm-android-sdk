package edts.apm.android.sdk.network

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.internal.Clock
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.scrub.Scrubber
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import java.util.UUID

/**
 * `APM.reportNetworkRequest` — A51, iOS parity with `APM.reportNetworkTask`.
 *
 * [ApmEventListener] sees only `OkHttpClient`s that carry it. A request made with `HttpURLConnection`, Cronet,
 * Volley's default stack or Ktor on another engine is invisible to it, as is a second `OkHttpClient` nobody
 * attached the listener to. This lets the host app report such a request itself.
 *
 * What comes out is **the same events** the listener would have produced — it builds them through the same
 * [NetworkEventAttrs] — including MOB-02b's rule that a response with status >= 400 yields a `network` event
 * **and** a `network_failure(http_error)`, and `01` §5's failure categories for a transport failure.
 *
 * What it cannot provide are the phase timings (`dns_ms`, `tcp_ms`, `tls_ms`, `ttfb_ms`), `protocol`,
 * `tls_version` and `reused_connection`: a host's own stack does not hand them over, and the attributes are
 * optional, so they are left out rather than invented.
 *
 * Privacy: only the **host** and the **normalised path** (SEC-03b) are kept. The query string, fragment and any
 * `user:password@` part of [url] are dropped before anything is built — SEC-03, and nothing here can carry them
 * to the wire. Request and response bodies and headers are not parameters at all.
 *
 * It does not de-duplicate against the listener: a request that goes through an instrumented `OkHttpClient`
 * **and** is reported here is counted twice. Report only what the listener cannot see.
 */
internal class ManualNetworkReporter(
    private val queue: EventQueue,
    private val seqProvider: () -> Long,
    private val contextProvider: () -> EventContext,
    private val ingestHost: String?   // excluded from capture (MOB-10)
) {

    /**
     * Reports one finished request. Exactly one of [statusCode] / [error] describes the outcome: a [statusCode]
     * is a completed response; otherwise an [error] is a transport failure. With neither, there is nothing to
     * report and nothing is recorded. Returns whether anything was enqueued (for tests; the public API is `Unit`).
     */
    fun report(
        url: String,
        method: String,
        durationMs: Long,
        statusCode: Int?,
        error: Throwable?,
        requestBytes: Long?,
        responseBytes: Long?
    ): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false           // not an http(s) URL: nothing safe to record
        val host = parsed.host
        if (ingestHost != null && host.equals(ingestHost, ignoreCase = true)) return false   // MOB-10: never the SDK's own traffic
        val path = Scrubber.normalizePath(parsed.encodedPath)         // SEC-03b; the query is never read
        val verb = method.trim().uppercase(Locale.ROOT).take(MAX_METHOD_LENGTH).ifEmpty { return false }
        val duration = durationMs.coerceAtLeast(0)

        if (statusCode != null) {
            if (statusCode !in 100..599) return false                  // not an HTTP status
            enqueue(EventType.NETWORK, NetworkEventAttrs.network(
                host, path, verb, statusCode, duration, reqBytes = requestBytes, resBytes = responseBytes
            ))
            if (statusCode >= 400) {
                enqueue(EventType.NETWORK_FAILURE, NetworkEventAttrs.httpError(host, path, verb, statusCode, duration))
            }
            return true
        }
        if (error == null) return false
        enqueue(EventType.NETWORK_FAILURE, NetworkEventAttrs.transportFailure(
            host, path, verb, FailureCategoryMapper.map(error), duration, error
        ))
        return true
    }

    private fun enqueue(type: EventType, attrs: kotlinx.serialization.json.JsonObject) {
        queue.enqueue(
            Event(
                eventId = UUID.randomUUID().toString(),
                type = type,
                tsClient = Clock.nowIso8601(),
                seq = seqProvider(),
                attrs = attrs,
                ctx = contextProvider()
            )
        )
    }

    private companion object {
        /** HTTP methods are short; this only stops a caller from putting arbitrary text in the slot. */
        const val MAX_METHOD_LENGTH = 16
    }
}
