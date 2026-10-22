package edts.apm.android.sdk.stability

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** MOB-19 — cold start to first frame, emitted as `lifecycle` / `state: cold_start` (`01` §4.6), matching iOS. */
class ColdStartTrackerTest {

    private class RecordingQueue : EventQueue {
        val events = mutableListOf<Event>()
        override fun enqueue(event: Event): Boolean { events.add(event); return true }
        override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
        override fun delete(ids: List<String>) {}
        override fun stats() = QueueStats(events.size, 0)
    }

    private fun tracker(
        queue: EventQueue,
        processStartElapsedMs: Long = 1_000L,
        nowMs: Long = 1_850L,
        enabled: () -> Boolean = { true }
    ) = ColdStartTracker(
        queue = queue,
        seqProvider = { 3L },
        contextProvider = { EventContext(appState = "foreground") },
        processStartElapsedMs = processStartElapsedMs,
        isEnabled = enabled,
        elapsedRealtime = { nowMs }
    )

    @Test fun `emits a lifecycle event with state cold_start and duration_ms`() {
        val queue = RecordingQueue()

        assertEquals(850L, tracker(queue).onFirstFrame())

        val event = queue.events.single()
        assertEquals(EventType.LIFECYCLE, event.type)
        assertEquals("cold_start", event.attrs.getValue("state").jsonPrimitive.content)
        assertEquals("850", event.attrs.getValue("duration_ms").jsonPrimitive.content)
    }

    @Test fun `duration is measured from process start, not from SDK init`() {
        val queue = RecordingQueue()

        tracker(queue, processStartElapsedMs = 500L, nowMs = 3_000L).onFirstFrame()

        assertEquals("2500", queue.events.single().attrs.getValue("duration_ms").jsonPrimitive.content)
    }

    @Test fun `duration uses the time the frame was drawn, not the time the tracker was told (A46)`() {
        val queue = RecordingQueue()

        // Told at 9_000 about a frame drawn at 1_850: assembly finished long after the frame.
        assertEquals(850L, tracker(queue, nowMs = 9_000L).onFirstFrame(frameElapsedRealtimeMs = 1_850L))

        assertEquals("850", queue.events.single().attrs.getValue("duration_ms").jsonPrimitive.content)
    }

    @Test fun `fires at most once per process`() {
        val queue = RecordingQueue()
        val t = tracker(queue)

        assertNotNull(t.onFirstFrame())
        assertNull(t.onFirstFrame())
        assertEquals(1, queue.events.size)
    }

    @Test fun `an implausibly long start is discarded as a headless process launch`() {
        val queue = RecordingQueue()

        val duration = tracker(
            queue,
            processStartElapsedMs = 0L,
            nowMs = ColdStartTracker.MAX_PLAUSIBLE_COLD_START_MS + 1
        ).onFirstFrame()

        assertNull(duration)
        assertTrue(queue.events.isEmpty())
    }

    @Test fun `a negative measurement is discarded rather than sent as nonsense`() {
        val queue = RecordingQueue()

        assertNull(tracker(queue, processStartElapsedMs = 5_000L, nowMs = 4_000L).onFirstFrame())
        assertTrue(queue.events.isEmpty())
    }

    @Test fun `remotely disabling cold_start suppresses the event`() {
        val queue = RecordingQueue()

        assertNull(tracker(queue, enabled = { false }).onFirstFrame())
        assertTrue(queue.events.isEmpty())
    }

    @Test fun `on the wire the cold start is type lifecycle, the value iOS and the backend corpus use`() {
        val queue = RecordingQueue()
        tracker(queue).onFirstFrame()

        val wire = kotlinx.serialization.json.Json { encodeDefaults = true }
            .encodeToString(edts.apm.android.sdk.core.Event.serializer(), queue.events.single())

        assertTrue(wire, wire.contains("\"type\":\"lifecycle\""))
        assertFalse(wire, wire.contains("\"performance\""))
        assertTrue(wire, wire.contains("\"state\":\"cold_start\""))
    }
}
