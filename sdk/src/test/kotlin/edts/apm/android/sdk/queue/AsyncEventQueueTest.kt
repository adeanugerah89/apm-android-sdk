package edts.apm.android.sdk.queue

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.core.SdkHealth
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A18 / `02` §5 — capture must not block the caller's thread. */
class AsyncEventQueueTest {

    private class RecordingQueue(private val onEnqueue: (() -> Unit)? = null) : EventQueue {
        val events = java.util.Collections.synchronizedList(mutableListOf<Event>())
        var peeked = 0
        val deleted = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun enqueue(event: Event): Boolean { onEnqueue?.invoke(); events.add(event); return true }
        override fun peek(maxCount: Int): List<QueuedEvent> { peeked++; return emptyList() }
        override fun delete(ids: List<String>) { deleted.addAll(ids) }
        override fun stats() = QueueStats(events.size, 0)
    }

    private fun event(seq: Long) = Event(
        eventId = "e-$seq",
        type = EventType.ERROR,
        tsClient = "2026-09-07T00:00:00.000Z",
        seq = seq,
        attrs = JsonObject(emptyMap()),
        ctx = EventContext()
    )

    @Test fun `the event reaches the delegate, just not on the calling thread`() {
        val callingThread = Thread.currentThread()
        var writerThread: Thread? = null
        val delegate = RecordingQueue { writerThread = Thread.currentThread() }
        val queue = AsyncEventQueue(delegate, SdkHealth())

        assertTrue(queue.enqueue(event(1)))
        assertTrue(queue.awaitDrain(5_000))

        assertEquals(1, delegate.events.size)
        assertNotSame("the write must not happen on the caller's thread", callingThread, writerThread)
    }

    @Test fun `enqueue returns before the write completes`() {
        val released = CountDownLatch(1)
        val delegate = RecordingQueue { released.await(5, TimeUnit.SECONDS) }
        val queue = AsyncEventQueue(delegate, SdkHealth())

        queue.enqueue(event(1))
        // The writer is parked inside the delegate; if enqueue were synchronous this line would
        // not be reachable until the latch opened.
        assertTrue(delegate.events.isEmpty())

        released.countDown()
        assertTrue(queue.awaitDrain(5_000))
        assertEquals(1, delegate.events.size)
    }

    @Test fun `order is preserved — seq stays meaningful`() {
        val delegate = RecordingQueue()
        val queue = AsyncEventQueue(delegate, SdkHealth())

        (1L..200L).forEach { queue.enqueue(event(it)) }
        assertTrue(queue.awaitDrain(10_000))

        assertEquals((1L..200L).toList(), delegate.events.map { it.seq })
    }

    @Test fun `overflow is dropped and counted, never allowed to grow without bound`() {
        val blocked = CountDownLatch(1)
        val delegate = RecordingQueue { blocked.await(10, TimeUnit.SECONDS) }
        val health = SdkHealth()
        val queue = AsyncEventQueue(delegate, health, maxPending = 4)

        // First submission occupies the worker; the next 4 fill the pending queue; the rest overflow.
        val accepted = (1L..20L).count { queue.enqueue(event(it)) }

        assertTrue("some events must be refused once the buffer is full", accepted < 20)
        assertTrue(health.snapshot().dropped > 0)
        assertEquals(health.snapshot().dropped, health.snapshot().dropReasons.getValue("queue_full"))

        blocked.countDown()
        queue.awaitDrain(10_000)
    }

    @Test fun `reads and deletes pass straight through, unqueued`() {
        val delegate = RecordingQueue()
        val queue = AsyncEventQueue(delegate, SdkHealth())

        queue.peek(10)
        queue.delete(listOf("a", "b"))

        assertEquals(1, delegate.peeked)
        assertEquals(listOf("a", "b"), delegate.deleted)
    }

    @Test fun `a delegate that throws does not surface to the caller`() {
        val delegate = object : EventQueue {
            override fun enqueue(event: Event): Boolean = throw IllegalStateException("disk on fire")
            override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
            override fun delete(ids: List<String>) {}
            override fun stats() = QueueStats(0, 0)
        }
        val queue = AsyncEventQueue(delegate, SdkHealth())

        assertTrue(queue.enqueue(event(1)))
        queue.awaitDrain(5_000)
        // No exception on this thread is the assertion (CONSTITUTION rule #1).
    }
}
