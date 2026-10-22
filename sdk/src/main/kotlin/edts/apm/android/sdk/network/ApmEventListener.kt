package edts.apm.android.sdk.network

import edts.apm.android.sdk.core.*
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.scrub.Scrubber
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * OkHttp EventListener for APM network observability. (MOB-01/02/02b/03)
 *
 * Why EventListener not Interceptor:
 *   EventListener exposes timing for each phase (DNS, TCP connect, TLS handshake, TTFB),
 *   and callFailed with the actual IOException — so we can map the failure phase precisely.
 *   Interceptors see only the request/response pair, not the timing breakdown or where a
 *   transport failure occurred.
 *
 * MOB-02b dual-event rule:
 *   For every request that completes (including 4xx/5xx), we emit a `network` event.
 *   Additionally, if status >= 400, we emit a SECOND `network_failure(http_error)` event.
 *   These are two distinct events from one request — no double-counting of transport failures.
 *
 * Anti-loop (MOB-10):
 *   The SyncEngine uses a SEPARATE OkHttpClient with no ApmEventListener attached.
 *   Additionally, the ingest host is skipped in newForCall() via ApmEventListenerFactory.
 */
internal class ApmEventListener(
    private val queue: EventQueue,
    private val sessionProvider: () -> String,
    private val seqProvider: () -> Long,
    private val contextProvider: () -> EventContext,
    private val ingestHost: String   // excluded from capture (MOB-10)
) : EventListener() {

    private data class Timing(
        val callStart: Long                 = System.nanoTime(),
        var dnsStart: Long?                 = null,
        var dnsEnd: Long?                   = null,
        var connectStart: Long?             = null,
        var connectEnd: Long?               = null,
        var secureConnectStart: Long?       = null,
        var secureConnectEnd: Long?         = null,
        var requestHeadersSent: Long?       = null,
        var responseHeadersStart: Long?     = null,
        var tlsVersion: String?             = null
    )

    private val timings = ConcurrentHashMap<Call, Timing>()

    // ─── Timing callbacks ───────────────────────────────────────────────────

    override fun callStart(call: Call) {
        if (isIngestHost(call)) return
        timings[call] = Timing()
    }

    override fun dnsStart(call: Call, domainName: String) {
        timings[call]?.dnsStart = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        timings[call]?.dnsEnd = System.nanoTime()
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        timings[call]?.connectStart = System.nanoTime()
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        timings[call]?.connectEnd = System.nanoTime()
    }

    override fun secureConnectStart(call: Call) {
        timings[call]?.secureConnectStart = System.nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        timings[call]?.secureConnectEnd = System.nanoTime()
    }

    /**
     * Fires for every call, including ones that reuse a pooled connection and therefore never run
     * `secureConnectStart/End` — so this, not `secureConnectEnd`, is where the TLS version is read.
     */
    override fun connectionAcquired(call: Call, connection: Connection) {
        timings[call]?.tlsVersion = connection.handshake()?.tlsVersion?.javaName
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        timings[call]?.requestHeadersSent = System.nanoTime()
    }

    override fun responseHeadersStart(call: Call) {
        timings[call]?.responseHeadersStart = System.nanoTime()
    }

    // ─── Completion callbacks ───────────────────────────────────────────────

    override fun callEnd(call: Call) {
        // Normal completion without response = not useful (responseHeadersEnd handles response case)
        timings.remove(call)
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        val timing = timings[call] ?: return
        val now    = System.nanoTime()
        val req    = call.request()

        val durationMs = msFrom(timing.callStart, now)
        val dnsMs      = ms(timing.dnsStart, timing.dnsEnd)
        val tcpMs      = ms(timing.connectStart, timing.secureConnectStart ?: timing.connectEnd)
        val tlsMs      = ms(timing.secureConnectStart, timing.secureConnectEnd)
        val ttfbMs     = ms(timing.requestHeadersSent, timing.responseHeadersStart)

        val host   = req.url.host
        val path   = Scrubber.normalizePath(req.url.encodedPath)  // SEC-03b
        val method = req.method
        val status = response.code

        // MOB-02b: always emit `network` event
        emitNetworkEvent(host, path, method, status, durationMs, dnsMs, tcpMs, tlsMs, ttfbMs,
            response.protocol, req.body?.contentLength(), response.body?.contentLength(),
            tlsVersion = timing.tlsVersion,
            // A call that never started a TCP connect reused one from the pool.
            reusedConnection = timing.connectStart == null)

        // MOB-02b: additionally emit `network_failure(http_error)` if status >= 400
        if (status >= 400) {
            emitNetworkFailureHttpError(host, path, method, status, durationMs)
        }
    }

    override fun callFailed(call: Call, ioe: IOException) {
        val timing = timings.remove(call) ?: return
        val req    = call.request()
        val now    = System.nanoTime()

        val category   = FailureCategoryMapper.map(ioe)
        val durationMs = msFrom(timing.callStart, now)

        emitTransportFailure(
            host        = req.url.host,
            path        = Scrubber.normalizePath(req.url.encodedPath),  // SEC-03b
            method      = req.method,
            category    = category,
            durationMs  = durationMs,
            error       = ioe,
            tlsPhase    = tlsPhase(timing)
        )
    }

    // ─── Emit helpers ────────────────────────────────────────────────────────

    private fun emitNetworkEvent(
        host: String, path: String, method: String, status: Int,
        durationMs: Long, dnsMs: Long?, tcpMs: Long?, tlsMs: Long?, ttfbMs: Long?,
        protocol: Protocol?, reqBytes: Long?, resBytes: Long?,
        tlsVersion: String?, reusedConnection: Boolean
    ) {
        val attrs = buildJsonObject {
            put("host", host)
            put("path", path)
            put("method", method)
            put("status_code", status)
            put("duration_ms", durationMs)
            dnsMs?.let  { put("dns_ms",  it) }
            tcpMs?.let  { put("tcp_ms",  it) }
            tlsMs?.let  { put("tls_ms",  it) }
            ttfbMs?.let { put("ttfb_ms", it) }
            protocol?.let { put("protocol", it.toString()) }
            reqBytes?.takeIf { it >= 0 }?.let { put("req_bytes", it) }
            resBytes?.takeIf { it >= 0 }?.let { put("res_bytes", it) }
            tlsVersion?.let { put("tls_version", it) }
            put("reused_connection", reusedConnection)
        }
        enqueueEvent(EventType.NETWORK, attrs)
    }

    private fun emitNetworkFailureHttpError(
        host: String, path: String, method: String, status: Int, durationMs: Long
    ) {
        val attrs = buildJsonObject {
            put("host", host)
            put("path", path)
            put("method", method)
            put("failure_category", "http_error")
            put("status_code", status)
            put("duration_ms", durationMs)
        }
        enqueueEvent(EventType.NETWORK_FAILURE, attrs)
    }

    private fun emitTransportFailure(
        host: String, path: String, method: String,
        category: FailureCategory, durationMs: Long,
        error: IOException, tlsPhase: String?
    ) {
        val attrs = buildJsonObject {
            put("host", host)
            put("path", path)
            put("method", method)
            put("failure_category", Json.encodeToString(FailureCategory.serializer(), category).trim('"'))
            put("duration_ms", durationMs)
            val info = TransportError.of(error)
            put("error_domain", info.domain)
            put("error_code", info.code)
            TransportError.underlyingOf(error)?.let {
                put("underlying_domain", it.domain)
                put("underlying_code", it.code)
            }
            tlsPhase?.let { put("tls_phase_reached", it) }
        }
        enqueueEvent(EventType.NETWORK_FAILURE, attrs)
    }

    private fun enqueueEvent(type: EventType, attrs: JsonObject) {
        val event = Event(
            eventId  = UUID.randomUUID().toString(),
            type     = type,
            tsClient = edts.apm.android.sdk.internal.Clock.nowIso8601(),
            seq      = seqProvider(),
            attrs    = attrs,
            ctx      = contextProvider()
        )
        queue.enqueue(event)
    }

    private fun isIngestHost(call: Call) = call.request().url.host == ingestHost

    private fun tlsPhase(t: Timing): String? = when {
        t.secureConnectEnd   != null -> "completed"
        t.secureConnectStart != null -> "started"
        else                         -> "none"
    }

    private fun ms(start: Long?, end: Long?): Long? =
        if (start != null && end != null) (end - start) / 1_000_000 else null

    private fun msFrom(start: Long, end: Long): Long = (end - start) / 1_000_000
}

/**
 * Factory that skips instrumentation for the ingest host (MOB-10 anti-loop).
 * Usage: OkHttpClient.Builder().eventListenerFactory(ApmEventListenerFactory(...))
 */
internal class ApmEventListenerFactory(
    private val queue: EventQueue,
    private val sessionProvider: () -> String,
    private val seqProvider: () -> Long,
    private val contextProvider: () -> EventContext,
    private val ingestHost: String
) : EventListener.Factory {

    override fun create(call: Call): EventListener =
        if (call.request().url.host == ingestHost) {
            EventListener.NONE  // no instrumentation for SDK's own traffic
        } else {
            ApmEventListener(queue, sessionProvider, seqProvider, contextProvider, ingestHost)
        }
}
