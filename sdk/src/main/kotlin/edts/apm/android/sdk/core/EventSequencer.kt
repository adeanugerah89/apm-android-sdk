package edts.apm.android.sdk.core

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonically incrementing sequence counter per session. (§3 — `seq` field)
 * Backend uses seq to order events when ts_client is unreliable.
 * Resets when a new session starts.
 */
internal class EventSequencer {
    private val counter = AtomicLong(0)

    fun next(): Long = counter.incrementAndGet()

    fun reset() { counter.set(0) }
}
