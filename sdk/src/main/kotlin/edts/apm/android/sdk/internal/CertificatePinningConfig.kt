package edts.apm.android.sdk.internal

import okhttp3.CertificatePinner

/**
 * Certificate pinning for the ingest endpoint — **opt-in, off by default** (SEC-11, P2).
 *
 * Read the decision box in `02` §6.3 before changing anything here. This project exists because a
 * pinning failure cost a team its visibility; a monitoring SDK that can disable itself the same
 * way is not a default anyone should get by accident. Hence: no pins configured means no pinning,
 * and that is the normal case.
 *
 * **A backup pin is structurally mandatory, not validated.** [of] takes `primary` and `backup` as
 * two separate required parameters, so there is no way to express "pinning with one pin" in the
 * type system at all — no `require()` to forget, no config path that skips the check. That is what
 * `02` §6.3's "wajib menyertakan pin cadangan" means in practice: a server certificate rotation
 * must not be able to strand every installed app at once.
 *
 * [of] returns `null` rather than throwing when the input is unusable. The host app constructs
 * this, and the SDK may never take the host down (CONSTITUTION rule #1); `null` degrades to the
 * default — no pinning, TLS floor still enforced by [TlsPolicy].
 */
class CertificatePinningConfig private constructor(
    val hostname: String,
    /** SPKI SHA-256 pins in OkHttp's `sha256/<base64>` form. Always at least two. */
    val pins: List<String>
) {

    fun toCertificatePinner(): CertificatePinner {
        val builder = CertificatePinner.Builder()
        // Not `apply { pins.forEach ... }`: inside that scope `pins` resolves to the OkHttp
        // builder's own field, silently pinning nothing.
        pins.forEach { builder.add(hostname, it) }
        return builder.build()
    }

    override fun equals(other: Any?): Boolean =
        other is CertificatePinningConfig && other.hostname == hostname && other.pins == pins

    override fun hashCode(): Int = 31 * hostname.hashCode() + pins.hashCode()

    override fun toString(): String = "CertificatePinningConfig($hostname, ${pins.size} pins)"

    companion object {
        /**
         * Builds a pinning config, or `null` when [hostname] is blank or any pin is not a
         * SHA-256 SPKI hash in base64.
         *
         * [primary] and [backup] are both required — see the class docs for why that is a type
         * signature rather than a validation rule. [additional] allows further rotation pins.
         *
         * Pins may be given bare (`AAAA…=`) or already prefixed (`sha256/AAAA…=`); both normalize
         * to the same stored form, so a copy-pasted pin from either common tool works.
         */
        @JvmStatic
        fun of(
            hostname: String,
            primary: String,
            backup: String,
            vararg additional: String
        ): CertificatePinningConfig? {
            if (hostname.isBlank()) return null

            val normalized = (listOf(primary, backup) + additional).map { normalize(it) ?: return null }
            // Two identical pins is a backup in name only — it survives no rotation at all.
            if (normalized.distinct().size < 2) return null

            return CertificatePinningConfig(hostname.trim(), normalized.distinct())
        }

        private const val PREFIX = "sha256/"

        /** A SHA-256 digest is 32 bytes; base64 of that is 43 chars plus one '=' of padding. */
        private val BASE64_SHA256 = Regex("^[A-Za-z0-9+/]{43}=$")

        private fun normalize(pin: String): String? {
            val body = pin.trim().removePrefix(PREFIX)
            return if (BASE64_SHA256.matches(body)) PREFIX + body else null
        }
    }
}
