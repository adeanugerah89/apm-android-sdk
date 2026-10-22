package edts.apm.android.sdk.queue

import edts.apm.android.sdk.core.DropReason
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.SdkHealth
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Disk-backed FIFO event queue. (MOB-04/05/06)
 *
 * Storage: [queueDir] lives under `noBackupFilesDir` (see [edts.apm.android.sdk.internal.SdkStorage]), which the
 * platform's Auto Backup always excludes, regardless of the host's rules (SEC-07, A29).
 * Atomicity: write to <uuid>.tmp, then rename to <uuid>.evt — rename is atomic on POSIX.
 *   If the process dies during write, the .tmp file is left behind and cleaned up on next init.
 * FIFO: files sorted by lastModified(). On most Linux filesystems rename preserves the
 *   mtime of the temp file; we set it explicitly before rename.
 * Capacity: if count >= MAX_EVENTS or bytes >= MAX_BYTES, evict oldest before writing new. (MOB-06)
 * Thread-safety: single ReentrantLock guards all file operations.
 *
 * At-rest encryption (SEC-08) lives in [codec], not in this class: the file mechanics — atomic
 * rename, FIFO ordering, capacity eviction — are identical whether the bytes are ciphertext or
 * JSON, so they are written once here and the format is decided by whichever [EventCodec] is
 * supplied. See [EncryptedEventCodec].
 */
internal class DiskEventQueue(
    private val queueDir: File,
    private val health: SdkHealth,
    private val codec: EventCodec = JsonEventCodec(),
    private val maxEvents: Int = EventQueue.MAX_EVENTS,
    private val maxBytes: Long = EventQueue.MAX_BYTES
) : EventQueue {

    private val lock = ReentrantLock()

    companion object {
        private const val EXT_EVT  = ".evt"
        private const val EXT_TMP  = ".tmp"
    }

    init {
        queueDir.mkdirs()
        // Clean up any .tmp files left by a crashed write
        lock.withLock { cleanStaleTemps() }
    }

    override fun enqueue(event: Event): Boolean = lock.withLock {
        // Null covers both a malformed event and a failed encryption — either way nothing that
        // cannot be read back later is allowed onto disk.
        val payload = codec.encode(event) ?: return false
        // Encoded first, so eviction can account for what is about to be written (MOB-06, `02` §5).
        enforceCapacity(incomingBytes = payload.size.toLong())
        val id   = UUID.randomUUID().toString()
        val tmp  = File(queueDir, "$id$EXT_TMP")
        val dest = File(queueDir, "$id$EXT_EVT")
        return try {
            tmp.writeBytes(payload)
            tmp.renameTo(dest)   // atomic on POSIX (Linux/Android)
            health.recordWritten()
            true
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    /**
     * Undecodable files are **deleted here**, not skipped. Skipping them was survivable while every
     * file was plain JSON and an unreadable one meant a rare truncated write. Encryption creates a
     * way for many files to become unreadable at once — a Keystore key that is regenerated or
     * invalidated makes the whole backlog undecryptable — and a skipped file still occupies the
     * head of the FIFO, so `take(maxCount)` would keep returning nothing and the queue would stall
     * for good behind data no build can ever read. Purging costs the unreadable bytes and keeps
     * the queue moving; the loss is visible as `drop_reasons.undecodable` (§2.3, MOB-27).
     *
     * Walks past bad files until it has [maxCount] good ones, rather than reading exactly
     * [maxCount] files and returning however many survived.
     */
    override fun peek(maxCount: Int): List<QueuedEvent> = lock.withLock {
        val result = ArrayList<QueuedEvent>(maxCount)
        for (file in evtFiles()) {
            if (result.size >= maxCount) break
            val bytes = try {
                file.readBytes()
            } catch (e: Exception) {
                null
            }
            val event = bytes?.let { codec.decode(it) }
            if (event == null) {
                file.delete()
                health.recordDropped(DropReason.UNDECODABLE)
                continue
            }
            result += QueuedEvent(id = file.absolutePath, event = event, sizeBytes = bytes.size.toLong())
        }
        result
    }

    override fun delete(ids: List<String>): Unit = lock.withLock {
        ids.forEach { path -> File(path).delete() }
    }

    override fun stats(): QueueStats = lock.withLock {
        val files = evtFiles()
        QueueStats(
            eventCount = files.size,
            totalBytes = files.sumOf { it.length() }
        )
    }

    // ─── private ────────────────────────────────────────────────────────────

    /** Sorted oldest-first (FIFO). */
    private fun evtFiles(): List<File> =
        queueDir.listFiles { f -> f.name.endsWith(EXT_EVT) }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

    /**
     * Evicts oldest-first until the queue can take [incomingBytes] more without breaching its
     * limits (MOB-06). Called while the lock is held.
     *
     * [incomingBytes] is what makes the disk ceiling actually hold. Evicting until the *existing*
     * contents fit, then writing anyway, leaves the queue over the limit by exactly one event —
     * small at 20 MB, but `02` §5 states a ceiling, and a ceiling that is routinely exceeded by a
     * predictable amount is a ceiling nobody can rely on when sizing device storage.
     */
    private fun enforceCapacity(incomingBytes: Long) {
        val files = evtFiles().toMutableList()
        var count = files.size
        var bytes = files.sumOf { it.length() }
        while (count + 1 > maxEvents || bytes + incomingBytes > maxBytes) {
            val oldest = files.removeFirstOrNull() ?: break
            bytes -= oldest.length()
            count -= 1
            oldest.delete()
            health.recordDropped(DropReason.QUEUE_FULL)
        }
    }

    private fun cleanStaleTemps() {
        queueDir.listFiles { f -> f.name.endsWith(EXT_TMP) }
            ?.forEach { it.delete() }
    }
}
