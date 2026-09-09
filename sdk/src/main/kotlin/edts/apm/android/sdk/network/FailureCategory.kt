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
enum class FailureCategory {
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
 * - SSLPeerUnverifiedException from pinning: we detect this by a flag set during
 *   the secureConnectEnd callback when a custom hostname verifier / pinner rejects.
 *   At callFailed time, isPinningRejection flag → ssl_pinning_rejected. (MOB-03)
 * - SSLHandshakeException (not peer-unverified): handshake protocol failure → tls_handshake
 * - SocketTimeoutException: timeout (read, write, or connect)
 * - UnknownHostException: DNS failure
 * - "Canceled" message: OkHttp cancelled by application → cancelled
 */
object FailureCategoryMapper {

    fun map(e: IOException, isPinningRejection: Boolean = false): FailureCategory = when {
        isPinningRejection                    -> FailureCategory.SSL_PINNING_REJECTED
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
