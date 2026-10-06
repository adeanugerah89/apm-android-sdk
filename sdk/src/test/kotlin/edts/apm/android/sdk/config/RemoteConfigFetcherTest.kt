package edts.apm.android.sdk.config

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** MOB-20 — `GET /v1/config` (`01` §9): headers, decoding, and never throwing on failure. */
class RemoteConfigFetcherTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun fetcher(client: OkHttpClient = OkHttpClient()) = RemoteConfigFetcher(
        client = client,
        configUrl = server.url("/v1/config").toString(),
        appKey = "app-key-123",
        sdkHeaderValue = "apm-android-sdk/0.1.0",
        isSecureUrl = { true }   // MockWebServer is cleartext; the SEC-12 gate itself is covered in TlsFailClosedTest
    )

    @Test fun `sends a GET with the app key and sdk headers`() {
        server.enqueue(MockResponse().setBody("""{ "enabled": true }"""))

        fetcher().fetch()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/config", request.path)
        assertEquals("app-key-123", request.getHeader("X-APM-Key"))
        assertEquals("apm-android-sdk/0.1.0", request.getHeader("X-APM-Sdk"))
    }

    @Test fun `a 200 with the documented payload decodes to Success`() {
        server.enqueue(
            MockResponse().setBody(
                """{ "enabled": false,
                     "sampling": { "network": 0.5, "breadcrumb": 1.0 },
                     "max_batch": 100,
                     "upload_interval_s": 60,
                     "disabled_features": ["anr"] }"""
            )
        )

        val result = fetcher().fetch() as ConfigFetchResult.Success

        assertFalse(result.config.enabled)
        assertEquals(0.5, result.config.sampling.network, 0.0)
        assertEquals(100, result.config.maxBatch)
        assertEquals(60.0, result.config.uploadIntervalS, 0.0)
        assertTrue(result.config.isDisabled(RemoteConfig.Feature.ANR))
    }

    @Test fun `a non-2xx response is a Failure carrying the status code`() {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = fetcher().fetch() as ConfigFetchResult.Failure

        assertEquals(503, result.statusCode)
    }

    @Test fun `an undecodable 200 body is a Failure, not a crash`() {
        server.enqueue(MockResponse().setBody("<html>not json</html>"))

        val result = fetcher().fetch() as ConfigFetchResult.Failure

        assertEquals(200, result.statusCode)
    }

    @Test fun `a transport failure is a Failure with no status code`() {
        server.shutdown()   // nothing listening any more

        val result = fetcher().fetch() as ConfigFetchResult.Failure

        assertNull(result.statusCode)
    }

    @Test fun `out-of-range values from the backend are sanitized before reaching the caller`() {
        server.enqueue(MockResponse().setBody("""{ "enabled": true, "max_batch": 999999 }"""))

        val result = fetcher().fetch() as ConfigFetchResult.Success

        assertEquals(RemoteConfig.DEFAULT.maxBatch, result.config.maxBatch)
    }

    // ─── SEC-11: the kill switch must stay reachable (A24) ──────────────────

    /** What a pinned client does when the certificate no longer matches its pins: OkHttp's own exception. */
    private class RejectingFactory : okhttp3.Call.Factory {
        var calls = 0
        private val client = OkHttpClient.Builder().addInterceptor {
            throw javax.net.ssl.SSLPeerUnverifiedException("Certificate pinning failure!\n  Peer certificate chain:\n    sha256/AAAA…")
        }.build()
        override fun newCall(request: okhttp3.Request): okhttp3.Call { calls++; return client.newCall(request) }
    }

    /** A failure that is NOT a pin problem: nothing is listening. */
    private class RefusedFactory : okhttp3.Call.Factory {
        var calls = 0
        override fun newCall(request: okhttp3.Request): okhttp3.Call {
            calls++
            return OkHttpClient().newCall(request.newBuilder().url("http://127.0.0.1:1/v1/config").build())
        }
    }

    @Test fun `a pinned client that rejects everything falls back to the unpinned one`() {
        server.enqueue(MockResponse().setBody("""{ "enabled": false, "disabled_features": ["pinning"] }"""))
        val pinned = RejectingFactory()

        val result = RemoteConfigFetcher(
            client = pinned,
            configUrl = server.url("/v1/config").toString(),
            appKey = "k",
            sdkHeaderValue = "s",
            isSecureUrl = { true },
            fallbackClient = OkHttpClient()
        ).fetch()

        assertTrue("without the fallback a bad pin locks the kill switch away", result is ConfigFetchResult.Success)
        assertTrue(pinned.calls > 0)
        assertTrue((result as ConfigFetchResult.Success).config.isDisabled(RemoteConfig.Feature.PINNING))
        assertTrue("the result says it came over the unpinned path", result.viaUnpinnedFallback)
    }

    @Test fun `the fallback is not used when the first attempt succeeds`() {
        server.enqueue(MockResponse().setBody("""{ "enabled": true }"""))
        val fallback = RejectingFactory()

        val result = RemoteConfigFetcher(
            client = OkHttpClient(),
            configUrl = server.url("/v1/config").toString(),
            appKey = "k",
            sdkHeaderValue = "s",
            isSecureUrl = { true },
            fallbackClient = fallback
        ).fetch()

        assertTrue(result is ConfigFetchResult.Success)
        assertEquals("pinning must stay in force whenever it is working", 0, fallback.calls)
    }

    @Test fun `with no fallback configured the original failure is returned unchanged`() {
        val result = RemoteConfigFetcher(
            client = RejectingFactory(),
            configUrl = server.url("/v1/config").toString(),
            appKey = "k",
            sdkHeaderValue = "s",
            isSecureUrl = { true },
            fallbackClient = null
        ).fetch()

        assertTrue(result is ConfigFetchResult.Failure)
    }

    // ─── Shape of apm-backend's real GET /v1/config body (internal/config.Config) ───────────────

    @Test fun `a fractional upload_interval_s still decodes, and does not take the kill switch down with it`() {
        // apm-backend stores upload_interval_s as DOUBLE PRECISION with only `>= 30` enforced, so
        // 45.5 is legal. Go's encoding/json also writes sampling 1.0 as a bare `1`.
        server.enqueue(
            MockResponse().setBody(
                """{"enabled":false,"sampling":{"network":1,"breadcrumb":1},"max_batch":200,"upload_interval_s":45.5,"disabled_features":[]}"""
            )
        )

        val result = fetcher().fetch()

        assertTrue("a decode failure would silently discard enabled:false: $result", result is ConfigFetchResult.Success)
        val config = (result as ConfigFetchResult.Success).config
        assertFalse(config.enabled)
        assertEquals(45.5, config.uploadIntervalS, 0.0)
        assertEquals(1.0, config.sampling.network, 0.0)
    }

    // ─── review finding 2 · the unpinned fallback is for a pin rejection, and nothing else ────────

    private fun fetcherWith(first: okhttp3.Call.Factory, fallback: okhttp3.Call.Factory?) = RemoteConfigFetcher(
        client = first, configUrl = server.url("/v1/config").toString(), appKey = "k", sdkHeaderValue = "s",
        isSecureUrl = { true }, fallbackClient = fallback
    )

    @Test fun `a refused connection does not trigger the unpinned fallback`() {
        val fallback = RejectingFactory()   // counts any use

        val result = fetcherWith(RefusedFactory(), fallback).fetch()

        assertTrue(result is ConfigFetchResult.Failure)
        assertFalse("not a pin problem", (result as ConfigFetchResult.Failure).pinningRejected)
        assertEquals("no second, unprotected attempt", 0, fallback.calls)
    }

    @Test fun `a 5xx from the server does not trigger the fallback, and costs exactly one request`() {
        server.enqueue(MockResponse().setResponseCode(503))
        val fallback = RejectingFactory()

        val result = fetcherWith(OkHttpClient(), fallback).fetch()

        assertEquals(503, (result as ConfigFetchResult.Failure).statusCode)
        assertEquals("the fallback never ran", 0, fallback.calls)
        assertEquals("one request, not two, against a struggling backend", 1, server.requestCount)
    }

    @Test fun `a 429 and an undecodable body do not trigger the fallback either`() {
        val fallback = RejectingFactory()

        server.enqueue(MockResponse().setResponseCode(429))
        fetcherWith(OkHttpClient(), fallback).fetch()
        server.enqueue(MockResponse().setBody("this is not json"))
        fetcherWith(OkHttpClient(), fallback).fetch()

        assertEquals(0, fallback.calls)
        assertEquals(2, server.requestCount)
    }

    @Test fun `a genuine pin rejection is reported as one`() {
        val result = fetcherWith(RejectingFactory(), fallback = null).fetch()

        assertTrue((result as ConfigFetchResult.Failure).pinningRejected)
    }

    @Test fun `a pin rejection does use the fallback, exactly once`() {
        server.enqueue(MockResponse().setBody("""{ "disabled_features": ["pinning"] }"""))
        val pinned = RejectingFactory()

        val result = fetcherWith(pinned, OkHttpClient()).fetch()

        assertTrue(result is ConfigFetchResult.Success)
        assertEquals(1, pinned.calls)
        assertEquals(1, server.requestCount)
    }
}
