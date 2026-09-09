package edts.apm.android.sdk.manual

import edts.apm.android.sdk.breadcrumb.BreadcrumbCategory
import edts.apm.android.sdk.breadcrumb.BreadcrumbLevel
import edts.apm.android.sdk.breadcrumb.BreadcrumbRingBuffer
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.core.UserIdProvider
import edts.apm.android.sdk.internal.CallSiteLocator
import edts.apm.android.sdk.internal.Clock
import edts.apm.android.sdk.queue.EventQueue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Backing implementation for `APM.logError()` / `APM.setUser()` / `APM.breadcrumb()`
 * (MOB-11, MOB-11b, MOB-28) — kept out of the `APM` singleton so it's constructible and
 * unit-testable without a composition root; A16 wires a real instance into `APM.start()`,
 * same pattern as A2/A3/A5's standalone components.
 *
 * [queue] MUST already be the scrubbing-wrapped queue (SEC-05b) — this class deliberately does
 * no scrubbing of its own, so it can't be the place a future call site forgets it. The
 * `breadcrumbs` snapshot attached to `error` events rides the same guarantee: it's just another
 * string value in `attrs`, scrubbed the same way as everything else (`01` §4.5.1).
 */
class ManualApi(
    private val queue: EventQueue,
    private val userIdProvider: UserIdProvider,
    private val breadcrumbs: BreadcrumbRingBuffer,
    private val seqProvider: () -> Long,
    private val contextProvider: () -> EventContext
) {
    /** MOB-28 */
    fun setUser(id: String) = userIdProvider.setUser(id)

    /** MOB-11 */
    fun breadcrumb(message: String, category: BreadcrumbCategory, level: BreadcrumbLevel = BreadcrumbLevel.INFO) =
        breadcrumbs.record(message, category, level)

    /** MOB-11b. [extra] becomes `custom.<key>`, capped at [MAX_CUSTOM_KEYS] keys of [MAX_CUSTOM_VALUE_LENGTH] chars. */
    fun logError(throwable: Throwable, extra: Map<String, String> = emptyMap()) {
        val callSite = CallSiteLocator.locateCaller()
        val attrs = buildJsonObject {
            // Kotlin/Java exceptions have no NSError-style domain/code split worth fabricating —
            // the fully qualified exception type is the useful identifier, so it goes in `name`.
            put("name", throwable::class.java.name)
            put("message", throwable.message ?: "")
            put("handled", true)
            put("source_file", callSite.file)
            put("source_function", callSite.function)
            put("source_line", callSite.line)
            // MOB-13: ring buffer snapshot attached to every error, chronological oldest-first.
            put("breadcrumbs", breadcrumbs.snapshot())
            extra.entries.take(MAX_CUSTOM_KEYS).forEach { (key, value) ->
                put("custom.$key", value.take(MAX_CUSTOM_VALUE_LENGTH))
            }
        }
        val event = Event(
            eventId = UUID.randomUUID().toString(),
            type = EventType.ERROR,
            tsClient = Clock.nowIso8601(),
            seq = seqProvider(),
            attrs = attrs,
            ctx = contextProvider()
        )
        queue.enqueue(event)
    }

    companion object {
        private const val MAX_CUSTOM_KEYS = 20
        private const val MAX_CUSTOM_VALUE_LENGTH = 256
    }
}
