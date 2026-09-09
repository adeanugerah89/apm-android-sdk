package edts.apm.android.sdk.stability

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * MOB-18 — ANR detection emitted as `crash_type: anr` (`01` §4.3).
 *
 * Time is fully simulated: [FakeClock] advances only when the watchdog sleeps, so a "12-second
 * block" costs nothing and, more importantly, is exact. The A10b regression below depends on that
 * exactness — it is a statement about poll windows, and a wall-clock test could not make it.
 */
class AnrWatchdogTest {

    private class RecordingQueue : EventQueue {
        val events = mutableListOf<Event>()
        override fun enqueue(event: Event): Boolean { events.add(event); return true }
        override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
        override fun delete(ids: List<String>) {}
        override fun stats() = QueueStats(events.size, 0)
    }

    private class FakeClock(var nowMs: Long = 100_000L) {
        fun advance(ms: Long) { nowMs += ms }
    }

    /**
     * A main thread we control. While [responsive], a posted sentinel runs immediately — which is
     * what a healthy main thread does relative to a 1 s poll. While blocked, sentinels queue up and
     * run all at once on the next [drain], exactly as the real Looper behaves.
     */
    private class FakeProbe(
        var responsive: Boolean = true,
        var acceptsPosts: Boolean = true
    ) : MainThreadProbe {
        override val mainThread: Thread = Thread.currentThread()
        private val pending = mutableListOf<() -> Unit>()

        override fun post(action: () -> Unit): Boolean {
            if (!acceptsPosts) return false
            if (responsive) action() else pending.add(action)
            return true
        }

        /** The main thread unblocks: every queued sentinel runs now. */
        fun drain() {
            pending.forEach { it() }
            pending.clear()
            responsive = true
        }

        override fun allStackTraces(): Map<Thread, Array<StackTraceElement>> =
            mapOf(mainThread to arrayOf(
                StackTraceElement("com.example.Screen", "render", "Screen.kt", 42),
                StackTraceElement("java.lang.Object", "wait", "Object.java", -2)
            ))
    }

    private fun watchdog(
        queue: EventQueue,
        probe: FakeProbe,
        clock: FakeClock = FakeClock(),
        thresholdMs: Long = 5_000L,
        pollIntervalMs: Long = 1_000L,
        anrEnabled: () -> Boolean = { true }
    ) = AnrWatchdog(
        queue = queue,
        seqProvider = { 7L },
        contextProvider = { EventContext(appState = "foreground") },
        appPackagePrefix = "com.example",
        launchElapsedRealtimeMs = 1_000L,
        probe = probe,
        thresholdMs = thresholdMs,
        isAnrEnabled = anrEnabled,
        sleeper = { clock.advance(it) },   // sleeping is the only thing that moves time
        elapsedRealtime = { clock.nowMs },
        pollIntervalMs = pollIntervalMs
    )

    /** Runs the watchdog for [ms] of simulated time, unblocking the main thread after [blockMs]. */
    private fun runBlock(
        queue: RecordingQueue,
        blockMs: Long,
        totalMs: Long,
        thresholdMs: Long = 5_000L
    ): Int {
        val clock = FakeClock()
        val probe = FakeProbe(responsive = true)
        val w = watchdog(queue, probe, clock, thresholdMs = thresholdMs)
        val start = clock.nowMs
        probe.responsive = false                       // the block begins
        while (clock.nowMs - start < totalMs) {
            if (!probe.responsive && clock.nowMs - start >= blockMs) probe.drain()
            w.tick()
        }
        return queue.events.size
    }

    // ─── A10b regression ────────────────────────────────────────────────────

    @Test fun `a block just over the threshold is detected — it used to be missed entirely`() {
        val queue = RecordingQueue()

        // 7 s block, 5 s threshold. Before A10b the watchdog slept a full 5 s per cycle, so this
        // could begin and end inside one window and never be seen — reproduced on an API 36
        // emulator, not hypothetical.
        val detected = runBlock(queue, blockMs = 7_000, totalMs = 15_000)

        assertEquals("a 7s block must trip a 5s threshold", 1, detected)
    }

