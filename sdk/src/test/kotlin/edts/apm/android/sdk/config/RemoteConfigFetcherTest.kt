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
        assertEquals(60L, result.config.uploadIntervalS)
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
}
