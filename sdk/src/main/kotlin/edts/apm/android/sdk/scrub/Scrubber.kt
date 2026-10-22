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
internal object Scrubber {

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
    // SEC-03b rule 2: a bare version segment (`v2`, `v2.1`, `V3`) is not an id.
    private val VERSION_SEGMENT = Regex("""^v\d+(\.\d+)*$""", RegexOption.IGNORE_CASE)
    // Rule 4: lowercase words joined by single hyphens — a human-written route name. Also matches
    // one lowercase word with no hyphen at all, so a long all-lowercase segment is a word, not a token.
    private val KEBAB_WORD_SEGMENT = Regex("""^[a-z]+(-[a-z]+)*$""")
    // Rule 5: an opaque token. It only gets this far with no digit and not a kebab-case word.
    private val OPAQUE_TOKEN_SEGMENT = Regex("""^[A-Za-z0-9_-]{24,}$""")

    /** SEC-05: replace every PII-shaped substring with `[REDACTED]`. Safe to call on any string. */
    fun redact(text: String): String = REDACT_PATTERN.replace(text) { "[REDACTED]" }

    /**
     * SEC-03b (`docs/02` §6.1.1, binding on both platforms): replace identity-like path segments
     * with `{id}`. Rules run per segment, in this order, and the first match wins:
     * 1. UUID -> `{id}`
     * 2. version segment (`v2`, `v2.1`, `V3`) -> unchanged (before rule 3, or `v2` would match it)
     * 3. contains any digit -> `{id}`
     * 4. lowercase kebab-case word(s), any length -> unchanged
     * 5. no digit, length >= 24, only `[A-Za-z0-9_-]` -> `{id}` (opaque token)
     * 6. anything else -> unchanged
     *
     * Android-only safeguard, applied last: a segment the six rules leave alone but that still
     * holds a PII shape — an email accidentally used as a path segment, a JWT-shaped one — becomes
     * `{id}` too ([REDACT_PATTERN]). No row of the §6.1.1 table reaches it (every row with a digit
     * ends at rule 3, and none of the unchanged rows has an `@` or a dotted token).
     *
     * Takes the path only; a query string is SEC-03's job ([scrubQueryParams]).
     */
    fun normalizePath(path: String): String =
        path.split('/').joinToString("/") { segment ->
            when {
                segment.isEmpty() -> segment
                UUID_SEGMENT.matches(segment) -> "{id}"
                VERSION_SEGMENT.matches(segment) -> segment
                segment.any { it in '0'..'9' } -> "{id}"
                KEBAB_WORD_SEGMENT.matches(segment) -> segment
                OPAQUE_TOKEN_SEGMENT.matches(segment) -> "{id}"
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
