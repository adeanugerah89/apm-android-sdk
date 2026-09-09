package edts.apm.android.sdk.internal

import okhttp3.ConnectionSpec
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.UnknownServiceException

/** SEC-10/SEC-12 + MOB-10 — the one place the SDK's own client is built. */
class ApmHttpClientFactoryTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `the client carries the TLS floor and no cleartext spec`() {
        val client = ApmHttpClientFactory.createUninstrumented()

        assertEquals(TlsPolicy.CONNECTION_SPECS, client.connectionSpecs)
        assertTrue(client.connectionSpecs.none { it == ConnectionSpec.CLEARTEXT })
    }

    @Test fun `the client has no EventListener attached — it cannot instrument itself (MOB-10)`() {
        val client = ApmHttpClientFactory.createUninstrumented()

        // OkHttp's default factory yields the shared no-op listener for every call.
        val listener = client.eventListenerFactory.create(client.newCall(Request.Builder().url("https://example.com").build()))
        assertEquals(okhttp3.EventListener.NONE, listener)
    }

    @Test fun `a cleartext call is refused outright, not downgraded (SEC-12)`() {
        val client = ApmHttpClientFactory.createUninstrumented()
        server.enqueue(MockResponse().setResponseCode(202))

        val error = try {
            client.newCall(Request.Builder().url(server.url("/v1/ingest")).build()).execute()
            null
        } catch (e: IOException) {
            e
        }

        assertTrue("expected a refusal, got $error", error is UnknownServiceException)
        assertEquals("the request must never have reached the server", 0, server.requestCount)
    }

    @Test fun `every timeout is finite so a hung upload cannot stall the queue drain`() {
        val client = ApmHttpClientFactory.createUninstrumented()

        assertTrue(client.connectTimeoutMillis > 0)
        assertTrue(client.readTimeoutMillis > 0)
        assertTrue(client.writeTimeoutMillis > 0)
        assertTrue(client.callTimeoutMillis > 0)
    }

    @Test fun `each call returns a fresh client, never a client shared with the host app`() {
        assertNotSame(ApmHttpClientFactory.createUninstrumented(), ApmHttpClientFactory.createUninstrumented())
    }
}
