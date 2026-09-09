package edts.apm.android.sdk.queue

import edts.apm.android.sdk.core.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskEventQueueTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var queueDir: java.io.File
    private lateinit var health: SdkHealth
    private lateinit var queue: DiskEventQueue

    @Before fun setUp() {
        queueDir = tmp.newFolder("queue")
        health   = SdkHealth()
        queue    = DiskEventQueue(queueDir, health)
    }

    private fun makeEvent(seq: Long = 1L) = Event(
        eventId = java.util.UUID.randomUUID().toString(),
        type    = EventType.ERROR,
        tsClient = "2026-09-07T00:00:00.000Z",
        seq     = seq,
        attrs   = JsonObject(emptyMap()),
        ctx     = EventContext()
    )

    @Test fun `enqueue writes a file and stats reflects it`() {
        val ok = queue.enqueue(makeEvent())
        assertTrue(ok)
        val stats = queue.stats()
        assertEquals(1, stats.eventCount)
        assertTrue(stats.totalBytes > 0)
    }

    @Test fun `peek returns events in FIFO order`() {
        // Enqueue three with small sleep to ensure distinct mtime
        for (i in 1..3L) {
            queue.enqueue(makeEvent(seq = i))
            Thread.sleep(5)  // ensure distinct lastModified
        }
        val peeked = queue.peek()
        assertEquals(3, peeked.size)
        // FIFO: seq should be ascending
        assertEquals(1L, peeked[0].event.seq)
        assertEquals(2L, peeked[1].event.seq)
        assertEquals(3L, peeked[2].event.seq)
    }

    @Test fun `delete removes files from queue`() {
        queue.enqueue(makeEvent())
        val ids = queue.peek().map { it.id }
        queue.delete(ids)
        assertEquals(0, queue.stats().eventCount)
    }

    @Test fun `peek does not delete events`() {
        queue.enqueue(makeEvent())
        queue.peek()
        queue.peek()
        assertEquals(1, queue.stats().eventCount)
    }

    @Test fun `FIFO eviction when count exceeds max events`() {
        val tinyDir = tmp.newFolder("tiny")
        val tinyHealth = SdkHealth()
        val tinyQueue = DiskEventQueue(tinyDir, tinyHealth, maxEvents = 2, maxBytes = Long.MAX_VALUE)

        tinyQueue.enqueue(makeEvent(1))
        Thread.sleep(5)
        tinyQueue.enqueue(makeEvent(2))
        Thread.sleep(5)
        // Third enqueue pushes count to the limit — oldest (seq=1) must be evicted first.
        tinyQueue.enqueue(makeEvent(3))

        val remaining = tinyQueue.peek().map { it.event.seq }
        assertEquals(listOf(2L, 3L), remaining)
        assertEquals(1L, tinyHealth.snapshot().dropped)
    }

    @Test fun `stale tmp files cleaned on init`() {
        // Create a stale .tmp manually
        java.io.File(queueDir, "stale.tmp").writeText("garbage")
        // Re-init queue — should clean it
        DiskEventQueue(queueDir, health)
        assertFalse(java.io.File(queueDir, "stale.tmp").exists())
    }

    @Test fun `enqueue increments health written counter`() {
        queue.enqueue(makeEvent())
        assertEquals(1L, health.snapshot().written)
    }
}
