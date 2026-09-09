package edts.apm.android.sdk.queue

import edts.apm.android.sdk.config.RemoteConfig
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

/** MOB-21 — kill switch and per-feature gating at the capture end of the pipeline. */
class GatedEventQueueTest {

    private class RecordingQueue : EventQueue {
        val events = mutableListOf<Event>()
        var peeked = 0
        var deleted = mutableListOf<String>()
        override fun enqueue(event: Event): Boolean { events.add(event); return true }
        override fun peek(maxCount: Int): List<QueuedEvent> { peeked++; return emptyList() }
        override fun delete(ids: List<String>) { deleted.addAll(ids) }
        override fun stats() = QueueStats(events.size, 0)
    }

    private fun event(type: EventType) = Event(
        eventId = "e-${type.name}",
        type = type,
        tsClient = "2026-09-07T00:00:00.000Z",
        seq = 1,
        attrs = buildJsonObject { },
        ctx = EventContext()
    )

    @Test fun `passes events through when the SDK is enabled`() {
        val delegate = RecordingQueue()
        val gated = GatedEventQueue(delegate) { RemoteConfig.DEFAULT }

        assertTrue(gated.enqueue(event(EventType.NETWORK)))
        assertEquals(1, delegate.events.size)
    }

    @Test fun `kill switch rejects every event type`() {
        val delegate = RecordingQueue()
        val gated = GatedEventQueue(delegate) { RemoteConfig(enabled = false) }

        EventType.values().forEach { assertFalse(gated.enqueue(event(it))) }
        assertTrue(delegate.events.isEmpty())
    }

    @Test fun `a disabled feature blocks only its own event types`() {
        val delegate = RecordingQueue()
        val gated = GatedEventQueue(delegate) { RemoteConfig(disabledFeatures = listOf("network")) }

        assertFalse(gated.enqueue(event(EventType.NETWORK)))
        assertFalse(gated.enqueue(event(EventType.NETWORK_FAILURE)))
        assertTrue(gated.enqueue(event(EventType.CRASH)))
        assertTrue(gated.enqueue(event(EventType.BREADCRUMB)))
        assertEquals(2, delegate.events.size)
    }

    @Test fun `error and termination have no feature name and are never gated by disabled_features`() {
        val delegate = RecordingQueue()
        val allFeatureNames = RemoteConfig.Feature.values().map { it.key }
        val gated = GatedEventQueue(delegate) { RemoteConfig(disabledFeatures = allFeatureNames) }

        assertTrue(gated.enqueue(event(EventType.ERROR)))
        assertTrue(gated.enqueue(event(EventType.TERMINATION)))
        assertTrue(gated.enqueue(event(EventType.LIFECYCLE)))
        assertEquals(3, delegate.events.size)
    }

    @Test fun `a throwing config provider fails open rather than stopping capture`() {
        val delegate = RecordingQueue()
        val gated = GatedEventQueue(delegate) { throw IllegalStateException("config not ready") }

        assertTrue(gated.enqueue(event(EventType.CRASH)))
        assertEquals(1, delegate.events.size)
    }

    @Test fun `peek delete and stats pass through even while killed`() {
        val delegate = RecordingQueue()
        delegate.enqueue(event(EventType.CRASH))
        val gated = GatedEventQueue(delegate) { RemoteConfig(enabled = false) }

        gated.peek(10)
        gated.delete(listOf("a", "b"))

        assertEquals(1, delegate.peeked)
        assertEquals(listOf("a", "b"), delegate.deleted)
        assertEquals(1, gated.stats().eventCount)
    }

    @Test fun `the gate follows a config change without being rebuilt`() {
        val delegate = RecordingQueue()
        var config = RemoteConfig.DEFAULT
        val gated = GatedEventQueue(delegate) { config }

        assertTrue(gated.enqueue(event(EventType.NETWORK)))
        config = RemoteConfig(enabled = false)
        assertFalse(gated.enqueue(event(EventType.NETWORK)))
        config = RemoteConfig.DEFAULT
        assertTrue(gated.enqueue(event(EventType.NETWORK)))

        assertEquals(2, delegate.events.size)
    }
}
