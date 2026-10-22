package edts.apm.android.sdk.scrub

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent

/**
 * Decorator that scrubs every event before delegating to the real queue (SEC-01/05b).
 * Pipeline order Capture -> Scrub -> Disk -> Sync stays fixed by construction: compose this
 * around [edts.apm.android.sdk.queue.DiskEventQueue] so nothing reaches disk unscrubbed,
 * the same wrapping pattern the interface docs reserve for at-rest encryption (A14).
 */
internal class ScrubbingEventQueue(private val delegate: EventQueue) : EventQueue {

    override fun enqueue(event: Event): Boolean = delegate.enqueue(Scrubber.scrubEvent(event))

    override fun peek(maxCount: Int): List<QueuedEvent> = delegate.peek(maxCount)

    override fun delete(ids: List<String>) = delegate.delete(ids)

    override fun stats(): QueueStats = delegate.stats()
}
