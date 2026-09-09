package edts.apm.android.sdk.crash

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.Assert.*

/** MOB-15/16/17 — Java crash path. */
class JvmCrashHandlerTest {

    private class RecordingQueue : EventQueue {
        val events = mutableListOf<Event>()
        override fun enqueue(event: Event): Boolean { events.add(event); return true }
        override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
        override fun delete(ids: List<String>) {}
        override fun stats() = QueueStats(events.size, 0)
    }

    private class RecordingDefaultHandler : Thread.UncaughtExceptionHandler {
        var invokedWith: Pair<Thread, Throwable>? = null
        override fun uncaughtException(t: Thread, e: Throwable) { invokedWith = t to e }
    }

    private fun handler(
        queue: EventQueue,
        defaultHandler: Thread.UncaughtExceptionHandler,
        elapsedNow: Long = 5_000L
    ) = JvmCrashHandler(
        queue = queue,
        seqProvider = { 1L },
        contextProvider = { EventContext(appState = "foreground") },
        appPackagePrefix = "com.example",
        launchElapsedRealtimeMs = 1_000L,
        elapsedRealtime = { elapsedNow },
        defaultHandler = defaultHandler
    )

    @Test fun `uncaughtException enqueues a CRASH event with the exception type and reason`() {
        val queue = RecordingQueue()
        val h = handler(queue, RecordingDefaultHandler())

        h.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        val event = queue.events.single()
        assertEquals(EventType.CRASH, event.type)
        assertEquals("exception", event.attrs.getValue("crash_type").jsonPrimitive.content)
        assertEquals("java.lang.IllegalStateException", event.attrs.getValue("name").jsonPrimitive.content)
        assertEquals("boom", event.attrs.getValue("reason").jsonPrimitive.content)
        assertEquals("true", event.attrs.getValue("is_fatal").jsonPrimitive.content)
    }

    @Test fun `time_since_launch_ms is computed from the injected clock`() {
        val queue = RecordingQueue()
        val h = handler(queue, RecordingDefaultHandler(), elapsedNow = 9_000L)

        h.uncaughtException(Thread.currentThread(), RuntimeException("x"))

        assertEquals("8000", queue.events.single().attrs.getValue("time_since_launch_ms").jsonPrimitive.content)
    }

    @Test fun `app_state from context is included as a top-level attr`() {
        val queue = RecordingQueue()
        val h = handler(queue, RecordingDefaultHandler())

        h.uncaughtException(Thread.currentThread(), RuntimeException("x"))

        assertEquals("foreground", queue.events.single().attrs.getValue("app_state").jsonPrimitive.content)
    }

    @Test fun `threads array includes the crashing thread`() {
        val queue = RecordingQueue()
        val h = handler(queue, RecordingDefaultHandler())

        h.uncaughtException(Thread.currentThread(), RuntimeException("x"))

        val threadsJson = queue.events.single().attrs.getValue("threads").toString()
        assertTrue(threadsJson.contains("\"crashed\":true"))
    }

    @Test fun `binary_images is empty for a Java crash`() {
        val queue = RecordingQueue()
        val h = handler(queue, RecordingDefaultHandler())

        h.uncaughtException(Thread.currentThread(), RuntimeException("x"))

        assertEquals("[]", queue.events.single().attrs.getValue("binary_images").toString())
    }

    @Test fun `always chains to the default handler, even on success`() {
        val defaultHandler = RecordingDefaultHandler()
        val h = handler(RecordingQueue(), defaultHandler)
        val thread = Thread.currentThread()
        val throwable = RuntimeException("x")

        h.uncaughtException(thread, throwable)

        assertEquals(thread to throwable, defaultHandler.invokedWith)
    }

    @Test fun `chains to the default handler even if writing the crash event throws`() {
        val throwingQueue = object : EventQueue {
            override fun enqueue(event: Event): Boolean = throw RuntimeException("disk full")
            override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
            override fun delete(ids: List<String>) {}
            override fun stats() = QueueStats(0, 0)
        }
        val defaultHandler = RecordingDefaultHandler()
        val h = handler(throwingQueue, defaultHandler)
        val thread = Thread.currentThread()
        val throwable = RuntimeException("original crash")

        h.uncaughtException(thread, throwable)

        assertEquals(thread to throwable, defaultHandler.invokedWith)
    }
}
