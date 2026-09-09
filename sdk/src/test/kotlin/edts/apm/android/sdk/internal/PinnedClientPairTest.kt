package edts.apm.android.sdk.internal

import org.junit.Assert.*
import org.junit.Test

/** SEC-11 — how the pinned/unpinned pair is built. */
class PinnedClientPairTest {

    private val pinA = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val pinB = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
    private val config = CertificatePinningConfig.of("ingest.example.com", pinA, pinB)!!

    // Asserted as "has no pins for any host" rather than "is CertificatePinner.DEFAULT": OkHttp's
    // builder installs a fresh empty pinner instance, so identity/equality says nothing useful.
    private fun assertPinsNothing(client: okhttp3.OkHttpClient) {
        assertTrue(client.certificatePinner.findMatchingPins("ingest.example.com").isEmpty())
        assertTrue(client.certificatePinner.findMatchingPins("anything.else").isEmpty())
    }

    @Test fun `no pinning configured yields no pinner — off by default`() {
        val (pinned, unpinned) = ApmHttpClientFactory.createPinnedPair(null)

        assertPinsNothing(pinned)
        assertPinsNothing(unpinned)
    }

    @Test fun `a configured pair pins one client and leaves the other unpinned`() {
        val (pinned, unpinned) = ApmHttpClientFactory.createPinnedPair(config)

        assertEquals(2, pinned.certificatePinner.findMatchingPins("ingest.example.com").size)
        assertPinsNothing(unpinned)
    }

    @Test fun `both clients share one connection pool and dispatcher`() {
        val (pinned, unpinned) = ApmHttpClientFactory.createPinnedPair(config)

        assertSame(unpinned.connectionPool, pinned.connectionPool)
        assertSame(unpinned.dispatcher, pinned.dispatcher)
    }

    @Test fun `neither client is instrumented — MOB-10 holds for the pinned one too`() {
        val (pinned, unpinned) = ApmHttpClientFactory.createPinnedPair(config)

        listOf(pinned, unpinned).forEach { client ->
            val listener = client.eventListenerFactory.create(
                client.newCall(okhttp3.Request.Builder().url("https://example.com").build())
            )
            assertEquals(okhttp3.EventListener.NONE, listener)
        }
    }
}
