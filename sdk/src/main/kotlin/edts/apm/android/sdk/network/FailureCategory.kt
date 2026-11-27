package edts.apm.android.sdk.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Network failure categories — §5 of docs/01-Kontrak-Data-API.md.
 * Wire values IDENTICAL to iOS. Do not rename @SerialName values.
 */
@Serializable
internal enum class FailureCategory {
    @SerialName("ssl_certificate")      SSL_CERTIFICATE,
    @SerialName("ssl_pinning_rejected") SSL_PINNING_REJECTED,
    @SerialName("tls_handshake")        TLS_HANDSHAKE,
    @SerialName("timeout")              TIMEOUT,
    @SerialName("dns")                  DNS,
    @SerialName("connectivity")         CONNECTIVITY,
    @SerialName("cancelled")            CANCELLED,
    @SerialName("http_error")           HTTP_ERROR,
    @SerialName("unknown")              UNKNOWN;

    companion object {
        val wireValue get() = values().associate { it to it.name.lowercase() }
    }
}

/**
 * Maps an OkHttp callFailed exception to a FailureCategory.
 *
 * Android-specific notes:
 * - SSLPeerUnverifiedException: cert chain invalid / not trusted → ssl_certificate
 * - SSLPeerUnverifiedException from `CertificatePinner`: OkHttp throws the same type for a
 *   pin mismatch, distinguishable only by its message, which starts with
 *   "Certificate pinning failure!" (okhttp 4.12.0, `CertificatePinner.check`). → ssl_pinning_rejected (MOB-03)
 * - SSLHandshakeException (not peer-unverified): handshake protocol failure → tls_handshake
 * - SocketTimeoutException: timeout (read, write, or connect)
 * - UnknownHostException: DNS failure
 * - "Canceled" message: OkHttp cancelled by application → cancelled
 */
internal object FailureCategoryMapper {

    /** Prefix of the message OkHttp's `CertificatePinner` puts on a pin mismatch. */
    private const val PINNING_FAILURE_PREFIX = "Certificate pinning failure!"

    /**
     * For a failure reported by hand (A51), where the host's own stack may throw something that is not an
     * `IOException`: an `IOException` is classified exactly as OkHttp's are; a `java.util.concurrent.CancellationException`
     * (what coroutines and futures throw when cancelled) is `cancelled`; anything else is `unknown` — never guessed.
     */
    fun map(e: Throwable): FailureCategory = when (e) {
        is IOException -> map(e)
        is java.util.concurrent.CancellationException -> FailureCategory.CANCELLED
        else -> FailureCategory.UNKNOWN
    }

    fun map(e: IOException): FailureCategory = when {
        e is SSLPeerUnverifiedException && e.message?.startsWith(PINNING_FAILURE_PREFIX) == true ->
                                                 FailureCategory.SSL_PINNING_REJECTED
        e is SSLPeerUnverifiedException       -> FailureCategory.SSL_CERTIFICATE
        e is SSLHandshakeException            -> FailureCategory.TLS_HANDSHAKE
        e is SSLException                     -> FailureCategory.TLS_HANDSHAKE
        e is SocketTimeoutException           -> FailureCategory.TIMEOUT
        e is UnknownHostException             -> FailureCategory.DNS
        e.message?.contains("Canceled", ignoreCase = true) == true -> FailureCategory.CANCELLED
        e.message?.contains("timeout", ignoreCase = true) == true  -> FailureCategory.TIMEOUT
        e.message?.contains("connect", ignoreCase = true) == true  -> FailureCategory.CONNECTIVITY
        else                                  -> FailureCategory.UNKNOWN
    }
}
