package edts.apm.android.sdk.sync

import edts.apm.android.sdk.core.AppInfo
import edts.apm.android.sdk.core.DeviceInfo
import edts.apm.android.sdk.core.DropReason
import edts.apm.android.sdk.core.Envelope
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.core.IntegrityInfo
import edts.apm.android.sdk.core.SdkHealth
import edts.apm.android.sdk.core.UserIdSource
import edts.apm.android.sdk.queue.DiskEventQueue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * A44 — what the backend actually reads. `apm-backend` stores `sdk.health` from each envelope
 * (`worker.go` → table `sdk_health`) and its integration endpoint reports `dropped` and `drop_reasons`
 * from there (`01` §2.3). Until now nothing in this repo pinned those **wire keys** for a drop that really
 * happened, so a rename of a `@SerialName` would have silently blinded the backend's only view of upload
 * loss. This drives a real overflow through a real `DiskEventQueue`, builds the envelope the way the
 * uploader does, and reads the JSON.
 */
class SdkHealthWireTest {

    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { encodeDefaults = true }

    private fun event(seq: Long) = Event(
        eventId = UUID.randomUUID().toString(), type = EventType.ERROR, tsClient = "2026-09-07T00:00:00.000Z",
        seq = seq, attrs = JsonObject(emptyMap()), ctx = EventContext()
    )

    private fun context() = EnvelopeStaticContext(
        sdkName = "apm-android-sdk", sdkVersion = "0.1.0",
        app = AppInfo("edts.apm.android.sdk", "1.0", "100"),
        device = DeviceInfo(osVersion = "14", model = "Pixel 9", locale = "id_ID", timezone = "Asia/Jakarta"),
        integrity = IntegrityInfo(), installId = "install-1", sessionId = "session-1",
        userId = "user-1", userIdSource = UserIdSource.HOST
    )

    private fun healthOnTheWire(health: SdkHealth, queue: DiskEventQueue): JsonObject {
        val envelope = buildEnvelope(context(), health.snapshot(), queue.peek(10))
        val root = json.parseToJsonElement(json.encodeToString(Envelope.serializer(), envelope)).jsonObject
        return root.getValue("sdk").jsonObject.getValue("health").jsonObject
    }

    @Test fun `a real queue overflow reaches the envelope as sdk health dropped and drop_reasons queue_full`() {
        val health = SdkHealth()
        val queue = DiskEventQueue(tmp.newFolder("q"), health, maxEvents = 2, maxBytes = Long.MAX_VALUE)
        queue.enqueue(event(1)); Thread.sleep(5)
        queue.enqueue(event(2)); Thread.sleep(5)
        queue.enqueue(event(3))     // evicts the oldest: a real loss

        val wire = healthOnTheWire(health, queue)

        // Exactly the keys `01` §2.3 and the backend's worker read.
        assertEquals(setOf("written", "sent", "dropped", "drop_reasons"), wire.keys)
        assertEquals("3", wire.getValue("written").jsonPrimitive.content)
        assertEquals("1", wire.getValue("dropped").jsonPrimitive.content)
        assertEquals("1", wire.getValue("drop_reasons").jsonObject.getValue("queue_full").jsonPrimitive.content)
    }

    @Test fun `every drop reason the SDK records has its documented wire key, and counts are cumulative numbers`() {
        val health = SdkHealth()
        health.recordDropped(DropReason.QUEUE_FULL, 2)
        health.recordDropped(DropReason.UNDECODABLE)
        health.recordDropped(DropReason.REJECTED, 4)
        val queue = DiskEventQueue(tmp.newFolder("q2"), health)

        val reasons = healthOnTheWire(health, queue).getValue("drop_reasons").jsonObject

        assertEquals(setOf("queue_full", "undecodable", "rejected"), reasons.keys)
        assertEquals(listOf("2", "1", "4"), listOf("queue_full", "undecodable", "rejected").map { reasons.getValue(it).jsonPrimitive.content })
    }

    @Test fun `every reason the SDK records is a real loss, so dropped equals the sum of drop_reasons (01 section 2_3)`() {
        // 01 §2.3: drop_reasons MAY include deliberate drops that `dropped` does not count. Android records none
        // of those today — each DropReason goes through the one recordDropped path — so the two agree. If a
        // deliberate-drop reason is ever added, this test is where that has to be decided on purpose.
        val health = SdkHealth()
        DropReason.values().forEachIndexed { i, r -> health.recordDropped(r, i + 1) }
        val snap = health.snapshot()
        assertEquals(snap.dropped, snap.dropReasons.values.sum())
    }

    @Test fun `a clean install reports zero drops and an empty drop_reasons object, not a missing key`() {
        val health = SdkHealth()
        val queue = DiskEventQueue(tmp.newFolder("q3"), health)

        val wire = healthOnTheWire(health, queue)

        assertEquals("0", wire.getValue("dropped").jsonPrimitive.content)
        assertTrue(wire.getValue("drop_reasons").jsonObject.isEmpty())
        assertFalse("drop_reasons must be present even when empty", "drop_reasons" !in wire)
    }
}
