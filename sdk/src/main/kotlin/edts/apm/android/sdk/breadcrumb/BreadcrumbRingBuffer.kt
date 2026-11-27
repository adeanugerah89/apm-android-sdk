package edts.apm.android.sdk.breadcrumb

import edts.apm.android.sdk.internal.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * In-memory ring buffer of the last [CAPACITY] breadcrumbs (MOB-13). Never written to disk as
 * its own event — [snapshot] renders it as a JSON-array **string** for embedding into a single
 * `crash`/`error` event's `breadcrumbs` attribute (`01` §4.5.1). Being a plain string means it
 * passes through the same SEC-05 pattern scrubbing as every other attrs value automatically,
 * with no special-cased path required.
 */
internal class BreadcrumbRingBuffer(private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = ReentrantLock()
    private val entries = ArrayDeque<BreadcrumbEntry>(CAPACITY)
    private val json = Json { encodeDefaults = true }

    fun record(message: String, category: BreadcrumbCategory, level: BreadcrumbLevel = BreadcrumbLevel.INFO) {
        lock.withLock {
            // MOB-12 (0.0.16): `network` is written by the connectivity observer without the host asking, so it
            // must never crowd out the screens and actions the host records on purpose. At the cap the OLDEST
            // network entry makes room, so the newest connectivity change always survives.
            if (category == BreadcrumbCategory.NETWORK && entries.count { it.category == category } >= NETWORK_LIMIT) {
                entries.removeAt(entries.indexOfFirst { it.category == category })
            }
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(
                BreadcrumbEntry(
                    timestamp = Clock.nowIso8601(),
                    category = category,
                    level = level,
                    message = message
                )
            )
        }
    }

    /** Chronological order, oldest first — last element is closest to whatever triggers the snapshot. */
    fun snapshot(): String = lock.withLock {
        json.encodeToString(entries.toList())
    }

    companion object {
        const val CAPACITY = 100

        /**
         * Most `network` entries the buffer holds at once: half of it (MOB-12, 0.0.16). Android sends the whole
         * buffer with a crash (`breadcrumbs_dropped` is always 0), so this one cap is also what keeps connectivity
         * to at most half of a crash report's breadcrumbs.
         */
        const val NETWORK_LIMIT = CAPACITY / 2
    }
}

/**
 * Attaches a [BreadcrumbRingBuffer.snapshot] to a `crash`/`error` event the way `01` §4.5.1/§4.5.2
 * require: the `breadcrumbs` JSON string **and** its companion `breadcrumbs_dropped`.
 *
 * `breadcrumbs_dropped` counts crumbs removed by a byte *budget encoder* before upload, which iOS
 * needs because KSCrash truncates its user-info string at 1 KB. Android has no such cap — the whole
 * 100-entry buffer is sent — so the count is always `0`. It is still written, as a string, because
 * the contract says `0` is written explicitly and never omitted: an absent field is read by the
 * backend as "an SDK older than the buffer fix", which would be a false signal for this one.
 */
internal fun JsonObjectBuilder.putBreadcrumbSnapshot(snapshot: String) {
    put("breadcrumbs", snapshot)
    put("breadcrumbs_dropped", "0")
}
