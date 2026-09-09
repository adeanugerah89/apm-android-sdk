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
}
