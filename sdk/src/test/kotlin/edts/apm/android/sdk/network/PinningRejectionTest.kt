package edts.apm.android.sdk.network

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * MOB-03 end to end: a host app's `CertificatePinner` rejecting a certificate must surface as
 * `ssl_pinning_rejected`, not `ssl_certificate`.
 *
 * The earlier mapper test passed a hand-set boolean and proved nothing about a real failure — the
 * flag it exercised was never set by anything. These tests run a real TLS handshake against a local
 * HTTPS server using a fixture certificate (`test/resources/tls/localhost-test.p12`, a throwaway
 * key that protects nothing), with OkHttp itself producing the exception.
 */
class PinningRejectionTest {

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

    private fun url(): HttpUrl = tls.url()

    private fun client(pinner: CertificatePinner?, trustServerCert: Boolean = true): OkHttpClient =
        (if (trustServerCert) tls.trustingClient() else OkHttpClient.Builder())
            .apply { if (pinner != null) certificatePinner(pinner) }
            .eventListenerFactory(
                ApmEventListenerFactory(
                    queue = queue,
                    sessionProvider = { "session-1" },
                    seqProvider = { 1L },
                    contextProvider = { EventContext() },
                    ingestHost = "ingest.example.com"
                )
            )
            .build()

    private fun failureCategories(): List<String> = queue.events
        .filter { it.type == EventType.NETWORK_FAILURE }
        .map { it.attrs.getValue("failure_category").jsonPrimitive.content }

    @Test fun `a pin mismatch is reported as ssl_pinning_rejected`() {
        val wrongPin = CertificatePinner.Builder().add("localhost", WRONG_PIN).build()

        try {
            client(wrongPin).newCall(Request.Builder().url(url()).build()).execute().close()
            fail("the pinner should have rejected the connection")
        } catch (e: SSLPeerUnverifiedException) {
            // OkHttp's own exception — asserting its shape keeps this test honest if OkHttp changes it.
            assertTrue("unexpected message: ${e.message}", e.message!!.startsWith("Certificate pinning failure!"))
        }

        assertEquals(listOf("ssl_pinning_rejected"), failureCategories())
        assertTrue("a rejected call must not also emit a success event",
            queue.events.none { it.type == EventType.NETWORK })
    }

    @Test fun `control - the correct pin connects and reports no failure`() {
        tls.server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val rightPin = CertificatePinner.Builder().add("localhost", CertificatePinner.pin(tls.serverCert)).build()

        client(rightPin).newCall(Request.Builder().url(url()).build()).execute().use {
            assertEquals(200, it.code)
        }

        assertTrue(failureCategories().isEmpty())
        assertEquals(1, queue.events.count { it.type == EventType.NETWORK })
    }

    @Test fun `control - an untrusted certificate without a pinner is not labelled as pinning`() {
        // Default trust store does not know the fixture certificate, so the handshake fails on
        // trust, with no pinner involved at all.
        try {
            client(pinner = null, trustServerCert = false).newCall(Request.Builder().url(url()).build()).execute().close()
            fail("the default trust store should not accept the fixture certificate")
        } catch (_: IOException) {
        }

        val categories = failureCategories()
        assertEquals(1, categories.size)
        assertNotEquals("ssl_pinning_rejected", categories.single())
    }

    private companion object {
        /** 32 zero bytes, base64 — a syntactically valid pin that matches no real certificate. */
        const val WRONG_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
