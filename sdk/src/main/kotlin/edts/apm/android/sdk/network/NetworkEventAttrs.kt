package edts.apm.android.sdk.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `attrs` of `network` and `network_failure` events (`01` §4.1/§4.2) — **one** place that decides what
 * they contain, shared by the OkHttp listener ([ApmEventListener]) and the manual API for other stacks
 * ([ManualNetworkReporter], A51), the same way iOS routes both of its paths through `NetworkEventBuilder`.
 * A request reported by hand therefore cannot end up shaped differently from one captured automatically.
 *
 * Callers pass an already-normalised [path] (SEC-03b) and a [host] that carries no credentials; this object does
 * no scrubbing of its own.
 */
internal object NetworkEventAttrs {

    fun network(
        host: String, path: String, method: String, status: Int, durationMs: Long,
        dnsMs: Long? = null, tcpMs: Long? = null, tlsMs: Long? = null, ttfbMs: Long? = null,
        protocol: String? = null, reqBytes: Long? = null, resBytes: Long? = null,
        tlsVersion: String? = null, reusedConnection: Boolean? = null
    ): JsonObject = buildJsonObject {
        put("host", host)
        put("path", path)
        put("method", method)
        put("status_code", status)
        put("duration_ms", durationMs)
        dnsMs?.let  { put("dns_ms",  it) }
        tcpMs?.let  { put("tcp_ms",  it) }
        tlsMs?.let  { put("tls_ms",  it) }
        ttfbMs?.let { put("ttfb_ms", it) }
        protocol?.let { put("protocol", it) }
        reqBytes?.takeIf { it >= 0 }?.let { put("req_bytes", it) }
        resBytes?.takeIf { it >= 0 }?.let { put("res_bytes", it) }
        tlsVersion?.let { put("tls_version", it) }
        reusedConnection?.let { put("reused_connection", it) }
    }

    /** MOB-02b: the second event every response with status >= 400 produces. */
    fun httpError(host: String, path: String, method: String, status: Int, durationMs: Long): JsonObject = buildJsonObject {
        put("host", host)
        put("path", path)
        put("method", method)
        put("failure_category", "http_error")
        put("status_code", status)
        put("duration_ms", durationMs)
    }

    fun transportFailure(
        host: String, path: String, method: String,
        category: FailureCategory, durationMs: Long,
        error: Throwable, tlsPhase: String? = null
    ): JsonObject = buildJsonObject {
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
}
