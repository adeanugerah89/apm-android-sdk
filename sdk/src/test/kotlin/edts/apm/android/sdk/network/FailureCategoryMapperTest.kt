package edts.apm.android.sdk.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * FailureCategory mapping — §5 of docs/01-Kontrak-Data-API.md. Wire values must match iOS.
 */
class FailureCategoryMapperTest {

    @Test fun `OkHttp pin mismatch message maps to ssl_pinning_rejected`() {
        // Shape of the message CertificatePinner.check throws. The real thing, produced by OkHttp
        // through a live handshake, is covered by PinningRejectionTest.
        val e = SSLPeerUnverifiedException("Certificate pinning failure!\n  Peer certificate chain:\n    sha256/...")
        assertEquals(FailureCategory.SSL_PINNING_REJECTED, FailureCategoryMapper.map(e))
    }

    @Test fun `hostname mismatch is ssl_certificate, not pinning`() {
        val e = SSLPeerUnverifiedException("Hostname example.com not verified")
        assertEquals(FailureCategory.SSL_CERTIFICATE, FailureCategoryMapper.map(e))
    }

    @Test fun `SSLPeerUnverifiedException maps to ssl_certificate`() {
        val result = FailureCategoryMapper.map(SSLPeerUnverifiedException("untrusted"))
        assertEquals(FailureCategory.SSL_CERTIFICATE, result)
    }

    @Test fun `SSLHandshakeException maps to tls_handshake`() {
        val result = FailureCategoryMapper.map(SSLHandshakeException("handshake failed"))
        assertEquals(FailureCategory.TLS_HANDSHAKE, result)
    }

    @Test fun `SocketTimeoutException maps to timeout`() {
        val result = FailureCategoryMapper.map(SocketTimeoutException("timed out"))
        assertEquals(FailureCategory.TIMEOUT, result)
    }

    @Test fun `UnknownHostException maps to dns`() {
        val result = FailureCategoryMapper.map(UnknownHostException("no such host"))
        assertEquals(FailureCategory.DNS, result)
    }

    @Test fun `Canceled message maps to cancelled`() {
        val result = FailureCategoryMapper.map(IOException("Canceled"))
        assertEquals(FailureCategory.CANCELLED, result)
    }

    @Test fun `connect message maps to connectivity`() {
        val result = FailureCategoryMapper.map(IOException("Failed to connect to host"))
        assertEquals(FailureCategory.CONNECTIVITY, result)
    }

    @Test fun `unrecognized IOException maps to unknown`() {
        val result = FailureCategoryMapper.map(IOException("something else entirely"))
        assertEquals(FailureCategory.UNKNOWN, result)
    }

    @Test fun `wire values are lowercase snake_case matching spec §5`() {
        val expected = mapOf(
            FailureCategory.SSL_CERTIFICATE to "ssl_certificate",
            FailureCategory.SSL_PINNING_REJECTED to "ssl_pinning_rejected",
            FailureCategory.TLS_HANDSHAKE to "tls_handshake",
            FailureCategory.TIMEOUT to "timeout",
            FailureCategory.DNS to "dns",
            FailureCategory.CONNECTIVITY to "connectivity",
            FailureCategory.CANCELLED to "cancelled",
            FailureCategory.HTTP_ERROR to "http_error",
            FailureCategory.UNKNOWN to "unknown"
        )
        expected.forEach { (category, wire) ->
            assertEquals("$category wire value", wire, FailureCategory.wireValue[category])
        }
    }
}
