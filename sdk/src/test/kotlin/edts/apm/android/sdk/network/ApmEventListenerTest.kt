package edts.apm.android.sdk.network

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ApmEventListener — MOB-01/02/02b/03/10.
 * Drives real requests through OkHttp against MockWebServer so the EventListener timing
 * callbacks fire exactly as they would on-device.
 */
class ApmEventListenerTest {

    private class RecordingQueue : EventQueue {
        val events = CopyOnWriteArrayList<Event>()
        override fun enqueue(event: Event): Boolean { events.add(event); return true }
        override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
        override fun delete(ids: List<String>) {}
        override fun stats(): QueueStats = QueueStats(events.size, 0)
    }

    private lateinit var server: MockWebServer
    private lateinit var queue: RecordingQueue

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        queue = RecordingQueue()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun clientFor(ingestHost: String) = OkHttpClient.Builder()
        .eventListenerFactory(
            ApmEventListenerFactory(
                queue = queue,
                sessionProvider = { "session-1" },
                seqProvider = { 1L },
                contextProvider = { EventContext() },
                ingestHost = ingestHost
            )
        )
        .build()

    @Test fun `successful call emits one network event`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = clientFor(ingestHost = "ingest.example.com")

        client.newCall(Request.Builder().url(server.url("/v1/thing")).build()).execute().use {
            assertEquals(200, it.code)
        }

        assertEquals(1, queue.events.size)
        val event = queue.events.single()
        assertEquals(EventType.NETWORK, event.type)
        assertEquals("/v1/thing", event.attrs.getValue("path").jsonPrimitive.content)
        assertEquals("200", event.attrs.getValue("status_code").jsonPrimitive.content)
    }

    @Test fun `4xx response emits both network and network_failure http_error`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val client = clientFor(ingestHost = "ingest.example.com")

        client.newCall(Request.Builder().url(server.url("/v1/missing")).build()).execute().use {
            assertEquals(404, it.code)
        }

        assertEquals(2, queue.events.size)
        assertEquals(EventType.NETWORK, queue.events[0].type)
        assertEquals(EventType.NETWORK_FAILURE, queue.events[1].type)
        assertEquals("http_error", queue.events[1].attrs.getValue("failure_category").jsonPrimitive.content)
        assertEquals("404", queue.events[1].attrs.getValue("status_code").jsonPrimitive.content)
    }

    @Test fun `ingest host is excluded from capture (MOB-10)`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val ingestHost = server.hostName
        val client = clientFor(ingestHost = ingestHost)

        client.newCall(Request.Builder().url(server.url("/v1/ingest")).build()).execute().use {
            assertEquals(200, it.code)
        }

        assertTrue("no events should be captured for the ingest host itself", queue.events.isEmpty())
    }

    @Test fun `transport failure emits network_failure with a mapped category`() {
        val deadPort = server.port
        server.shutdown()  // connection will now be refused
        val client = clientFor(ingestHost = "ingest.example.com")

        try {
            client.newCall(Request.Builder().url("http://127.0.0.1:$deadPort/v1/thing").build()).execute()
            fail("expected connection to be refused")
        } catch (e: ConnectException) {
            // expected — MockWebServer is no longer listening
        }

        assertEquals(1, queue.events.size)
        val event = queue.events.single()
        assertEquals(EventType.NETWORK_FAILURE, event.type)
        assertNotNull(event.attrs["failure_category"])
    }

    @Test fun `path is normalized before it ever reaches the event (SEC-03b)`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = clientFor(ingestHost = "ingest.example.com")

        client.newCall(Request.Builder().url(server.url("/user/628123456789/profile")).build())
            .execute().use { assertEquals(200, it.code) }

        assertEquals("/user/{id}/profile", queue.events.single().attrs.getValue("path").jsonPrimitive.content)
    }

    @Test fun `request and response bodies are never read into an event (SEC-04)`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"phone":"08123456789"}"""))
        val client = clientFor(ingestHost = "ingest.example.com")

        val body = """{"otp":"999999"}""".toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(server.url("/v1/thing")).post(body).build())
            .execute().use { assertEquals(200, it.code) }

        val serialized = queue.events.joinToString { it.attrs.toString() }
        assertFalse("request body must never appear in an event", serialized.contains("999999"))
        assertFalse("response body must never appear in an event", serialized.contains("08123456789"))
    }
}
