package edts.apm.android.sdk.network

import android.system.ErrnoException
import android.system.OsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * A33 — `error_domain` / `error_code` / `underlying_*` come from the real exception, not constants.
 * Robolectric, because `ErrnoException` is an Android class and libcore is what wraps real errnos in it.
 */
@RunWith(RobolectricTestRunner::class)
class TransportErrorTest {

    @Test fun `the domain is the exception's own class name`() {
        assertEquals("java.net.UnknownHostException", TransportError.of(UnknownHostException("x")).domain)
        assertEquals("javax.net.ssl.SSLHandshakeException", TransportError.of(SSLHandshakeException("x")).domain)
        assertEquals("java.net.ConnectException", TransportError.of(ConnectException("x")).domain)
    }

    @Test fun `with no OS errno anywhere the code is NO_CODE, never guessed from the message`() {
        // The message says "reset", and a guess from text would be tempting — the code must still be 0.
        val info = TransportError.of(SocketException("Connection reset"))
        assertEquals(TransportError.NO_CODE, info.code)
    }

    @Test fun `an ErrnoException in the cause chain supplies the real errno`() {
        // How libcore reports a reset connection: a SocketException wrapping the ErrnoException.
        val reset = ErrnoException("read", OsConstants.ECONNRESET)
        val failure = SocketException("Connection reset").apply { initCause(reset) }

        val info = TransportError.of(failure)

        assertEquals("java.net.SocketException", info.domain)
        assertEquals(OsConstants.ECONNRESET, info.code)
    }

    @Test fun `an errno found deeper than the direct cause is still found`() {
        val reset = ErrnoException("connect", OsConstants.ECONNREFUSED)
        val failure = java.io.IOException("outer", RuntimeException("middle", reset))

        assertEquals(OsConstants.ECONNREFUSED, TransportError.of(failure).code)
    }

    @Test fun `underlying is the direct cause, with its own class and errno`() {
        val reset = ErrnoException("read", OsConstants.ECONNRESET)
        val underlying = TransportError.underlyingOf(SocketException("x").apply { initCause(reset) })!!

        assertEquals("android.system.ErrnoException", underlying.domain)
        assertEquals(OsConstants.ECONNRESET, underlying.code)
    }

    @Test fun `a non-errno cause reports its class and NO_CODE`() {
        val underlying = TransportError.underlyingOf(
            SSLHandshakeException("x").apply { initCause(java.security.cert.CertificateException("untrusted")) }
        )!!

        assertEquals("java.security.cert.CertificateException", underlying.domain)
        assertEquals(TransportError.NO_CODE, underlying.code)
    }

    @Test fun `no cause means no underlying attributes at all`() {
        assertNull(TransportError.underlyingOf(UnknownHostException("x")))
    }

    @Test fun `a cause that is the exception itself is ignored rather than looped on`() {
        val selfCaused = object : java.io.IOException("self") { override val cause: Throwable get() = this }

        assertNull(TransportError.underlyingOf(selfCaused))
        assertNotNull(TransportError.of(selfCaused))   // and the errno walk terminates
    }

    @Test fun `a cyclic cause chain terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        assertEquals(TransportError.NO_CODE, TransportError.of(a).code)   // reaching here at all is the assertion
    }
}
