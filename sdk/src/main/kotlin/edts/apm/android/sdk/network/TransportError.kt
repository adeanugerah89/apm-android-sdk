package edts.apm.android.sdk.network

import android.system.ErrnoException

/**
 * `error_domain` / `error_code` / `underlying_*` for a failed request (`01` §4.2).
 *
 * iOS fills these from the `NSError` it was handed. Java has no domain/code split, so the nearest
 * honest equivalents are used rather than invented values:
 *  - **domain** is the exception's fully qualified class name (`java.net.UnknownHostException`,
 *    `javax.net.ssl.SSLHandshakeException`, …). JDK and platform classes are never renamed by R8,
 *    and a class name carries no user data.
 *  - **code** is the OS `errno` when an [ErrnoException] sits in the cause chain — libcore wraps
 *    `ECONNRESET`, `ETIMEDOUT`, `ECONNREFUSED` and friends that way — and [NO_CODE] otherwise. It is
 *    never guessed from the message text.
 *
 * `android.system.GaiException` (the cause of DNS failures) is not public API, so a DNS failure
 * reports its class name as the underlying domain and carries no code.
 */
internal object TransportError {

    /** "The platform gave no code". `0` is never a real errno (it means success), so it cannot be confused with one. */
    const val NO_CODE = 0

    private const val MAX_CAUSE_DEPTH = 5

    class Info(val domain: String, val code: Int)

    fun of(failure: Throwable): Info = Info(failure.javaClass.name, firstErrno(failure) ?: NO_CODE)

    /** The direct cause, or `null` when there is none — mirrors `NSUnderlyingErrorKey` being optional. */
    fun underlyingOf(failure: Throwable): Info? {
        val cause = failure.cause?.takeIf { it !== failure } ?: return null
        return Info(cause.javaClass.name, (cause as? ErrnoException)?.errno ?: NO_CODE)
    }

    private fun firstErrno(failure: Throwable): Int? {
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is ErrnoException) return current.errno
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return null
    }
}
