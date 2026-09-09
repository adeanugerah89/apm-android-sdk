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
class RemoteConfigProvider(
    private val store: RemoteConfigStore,
    private val fetcher: ConfigFetcher
) {
    private val current = AtomicReference(RemoteConfig.DEFAULT)

    /** The effective config right now. Never null; never throws. */
    val config: RemoteConfig get() = current.get()

    /** Convenience for the hottest check in the SDK — the kill switch (MOB-21). */
    val isEnabled: Boolean get() = current.get().enabled

    /**
     * Seeds the in-memory value from the on-disk cache. Call once at startup, before any capture
     * component is installed. No-op when there is no usable cache.
     */
    fun loadFromCache(): RemoteConfig {
        store.read()?.let { current.set(it) }
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
            is ConfigFetchResult.Success -> {
                current.set(result.config)
                store.write(result.config)
            }
            is ConfigFetchResult.Failure -> Unit  // keep whatever we already had
        }
        return current.get()
    }
}
