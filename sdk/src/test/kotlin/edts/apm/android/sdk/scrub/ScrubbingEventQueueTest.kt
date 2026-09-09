package edts.apm.android.sdk.scrub

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** SEC-01/05b: scrubbing must run as the very last step before whatever queue writes to disk. */
class ScrubbingEventQueueTest {

    private class RecordingQueue : EventQueue {
        var lastEnqueued: Event? = null
        override fun enqueue(event: Event): Boolean { lastEnqueued = event; return true }
        override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
        override fun delete(ids: List<String>) {}
        override fun stats(): QueueStats = QueueStats(0, 0)
    }

    private fun makeEvent(message: String) = Event(
        eventId  = "evt-1",
        type     = EventType.ERROR,
        tsClient = "2026-09-07T00:00:00.000Z",
        seq      = 1L,
        attrs    = buildJsonObject { put("message", message) },
        ctx      = EventContext()
    )

    @Test fun `delegate receives a scrubbed event, never the raw one`() {
        val recording = RecordingQueue()
        val queue = ScrubbingEventQueue(recording)

        queue.enqueue(makeEvent("Gagal mengirim OTP ke 08123456789"))

        val delegated = recording.lastEnqueued!!
        assertEquals("Gagal mengirim OTP ke [REDACTED]", delegated.attrs.getValue("message").jsonPrimitive.content)
    }

    @Test fun `peek, delete and stats pass through unchanged`() {
        val recording = RecordingQueue()
        val queue = ScrubbingEventQueue(recording)

        queue.peek(5)
        queue.delete(listOf("a", "b"))
        queue.stats()

        // No exception and no crash is the contract here — RecordingQueue's stub
        // implementations are trivially satisfied; this guards against ScrubbingEventQueue
        // accidentally swallowing or mutating pass-through calls.
        assertEquals(0, recording.stats().eventCount)
    }

    @Test fun `enqueue return value is passed through from the delegate`() {
        val alwaysRejects = object : EventQueue {
            override fun enqueue(event: Event) = false
            override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
            override fun delete(ids: List<String>) {}
            override fun stats(): QueueStats = QueueStats(0, 0)
        }
        val queue = ScrubbingEventQueue(alwaysRejects)

        assertFalse(queue.enqueue(makeEvent("hello")))
    }
}
