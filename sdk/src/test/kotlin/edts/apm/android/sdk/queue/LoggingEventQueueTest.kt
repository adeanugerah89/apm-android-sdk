package edts.apm.android.sdk.queue

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.scrub.ScrubbingEventQueue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** MOB-26 — local debug log of captured events, off in release builds. */
class LoggingEventQueueTest {

    private class RecordingQueue(private val accept: Boolean = true) : EventQueue {
        val events = mutableListOf<Event>()
        override fun enqueue(event: Event): Boolean { events.add(event); return accept }
        override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
        override fun delete(ids: List<String>) {}
        override fun stats() = QueueStats(events.size, 0)
    }

    private fun event(message: String = "hello", type: EventType = EventType.ERROR) = Event(
        eventId = "e-1",
        type = type,
        tsClient = "2026-09-09T00:00:00.000Z",
        seq = 42,
        attrs = buildJsonObject { put("message", message) },
        ctx = EventContext(screen = "CheckoutActivity", appState = "foreground")
    )

    @Test fun `logs nothing when debug mode is off`() {
        val lines = mutableListOf<String>()
        val queue = LoggingEventQueue(RecordingQueue(), isEnabled = { false }) { _, m -> lines.add(m) }

        queue.enqueue(event())

        assertTrue(lines.isEmpty())
    }

    @Test fun `logs the event when debug mode is on`() {
        val lines = mutableListOf<String>()
        val queue = LoggingEventQueue(RecordingQueue(), isEnabled = { true }) { _, m -> lines.add(m) }

        queue.enqueue(event())

        val line = lines.single()
        assertTrue(line.contains("ERROR"))
        assertTrue(line.contains("seq=42"))
        assertTrue(line.contains("CheckoutActivity"))
        assertTrue(line.contains("hello"))
    }

    @Test fun `a rejected event is logged as rejected, not as queued`() {
        val lines = mutableListOf<String>()
        val queue = LoggingEventQueue(RecordingQueue(accept = false), isEnabled = { true }) { _, m -> lines.add(m) }

        assertFalse(queue.enqueue(event()))

        assertTrue(lines.single().startsWith("REJECTED"))
    }

    @Test fun `the event still reaches the delegate either way`() {
        val delegate = RecordingQueue()
        LoggingEventQueue(delegate, isEnabled = { false }).enqueue(event())
        LoggingEventQueue(delegate, isEnabled = { true }) { _, _ -> }.enqueue(event())

        assertEquals(2, delegate.events.size)
    }

    @Test fun `very large attributes are truncated so logcat does not drop the line`() {
        val lines = mutableListOf<String>()
        val queue = LoggingEventQueue(RecordingQueue(), isEnabled = { true }) { _, m -> lines.add(m) }

        queue.enqueue(event(message = "x".repeat(50_000)))

        val line = lines.single()
        assertTrue("got ${line.length} chars", line.length < 2_000)
        assertTrue(line.contains("chars)"))
    }

    @Test fun `a throwing enabled-check disables logging rather than breaking capture`() {
        val delegate = RecordingQueue()
        val queue = LoggingEventQueue(delegate, isEnabled = { throw IllegalStateException("boom") })

        assertTrue(queue.enqueue(event()))
        assertEquals(1, delegate.events.size)
    }

    /**
     * The composition-order requirement, asserted rather than left to a comment: the logger must be
     * wrapped by the scrubber, so what it prints has already been redacted. Getting this backwards
     * would put personal data into logcat — the one sink nobody audits.
     */
    @Test fun `composed inside the scrubber, the log never shows unredacted personal data`() {
        val lines = mutableListOf<String>()
        val delegate = RecordingQueue()
        val pipeline = ScrubbingEventQueue(
            LoggingEventQueue(delegate, isEnabled = { true }) { _, m -> lines.add(m) }
        )

        pipeline.enqueue(event(message = "call me at 081234567890 or a@b.com"))

        val line = lines.single()
        assertFalse("phone number leaked into logcat", line.contains("081234567890"))
        assertFalse("email leaked into logcat", line.contains("a@b.com"))
        assertTrue(line.contains("[REDACTED]"))
    }

    @Test fun `peek delete and stats pass through untouched`() {
        val delegate = RecordingQueue()
        delegate.enqueue(event())
        val queue = LoggingEventQueue(delegate, isEnabled = { true }) { _, _ -> }

        queue.peek(5)
        queue.delete(listOf("a"))

        assertEquals(1, queue.stats().eventCount)
    }
}
