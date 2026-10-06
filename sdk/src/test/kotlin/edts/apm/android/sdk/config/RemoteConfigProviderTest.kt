package edts.apm.android.sdk.config

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** MOB-20/21 — startup resolution order and the failure behaviour that makes the kill switch stick. */
class RemoteConfigProviderTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeFetcher(var result: ConfigFetchResult) : ConfigFetcher {
        var calls = 0
        override fun fetch(): ConfigFetchResult { calls++; return result }
    }

    private fun store() = RemoteConfigStore(File(tmp.root, "config.json"))

    @Test fun `starts at the default before anything is loaded`() {
        val provider = RemoteConfigProvider(store(), FakeFetcher(ConfigFetchResult.Failure(null)))

        assertTrue(provider.isEnabled)
        assertEquals(RemoteConfig.DEFAULT, provider.config)
    }

    @Test fun `loadFromCache applies a cached kill switch with no network at all`() {
        val s = store()
        s.write(RemoteConfig(enabled = false))
        val fetcher = FakeFetcher(ConfigFetchResult.Failure(null))

        val provider = RemoteConfigProvider(s, fetcher)
        provider.loadFromCache()

        assertFalse(provider.isEnabled)
        assertEquals(0, fetcher.calls)
    }

    @Test fun `loadFromCache leaves the default in place when there is no cache`() {
        val provider = RemoteConfigProvider(store(), FakeFetcher(ConfigFetchResult.Failure(null)))

        provider.loadFromCache()

        assertTrue(provider.isEnabled)
    }

    @Test fun `refresh publishes and caches a successful fetch`() {
        val s = store()
        val fetched = RemoteConfig(enabled = false, maxBatch = 10)
        val provider = RemoteConfigProvider(s, FakeFetcher(ConfigFetchResult.Success(fetched)))

        provider.refresh()

        assertFalse(provider.isEnabled)
        assertEquals(10, provider.config.maxBatch)
        assertEquals(fetched, s.read())
    }

    @Test fun `a failed refresh never re-enables an SDK that was killed`() {
        val s = store()
        s.write(RemoteConfig(enabled = false))
        val fetcher = FakeFetcher(ConfigFetchResult.Failure(500))
        val provider = RemoteConfigProvider(s, fetcher)
        provider.loadFromCache()

        provider.refresh()

        assertFalse(provider.isEnabled)
        assertFalse(s.read()!!.enabled)   // the bad response did not overwrite the cache either
    }

    @Test fun `refresh can lift the kill switch again once the backend says enabled`() {
        val s = store()
        s.write(RemoteConfig(enabled = false))
        val fetcher = FakeFetcher(ConfigFetchResult.Success(RemoteConfig(enabled = true)))
        val provider = RemoteConfigProvider(s, fetcher)
        provider.loadFromCache()
        assertFalse(provider.isEnabled)

        provider.refresh()

        assertTrue(provider.isEnabled)
        assertTrue(s.read()!!.enabled)
    }

    // ─── A31 · upload_interval_s ───────────────────────────────────────────────

    @Test fun `the host's interval stands until the server has spoken`() {
        val provider = RemoteConfigProvider(store(), FakeFetcher(ConfigFetchResult.Failure(null)))
        provider.loadFromCache()   // no cache

        // The compiled-in default is also 30 s; a host that chose 90 s must not be reset to it.
        assertEquals(90_000L, provider.uploadIntervalMs(hostDefaultMs = 90_000L))
    }

    @Test fun `a cached server interval wins over the host's, with no network`() {
        val s = store()
        s.write(RemoteConfig(uploadIntervalS = 120.0))
        val provider = RemoteConfigProvider(s, FakeFetcher(ConfigFetchResult.Failure(null)))

        provider.loadFromCache()

        assertEquals(120_000L, provider.uploadIntervalMs(hostDefaultMs = 90_000L))
    }

    @Test fun `a live fetch takes over, in either direction`() {
        val provider = RemoteConfigProvider(store(), FakeFetcher(ConfigFetchResult.Success(RemoteConfig(uploadIntervalS = 10.0))))
        assertEquals("before the fetch", 90_000L, provider.uploadIntervalMs(90_000L))

        provider.refresh()

        assertEquals("faster than the host asked", 10_000L, provider.uploadIntervalMs(90_000L))
    }

    @Test fun `a failed refresh does not hand the interval back to the host`() {
        val s = store()
        s.write(RemoteConfig(uploadIntervalS = 120.0))
        val provider = RemoteConfigProvider(s, FakeFetcher(ConfigFetchResult.Failure(500)))
        provider.loadFromCache()

        provider.refresh()

        assertEquals(120_000L, provider.uploadIntervalMs(hostDefaultMs = 90_000L))
    }

    @Test fun `a fractional server interval converts to milliseconds without losing the fraction`() {
        val s = store()
        s.write(RemoteConfig(uploadIntervalS = 45.5))
        val provider = RemoteConfigProvider(s, FakeFetcher(ConfigFetchResult.Failure(null)))
        provider.loadFromCache()

        assertEquals(45_500L, provider.uploadIntervalMs(hostDefaultMs = 90_000L))
    }

    // ─── review finding 2 · a config that arrived over the unpinned recovery path ────────────────

    private fun viaFallback(config: RemoteConfig) = ConfigFetchResult.Success(config, viaUnpinnedFallback = true)

    @Test fun `from the unpinned path only 'switch pinning off' is honoured - nothing else`() {
        val provider = RemoteConfigProvider(store(), FakeFetcher(viaFallback(RemoteConfig(
            enabled = false, maxBatch = 5, uploadIntervalS = 999.0, disabledFeatures = listOf("pinning", "network", "crash")
        ))))

        provider.refresh()

        assertTrue(provider.config.isDisabled(RemoteConfig.Feature.PINNING))
        assertTrue("a man-in-the-middle must not also be able to kill the SDK", provider.isEnabled)
        assertFalse("nor switch off capture", provider.config.isDisabled(RemoteConfig.Feature.NETWORK))
        assertFalse(provider.config.isDisabled(RemoteConfig.Feature.CRASH))
        assertEquals(RemoteConfig.DEFAULT.maxBatch, provider.config.maxBatch)
    }

    @Test fun `a config from the unpinned path is never written to the cache - a restart retries the pinned path`() {
        val s = store()
        val provider = RemoteConfigProvider(s, FakeFetcher(viaFallback(RemoteConfig(disabledFeatures = listOf("pinning")))))

        provider.refresh()

        assertNull(s.read())
        // And it does not count as the server having spoken, so the host's own interval is not overridden.
        assertEquals(90_000L, provider.uploadIntervalMs(hostDefaultMs = 90_000L))
    }

    @Test fun `an ordinary successful fetch is still cached and applied in full`() {
        val s = store()
        val provider = RemoteConfigProvider(s, FakeFetcher(ConfigFetchResult.Success(RemoteConfig(enabled = false, maxBatch = 10))))

        provider.refresh()

        assertFalse(provider.isEnabled)
        assertEquals(10, s.read()!!.maxBatch)
    }
}
