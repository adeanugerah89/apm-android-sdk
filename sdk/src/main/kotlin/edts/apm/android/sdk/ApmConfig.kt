package edts.apm.android.sdk

import edts.apm.android.sdk.internal.CertificatePinningConfig

/**
 * Configuration for the APM SDK.
 *
 * appKey is treated as an identifier, NOT a credential — it is embedded in the binary
 * and can be extracted. It is write-only (no secret operations depend on it). (SEC-13)
 *
 * pinning is OFF by default (SEC-11, P2) and is `null` unless the host app deliberately builds a
 * [CertificatePinningConfig]. That type takes primary and backup pins as two separate required
 * parameters, so "pinning with no backup pin" cannot be expressed at all — the constraint lives in
 * the type signature rather than in a validation rule that a future call site could route around.
 * See `02` §6.3 for why this stays opt-in.
 */
data class ApmConfig(
    val appId: String,
    val appVersion: String,
    val appBuild: String,
    val ingestUrl: String,
    val appKey: String,
    /**
     * `GET /v1/config` (`01` §9). Defaults to [ingestUrl] with the trailing path swapped, which is
     * right whenever both endpoints sit on one host — the normal deployment. Set it explicitly when
     * they do not, rather than reshaping [ingestUrl] to make the guess work.
     */
    val configUrl: String = ingestUrl.substringBeforeLast("/v1/ingest") + "/v1/config",
    // MOB-08: periodic sync trigger interval. `01` §9 config example default is 30s;
    // may be overridden lower by GET /v1/config once A10 (remote control) lands.
    val uploadIntervalMs: Long = 30_000L,
    val debugLogging: Boolean = false,
    // Cert pinning (A15, P2) — null = disabled, the default. The TLS floor (A11) applies either
    // way, and the remote kill switch can disable pinning without disabling transport security.
    val pinning: CertificatePinningConfig? = null,
)
