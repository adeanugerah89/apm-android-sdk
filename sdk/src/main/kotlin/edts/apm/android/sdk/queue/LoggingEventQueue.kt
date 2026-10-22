package edts.apm.android.sdk.queue

import edts.apm.android.sdk.core.Event

/**
 * Logs each captured event to logcat when debug mode is on (MOB-26).
 *
 * **Composition is a security requirement, not a preference: this must sit INSIDE
 * [edts.apm.android.sdk.scrub.ScrubbingEventQueue]**, i.e. `ScrubbingEventQueue(LoggingEventQueue(disk))`,
 * so it only ever sees events that have already been redacted. Logcat is not private — on older
 * Android versions other apps could read it, and any developer with `adb` can. Logging before
 * scrubbing would print exactly the personal data SEC-05 exists to remove, into the one place
 * nobody thinks to audit.
 *
 * Attribute payloads are truncated: a native crash event serialises to tens of kilobytes, and
 * logcat drops long lines silently, which would make the log misleading rather than merely noisy.
 */
internal class LoggingEventQueue(
    private val delegate: EventQueue,
    private val isEnabled: () -> Boolean,
    private val log: (String, String) -> Unit = { tag, message -> android.util.Log.d(tag, message) }
) : EventQueue {

    override fun enqueue(event: Event): Boolean {
        val accepted = delegate.enqueue(event)
        if (enabledSafely()) {
            // Logged after the delegate so the line reflects what actually happened: an event the
            // queue rejected is more interesting than one it took, and a log that claims otherwise
            // sends whoever is debugging in the wrong direction.
            log(TAG, describe(event, accepted))
        }
        return accepted
    }

    override fun peek(maxCount: Int): List<QueuedEvent> = delegate.peek(maxCount)

    override fun delete(ids: List<String>) = delegate.delete(ids)

    override fun stats(): QueueStats = delegate.stats()

    private fun enabledSafely(): Boolean = try {
        isEnabled()
    } catch (e: Exception) {
        false
    }

    private fun describe(event: Event, accepted: Boolean): String {
        val attrs = event.attrs.toString()
        val body = if (attrs.length > MAX_ATTR_CHARS) {
            attrs.take(MAX_ATTR_CHARS) + "…(${attrs.length} chars)"
        } else {
            attrs
        }
        val verdict = if (accepted) "queued" else "REJECTED"
        return "$verdict ${event.type} seq=${event.seq} id=${event.eventId} " +
            "screen=${event.ctx.screen} state=${event.ctx.appState} attrs=$body"
    }

    companion object {
        const val TAG = "APM"
        const val MAX_ATTR_CHARS = 800
    }
}
