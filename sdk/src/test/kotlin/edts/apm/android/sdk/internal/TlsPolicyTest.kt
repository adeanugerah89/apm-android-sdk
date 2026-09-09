package edts.apm.android.sdk.internal

import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import org.junit.Assert.*
import org.junit.Test

/** SEC-10 — the transport floor, asserted as a value rather than inferred from behaviour. */
class TlsPolicyTest {

    @Test fun `the connection spec allows only TLS 1_2 and 1_3`() {
        val versions = TlsPolicy.CONNECTION_SPEC.tlsVersions

        assertNotNull(versions)
        assertEquals(setOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2), versions!!.toSet())
    }

    @Test fun `the connection spec is a TLS spec, never cleartext`() {
        assertTrue(TlsPolicy.CONNECTION_SPEC.isTls)
        assertNotEquals(ConnectionSpec.CLEARTEXT, TlsPolicy.CONNECTION_SPEC)
    }

    @Test fun `the spec list the SDK ships contains no cleartext entry`() {
        assertEquals(1, TlsPolicy.CONNECTION_SPECS.size)
        assertTrue(TlsPolicy.CONNECTION_SPECS.none { it == ConnectionSpec.CLEARTEXT })
        assertTrue(TlsPolicy.CONNECTION_SPECS.all { it.isTls })
    }

    @Test fun `isSecureUrl accepts https regardless of scheme casing`() {
        assertTrue(TlsPolicy.isSecureUrl("https://ingest.example.com/v1/ingest"))
        assertTrue(TlsPolicy.isSecureUrl("HTTPS://ingest.example.com/v1/ingest"))
        assertTrue(TlsPolicy.isSecureUrl("  https://ingest.example.com/v1/ingest  "))
    }

    @Test fun `isSecureUrl rejects cleartext and anything that is not https`() {
        assertFalse(TlsPolicy.isSecureUrl("http://ingest.example.com/v1/ingest"))
        assertFalse(TlsPolicy.isSecureUrl("ftp://ingest.example.com"))
        assertFalse(TlsPolicy.isSecureUrl("ingest.example.com"))
        assertFalse(TlsPolicy.isSecureUrl("httpsx://ingest.example.com"))
    }

    @Test fun `isSecureUrl rejects empty, blank, null and a bare scheme`() {
        assertFalse(TlsPolicy.isSecureUrl(null))
        assertFalse(TlsPolicy.isSecureUrl(""))
        assertFalse(TlsPolicy.isSecureUrl("   "))
        assertFalse(TlsPolicy.isSecureUrl("https://"))
    }
}