    @Test fun `detection lands within one poll interval of the threshold`() {
        val queue = RecordingQueue()
        val clock = FakeClock()
        val probe = FakeProbe(responsive = false)
        val w = watchdog(queue, probe, clock, thresholdMs = 5_000L, pollIntervalMs = 1_000L)
        val start = clock.nowMs

        while (queue.events.isEmpty() && clock.nowMs - start < 30_000) w.tick()

        val detectedAfter = clock.nowMs - start
        assertTrue(
            "expected detection between 5s and 6s, got ${detectedAfter}ms",
            detectedAfter in 5_000..6_000
        )
    }

    @Test fun `a block shorter than the threshold is correctly ignored`() {
        val queue = RecordingQueue()

        val detected = runBlock(queue, blockMs = 3_000, totalMs = 15_000)

        assertEquals(0, detected)
    }

    // ─── behaviour ──────────────────────────────────────────────────────────

    @Test fun `a responsive main thread produces no event`() {
        val queue = RecordingQueue()
        val w = watchdog(queue, FakeProbe(responsive = true))

        repeat(20) { assertFalse(w.tick()) }
        assertTrue(queue.events.isEmpty())
    }

    @Test fun `an unresponsive main thread emits a CRASH event with crash_type anr`() {
        val queue = RecordingQueue()
        val clock = FakeClock()
        val w = watchdog(queue, FakeProbe(responsive = false), clock)

        repeat(10) { w.tick() }

        val event = queue.events.single()
        assertEquals(EventType.CRASH, event.type)
        assertEquals("anr", event.attrs.getValue("crash_type").jsonPrimitive.content)
        assertEquals("ApplicationNotResponding", event.attrs.getValue("name").jsonPrimitive.content)
    }

    @Test fun `an ANR is not fatal — the process is blocked, not dead`() {
        val queue = RecordingQueue()
        val w = watchdog(queue, FakeProbe(responsive = false))
        repeat(10) { w.tick() }

        assertEquals("false", queue.events.single().attrs.getValue("is_fatal").jsonPrimitive.content)
    }

    @Test fun `the report carries the main thread stack marked crashed, with is_app per frame`() {
        val queue = RecordingQueue()
        val w = watchdog(queue, FakeProbe(responsive = false))
        repeat(10) { w.tick() }

        val threads = queue.events.single().attrs.getValue("threads").jsonArray
        val main = threads.single().jsonObject
        assertEquals("true", main.getValue("crashed").jsonPrimitive.content)

        val frames = main.getValue("frames").jsonArray
        assertEquals("true", frames[0].jsonObject.getValue("is_app").jsonPrimitive.content)
        assertEquals("false", frames[1].jsonObject.getValue("is_app").jsonPrimitive.content)
        assertTrue(queue.events.single().attrs.getValue("binary_images").jsonArray.isEmpty())
    }

    @Test fun `one continuous block reports once, not once per poll`() {
        val queue = RecordingQueue()
        val w = watchdog(queue, FakeProbe(responsive = false))

        repeat(60) { w.tick() }   // a full minute of simulated block

        assertEquals(1, queue.events.size)
    }

    @Test fun `a second block after recovery is reported again`() {
        val queue = RecordingQueue()
        assertEquals(1, runBlock(queue, blockMs = 7_000, totalMs = 15_000))

        // Same queue, a fresh block later in the process's life.
        assertEquals(2, runBlock(queue, blockMs = 7_000, totalMs = 15_000))
    }

    @Test fun `a refused post yields no verdict either way`() {
        val queue = RecordingQueue()
        val w = watchdog(queue, FakeProbe(responsive = false, acceptsPosts = false))

        repeat(20) { assertFalse(w.tick()) }
        assertTrue(queue.events.isEmpty())
    }

    @Test fun `remotely disabling the anr feature suppresses the event`() {
        val queue = RecordingQueue()
        val w = watchdog(queue, FakeProbe(responsive = false), anrEnabled = { false })

        repeat(20) { w.tick() }

        assertTrue(queue.events.isEmpty())
    }

    @Test fun `time_since_launch_ms is measured from the injected launch time`() {
        val queue = RecordingQueue()
        val clock = FakeClock(nowMs = 100_000L)
        val w = watchdog(queue, FakeProbe(responsive = false), clock)

        repeat(10) { w.tick() }

        // Launch was at 1_000; detection happens at 100_000 + 5 polls of 1_000 = 105_000.
        assertEquals(
            "104000",
            queue.events.single().attrs.getValue("time_since_launch_ms").jsonPrimitive.content
        )
    }
}
