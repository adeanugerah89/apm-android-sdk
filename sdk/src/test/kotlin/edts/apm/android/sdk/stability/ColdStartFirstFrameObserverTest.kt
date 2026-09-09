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
        processStartUptimeMs = 0L,
        elapsedRealtime = { 700L }
    )

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
        val observer = ColdStartFirstFrameObserver(tracker(queue))

        observer.onActivityResumed(activity)
        dispatchDraw(activity)

        assertEquals(1, queue.events.size)
    }

    @Test fun `further draws after the first do not emit again`() {
        val queue = RecordingQueue()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val observer = ColdStartFirstFrameObserver(tracker(queue))

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
        val observer = ColdStartFirstFrameObserver(tracker(queue))

        observer.onActivityResumed(first)
        observer.onActivityResumed(second)
        dispatchDraw(second)

        assertTrue(queue.events.isEmpty())   // second activity was never hooked
        dispatchDraw(first)
        assertEquals(1, queue.events.size)
    }

    @Test fun `the remaining lifecycle callbacks are no-ops and never crash`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val observer = ColdStartFirstFrameObserver(tracker(RecordingQueue()))

        observer.onActivityCreated(activity, null)
        observer.onActivityStarted(activity)
        observer.onActivityPaused(activity)
        observer.onActivityStopped(activity)
        observer.onActivityDestroyed(activity)
        // No exception is the assertion.
    }
}
