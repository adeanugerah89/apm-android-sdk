package edts.apm.android.sdk.queue

import edts.apm.android.sdk.core.Event

/**
 * Abstraction over the disk-backed event queue. (MOB-04/05/06)
 *
 * Rationale for interface: enables mock implementations in unit tests without touching
 * disk; enables future encryption (A14) by wrapping this interface transparently.
 *
 * Thread-safety contract: all implementations must be thread-safe.
 */
internal interface EventQueue {
    /**
     * Write a single event to the queue atomically (temp + rename).
     * Must complete before any network operation is attempted. (MOB-04)
     * Returns false if the queue is at capacity and the event was evicted or dropped.
     */
    fun enqueue(event: Event): Boolean

    /**
     * Returns up to [maxCount] events for upload, oldest first (FIFO). Does NOT remove them.
     * Caller deletes via [delete] only after a 2xx response. (MOB-09)
     */
    fun peek(maxCount: Int = MAX_BATCH): List<QueuedEvent>

    /**
     * Permanently remove events by their file paths. Only called after confirmed 2xx. (MOB-09)
     */
    fun delete(ids: List<String>)

    /**
     * Current event count and disk usage. Used for health reporting and capacity checks.
     */
    fun stats(): QueueStats

    companion object {
        const val MAX_EVENTS = 5_000
        const val MAX_BYTES  = 20L * 1024 * 1024  // 20 MB
        const val MAX_BATCH  = 200
    }
}

internal data class QueuedEvent(
    val id: String,       // opaque — maps to file path for delete
    val event: Event,
    val sizeBytes: Long
)

internal data class QueueStats(
    val eventCount: Int,
    val totalBytes: Long
)
