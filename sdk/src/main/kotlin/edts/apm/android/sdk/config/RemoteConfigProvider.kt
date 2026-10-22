package edts.apm.android.sdk.config

import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth for the currently-effective [RemoteConfig] (MOB-20/21).
 *
 * Resolution order at startup is deliberate: **cache first, then network**. [loadFromCache] is
 * synchronous and cheap enough to run before the first event is captured, so the SDK never spends
 * the window between launch and the first HTTP round-trip believing it is enabled when the last
 * known answer was that it is not. [refresh] then replaces it with the live answer.
 *
 * When both are unavailable the SDK falls back to [RemoteConfig.DEFAULT] — enabled. Failing open
 * is the right default for a monitoring SDK: an unreachable config endpoint must not silently
 * blind every app in the fleet. The kill switch is for turning capture *off* deliberately, and a
 * deliberate `enabled = false` survives offline launches through the cache.
 *
 * Thread-safe: the current value is an [AtomicReference], read from capture and sync paths on
 * arbitrary threads. [refresh] performs blocking I/O and must be called off the main thread.
 */
internal class RemoteConfigProvider(
    private val store: RemoteConfigStore,
    private val fetcher: ConfigFetcher
) {
    private val current = AtomicReference(RemoteConfig.DEFAULT)

    /**
     * True once [current] holds something the server actually said — from the cache or from a live
     * fetch — rather than the compiled-in [RemoteConfig.DEFAULT]. [RemoteConfig] cannot tell an
     * absent `upload_interval_s` from an explicit `30`, so without this the default would silently
     * overwrite whatever the host app configured, on every launch that has no network yet.
     */
    @Volatile private var hasServerValue = false

    /** The effective config right now. Never null; never throws. */
    val config: RemoteConfig get() = current.get()

    /** Convenience for the hottest check in the SDK — the kill switch (MOB-21). */
    val isEnabled: Boolean get() = current.get().enabled

    /**
     * Seconds between upload cycles (`01` §9 `upload_interval_s`). Once the server has spoken, its
     * value wins: this is remote control, and the operator needs to be able to slow a fleet down as
     * well as speed it up. Until then [hostDefaultMs] — what the host app put in `ApmConfig` — stands.
     */
    fun uploadIntervalMs(hostDefaultMs: Long): Long =
        if (hasServerValue) (current.get().uploadIntervalS * 1_000).toLong() else hostDefaultMs

    /**
     * Seeds the in-memory value from the on-disk cache. Call once at startup, before any capture
     * component is installed. No-op when there is no usable cache.
     */
    fun loadFromCache(): RemoteConfig {
        store.read()?.let { current.set(it); hasServerValue = true }
        return current.get()
    }

    /**
     * Fetches the live config and, on success, publishes and caches it. On failure the previous
     * value stays in effect — a failed refresh never resets the SDK to defaults, because that
     * would let a flaky config endpoint re-enable a deliberately killed SDK.
     *
     * Blocking; call from a background thread or worker. Returns the config in effect afterwards.
     */
    fun refresh(): RemoteConfig {
        when (val result = fetcher.fetch()) {
            is ConfigFetchResult.Success ->
                if (result.viaUnpinnedFallback) {
                    // Came over the recovery path that skips the pin: take ONLY "switch pinning off", keep
                    // everything else as it was, and do not cache it — a restart retries the pinned path.
                    val base = current.get()
                    val pinningOff = result.config.isDisabled(RemoteConfig.Feature.PINNING)
                    if (pinningOff && !base.isDisabled(RemoteConfig.Feature.PINNING)) {
                        current.set(base.copy(disabledFeatures = base.disabledFeatures + RemoteConfig.Feature.PINNING.key))
                    }
                } else {
                    current.set(result.config)
                    hasServerValue = true
                    store.write(result.config)
                }
            is ConfigFetchResult.Failure -> Unit  // keep whatever we already had
        }
        return current.get()
    }
}
