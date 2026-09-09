package edts.apm.android.sdk.config

import edts.apm.android.sdk.internal.TlsPolicy
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Request
import java.io.IOException

sealed class ConfigFetchResult {
    data class Success(val config: RemoteConfig) : ConfigFetchResult()
    /** Reached the server but got something unusable — non-2xx, or a body we cannot decode. */
    data class Failure(val statusCode: Int?) : ConfigFetchResult()
}

/** Seam for tests and for the provider, which must not depend on OkHttp directly. */
interface ConfigFetcher {
    fun fetch(): ConfigFetchResult
}

/**
 * `GET /v1/config` (`01` §9, MOB-20).
 *
 * MOB-10 applies here exactly as it does to [edts.apm.android.sdk.sync.IngestUploader]: [client]
 * must be the SDK's own uninstrumented OkHttpClient. A config fetch on the instrumented client
 * would be captured as a network event, which would then be uploaded, which is the same
 * self-instrumentation loop MOB-10 exists to prevent.
 *
 * SEC-12 applies here too: [configUrl] is checked against [TlsPolicy] on every call, so a
 * misconfigured cleartext config endpoint fails closed instead of handing the SDK a remote
 * config — including a kill-switch value — that anyone on the path could have written.
 *
 * Never throws — a fetch failure is an ordinary outcome, handled by falling back to cache or
 * default in [RemoteConfigProvider].
 */
class RemoteConfigFetcher(
    private val client: Call.Factory,   // OkHttpClient, or a PinnedCallFactory wrapping two
    private val configUrl: String,
    private val appKey: String,
    private val sdkHeaderValue: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** SEC-12 gate. A seam only so tests can drive the real HTTP paths against MockWebServer. */
    private val isSecureUrl: (String) -> Boolean = TlsPolicy::isSecureUrl
) : ConfigFetcher {

    override fun fetch(): ConfigFetchResult {
        if (!isSecureUrl(configUrl)) return ConfigFetchResult.Failure(null)

        val request = Request.Builder()
            .url(configUrl)
            .header("X-APM-Key", appKey)
            .header("X-APM-Sdk", sdkHeaderValue)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return ConfigFetchResult.Failure(response.code)
                val body = response.body?.string() ?: return ConfigFetchResult.Failure(response.code)
                try {
                    ConfigFetchResult.Success(json.decodeFromString<RemoteConfig>(body).sanitized())
                } catch (e: Exception) {
                    ConfigFetchResult.Failure(response.code)
                }
            }
        } catch (e: IOException) {
            ConfigFetchResult.Failure(null)
        }
    }
}
