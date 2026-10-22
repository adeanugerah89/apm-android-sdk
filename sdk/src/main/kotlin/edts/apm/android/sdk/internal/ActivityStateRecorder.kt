package edts.apm.android.sdk.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Remembers which activities have started and which one last resumed, from the very beginning of the process,
 * so callbacks built later can be told what they missed (A48).
 *
 * `APM.start()` assembles in the background when called from the main thread (A18). On a slow device the launch
 * activity has started and resumed before the graph exists, and everything that counts on seeing those
 * callbacks — the started-activity counter behind `app_state`, the current screen, the first-screen breadcrumb —
 * was registered inside the assembly and never did. The result was an event captured in the foreground saying
 * `app_state: background`, and, once a second activity started while the first stopped, a counter that reached
 * zero with the app plainly on screen: a spurious `background` breadcrumb, a session timer started, an upload
 * triggered. Same root cause as A46, same remedy: register early (see `ApmBootstrap.observeLaunch`), bind later.
 *
 * [attach] **replays** the current state to a delegate as the callbacks it would have received —
 * `onActivityStarted` for every activity still started, then `onActivityResumed` for the one that last resumed — and
 * from then on forwards every callback to it. The replay and the hand-over to live delivery happen under one lock, so
 * a callback arriving on the main thread during [attach] reaches the delegate exactly once: in the replay, or live.
 *
 * Activities are held weakly; one that is gone by the time the SDK is up simply cannot be replayed. A delegate that
 * throws is isolated — the host's own lifecycle dispatch must never see it.
 *
 * **Limit:** it only knows about activities that started after it was registered. A host that calls `APM.start()`
 * late — from an activity, say — has no way to recover the ones before.
 */
internal class ActivityStateRecorder : Application.ActivityLifecycleCallbacks {

    private val lock = Any()
    private val started = ArrayList<WeakReference<Activity>>()   // in start order. Guarded by [lock].
    private var lastResumed: WeakReference<Activity>? = null      // Guarded by [lock].
    private var delegates: List<Application.ActivityLifecycleCallbacks> = emptyList()   // copy-on-write. Guarded by [lock].

    /** Brings [callbacks] up to date with what already happened, then forwards everything that happens next. */
    fun attach(callbacks: Application.ActivityLifecycleCallbacks) {
        synchronized(lock) {
            started.mapNotNull { it.get() }.forEach { deliver(callbacks) { onActivityStarted(it) } }
            lastResumed?.get()?.let { activity -> deliver(callbacks) { onActivityResumed(activity) } }
            delegates = delegates + callbacks
        }
    }

    // The state change and the choice of who is told about it are one atomic step, so an event cannot slip between
    // a replay and the delegate joining the live list. Delivery itself happens outside the lock.

    override fun onActivityStarted(activity: Activity) {
        val targets = synchronized(lock) { started.add(WeakReference(activity)); delegates }
        targets.forEach { deliver(it) { onActivityStarted(activity) } }
    }

    override fun onActivityStopped(activity: Activity) {
        val targets = synchronized(lock) {
            val index = started.indexOfFirst { it.get() === activity }
            if (index >= 0) started.removeAt(index)
            started.removeAll { it.get() == null }
            delegates
        }
        targets.forEach { deliver(it) { onActivityStopped(activity) } }
    }

    override fun onActivityResumed(activity: Activity) {
        val targets = synchronized(lock) { lastResumed = WeakReference(activity); delegates }
        targets.forEach { deliver(it) { onActivityResumed(activity) } }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
        forward { onActivityCreated(activity, savedInstanceState) }

    override fun onActivityPaused(activity: Activity) = forward { onActivityPaused(activity) }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) =
        forward { onActivitySaveInstanceState(activity, outState) }

    override fun onActivityDestroyed(activity: Activity) = forward { onActivityDestroyed(activity) }

    private fun forward(call: Application.ActivityLifecycleCallbacks.() -> Unit) {
        val targets = synchronized(lock) { delegates }
        targets.forEach { deliver(it, call) }
    }

    private fun deliver(target: Application.ActivityLifecycleCallbacks, call: Application.ActivityLifecycleCallbacks.() -> Unit) {
        try {
            target.call()
        } catch (e: Throwable) {
            // One delegate failing must neither starve the others nor reach the host's lifecycle dispatch.
        }
    }
}
