package edts.apm.android.sdk.sync

import edts.apm.android.sdk.core.Envelope
import edts.apm.android.sdk.internal.TlsPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Request
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import java.io.IOException
import java.net.UnknownServiceException

sealed class UploadResult {
    data class HttpResponse(val statusCode: Int, val retryAfterSeconds: Long?) : UploadResult()
    data class TransportFailure(val exception: IOException) : UploadResult()
}

/** Seam for tests to substitute a fake — see [IngestUploader] for the real implementation. */
interface Uploader {
    fun upload(envelope: Envelope): UploadResult
}

/**
 * POSTs one envelope to `/v1/ingest` (`01` §7).
 *
 * MOB-10 anti-loop: [client] MUST be a separate OkHttpClient instance from the one exposed to
 * the host app, with no [edts.apm.android.sdk.network.ApmEventListener] attached — otherwise the
 * SDK would instrument its own upload traffic and create an unbounded event loop.
 * [edts.apm.android.sdk.internal.ApmHttpClientFactory] is the only supported way to build it.
 *
 * SEC-12: [ingestUrl] is re-checked against [TlsPolicy] on every call, not trusted from
 * construction. The client's connection specs already make cleartext unreachable, so this is
 * redundant by design — it is the check that still holds if someone assembles this class with a
 * client that lacks the TLS floor. A telemetry payload must never leave over an unprotected
 * connection because one composition site was wired wrong.
 */
class IngestUploader(
    private val client: Call.Factory,   // OkHttpClient, or a PinnedCallFactory wrapping two
    private val ingestUrl: String,
    private val appKey: String,
    private val sdkHeaderValue: String,   // "apm-android-sdk/0.1.0" — name & version, X-APM-Sdk
    private val json: Json = Json { encodeDefaults = true },
    /** SEC-12 gate. A seam only so tests can drive the real HTTP paths against MockWebServer. */
    private val isSecureUrl: (String) -> Boolean = TlsPolicy::isSecureUrl
) : Uploader {
    private val jsonMediaType = "application/json".toMediaType()

    override fun upload(envelope: Envelope): UploadResult {
        if (!isSecureUrl(ingestUrl)) {
            // Refused before the payload is even serialized — nothing to leak, nothing to retry
            // against. Reported as a transport failure so the batch stays on disk (MOB-09).
            return UploadResult.TransportFailure(UnknownServiceException("ingest URL is not https: fail closed (SEC-12)"))
        }
        val body = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        val request = Request.Builder()
            .url(ingestUrl)
            .header("X-APM-Key", appKey)
            .header("X-APM-Sdk", sdkHeaderValue)
            .header("Content-Encoding", "gzip")
            .post(gzip(body))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                UploadResult.HttpResponse(
                    statusCode = response.code,
                    retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                )
            }
        } catch (e: IOException) {
            UploadResult.TransportFailure(e)
        }
    }

    private fun gzip(payload: ByteArray) = object : okhttp3.RequestBody() {
        override fun contentType() = jsonMediaType
        override fun writeTo(sink: BufferedSink) {
            val gzipSink = GzipSink(sink).buffer()
            gzipSink.write(payload)
            gzipSink.close()
        }
    }
}
