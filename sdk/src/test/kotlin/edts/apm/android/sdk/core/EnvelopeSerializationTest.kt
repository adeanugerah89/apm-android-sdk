package edts.apm.android.sdk.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * A1 parity proof: serialized Envelope matches the wire shape specified in
 * docs/01-Kontrak-Data-API.md §2. These golden field names must stay stable —
 * changing them breaks the iOS/Android shared backend.
 *
 * iOS parity: same field names, same schema_version, same enum wire values.
 */
class EnvelopeSerializationTest {

    private val json = Json {
        encodeDefaults = true
        prettyPrint = false
    }

    private fun minimalEnvelope(events: List<Event> = emptyList()) = Envelope(
        schemaVersion = 1,
        sdk = SdkInfo(
            name    = "apm-android-sdk",
            version = "0.1.0",
            health  = SdkHealthSnapshot(written = 0, sent = 0, dropped = 0, dropReasons = emptyMap())
        ),
        app = AppInfo(id = "edts.apm.android.sdk", version = "1.0", build = "100"),
        device = DeviceInfo(
            osVersion = "14",
            model     = "Google Pixel 9",
            locale    = "id_ID",
            timezone  = "Asia/Jakarta"
        ),
        integrity = IntegrityInfo(),
        installId = "install-uuid",
        sessionId = "session-uuid",
        userId    = "user-123",
        userIdSource = UserIdSource.HOST,
        events    = events
    )

    @Test fun `schema_version is 1`() {
        val env = minimalEnvelope()
        val obj = Json.decodeFromString<kotlinx.serialization.json.JsonObject>(json.encodeToString(Envelope.serializer(), env))
        assertEquals(1, obj["schema_version"]!!.toString().trim().toInt())
    }

    @Test fun `sdk name is apm-android-sdk`() {
        val env = minimalEnvelope()
        val str = json.encodeToString(Envelope.serializer(), env)
        assertTrue("sdk.name must be apm-android-sdk", str.contains("\"apm-android-sdk\""))
    }

    @Test fun `device os is Android`() {
        val env = minimalEnvelope()
        val str = json.encodeToString(Envelope.serializer(), env)
        assertTrue("device.os must be Android", str.contains("\"Android\""))
    }

    @Test fun `user_id_source host serializes as host`() {
        val env = minimalEnvelope()
        val str = json.encodeToString(Envelope.serializer(), env)
        assertTrue("user_id_source host wire value", str.contains("\"user_id_source\":\"host\""))
    }

    @Test fun `user_id_source generated serializes as generated`() {
        val env = minimalEnvelope().copy(userIdSource = UserIdSource.GENERATED)
        val str = json.encodeToString(Envelope.serializer(), env)
        assertTrue("user_id_source generated wire value", str.contains("\"user_id_source\":\"generated\""))
    }

    @Test fun `field names match spec §2`() {
        val env = minimalEnvelope()
        val str = json.encodeToString(Envelope.serializer(), env)
        // §2 required top-level keys
        for (key in listOf("schema_version", "sdk", "app", "device", "integrity",
                           "install_id", "session_id", "user_id", "user_id_source", "events")) {
            assertTrue("Missing required field: $key", str.contains("\"$key\""))
        }
    }

    @Test fun `event type wire values match spec §4`() {
        // Verifies @SerialName values on EventType
        val cases = mapOf(
            EventType.NETWORK          to "network",
            EventType.NETWORK_FAILURE  to "network_failure",
            EventType.CRASH            to "crash",
            EventType.ERROR            to "error",
            EventType.BREADCRUMB       to "breadcrumb",
            EventType.LIFECYCLE        to "lifecycle",
            EventType.PERFORMANCE      to "performance",
            EventType.TERMINATION      to "termination"
        )
        cases.forEach { (type, wire) ->
            val serialized = json.encodeToString(EventType.serializer(), type)
            assertEquals("EventType.$type wire value", "\"$wire\"", serialized)
        }
    }
}
