package edts.apm.android.sdk.crash

import java.security.MessageDigest

/**
 * A52 — an `event_id` that is **the same every time the same report is sent**.
 *
 * `01` §3 makes `event_id` the backend's dedup key (7-day window), and since iOS 0.0.13 a `crash` or
 * `termination` takes it from the crash report's own id, so a report processed or sent twice is absorbed.
 * Android's equivalent of "the report" is the `ApplicationExitInfo` record of the dead process, identified by
 * `(package, pid, exit timestamp)`; the exit timestamp is what keeps a **recycled pid** — a different process
 * life that happens to get the same number — from being deduped away as a repeat.
 *
 * Why it matters: the local marker that stops a native crash or termination being reported twice (the exit
 * timestamp / pid in preferences) is written *after* the event is queued. A kill between the two, or a cleared
 * state file, would send the same crash again under a fresh random id and count it twice. With a derived id the
 * backend absorbs it instead.
 *
 * The result has the **shape** of a UUID v4 (version and variant bits set, lower-case, 36 characters), as `01`
 * §3 describes; it is derived from a SHA-256 of the inputs, not drawn at random. Nothing in it is reversible to
 * the inputs, and the inputs are not personal data.
 *
 * Used for native crashes and terminations only. A JVM crash is written once, synchronously, by the dying
 * process itself — there is no second look at it that could duplicate it.
 */
internal object ReportEventId {

    fun forExit(kind: String, packageName: String, pid: Int, exitTimestampMs: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("apm-android-sdk|$kind|$packageName|$pid|$exitTimestampMs".toByteArray(Charsets.UTF_8))
        val b = digest.copyOf(16)
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()   // version 4
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()   // RFC 4122 variant
        val hex = b.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}
