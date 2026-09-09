package edts.apm.android.sdk.internal

import edts.apm.android.sdk.config.ConfigFetchResult
import edts.apm.android.sdk.config.RemoteConfigFetcher
import edts.apm.android.sdk.core.AppInfo
import edts.apm.android.sdk.core.DeviceInfo
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.core.IntegrityInfo
import edts.apm.android.sdk.core.SdkHealth
import edts.apm.android.sdk.core.UserIdSource
import edts.apm.android.sdk.queue.DiskEventQueue
import edts.apm.android.sdk.sync.EnvelopeStaticContext
import edts.apm.android.sdk.sync.IngestUploader
import edts.apm.android.sdk.sync.SyncEngine
import edts.apm.android.sdk.sync.SyncOutcome
import edts.apm.android.sdk.sync.UploadResult
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * SEC-12 — "jika validasi TLS gagal karena sebab apa pun: fail closed, data tetap di disk, tidak
 * pernah fallback ke koneksi tanpa proteksi."
 *
 * These tests exercise the real [DiskEventQueue], the real [IngestUploader], and the real client
 * from [ApmHttpClientFactory] against a server that cannot satisfy the TLS floor. The assertion
 * that matters is the same in every case and is made against the filesystem, not a mock: the
 * event files are still there afterwards.
 */
class TlsFailClosedTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var queueDir: File
    private lateinit var queue: DiskEventQueue

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        queueDir = tmp.newFolder("queue")
        queue = DiskEventQueue(queueDir, SdkHealth())
        repeat(3) { queue.enqueue(event(it.toLong())) }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun event(seq: Long) = Event(
        eventId = UUID.randomUUID().toString(),
        type = EventType.ERROR,
        tsClient = "2026-09-07T00:00:00.000Z",
        seq = seq,
        attrs = JsonObject(emptyMap()),
        ctx = EventContext()
    )

    private fun staticContext() = EnvelopeStaticContext(
        sdkName = "apm-android-sdk",
        sdkVersion = "0.1.0",
        app = AppInfo("edts.apm.android.sdk", "1.0", "100"),
        device = DeviceInfo(osVersion = "14", model = "Pixel 9", locale = "id_ID", timezone = "Asia/Jakarta"),
        integrity = IntegrityInfo(),
        installId = "install-1",
        sessionId = "session-1",
        userId = "user-1",
        userIdSource = UserIdSource.HOST
    )

    /**
     * [gateOpen] = false disables the uploader's own https pre-check, leaving only the client's
     * connection specs to stop the request — which is how the two SEC-12 layers get proven
     * separately rather than one masking the other.
     */
    private fun uploaderFor(url: String, gateOpen: Boolean = true) = IngestUploader(
        client = ApmHttpClientFactory.createUninstrumented(),
        ingestUrl = url,
        appKey = "app-key-123",
        sdkHeaderValue = "apm-android-sdk/0.1.0",
        isSecureUrl = { gateOpen && TlsPolicy.isSecureUrl(it) }
    )

    /**
     * Makes a real TLS handshake fail, deterministically and without a new dependency.
     *
     * The server speaks plain HTTP, so an `https://` request to it cannot complete a handshake.
     * [SocketPolicy.DISCONNECT_AT_START] is what makes that *fast and repeatable*: without it the
     * server sits on the unparseable ClientHello and the client blocks in `startHandshake` until a
     * timeout, which made this test hang for minutes. Closing the socket immediately produces the
     * same failure the SDK must survive — a connection that cannot be secured — and produces it at
     * once. Call [expectHandshakeFailures] once per upload attempt the test will make.
     */
    private fun httpsUrlOfPlaintextServer(path: String) =
        server.url(path).newBuilder().scheme("https").build().toString()

    private fun expectHandshakeFailures(count: Int) {
        repeat(count) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
    }

    private fun eventFileCount() = queueDir.listFiles()!!.count { it.name.endsWith(".evt") }

    @Test fun `a cleartext ingest URL fails as transport failure, never as a successful upload`() {
        server.enqueue(MockResponse().setResponseCode(202))

        val result = uploaderFor(server.url("/v1/ingest").toString()).upload(
            edts.apm.android.sdk.sync.buildEnvelope(staticContext(), SdkHealth().snapshot(), queue.peek(10))
        )

        assertTrue("expected a transport failure, got $result", result is UploadResult.TransportFailure)
        assertEquals("nothing may reach a cleartext endpoint", 0, server.requestCount)
    }

    @Test fun `a failed TLS handshake is a transport failure, not a 2xx`() {
        expectHandshakeFailures(1)

        val result = uploaderFor(httpsUrlOfPlaintextServer("/v1/ingest")).upload(
            edts.apm.android.sdk.sync.buildEnvelope(staticContext(), SdkHealth().snapshot(), queue.peek(10))
        )

        assertTrue("expected a transport failure, got $result", result is UploadResult.TransportFailure)
    }

    @Test fun `after a TLS handshake failure every event is still on disk`() {
        expectHandshakeFailures(1)
        val engine = SyncEngine(
            queue = queue,
            uploader = uploaderFor(httpsUrlOfPlaintextServer("/v1/ingest")),
            health = SdkHealth(),
            contextProvider = ::staticContext
        )

        val outcome = engine.syncOnce()

        assertTrue("TLS failure must back off, not resolve: $outcome", outcome is SyncOutcome.RetryAfter)
        assertEquals(3, eventFileCount())
        assertEquals(3, queue.peek(10).size)
    }

    @Test fun `after a refused cleartext upload every event is still on disk`() {
        server.enqueue(MockResponse().setResponseCode(202))
        val engine = SyncEngine(
            queue = queue,
            uploader = uploaderFor(server.url("/v1/ingest").toString()),
            health = SdkHealth(),
            contextProvider = ::staticContext
        )

        val outcome = engine.syncOnce()

        assertTrue("cleartext refusal must back off, not resolve: $outcome", outcome is SyncOutcome.RetryAfter)
        assertEquals(3, eventFileCount())
        assertEquals(0, server.requestCount)
    }

    @Test fun `with the url gate bypassed, the client's connection specs still refuse cleartext`() {
        server.enqueue(MockResponse().setResponseCode(202))

        val result = uploaderFor(server.url("/v1/ingest").toString(), gateOpen = false).upload(
            edts.apm.android.sdk.sync.buildEnvelope(staticContext(), SdkHealth().snapshot(), queue.peek(10))
        )

        assertTrue("expected a transport failure, got $result", result is UploadResult.TransportFailure)
        assertTrue(
            "the second layer must be OkHttp refusing cleartext outright",
            (result as UploadResult.TransportFailure).exception is java.net.UnknownServiceException
        )
        assertEquals(0, server.requestCount)
        assertEquals(3, eventFileCount())
    }

    @Test fun `repeated TLS failures never drain the queue — no attempt count wears it down`() {
        expectHandshakeFailures(5)
        val engine = SyncEngine(
            queue = queue,
            uploader = uploaderFor(httpsUrlOfPlaintextServer("/v1/ingest")),
            health = SdkHealth(),
            contextProvider = ::staticContext
        )

        repeat(5) { engine.syncOnce() }

        assertEquals(3, eventFileCount())
    }

    @Test fun `the config fetcher fails closed the same way — no config over cleartext`() {
        server.enqueue(MockResponse().setBody("""{ "enabled": false }"""))
        val fetcher = RemoteConfigFetcher(
            client = ApmHttpClientFactory.createUninstrumented(),
            configUrl = server.url("/v1/config").toString(),
            appKey = "app-key-123",
            sdkHeaderValue = "apm-android-sdk/0.1.0"
        )

        val result = fetcher.fetch()

        assertTrue(result is ConfigFetchResult.Failure)
        assertNull((result as ConfigFetchResult.Failure).statusCode)
        assertEquals(0, server.requestCount)
    }
}
