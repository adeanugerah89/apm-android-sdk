package edts.apm.android.sdk.queue

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.core.SdkHealth
import edts.apm.android.sdk.crypto.AesGcmQueueCipher
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * SEC-08 end-to-end: the real [DiskEventQueue] writing through [EncryptedEventCodec].
 *
 * The assertion that actually proves the requirement is made against the bytes on disk, not
 * against a mock — a queue file must not contain the event's contents in readable form.
 */
class EncryptedDiskEventQueueTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var queueDir: File
    private lateinit var health: SdkHealth
    private lateinit var key: SecretKey

    @Before fun setUp() {
        queueDir = tmp.newFolder("queue")
        health = SdkHealth()
        key = newKey()
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun encryptedQueue(k: SecretKey = key, maxEvents: Int = EventQueue.MAX_EVENTS) =
        DiskEventQueue(
            queueDir = queueDir,
            health = health,
            codec = EncryptedEventCodec(AesGcmQueueCipher(k)),
            maxEvents = maxEvents
        )

    private fun plainQueue() = DiskEventQueue(queueDir, health, JsonEventCodec())

    private fun event(marker: String = "SENSITIVE-NEEDLE", seq: Long = 1) = Event(
        eventId = UUID.randomUUID().toString(),
        type = EventType.ERROR,
        tsClient = "2026-09-07T00:00:00.000Z",
        seq = seq,
        attrs = buildJsonObject { put("message", marker) },
        ctx = EventContext(screen = "LoginActivity")
    )

    private fun evtFiles() = queueDir.listFiles()!!.filter { it.name.endsWith(".evt") }

    @Test fun `nothing readable reaches disk`() {
        encryptedQueue().enqueue(event())

        val onDisk = String(evtFiles().single().readBytes(), Charsets.ISO_8859_1)
        assertFalse(onDisk.contains("SENSITIVE-NEEDLE"))
        assertFalse(onDisk.contains("LoginActivity"))
        assertFalse(onDisk.contains("event_id"))
    }

    @Test fun `every queue file is written in the encrypted format`() {
        val queue = encryptedQueue()
        repeat(5) { queue.enqueue(event(seq = it.toLong())) }

        assertEquals(5, evtFiles().size)
        assertTrue(evtFiles().all { AesGcmQueueCipher.looksEncrypted(it.readBytes()) })
    }

    @Test fun `peek returns the original events, decrypted`() {
        val queue = encryptedQueue()
        val written = (0L until 3L).map { event(seq = it) }
        written.forEach { queue.enqueue(it) }

        val read = queue.peek(10)

        assertEquals(written.toSet(), read.map { it.event }.toSet())
    }

    @Test fun `a queue written before encryption is still readable after the upgrade`() {
        plainQueue().enqueue(event(marker = "LEGACY-EVENT"))

        val read = encryptedQueue().peek(10)

        assertEquals(1, read.size)
        assertTrue(read.single().event.attrs.toString().contains("LEGACY-EVENT"))
    }

    @Test fun `a lost key purges the unreadable backlog instead of stalling the queue forever`() {
        val queue = encryptedQueue()
        repeat(3) { queue.enqueue(event(seq = it.toLong())) }
        assertEquals(3, evtFiles().size)

        // Key regenerated / invalidated: nothing already on disk can be decrypted any more.
        val afterKeyLoss = encryptedQueue(k = newKey())
        val read = afterKeyLoss.peek(10)

        assertTrue(read.isEmpty())
        assertEquals("undecodable files must not survive the read", 0, evtFiles().size)
        assertEquals(3L, health.snapshot().dropped)
        assertEquals(3L, health.snapshot().dropReasons["undecodable"])
    }

    @Test fun `the queue keeps working after a purge — new events go through normally`() {
        encryptedQueue().enqueue(event())
        val queue = encryptedQueue(k = newKey())
        queue.peek(10)                       // purges the now-unreadable file

        queue.enqueue(event(marker = "AFTER-PURGE"))

        val read = queue.peek(10)
        assertEquals(1, read.size)
        assertTrue(read.single().event.attrs.toString().contains("AFTER-PURGE"))
    }

    @Test fun `peek walks past bad files to fill its batch, rather than returning short`() {
        val queue = encryptedQueue()
        queue.enqueue(event(marker = "GOOD-1"))
        queue.enqueue(event(marker = "GOOD-2"))
        val corrupt = File(queueDir, "corrupt.evt").apply { writeBytes(byteArrayOf(0x01, 0x02, 0x03)) }

        // FIFO order is mtime order, and three files written in the same instant can share a
        // timestamp — so the corrupt file is stamped explicitly as the oldest instead of relying on
        // write order. Without this the batch fills from the good files and never reaches it, which
        // is precisely the case this test exists to cover: a bad file at the HEAD of the queue.
        stampOldestFirst(corrupt, *evtFiles().filter { it != corrupt }.toTypedArray())

        val read = queue.peek(2)

        assertEquals("a bad file at the head must not shorten the batch", 2, read.size)
        assertEquals(1L, health.snapshot().dropReasons["undecodable"])
        assertFalse("the bad file must be gone", corrupt.exists())
    }

    /** Stamps [files] with strictly increasing mtimes, so FIFO order is the argument order. */
    private fun stampOldestFirst(vararg files: File) {
        val base = System.currentTimeMillis() - 60_000
        files.forEachIndexed { index, file -> file.setLastModified(base + index * 1_000L) }
    }

    @Test fun `delete still removes exactly the peeked files`() {
        val queue = encryptedQueue()
        repeat(3) { queue.enqueue(event(seq = it.toLong())) }
        val batch = queue.peek(2)

        queue.delete(batch.map { it.id })

        assertEquals(1, evtFiles().size)
        assertEquals(1, queue.stats().eventCount)
    }

    @Test fun `capacity eviction is unaffected by encryption`() {
        val queue = encryptedQueue(maxEvents = 3)

        repeat(6) { queue.enqueue(event(seq = it.toLong())) }

        assertTrue("must stay within the injected limit", evtFiles().size <= 3)
        assertTrue(health.snapshot().dropReasons.getValue("queue_full") > 0)
    }
}
