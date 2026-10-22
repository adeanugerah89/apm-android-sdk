package edts.apm.android.sdk.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A48 — what a callback attached late is told it missed, and the guarantee that nothing is told twice or not at all. */
@RunWith(RobolectricTestRunner::class)
class ActivityStateRecorderTest {

    private class Recording : Application.ActivityLifecycleCallbacks {
        val calls = CopyOnWriteArrayList<String>()
        private fun name(a: Activity) = System.identityHashCode(a).toString()
        override fun onActivityCreated(a: Activity, b: Bundle?) { calls += "created:${name(a)}" }
        override fun onActivityStarted(a: Activity) { calls += "started:${name(a)}" }
        override fun onActivityResumed(a: Activity) { calls += "resumed:${name(a)}" }
        override fun onActivityPaused(a: Activity) { calls += "paused:${name(a)}" }
        override fun onActivityStopped(a: Activity) { calls += "stopped:${name(a)}" }
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) { calls += "saved:${name(a)}" }
        override fun onActivityDestroyed(a: Activity) { calls += "destroyed:${name(a)}" }
    }

    private fun activity() = Robolectric.buildActivity(Activity::class.java).get()
    private fun id(a: Activity) = System.identityHashCode(a).toString()

    @Test fun `a callback attached late is replayed every started activity, then the one that resumed`() {
        val recorder = ActivityStateRecorder()
        val a = activity(); val b = activity()
        recorder.onActivityStarted(a); recorder.onActivityResumed(a)
        recorder.onActivityStarted(b); recorder.onActivityResumed(b)

        val late = Recording()
        recorder.attach(late)

        assertEquals(listOf("started:${id(a)}", "started:${id(b)}", "resumed:${id(b)}"), late.calls.toList())
    }

    @Test fun `an activity that already stopped is not replayed as started`() {
        val recorder = ActivityStateRecorder()
        val a = activity(); val b = activity()
        recorder.onActivityStarted(a)
        recorder.onActivityStarted(b)
        recorder.onActivityStopped(a)

        val late = Recording()
        recorder.attach(late)

        assertEquals(listOf("started:${id(b)}"), late.calls.toList())
    }

    @Test fun `nothing is replayed when nothing has happened`() {
        val late = Recording()
        ActivityStateRecorder().attach(late)
        assertTrue(late.calls.isEmpty())
    }

    @Test fun `after attaching, every callback is forwarded live, once`() {
        val recorder = ActivityStateRecorder()
        val late = Recording()
        recorder.attach(late)
        val a = activity()

        recorder.onActivityCreated(a, null)
        recorder.onActivityStarted(a)
        recorder.onActivityResumed(a)
        recorder.onActivityPaused(a)
        recorder.onActivitySaveInstanceState(a, Bundle())
        recorder.onActivityStopped(a)
        recorder.onActivityDestroyed(a)

        assertEquals(
            listOf("created", "started", "resumed", "paused", "saved", "stopped", "destroyed").map { "$it:${id(a)}" },
            late.calls.toList()
        )
    }

    @Test fun `a delegate that throws neither starves the others nor reaches the caller`() {
        val recorder = ActivityStateRecorder()
        val bad = object : Application.ActivityLifecycleCallbacks by Recording() {
            override fun onActivityStarted(activity: Activity) = throw IllegalStateException("host-visible failure")
            override fun onActivityResumed(activity: Activity) = throw IllegalStateException("host-visible failure")
        }
        val good = Recording()
        recorder.attach(bad)
        recorder.attach(good)
        val a = activity()

        recorder.onActivityStarted(a)      // must not throw
        recorder.onActivityResumed(a)

        assertEquals(listOf("started:${id(a)}", "resumed:${id(a)}"), good.calls.toList())
        // And a delegate attached later whose REPLAY throws does not break attach().
        recorder.attach(object : Application.ActivityLifecycleCallbacks by Recording() {
            override fun onActivityStarted(activity: Activity) = throw IllegalStateException("replay failure")
        })
    }

    /**
     * The guarantee that matters: an activity that starts WHILE the replay is running reaches the delegate exactly once —
     * after the replay, live — and is neither lost nor delivered twice.
     *
     * Made deterministic instead of raced: the replay callback itself starts a second activity on another thread and waits.
     * With the replay and the delegate's registration in one critical section, that thread is parked on the lock until
     * the attach is over, then delivers live. If the two were separate sections it would run straight through, find no
     * delegate yet, and the activity would be lost. (Two earlier attempts at a randomised stress test could not tell
     * the difference: the window is nanoseconds wide.)
     */
    @Test fun `an activity that starts while the replay is running is delivered after it, not lost`() {
        val recorder = ActivityStateRecorder()
        val a = activity(); val b = activity()
        recorder.onActivityStarted(a)
        val late = Recording()
        var concurrent: Thread? = null
        val probing = object : Application.ActivityLifecycleCallbacks by late {
            override fun onActivityStarted(activity: Activity) {
                late.onActivityStarted(activity)
                if (activity === a) {                              // inside the replay, on the attaching thread
                    concurrent = thread { recorder.onActivityStarted(b) }
                    concurrent!!.join(300)                         // correct code: parked on the recorder's lock all this time
                }
            }
        }

        recorder.attach(probing)
        concurrent!!.join()

        assertEquals(listOf("started:${id(a)}", "started:${id(b)}"), late.calls.toList())
    }
}
