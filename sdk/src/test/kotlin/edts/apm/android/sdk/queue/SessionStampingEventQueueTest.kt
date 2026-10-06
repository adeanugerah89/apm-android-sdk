package edts.apm.android.sdk.queue

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** A28 — the session is fixed when the event is captured, not when it is written or uploaded. */
class SessionStampingEventQueueTest {

    private class Recording : EventQueue {
        val events = mutableListOf<Event>()
        override fun enqueue(event: Event): Boolean { events += event; return true }
        override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
        override fun delete(ids: List<String>) {}
        override fun stats() = QueueStats(events.size, 0)
    }

    private fun event(sessionId: String? = null) = Event(
        eventId = "e", type = EventType.ERROR, tsClient = "2026-09-07T00:00:00.000Z",
        seq = 1, attrs = JsonObject(emptyMap()), ctx = EventContext(), sessionId = sessionId
    )

    @Test fun `an event without a session gets the current one`() {
        val sink = Recording()
        SessionStampingEventQueue(sink) { "session-now" }.enqueue(event())

        assertEquals("session-now", sink.events.single().sessionId)
    }

    @Test fun `an event that already carries a session is left alone`() {
        // A crash recovered from the previous process: stamping it with the current session is
        // precisely the bug this class exists to prevent.
        val sink = Recording()
        SessionStampingEventQueue(sink) { "session-now" }.enqueue(event(sessionId = "session-previous"))

        assertEquals("session-previous", sink.events.single().sessionId)
    }

    @Test fun `the session is read at capture time, so a later rollover does not change it`() {
        val sink = Recording()
        var current = "session-A"
        val queue = SessionStampingEventQueue(sink) { current }

        queue.enqueue(event())
        current = "session-B"
        queue.enqueue(event())

        assertEquals(listOf("session-A", "session-B"), sink.events.map { it.sessionId })
    }

    // ─── A39 ────────────────────────────────────────────────────────────────────

    @Test fun `the app version, build and OS version are recorded at capture`() {
        val sink = Recording()
        SessionStampingEventQueue(sink, { edts.apm.android.sdk.core.AppStamp("2.0", "200", "14") }) { "s" }.enqueue(event())

        val e = sink.events.single()
        assertEquals(Triple("2.0", "200", "14"), Triple(e.appVersion, e.appBuild, e.osVersion))
    }

    @Test fun `a later app update does not rewrite what was already captured`() {
        val sink = Recording()
        var app = edts.apm.android.sdk.core.AppStamp("1.0", "100", "13")
        val queue = SessionStampingEventQueue(sink, { app }) { "s" }

        queue.enqueue(event())
        app = edts.apm.android.sdk.core.AppStamp("2.0", "200", "14")
        queue.enqueue(event())

        assertEquals(listOf("1.0", "2.0"), sink.events.map { it.appVersion })
    }

    @Test fun `an event recovered from a previous process keeps its own version - the bug A39 fixes`() {
        val sink = Recording()
        val recovered = event(sessionId = "s-prev").copy(appVersion = "1.0", appBuild = "100", osVersion = "13")

        SessionStampingEventQueue(sink, { edts.apm.android.sdk.core.AppStamp("2.0", "200", "14") }) { "s-now" }.enqueue(recovered)

        assertEquals(Triple("1.0", "100", "13"), sink.events.single().let { Triple(it.appVersion, it.appBuild, it.osVersion) })
    }

    @Test fun `each fact is filled on its own, so a partly known event keeps what it knows`() {
        val sink = Recording()
        val partial = event().copy(appVersion = "1.0")   // build and OS unknown

        SessionStampingEventQueue(sink, { edts.apm.android.sdk.core.AppStamp("2.0", "200", "14") }) { "s" }.enqueue(partial)

        val e = sink.events.single()
        assertEquals("1.0", e.appVersion)
        assertEquals("200", e.appBuild)
    }

    @Test fun `with no app stamp configured nothing is recorded, as before A39`() {
        val sink = Recording()
        SessionStampingEventQueue(sink) { "s" }.enqueue(event())

        assertEquals(null, sink.events.single().appVersion)
    }

    @Test fun `the identity epoch is recorded at capture, and an earlier one is not overwritten`() {
        val sink = Recording()
        var epoch = 3
        val queue = SessionStampingEventQueue(sink, { edts.apm.android.sdk.core.AppStamp(null, null, null) }, { epoch }) { "s" }

        queue.enqueue(event())
        epoch = 4
        queue.enqueue(event())
        queue.enqueue(event().copy(identityEpoch = 1))   // e.g. recovered from the previous process

        assertEquals(listOf(3, 4, 1), sink.events.map { it.identityEpoch })
    }
}
