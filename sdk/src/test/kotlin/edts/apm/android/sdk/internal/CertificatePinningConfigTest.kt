package edts.apm.android.sdk.internal

import org.junit.Assert.*
import org.junit.Test

/** SEC-11 — opt-in pinning, with a backup pin that cannot be omitted. */
class CertificatePinningConfigTest {

    // Well-formed SPKI SHA-256 pins: 43 base64 chars + '='.
    private val pinA = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val pinB = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
    private val pinC = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC="

    @Test fun `a primary and backup pin produce a config with both`() {
        val config = CertificatePinningConfig.of("ingest.example.com", pinA, pinB)!!

        assertEquals("ingest.example.com", config.hostname)
        assertEquals(listOf("sha256/$pinA", "sha256/$pinB"), config.pins)
    }

    @Test fun `additional rotation pins are kept in order after the required two`() {
        val config = CertificatePinningConfig.of("ingest.example.com", pinA, pinB, pinC)!!

        assertEquals(listOf("sha256/$pinA", "sha256/$pinB", "sha256/$pinC"), config.pins)
    }

    @Test fun `pins are accepted bare or already prefixed, normalizing to one form`() {
        val bare = CertificatePinningConfig.of("h.example.com", pinA, pinB)!!
        val prefixed = CertificatePinningConfig.of("h.example.com", "sha256/$pinA", "sha256/$pinB")!!

        assertEquals(bare.pins, prefixed.pins)
        assertEquals(bare, prefixed)
    }

    @Test fun `surrounding whitespace from a copy-paste is tolerated`() {
        val config = CertificatePinningConfig.of("  ingest.example.com  ", "  $pinA ", "\t$pinB\n")!!

        assertEquals("ingest.example.com", config.hostname)
        assertEquals(listOf("sha256/$pinA", "sha256/$pinB"), config.pins)
    }

    @Test fun `a duplicated backup pin is refused — it survives no rotation`() {
        assertNull(CertificatePinningConfig.of("ingest.example.com", pinA, pinA))
        assertNull(CertificatePinningConfig.of("ingest.example.com", pinA, "sha256/$pinA"))
    }

    @Test fun `a malformed pin yields null rather than throwing at the host app`() {
        assertNull(CertificatePinningConfig.of("ingest.example.com", "not-a-pin", pinB))
        assertNull(CertificatePinningConfig.of("ingest.example.com", pinA, ""))
        assertNull(CertificatePinningConfig.of("ingest.example.com", pinA, "AAAA="))          // too short
        assertNull(CertificatePinningConfig.of("ingest.example.com", pinA, pinB.dropLast(1))) // no padding
    }

    @Test fun `a blank hostname yields null`() {
        assertNull(CertificatePinningConfig.of("", pinA, pinB))
        assertNull(CertificatePinningConfig.of("   ", pinA, pinB))
    }

    @Test fun `the resulting CertificatePinner carries every pin for the host`() {
        val pinner = CertificatePinningConfig.of("ingest.example.com", pinA, pinB)!!.toCertificatePinner()

        val found = pinner.findMatchingPins("ingest.example.com")
        assertEquals("both pins must reach the pinner — the shadowing bug pinned nothing", 2, found.size)
        assertTrue(pinner.findMatchingPins("other.example.com").isEmpty())
    }

    @Test fun `pinning is not expressible without a backup — of requires two pins`() {
        // A compile-time property, asserted here as documentation: there is no single-pin overload.
        val overloads = CertificatePinningConfig.Companion::class.java.methods.filter { it.name == "of" }

        assertTrue(overloads.isNotEmpty())
        assertTrue(
            "every of(...) must take at least hostname + primary + backup",
            overloads.all { it.parameterCount >= 3 }
        )
    }
}
