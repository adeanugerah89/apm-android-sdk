package edts.apm.android.sdk.internal

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Builds the SDK's own OkHttpClient — the one used by
 * [edts.apm.android.sdk.sync.IngestUploader] and
 * [edts.apm.android.sdk.config.RemoteConfigFetcher].
 *
 * Two invariants are enforced here rather than at each call site, so there is exactly one place
 * that can get them wrong:
 *
 *  - **MOB-10:** the returned client has no `EventListener`. It is a fresh instance, never derived
 *    from the host app's client, so the SDK cannot instrument its own upload traffic.
 *  - **SEC-10/SEC-12:** [TlsPolicy.CONNECTION_SPECS] is the whole spec list, so TLS 1.2 is the
 *    floor and cleartext is unreachable. `retryOnConnectionFailure` stays at OkHttp's default —
 *    it retries the same secured route, never a downgraded one.
 *
 * Timeouts are finite and short-ish on purpose: telemetry is not worth holding a socket open for
 * minutes, and a hung upload delays the queue drain behind it.
 */
internal object ApmHttpClientFactory {

    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_TIMEOUT_S = 20L
    private const val WRITE_TIMEOUT_S = 20L
    private const val CALL_TIMEOUT_S = 30L

    fun createUninstrumented(): OkHttpClient = builder().build()

    /**
     * The pair [PinnedCallFactory] switches between (SEC-11). Both come from one base client via
     * `newBuilder()`, so they share a connection pool and dispatcher rather than standing up two
     * independent HTTP stacks for what is one endpoint.
     *
     * Returns two identical clients when [pinning] is null — pinning is off by default, and the
     * caller should simply not wrap them.
     */
    fun createPinnedPair(pinning: CertificatePinningConfig?): Pair<OkHttpClient, OkHttpClient> {
        val unpinned = createUninstrumented()
        val pinned = pinning
            ?.let { unpinned.newBuilder().certificatePinner(it.toCertificatePinner()).build() }
            ?: unpinned
        return pinned to unpinned
    }

    private fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectionSpecs(TlsPolicy.CONNECTION_SPECS)
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
}
