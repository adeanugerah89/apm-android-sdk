package edts.apm.android.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
    val ctx: EventContext,
    /**
     * The session this event belongs to, fixed when it is captured (A28). `@Transient`: it is NOT part
     * of the wire event — `01` §3 does not define it, and `session_id` lives on the envelope. It is
     * carried in memory through the queue layers and persisted next to the event by
     * [edts.apm.android.sdk.queue.JsonEventCodec], so an event uploaded long after it was captured —
     * a backlog, or a crash reported at the next launch — still says which session it came from.
     * `null` means "not recorded" (files written before A28), and falls back to the current session.
     */
    @Transient val sessionId: String? = null,
    /**
     * The app version/build and OS version in force when the event was **captured** (A39), for the same
     * reason as [sessionId]: the backend applies one envelope's `app` and `device.os_version` to every event
     * in it, and an envelope is built at upload time. A native crash reported after an app update would
     * otherwise be filed under the *new* version, and "is this fixed in the new build" would read wrongly.
     * `@Transient`, persisted by the codec, `null` = not recorded (falls back to the current value).
     */
    @Transient val appVersion: String? = null,
    @Transient val appBuild: String? = null,
    @Transient val osVersion: String? = null,
    /**
     * Which user identity was in force when the event was captured (A39), as a counter that rises each time
     * [edts.apm.android.sdk.core.UserIdProvider] sees the identity change — never the `user_id` itself, so
     * nothing personal is written per event. The envelope's `user_id` is chosen from it at upload. `null` =
     * not recorded (files from before this), which keeps the old behaviour: the current identity.
     */
    @Transient val identityEpoch: Int? = null
) {
    /** Events that may share one envelope: everything the envelope states once for all of its events. */
    internal val originKey: List<Any?> get() = listOf(sessionId, appVersion, appBuild, osVersion, identityEpoch)
}

/** App and OS identity at a moment in time — see [Event.appVersion]. Any part may be unknown. */
data class AppStamp(val version: String?, val build: String?, val osVersion: String?)

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
