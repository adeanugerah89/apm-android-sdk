package edts.apm.android.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A single event — §3 of docs/01-Kontrak-Data-API.md.
 *
 * event_id is the dedup key: at-least-once delivery means the same event can arrive twice.
 * seq is a monotonic counter per session used for ordering when client clock is unreliable.
 * ts_client is ISO-8601 UTC — backend stores ts_server separately; client time is NOT trusted.
 *
 * attrs is type-specific (§4). ctx is volatile context that can change within a session.
 */
@Serializable
data class Event(
    @SerialName("event_id") val eventId: String,
    val type: EventType,
    @SerialName("ts_client") val tsClient: String,   // ISO-8601 UTC
    val seq: Long,
    val attrs: JsonObject,
    val ctx: EventContext
)

/** Wire values match iOS exactly — do NOT rename. (§4 type enum) */
@Serializable
enum class EventType {
    @SerialName("network")          NETWORK,
    @SerialName("network_failure")  NETWORK_FAILURE,
    @SerialName("crash")            CRASH,
    @SerialName("error")            ERROR,
    @SerialName("breadcrumb")       BREADCRUMB,
    @SerialName("lifecycle")        LIFECYCLE,
    @SerialName("performance")      PERFORMANCE,
    @SerialName("termination")      TERMINATION
}

/**
 * Volatile context captured at event creation time.
 * All fields nullable — if unknown at capture time, omit (don't emit null to wire).
 */
@Serializable
data class EventContext(
    val connectivity: String? = null,
    val screen: String? = null,
    @SerialName("app_state") val appState: String? = null,
    @SerialName("low_power") val lowPower: Boolean = false
)
