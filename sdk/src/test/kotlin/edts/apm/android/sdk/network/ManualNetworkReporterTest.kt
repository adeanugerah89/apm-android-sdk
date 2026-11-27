package edts.apm.android.sdk.network

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList

/** A51 — `APM.reportNetworkRequest`: the same events the OkHttp listener produces, for stacks it cannot see. */
class ManualNetworkReporterTest {

    private class RecordingQueue : EventQueue {
        val events = CopyOnWriteArrayList<Event>()
        override fun enqueue(event: Event): Boolean { events.add(event); return true }
        override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
        override fun delete(ids: List<String>) {}
        override fun stats(): QueueStats = QueueStats(events.size, 0)
    }

    private val queue = RecordingQueue()
    private var seq = 0L
    private fun reporter(ingestHost: String? = "ingest.example.com") =
        ManualNetworkReporter(queue, { ++seq }, { EventContext() }, ingestHost)

    private fun ManualNetworkReporter.ok(
        url: String = "https://api.example.com/v1/orders", method: String = "GET", status: Int = 200,
        durationMs: Long = 120, req: Long? = null, res: Long? = null
    ) = report(url, method, durationMs, status, null, req, res)

    private fun Event.str(key: String) = attrs.getValue(key).jsonPrimitive.content

    // ─── completed responses ────────────────────────────────────────────────

    @Test fun `a 200 produces one network event with host, path, method, status and duration`() {
        assertTrue(reporter().ok(status = 200, durationMs = 87, req = 10, res = 2048))

        val e = queue.events.single()
        assertEquals(EventType.NETWORK, e.type)
        assertEquals("api.example.com", e.str("host"))
        assertEquals("/v1/orders", e.str("path"))
        assertEquals("GET", e.str("method"))
        assertEquals("200", e.str("status_code"))
        assertEquals("87", e.str("duration_ms"))
        assertEquals("10", e.str("req_bytes"))
        assertEquals("2048", e.str("res_bytes"))
    }

    @Test fun `a response with status 400 or above produces network AND network_failure(http_error) (MOB-02b)`() {
        reporter().ok(status = 503)

        assertEquals(listOf(EventType.NETWORK, EventType.NETWORK_FAILURE), queue.events.map { it.type })
        val failure = queue.events[1]
        assertEquals("http_error", failure.str("failure_category"))
        assertEquals("503", failure.str("status_code"))
        assertTrue("sequence numbers advance per event", queue.events[0].seq < queue.events[1].seq)
    }

    @Test fun `a 399 is not an error, a 400 is`() {
        reporter().ok(status = 399)
        assertEquals(1, queue.events.size)
        reporter().ok(status = 400)
        assertEquals(3, queue.events.size)
    }

    @Test fun `phase timings and connection details are left out rather than invented`() {
        reporter().ok()
        val keys = queue.events.single().attrs.keys
        for (k in listOf("dns_ms", "tcp_ms", "tls_ms", "ttfb_ms", "protocol", "tls_version", "reused_connection")) {
            assertFalse("$k must be absent", k in keys)
        }
    }

    // ─── transport failures ─────────────────────────────────────────────────

    @Test fun `a transport failure maps to the same categories as OkHttp's`() {
        val cases = listOf<Pair<Throwable, String>>(
            SocketTimeoutException("timeout") to "timeout",
            UnknownHostException("nope") to "dns",
            IOException("Canceled") to "cancelled",
            CancellationException("job cancelled") to "cancelled",
            IllegalStateException("not an IO error") to "unknown"
        )
        for ((error, expected) in cases) {
            queue.events.clear()
            assertTrue(reporter().report("https://api.example.com/x", "POST", 30, null, error, null, null))
            val e = queue.events.single()
            assertEquals(EventType.NETWORK_FAILURE, e.type)
            assertEquals("${error.javaClass.simpleName} -> $expected", expected, e.str("failure_category"))
            assertEquals(error.javaClass.name, e.str("error_domain"))
            assertEquals("30", e.str("duration_ms"))
        }
    }

