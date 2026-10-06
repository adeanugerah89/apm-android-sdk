package edts.apm.android.sdk.network

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A33 — what `network` and `network_failure` carry beyond the basics (`01` §4.1/§4.2), driven by real
 * OkHttp calls so the values come from real handshakes and real exceptions.
 */
class NetworkEventAttrsTest {

    private class RecordingQueue : EventQueue {
        val events = CopyOnWriteArrayList<Event>()
        override fun enqueue(event: Event): Boolean { events.add(event); return true }
        override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
        override fun delete(ids: List<String>) {}
        override fun stats(): QueueStats = QueueStats(events.size, 0)
    }

    private lateinit var tls: LocalTlsServer
    private lateinit var queue: RecordingQueue

    @Before fun setUp() {
        tls = LocalTlsServer()
        queue = RecordingQueue()
    }

    @After fun tearDown() {
        tls.close()
    }

    private fun instrument(b: OkHttpClient.Builder): OkHttpClient = b.eventListenerFactory(
        ApmEventListenerFactory(queue, { "session-1" }, { 1L }, { EventContext() }, ingestHost = "ingest.example.com")
    ).build()

    private fun attr(e: Event, key: String) = e.attrs[key]?.jsonPrimitive?.content

    // ─── network: tls_version / reused_connection ───────────────────────────────

    @Test fun `an https call reports the negotiated TLS version`() {
        tls.server.enqueue(MockResponse().setBody("ok"))

        instrument(tls.trustingClient()).newCall(Request.Builder().url(tls.url()).build()).execute().close()

        val version = attr(queue.events.single(), "tls_version")
        assertNotNull(version)
        assertTrue("same spelling as iOS (TLSv1.3): $version", version!!.matches(Regex("""TLSv1\.[23]""")))
    }

    @Test fun `a second call on a pooled connection is reused, and still reports the TLS version`() {
        tls.server.enqueue(MockResponse().setBody("a"))
        tls.server.enqueue(MockResponse().setBody("b"))
        val client = instrument(tls.trustingClient())

        client.newCall(Request.Builder().url(tls.url()).build()).execute().close()
        client.newCall(Request.Builder().url(tls.url()).build()).execute().close()

        val (first, second) = queue.events.toList()
        assertEquals("false", attr(first, "reused_connection"))
        assertEquals("a pooled call never runs secureConnect, so this proves the version is read elsewhere",
            "true", attr(second, "reused_connection"))
        assertEquals(attr(first, "tls_version"), attr(second, "tls_version"))
    }

    @Test fun `a plain http call has no tls_version but does say whether the connection was reused`() {
        val plain = MockWebServer().apply { start(); enqueue(MockResponse().setBody("ok")) }
        try {
            instrument(OkHttpClient.Builder()).newCall(Request.Builder().url(plain.url("/x")).build()).execute().close()
        } finally {
            plain.shutdown()
        }

        val event = queue.events.single()
        assertEquals(null, attr(event, "tls_version"))
        assertEquals("false", attr(event, "reused_connection"))
    }

    // ─── network_failure: error_domain / error_code / underlying_* ──────────────

    @Test fun `a refused connection reports the real exception class, not a placeholder`() {
        val dead = MockWebServer().apply { start() }
        val port = dead.port
        dead.shutdown()

        val thrown = try {
            instrument(OkHttpClient.Builder()).newCall(Request.Builder().url("http://127.0.0.1:$port/").build()).execute().close()
            fail("expected the connection to be refused"); null
        } catch (e: IOException) { e }!!

        val event = queue.events.single { it.type == EventType.NETWORK_FAILURE }
        assertEquals(thrown.javaClass.name, attr(event, "error_domain"))
        assertNotEquals("the old constant", "java.io.IOException", attr(event, "error_domain"))
        assertNotNull("error_code is required for transport failures (01 4.2)", attr(event, "error_code"))
    }

