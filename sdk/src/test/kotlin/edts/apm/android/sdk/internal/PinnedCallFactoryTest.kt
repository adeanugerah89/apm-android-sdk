package edts.apm.android.sdk.internal

import edts.apm.android.sdk.config.RemoteConfig
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** SEC-11 — the pinning kill switch that must work without an app release. */
class PinnedCallFactoryTest {

    private class RecordingFactory(val label: String) : Call.Factory {
        var calls = 0
        override fun newCall(request: Request): Call {
            calls++
            return OkHttpClient().newCall(request)
        }
    }

    private val request = Request.Builder().url("https://ingest.example.com/v1/ingest").build()

    @Test fun `pinning on routes through the pinned client`() {
        val pinned = RecordingFactory("pinned")
        val unpinned = RecordingFactory("unpinned")

        PinnedCallFactory(pinned, unpinned) { true }.newCall(request)

        assertEquals(1, pinned.calls)
        assertEquals(0, unpinned.calls)
    }

    @Test fun `the kill switch routes through the unpinned client`() {
        val pinned = RecordingFactory("pinned")
        val unpinned = RecordingFactory("unpinned")

        PinnedCallFactory(pinned, unpinned) { false }.newCall(request)

        assertEquals(0, pinned.calls)
        assertEquals(1, unpinned.calls)
    }

    @Test fun `the switch is read per call, so a config refresh takes effect without a restart`() {
        val pinned = RecordingFactory("pinned")
        val unpinned = RecordingFactory("unpinned")
        var enabled = true
        val factory = PinnedCallFactory(pinned, unpinned) { enabled }

        factory.newCall(request)
        enabled = false                      // remote config says: stop pinning
        factory.newCall(request)
        enabled = true                       // ...and back on once the cert is fixed
        factory.newCall(request)

        assertEquals(2, pinned.calls)
        assertEquals(1, unpinned.calls)
    }

    @Test fun `a throwing switch keeps pinning on rather than silently relaxing it`() {
        val pinned = RecordingFactory("pinned")
        val unpinned = RecordingFactory("unpinned")

        PinnedCallFactory(pinned, unpinned) { throw IllegalStateException("config unavailable") }
            .newCall(request)

        assertEquals("a broken config read must not decide security policy", 1, pinned.calls)
        assertEquals(0, unpinned.calls)
    }

    @Test fun `pinning is a remotely-disableable feature name`() {
        val config = RemoteConfig(disabledFeatures = listOf("pinning"))

        assertTrue(config.isDisabled(RemoteConfig.Feature.PINNING))
        assertEquals("pinning", RemoteConfig.Feature.PINNING.key)
    }

    @Test fun `disabling pinning does not relax the TLS floor — SEC-12 still holds`() {
        val (pinned, unpinned) = ApmHttpClientFactory.createPinnedPair(
            CertificatePinningConfig.of(
                "ingest.example.com",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
            )
        )

        listOf(pinned, unpinned).forEach { client ->
            assertEquals(TlsPolicy.CONNECTION_SPECS, client.connectionSpecs)
            assertTrue(client.connectionSpecs.none { it == ConnectionSpec.CLEARTEXT })
        }
    }
}
