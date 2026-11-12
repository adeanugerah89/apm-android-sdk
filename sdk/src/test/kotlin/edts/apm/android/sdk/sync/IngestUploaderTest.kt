package edts.apm.android.sdk.sync

import edts.apm.android.sdk.core.AppInfo
import edts.apm.android.sdk.core.DeviceInfo
import edts.apm.android.sdk.core.Envelope
import edts.apm.android.sdk.core.IntegrityInfo
import edts.apm.android.sdk.core.SdkHealthSnapshot
import edts.apm.android.sdk.core.SdkInfo
import edts.apm.android.sdk.core.UserIdSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.zip.GZIPInputStream

/** `01` §7 — headers, gzip body, response parsing (MOB-07). */
class IngestUploaderTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun minimalEnvelope() = Envelope(
        sdk = SdkInfo("apm-android-sdk", "0.1.0", SdkHealthSnapshot(0, 0, 0, emptyMap())),
        app = AppInfo(id = "edts.apm.android.sdk", version = "1.0", build = "100"),
        device = DeviceInfo(osVersion = "14", model = "Pixel 9", locale = "id_ID", timezone = "Asia/Jakarta"),
        integrity = IntegrityInfo(),
        installId = "install-1",
        sessionId = "session-1",
        userId = "user-1",
        userIdSource = UserIdSource.HOST,
        events = emptyList()
    )

    private fun uploader() = IngestUploader(
        client = OkHttpClient.Builder().build(),
        ingestUrl = server.url("/v1/ingest").toString(),
        appKey = "test-app-key",
        sdkHeaderValue = "apm-android-sdk/0.1.0",
        isSecureUrl = { true }   // MockWebServer is cleartext; the SEC-12 gate itself is covered in TlsFailClosedTest
    )

    @Test fun `sends required headers and a gzip-compressed body`() {
        server.enqueue(MockResponse().setResponseCode(202))

        uploader().upload(minimalEnvelope())

        val recorded = server.takeRequest()
        assertEquals("test-app-key", recorded.getHeader("X-APM-Key"))
        assertEquals("apm-android-sdk/0.1.0", recorded.getHeader("X-APM-Sdk"))
        assertEquals("gzip", recorded.getHeader("Content-Encoding"))

        val decompressed = GZIPInputStream(recorded.body.inputStream()).readBytes().toString(Charsets.UTF_8)
        assertTrue(decompressed.contains("\"install_id\":\"install-1\""))
    }

    @Test fun `declares the compressed size up front instead of streaming chunked`() {
        server.enqueue(MockResponse().setResponseCode(202))

        uploader().upload(minimalEnvelope())

        val recorded = server.takeRequest()
        assertNull("A26: body must not be chunked", recorded.getHeader("Transfer-Encoding"))
        assertEquals(recorded.bodySize, recorded.getHeader("Content-Length")?.toLong())
    }

    @Test fun `a 413 decision can be made from the declared length alone`() {
        // The reason A26 exists: an intermediary that sees Content-Length can refuse an oversized
        // body before reading it. The declared length must therefore be the *compressed* length,
        // because that is what `01` §7 caps at 1 MB.
        server.enqueue(MockResponse().setResponseCode(202))

        uploader().upload(minimalEnvelope())

        val recorded = server.takeRequest()
        val declared = recorded.getHeader("Content-Length")!!.toLong()
        val raw = GZIPInputStream(recorded.body.inputStream()).readBytes()
        assertTrue("declared length is the gzip size, not the JSON size", declared < raw.size)
    }

    @Test fun `parses a 202 as success`() {
        server.enqueue(MockResponse().setResponseCode(202))
        val result = uploader().upload(minimalEnvelope()) as UploadResult.HttpResponse
        assertEquals(202, result.statusCode)
        assertNull(result.retryAfterSeconds)
    }

    @Test fun `parses Retry-After header on 429`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "90"))
        val result = uploader().upload(minimalEnvelope()) as UploadResult.HttpResponse
        assertEquals(429, result.statusCode)
        assertEquals(90L, result.retryAfterSeconds)
    }

    @Test fun `connection failure surfaces as a transport failure`() {
        val deadPort = server.port
        server.shutdown()
        val dead = IngestUploader(
            client = OkHttpClient.Builder().build(),
            ingestUrl = "http://127.0.0.1:$deadPort/v1/ingest",
            appKey = "k",
            sdkHeaderValue = "apm-android-sdk/0.1.0",
        isSecureUrl = { true }   // MockWebServer is cleartext; the SEC-12 gate itself is covered in TlsFailClosedTest
        )

        val result = dead.upload(minimalEnvelope())
        assertTrue(result is UploadResult.TransportFailure)
    }
}
