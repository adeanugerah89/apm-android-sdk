package edts.apm.android.sdk.scrub

import edts.apm.android.sdk.core.Event
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * PII scrubbing — §6.1 of docs/02-Mobile-SDK.md (SEC-01 through SEC-05b).
 *
 * Not a call-site opt-in: [scrubEvent] is applied by [ScrubbingEventQueue] as the last layer
 * before every disk write, so it covers both automatic capture (network events) and anything
 * a developer passes through the manual API later (A6/A7) without relying on caller discipline
 * (SEC-05b).
 */
object Scrubber {

    private val JWT_LIKE = Regex("""[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    // Indonesian mobile numbers: 08xx or +62xx / 62xx.
    private val ID_PHONE = Regex("""(?:\+62|62|0)8[0-9]{7,11}""")
    private val DIGIT_RUN = Regex("""\d{10,}""")

    private val REDACT_PATTERN = Regex(
        listOf(JWT_LIKE, EMAIL, ID_PHONE, DIGIT_RUN).joinToString("|") { "(?:${it.pattern})" }
    )

    private val UUID_SEGMENT = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
    private val LONG_NUMERIC_SEGMENT = Regex("""^\d{5,}$""")

    /** SEC-05: replace every PII-shaped substring with `[REDACTED]`. Safe to call on any string. */
    fun redact(text: String): String = REDACT_PATTERN.replace(text) { "[REDACTED]" }

    /**
     * SEC-03b: normalize identity-like path segments to `{id}` for backend grouping quality.
     * `/user/628123456789/profile` -> `/user/{id}/profile`.
     * Also runs [redact] per segment as a fallback for identity patterns that aren't purely
     * numeric/UUID (e.g. an email accidentally used as a path segment).
     */
    fun normalizePath(path: String): String =
        path.split('/').joinToString("/") { segment ->
            when {
                segment.isEmpty() -> segment
                UUID_SEGMENT.matches(segment) -> "{id}"
                LONG_NUMERIC_SEGMENT.matches(segment) -> "{id}"
                REDACT_PATTERN.containsMatchIn(segment) -> "{id}"
                else -> segment
            }
        }

    /**
     * SEC-03: redact query parameter values, keeping only the parameter name.
     * [valueAllowlist] holds parameter names an app has explicitly opted to keep unredacted
     * (e.g. "page", "sort") — everything else is replaced with `[redacted]`.
     * No current capture site emits a query string on the wire (network event schema §4.1 has
     * no query field); this exists for manual-API call sites that pass a full URL as an attribute.
     */
    fun scrubQueryParams(query: String?, valueAllowlist: Set<String> = emptySet()): String? {
        if (query.isNullOrEmpty()) return query
        return query.split('&').joinToString("&") { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) return@joinToString pair
            val name = pair.substring(0, idx)
            if (name in valueAllowlist) pair else "$name=[redacted]"
        }
    }

    /** SEC-01/05/05b: scrub every string reachable from an event's attrs and volatile context. */
    fun scrubEvent(event: Event): Event = event.copy(
        attrs = scrubJsonObject(event.attrs),
        ctx = event.ctx.copy(
            connectivity = event.ctx.connectivity?.let(::redact),
            screen = event.ctx.screen?.let(::redact),
            appState = event.ctx.appState?.let(::redact)
        )
    )

    private fun scrubJsonObject(obj: JsonObject): JsonObject =
        JsonObject(obj.mapValues { (_, value) -> scrubElement(value) })

    private fun scrubElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject    -> scrubJsonObject(element)
        is JsonArray     -> JsonArray(element.map { scrubElement(it) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(redact(element.content)) else element
        else             -> element
    }
}