    @Test fun `a TLS trust failure carries its real cause as underlying`() {
        val thrown = try {
            // No custom trust store: the default one does not know the fixture certificate.
            instrument(OkHttpClient.Builder()).newCall(Request.Builder().url(tls.url()).build()).execute().close()
            fail("the default trust store should reject the fixture certificate"); null
        } catch (e: IOException) { e }!!

        val event = queue.events.single { it.type == EventType.NETWORK_FAILURE }
        assertEquals(thrown.javaClass.name, attr(event, "error_domain"))
        val cause = thrown.cause
        assertNotNull("a handshake failure should wrap a validation cause", cause)
        assertEquals(cause!!.javaClass.name, attr(event, "underlying_domain"))
        assertNotNull(attr(event, "underlying_code"))
    }

    @Test fun `a DNS failure reports UnknownHostException and the category dns`() {
        // .invalid is reserved (RFC 2606) and never resolves; offline machines fail the same way.
        val thrown = try {
            instrument(OkHttpClient.Builder()).newCall(Request.Builder().url("http://apm-a33.invalid/").build()).execute().close()
            fail("a reserved name must not resolve"); null
        } catch (e: IOException) { e }!!

        val event = queue.events.single { it.type == EventType.NETWORK_FAILURE }
        assertEquals("dns", attr(event, "failure_category"))
        assertEquals(thrown.javaClass.name, attr(event, "error_domain"))
    }

    @Test fun `an http_error response still carries no error_domain or error_code (iOS does not invent them)`() {
        val plain = MockWebServer().apply { start(); enqueue(MockResponse().setResponseCode(500)) }
        try {
            instrument(OkHttpClient.Builder()).newCall(Request.Builder().url(plain.url("/x")).build()).execute().close()
        } finally {
            plain.shutdown()
        }

        val failure = queue.events.single { it.type == EventType.NETWORK_FAILURE }
        assertEquals("http_error", attr(failure, "failure_category"))
        assertFalse(failure.attrs.containsKey("error_domain"))
        assertFalse(failure.attrs.containsKey("error_code"))
    }

    // ─── A38 · SEC-02 / SEC-03: no header and no query ever reaches an event ────────────────────

    private fun wire(e: Event) = Json { encodeDefaults = true }.encodeToString(Event.serializer(), e)

    @Test fun `Authorization, Cookie and the query string never appear in a network event, as JSON or in any attribute`() {
        val plain = MockWebServer().apply { start(); enqueue(MockResponse().setBody("ok").addHeader("Set-Cookie", "session=server-secret-cookie")) }
        try {
            val request = Request.Builder().url(plain.url("/v1/thing?msisdn=081234567890&page=2"))
                .header("Authorization", "Bearer super-secret-token-xyz")
                .header("Cookie", "sid=client-secret-cookie")
                .header("X-Custom-Tracking", "custom-header-value")
                .build()
            instrument(OkHttpClient.Builder()).newCall(request).execute().close()
        } finally {
            plain.shutdown()
        }

        val event = queue.events.single()
        val json = wire(event)
        for (secret in listOf(
            "super-secret-token-xyz", "client-secret-cookie", "server-secret-cookie", "custom-header-value",
            "081234567890", "msisdn", "page=2", "Authorization", "Cookie", "Set-Cookie", "X-Custom-Tracking"
        )) {
            assertFalse("'$secret' must never reach an event: $json", json.contains(secret))
        }
        // The query is not even kept as parameter names: the path is the path.
        assertEquals("/v1/thing", event.attrs.getValue("path").jsonPrimitive.content)
        // And no header-shaped attribute exists at all.
        assertFalse(event.attrs.keys.any { it.contains("header", ignoreCase = true) })
    }

    @Test fun `a failed request records no headers or query either`() {
        val dead = MockWebServer().apply { start() }
        val port = dead.port
        dead.shutdown()

        try {
            instrument(OkHttpClient.Builder()).newCall(
                Request.Builder().url("http://127.0.0.1:$port/v1/thing?otp=998877").header("Authorization", "Bearer failed-call-token").build()
            ).execute().close()
        } catch (_: IOException) {
        }

        val json = wire(queue.events.single { it.type == EventType.NETWORK_FAILURE })
        assertFalse(json, json.contains("failed-call-token") || json.contains("998877") || json.contains("otp"))
    }
}
