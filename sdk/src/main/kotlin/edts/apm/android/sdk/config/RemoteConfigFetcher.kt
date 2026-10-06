package edts.apm.android.sdk.config

import edts.apm.android.sdk.internal.TlsPolicy
import edts.apm.android.sdk.network.FailureCategory
import edts.apm.android.sdk.network.FailureCategoryMapper
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Request
import java.io.IOException

sealed class ConfigFetchResult {
    /**
     * [viaUnpinnedFallback]: this config arrived over the unpinned recovery path (SEC-11). The provider treats
     * it as untrusted-by-construction: only its "switch pinning off" part is honoured, in memory, never cached.
     */
    data class Success(val config: RemoteConfig, val viaUnpinnedFallback: Boolean = false) : ConfigFetchResult()
    /** Reached the server but got something unusable — non-2xx, or a body we cannot decode. */
    data class Failure(val statusCode: Int?, val pinningRejected: Boolean = false) : ConfigFetchResult()
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
    private val isSecureUrl: (String) -> Boolean = TlsPolicy::isSecureUrl,
    /**
     * Second attempt, used only when the first fails (SEC-11).
     *
     * Certificate pinning exists to be switched off remotely when a pin stops matching — that is
     * the whole reason `02` §6.3 makes a kill switch mandatory. But if the config fetch goes
     * through the pinned client, a bad pin blocks the very request that would disable pinning, and
     * recovery needs an app release: the multi-week blackout the project was founded on.
     * Reproduced on a device with a deliberately wrong pin before this existed.
     *
     * So a failed fetch is retried once against the **unpinned** client. That client still enforces
     * the TLS 1.2 floor and still cannot speak cleartext (SEC-12); what it drops is only the pin.
     * **Residual exposure, stated plainly:** an attacker holding a certificate from a CA the device
     * already trusts makes the pinned attempt fail and can then serve a config that turns pinning off. (The
     * earlier note that "the same attacker could intercept an unpinned ingest endpoint anyway" is only true
     * when pinning is off; with pinning on, pinning is what stops them.) The damage is bounded: the fallback
     * runs only after a real pin rejection, only its "disable pinning" part is honoured, and it is held in
     * memory for this process and never cached — so it lasts until the app restarts and the pinned path is
     * tried again. Whether that is acceptable, or the fallback should go, is an owner decision.
     */
    private val fallbackClient: Call.Factory? = null
) : ConfigFetcher {

    override fun fetch(): ConfigFetchResult {
        if (!isSecureUrl(configUrl)) return ConfigFetchResult.Failure(null)

        val first = fetchWith(client)
        if (first !is ConfigFetchResult.Failure) return first

        // The unpinned retry exists for ONE situation: the pin no longer matches. Any other failure — a 5xx, a
        // 429, a timeout, an undecodable body — is not a pin problem, and retrying it unpinned would double
        // the load on a struggling backend during an outage and hand an attacker a second, unprotected chance.
        val fallback = fallbackClient
        if (fallback == null || !first.pinningRejected) return first

        val second = fetchWith(fallback)
        return if (second is ConfigFetchResult.Success) second.copy(viaUnpinnedFallback = true) else second
    }

    private fun fetchWith(callFactory: Call.Factory): ConfigFetchResult {
        val request = Request.Builder()
            .url(configUrl)
            .header("X-APM-Key", appKey)
            .header("X-APM-Sdk", sdkHeaderValue)
            .get()
            .build()

        return try {
            callFactory.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return ConfigFetchResult.Failure(response.code)
                val body = response.body?.string() ?: return ConfigFetchResult.Failure(response.code)
                try {
                    ConfigFetchResult.Success(json.decodeFromString<RemoteConfig>(body).sanitized())
                } catch (e: Exception) {
                    ConfigFetchResult.Failure(response.code)
                }
            }
        } catch (e: IOException) {
            ConfigFetchResult.Failure(null, pinningRejected = FailureCategoryMapper.map(e) == FailureCategory.SSL_PINNING_REJECTED)
        }
    }
}
