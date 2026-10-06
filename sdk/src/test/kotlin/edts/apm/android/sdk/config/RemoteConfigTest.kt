package edts.apm.android.sdk.config

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** MOB-20/21 — `01` §9 payload decoding, defaults, and sanitization. */
class RemoteConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `decodes the exact payload documented in 01 section 9`() {
        val config = json.decodeFromString<RemoteConfig>(
            """{ "enabled": true,
                 "sampling": { "network": 1.0, "breadcrumb": 1.0 },
                 "max_batch": 200,
                 "upload_interval_s": 30,
                 "disabled_features": [] }"""
        )

        assertTrue(config.enabled)
        assertEquals(1.0, config.sampling.network, 0.0)
        assertEquals(200, config.maxBatch)
        assertEquals(30.0, config.uploadIntervalS, 0.0)
        assertTrue(config.disabledFeatures.isEmpty())
    }

    @Test fun `a partial payload falls back to defaults field by field`() {
        val config = json.decodeFromString<RemoteConfig>("""{ "enabled": false }""")

        assertFalse(config.enabled)
        assertEquals(RemoteConfig.DEFAULT.maxBatch, config.maxBatch)
        assertEquals(RemoteConfig.DEFAULT.uploadIntervalS, config.uploadIntervalS, 0.0)
        assertEquals(RemoteConfig.DEFAULT.sampling, config.sampling)
    }

    @Test fun `unknown fields from a newer backend do not fail the decode`() {
        val config = json.decodeFromString<RemoteConfig>(
            """{ "enabled": true, "some_future_flag": 42, "nested": { "a": 1 } }"""
        )

        assertTrue(config.enabled)
    }

    @Test fun `isDisabled matches known feature names and ignores unknown ones`() {
        val config = RemoteConfig(disabledFeatures = listOf("network", "not_a_real_feature"))

        assertTrue(config.isDisabled(RemoteConfig.Feature.NETWORK))
        assertFalse(config.isDisabled(RemoteConfig.Feature.CRASH))
        assertFalse(config.isDisabled(RemoteConfig.Feature.ANR))
    }

    @Test fun `sanitized replaces out-of-range values with defaults, not clamped edges`() {
        val sane = RemoteConfig(
            sampling = RemoteConfig.Sampling(network = 5.0, breadcrumb = -1.0),
            maxBatch = 0,
            uploadIntervalS = -30.0
        ).sanitized()

        assertEquals(RemoteConfig.DEFAULT.sampling.network, sane.sampling.network, 0.0)
        assertEquals(RemoteConfig.DEFAULT.sampling.breadcrumb, sane.sampling.breadcrumb, 0.0)
        assertEquals(RemoteConfig.DEFAULT.maxBatch, sane.maxBatch)
        assertEquals(RemoteConfig.DEFAULT.uploadIntervalS, sane.uploadIntervalS, 0.0)
    }

    @Test fun `sanitized leaves in-range values untouched and never resurrects the kill switch`() {
        val sane = RemoteConfig(
            enabled = false,
            sampling = RemoteConfig.Sampling(network = 0.25, breadcrumb = 0.0),
            maxBatch = 50,
            uploadIntervalS = 15.0
        ).sanitized()

        assertFalse(sane.enabled)
        assertEquals(0.25, sane.sampling.network, 0.0)
        assertEquals(0.0, sane.sampling.breadcrumb, 0.0)
        assertEquals(50, sane.maxBatch)
        assertEquals(15.0, sane.uploadIntervalS, 0.0)
    }
}