    @Test fun `status wins when both a status and an error are given`() {
        reporter().report("https://api.example.com/x", "GET", 10, 200, IOException("late"), null, null)
        assertEquals(EventType.NETWORK, queue.events.single().type)
    }

    // ─── what is never recorded ─────────────────────────────────────────────

    @Test fun `the query string, fragment and credentials never reach an event (SEC-03)`() {
        reporter().ok(url = "https://user:hunter2@api.example.com/v1/orders?token=SECRET&email=a@b.co#frag")

        val dump = queue.events.single().attrs.toString()
        for (secret in listOf("hunter2", "user", "SECRET", "token", "a@b.co", "frag", "?")) {
            assertFalse("`$secret` must not appear in $dump", dump.contains(secret))
        }
        assertEquals("/v1/orders", queue.events.single().str("path"))
    }

    @Test fun `the path is normalised like the automatic capture's (SEC-03b)`() {
        reporter().ok(url = "https://api.example.com/users/550e8400-e29b-41d4-a716-446655440000/orders/123456")
        val path = queue.events.single().str("path")
        assertFalse(path, path.contains("550e8400"))
        assertFalse(path, path.contains("123456"))
    }

    @Test fun `the ingest host is never reported (MOB-10)`() {
        assertFalse(reporter(ingestHost = "ingest.example.com").ok(url = "https://ingest.example.com/v1/ingest"))
        assertFalse(reporter(ingestHost = "ingest.example.com").ok(url = "https://INGEST.example.com/v1/ingest"))
        assertTrue(queue.events.isEmpty())
    }

    @Test fun `input that is not a request is ignored, never thrown on`() {
        val r = reporter()
        assertFalse(r.report("not a url", "GET", 1, 200, null, null, null))
        assertFalse(r.report("ftp://files.example.com/x", "GET", 1, 200, null, null, null))
        assertFalse(r.report("", "GET", 1, 200, null, null, null))
        assertFalse(r.report("https://api.example.com/x", "  ", 1, 200, null, null, null))   // blank method
        assertFalse(r.report("https://api.example.com/x", "GET", 1, 99, null, null, null))   // not an HTTP status
        assertFalse(r.report("https://api.example.com/x", "GET", 1, 600, null, null, null))
        assertFalse(r.report("https://api.example.com/x", "GET", 1, null, null, null, null)) // neither status nor error
        assertTrue(queue.events.isEmpty())
    }

    @Test fun `method is upper-cased and bounded, duration and sizes are sanitised`() {
        reporter().report("https://api.example.com/x", "get", -5, 200, null, -1, -1)
        val e = queue.events.single()
        assertEquals("GET", e.str("method"))
        assertEquals("0", e.str("duration_ms"))
        assertFalse("req_bytes" in e.attrs)
        assertFalse("res_bytes" in e.attrs)

        queue.events.clear()
        reporter().report("https://api.example.com/x", "a".repeat(500), 1, 200, null, null, null)
        assertTrue(queue.events.single().str("method").length <= 16)
    }

    // ─── parity with the automatic capture ──────────────────────────────────

    @Test fun `the attribute keys match what the OkHttp listener emits for the same request, minus the timings`() {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
            val auto = RecordingQueue()
            val client = OkHttpClient.Builder()
                .eventListenerFactory { call -> ApmEventListener(auto, { "s" }, { 1L }, { EventContext() }, "other.host") }
                .build()
            val url = server.url("/v1/orders")
            client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }

            reporter(ingestHost = null).ok(url = url.toString(), status = 404)

            val manualTypes = queue.events.map { it.type }
            assertEquals("same event types in the same order", auto.events.map { it.type }, manualTypes)
            val timingOnly = setOf("dns_ms", "tcp_ms", "tls_ms", "ttfb_ms", "protocol", "tls_version", "reused_connection", "req_bytes", "res_bytes")
            for (i in auto.events.indices) {
                val autoKeys = auto.events[i].attrs.keys - timingOnly
                assertEquals("event $i", autoKeys, queue.events[i].attrs.keys - timingOnly)
                for (k in autoKeys - "duration_ms") assertEquals(k, auto.events[i].str(k), queue.events[i].str(k))
            }
        } finally { server.shutdown() }
    }
}
