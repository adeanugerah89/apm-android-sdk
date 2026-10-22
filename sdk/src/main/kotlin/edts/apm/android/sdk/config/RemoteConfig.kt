package edts.apm.android.sdk.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Remote configuration payload — `01` §9 (`GET /v1/config`). (MOB-20/21)
 *
 * Every field carries a default identical to [DEFAULT], so a partial or older payload still
 * decodes: the backend can add fields without breaking installed SDKs, and can omit fields it
 * has no opinion on. `ignoreUnknownKeys` on the decoder covers the other direction — a newer
 * backend sending fields this SDK version has never heard of must not fail the whole fetch.
 *
 * SEC-20: this object only flips flags the SDK already implements. Nothing here can introduce
 * new behaviour — [disabledFeatures] is matched against a fixed, compiled-in set of names.
 */
@Serializable
internal data class RemoteConfig(
    /** Kill switch (MOB-21). `false` = SDK captures nothing and uploads nothing. */
    val enabled: Boolean = true,
    val sampling: Sampling = Sampling(),
    @SerialName("max_batch") val maxBatch: Int = 200,
    /**
     * A `Double`, not a `Long`: the backend stores it as `DOUBLE PRECISION` (apm-backend migration
     * 000006, only `>= 30` is enforced), so `45.5` is a legal value. Decoding it as `Long` throws, and a
     * thrown decode discards the whole config — including `enabled: false`. iOS decodes it as `Double`.
     */
    @SerialName("upload_interval_s") val uploadIntervalS: Double = 30.0,
    @SerialName("disabled_features") val disabledFeatures: List<String> = emptyList()
) {
    @Serializable
    data class Sampling(
        val network: Double = 1.0,
        val breadcrumb: Double = 1.0
    )

    /**
     * True when [feature] is switched off remotely. Unknown names in [disabledFeatures] are
     * simply never matched — an unrecognised name must not disable something at random.
     */
    fun isDisabled(feature: Feature): Boolean = disabledFeatures.contains(feature.key)

    /**
     * Clamps values that would otherwise be nonsense if a backend sent them wrong. The SDK is
     * the last line of defence here: a `max_batch` of 0 would stall the queue forever and a
     * negative interval would busy-loop the scheduler. Out-of-range values fall back to the
     * corresponding [DEFAULT] rather than being clamped to an edge, so a clearly-broken payload
     * behaves exactly like no payload at all.
     */
    fun sanitized(): RemoteConfig = copy(
        sampling = Sampling(
            network = sampling.network.takeIf { it in 0.0..1.0 } ?: DEFAULT.sampling.network,
            breadcrumb = sampling.breadcrumb.takeIf { it in 0.0..1.0 } ?: DEFAULT.sampling.breadcrumb
        ),
        maxBatch = maxBatch.takeIf { it in 1..MAX_ALLOWED_BATCH } ?: DEFAULT.maxBatch,
        uploadIntervalS = uploadIntervalS.takeIf { it in MIN_INTERVAL_S..MAX_INTERVAL_S } ?: DEFAULT.uploadIntervalS
    )

    /** Feature names the SDK understands in `disabled_features`. Compiled-in set — SEC-20. */
    enum class Feature(val key: String) {
        NETWORK("network"),
        BREADCRUMB("breadcrumb"),
        CRASH("crash"),
        ANR("anr"),
        COLD_START("cold_start"),
        /** SEC-11's mandatory pinning kill switch — see [edts.apm.android.sdk.internal.PinnedCallFactory]. */
        PINNING("pinning"),
    }

    companion object {
        /** Fallback used before the first successful fetch and whenever the cache is unusable. */
        val DEFAULT = RemoteConfig()

        const val MAX_ALLOWED_BATCH = 1_000
        const val MIN_INTERVAL_S = 5.0
        const val MAX_INTERVAL_S = 24.0 * 60 * 60
    }
}
