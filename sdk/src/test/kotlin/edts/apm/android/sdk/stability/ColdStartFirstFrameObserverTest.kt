package edts.apm.android.sdk.stability

import android.app.Activity
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * MOB-19 — the lifecycle seam that turns "first frame drawn" into a [ColdStartTracker] call.
 *
 * The draw itself is dispatched here by invoking `ViewTreeObserver.dispatchOnDraw()` reflectively:
 * Robolectric never paints, so nothing else would ever fire an `OnDrawListener`. Confirming that a
 * *real* first frame lands on the same path is device-only work — see the manual checklist.
 */
@RunWith(RobolectricTestRunner::class)
class ColdStartFirstFrameObserverTest {

    private class RecordingQueue : EventQueue {
        val events = mutableListOf<Event>()
        override fun enqueue(event: Event): Boolean { events.add(event); return true }
        override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
        override fun delete(ids: List<String>) {}
        override fun stats() = QueueStats(events.size, 0)
    }

    private fun tracker(queue: EventQueue) = ColdStartTracker(
        queue = queue,
        seqProvider = { 1L },
        contextProvider = { EventContext(appState = "foreground") },
        processStartElapsedMs = 0L,
        elapsedRealtime = { 700L }
    )

    private fun observerFor(tracker: ColdStartTracker, clock: () -> Long = { 700L }) =
        ColdStartFirstFrameObserver(clock).also { it.attach(tracker) }

    private fun dispatchDraw(activity: Activity) {
        val observer = activity.window.decorView.viewTreeObserver
        observer.javaClass.getDeclaredMethod("dispatchOnDraw")
            .apply { isAccessible = true }
            .invoke(observer)
        ShadowLooper.idleMainLooper()   // the listener defers its work with decorView.post {}
    }

    @Test fun `a drawn first frame produces exactly one cold_start event`() {
        val queue = RecordingQueue()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val observer = observerFor(tracker(queue))

        observer.onActivityResumed(activity)
        dispatchDraw(activity)

        assertEquals(1, queue.events.size)
    }

    @Test fun `further draws after the first do not emit again`() {
        val queue = RecordingQueue()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val observer = observerFor(tracker(queue))

        observer.onActivityResumed(activity)
        dispatchDraw(activity)
        dispatchDraw(activity)
        dispatchDraw(activity)

        assertEquals(1, queue.events.size)
    }

    @Test fun `only the first resumed activity is instrumented`() {
        val queue = RecordingQueue()
        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        val observer = observerFor(tracker(queue))

        observer.onActivityResumed(first)
        observer.onActivityResumed(second)
        dispatchDraw(second)

        assertTrue(queue.events.isEmpty())   // second activity was never hooked
        dispatchDraw(first)
        assertEquals(1, queue.events.size)
    }

    @Test fun `the remaining lifecycle callbacks are no-ops and never crash`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val observer = observerFor(tracker(RecordingQueue()))

        observer.onActivityCreated(activity, null)
        observer.onActivityStarted(activity)
        observer.onActivityPaused(activity)
        observer.onActivityStopped(activity)
        observer.onActivityDestroyed(activity)
        // No exception is the assertion.
    }

    // ─── A46 · the observer is registered before the tracker exists ─────────────

    private fun durationOf(queue: RecordingQueue) =
        queue.events.single().attrs.getValue("duration_ms").toString().toLong()

    @Test fun `a frame drawn before the tracker is attached is reported, with the time it was drawn`() {
        val queue = RecordingQueue()
        var now = 600L
        val observer = ColdStartFirstFrameObserver { now }       // registered early, no tracker yet
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        observer.onActivityResumed(activity)
        dispatchDraw(activity)                                    // the frame, at 600
        assertTrue("nothing to report to yet", queue.events.isEmpty())
        now = 9_000L                                              // assembly finishes much later
        observer.attach(tracker(queue))

        assertEquals(1, queue.events.size)
        assertEquals("the frame's time, not the time the tracker arrived", 600L, durationOf(queue))
    }

    @Test fun `a later resume is never taken for the first frame`() {
        val queue = RecordingQueue()
        var now = 600L
        val observer = ColdStartFirstFrameObserver { now }
        val launch = Robolectric.buildActivity(Activity::class.java).setup()

        observer.onActivityResumed(launch.get())
        dispatchDraw(launch.get())
        now = 20_600L                                             // the user comes back 20 s later
        launch.pause().resume()
        observer.onActivityResumed(launch.get())
        dispatchDraw(launch.get())
        observer.attach(tracker(queue))

        assertEquals(600L, durationOf(queue))
    }

    @Test fun `a frame drawn after the tracker is attached is reported once, at the time it was drawn`() {
        val queue = RecordingQueue()
        var now = 0L
        val observer = ColdStartFirstFrameObserver { now }
        observer.attach(tracker(queue))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        observer.onActivityResumed(activity)
        now = 450L
        dispatchDraw(activity)
        dispatchDraw(activity)

        assertEquals(450L, durationOf(queue))
    }

    @Test fun `attaching a second tracker after the frame is told about it too (a start that was retried)`() {
        val first = RecordingQueue()
        val second = RecordingQueue()
        val observer = ColdStartFirstFrameObserver { 500L }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        observer.attach(tracker(first))
        observer.onActivityResumed(activity)
        dispatchDraw(activity)

        observer.attach(tracker(second))

        assertEquals(1, first.events.size)
        assertEquals("the retry's tracker gets the frame that had already been drawn", 500L, durationOf(second))
    }
}
