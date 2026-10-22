package edts.apm.android.sdk.internal

import okhttp3.ConnectionSpec
import okhttp3.TlsVersion

/**
 * The SDK's transport floor (SEC-10) and the rule that keeps it fail-closed (SEC-12).
 *
 * Pure and dependency-free on purpose — everything here is a value or a predicate, so the policy
 * can be asserted directly in tests instead of inferred from a client's behaviour.
 *
 * Scope note: this governs the **SDK's own** connections to the ingest and config endpoints. It
 * says nothing about the host app's traffic, which the SDK only observes (MOB-01/02). A library
 * cannot tighten the host app's TLS settings without breaking apps that legitimately use
 * cleartext, so it tightens its own and documents the rest — see [apm_network_security_config].
 */
internal object TlsPolicy {

    /**
     * TLS 1.2 and 1.3 only. Deliberately built from [ConnectionSpec.MODERN_TLS]'s cipher suites
     * rather than [ConnectionSpec.RESTRICTED_TLS]: RESTRICTED also narrows the cipher list to a
     * modern subset, which is stricter than SEC-10 asks for and is the kind of tightening that
     * silently stops telemetry against an ingest host that has not been upgraded yet — the exact
     * self-disabling failure mode the SEC-11 decision exists to avoid.
     *
     * There is no cleartext spec in this list, and that omission is the enforcement: OkHttp
     * refuses an `http://` call on a client with no [ConnectionSpec.CLEARTEXT] and raises
     * `UnknownServiceException` instead of connecting. That is SEC-12 at the transport layer —
     * a downgrade is not attempted and then rejected, it is never attempted.
     */
    val CONNECTION_SPEC: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .build()

    /** The spec list every SDK-owned OkHttpClient gets. One entry — cleartext is not on it. */
    val CONNECTION_SPECS: List<ConnectionSpec> = listOf(CONNECTION_SPEC)

    /**
     * Whether [url] is an endpoint the SDK is allowed to talk to at all. Checked before a client
     * is ever handed the URL, so a misconfigured `http://` ingest host fails at startup as a
     * disabled SDK rather than at upload time as an endless stream of retries.
     *
     * Case-insensitive on the scheme, since `HTTPS://` is a legal URL and a valid one.
     */
    fun isSecureUrl(url: String?): Boolean {
        val trimmed = url?.trim().orEmpty()
        if (!trimmed.startsWith("https://", ignoreCase = true)) return false
        return trimmed.length > "https://".length
    }

    /** Reference resource name a host app can point `android:networkSecurityConfig` at (SEC-10). */
    const val apm_network_security_config = "apm_network_security_config"
}
