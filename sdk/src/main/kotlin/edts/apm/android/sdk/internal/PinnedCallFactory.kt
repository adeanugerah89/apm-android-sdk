package edts.apm.android.sdk.internal

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The remote kill switch for certificate pinning (SEC-11: "wajib menyertakan pin cadangan **dan
 * kill switch via remote config**").
 *
 * An [OkHttpClient] is immutable and its [okhttp3.CertificatePinner] is fixed at build time, so a
 * pinning kill switch cannot work by mutating a client. Instead both clients are built once and
 * this picks between them per call. [pinned] and [unpinned] are made with `newBuilder()` from the
 * same base, so they share one connection pool and dispatcher — switching costs a branch, not a
 * new HTTP stack.
 *
 * [isPinningEnabled] is read on **every** call rather than captured, so a config refresh takes
 * effect at the next upload. That is the whole point of the requirement: recovering from a pin
 * that no longer matches the server must not require an app release, because waiting for users to
 * update is exactly the weeks-long blackout `02` §6.3 describes.
 *
 * Turning pinning off falls back to [unpinned] — which still carries [TlsPolicy]'s TLS 1.2 floor
 * and still cannot speak cleartext. The kill switch relaxes pinning, never transport security;
 * SEC-12 holds either way.
 */
class PinnedCallFactory(
    private val pinned: Call.Factory,
    private val unpinned: Call.Factory,
    private val isPinningEnabled: () -> Boolean
) : Call.Factory {

    override fun newCall(request: Request): Call {
        val enabled = try {
            isPinningEnabled()
        } catch (e: Exception) {
            // A broken config read must not decide security policy by accident. Falling back to
            // the pinned client keeps the app's stated intent — pinning was configured on purpose.
            true
        }
        return if (enabled) pinned.newCall(request) else unpinned.newCall(request)
    }
}
