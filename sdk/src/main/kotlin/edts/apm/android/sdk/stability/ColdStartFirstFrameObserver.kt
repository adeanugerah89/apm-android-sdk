package edts.apm.android.sdk.stability

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver

/**
 * Detects the first frame drawn and hands it, with the time it was drawn, to [ColdStartTracker] (MOB-19).
 *
 * **Registered before the SDK is assembled, and bound to its tracker afterwards (A46).** `APM.start()` assembles in
 * the background when called from the main thread, so on a slow device the launch activity has already resumed and
 * drawn by the time the graph exists. An observer created inside the assembly missed that frame entirely — and then
 * fired on the *next* resume, reporting how long the user had been in the app as a "cold start". So this observer is
 * registered at the top of `APM.start()` (see `ApmBootstrap.observeLaunch`), records when the first frame was drawn,
 * and [attach] hands that to the tracker whenever it arrives: at once if the frame is already in, otherwise when it is.
 *
 * Registered as an `ActivityLifecycleCallbacks` — the host app does nothing.
 *
 * First draw is taken from `ViewTreeObserver.OnDrawListener` on the first resumed activity's
 * decor view. `onResume` alone is too early: it means the activity is ready to draw, not that a
 * frame reached the screen, and the gap between the two is where slow inflation shows up.
 *
 * The listener removes itself after firing — via a `post`, because `OnDrawListener` may not be
 * removed from inside `onDraw` itself (Android throws `IllegalStateException` for that) — and the
 * callbacks unregister after the first frame so this costs nothing for the rest of the process.
 */
internal class ColdStartFirstFrameObserver(
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) : Application.ActivityLifecycleCallbacks {

    private val lock = Any()

    /** Only the first resumed activity is ever instrumented. Guarded by [lock]. */
    private var done = false

    /** When the first frame was drawn, once it has been. Guarded by [lock]. */
    private var frameAtMs: Long? = null
    private var tracker: ColdStartTracker? = null

    /**
     * Binds the tracker that will emit the event. If the first frame has already been drawn it is reported
     * now, with the time it was drawn; otherwise when it is. A second [attach] (a start that failed and was
     * retried) replaces the first and, if the frame is already in, is told about it too.
     */
    fun attach(tracker: ColdStartTracker) {
        val frame: Long?
        synchronized(lock) {
            this.tracker = tracker
            frame = frameAtMs
        }
        frame?.let { tracker.onFirstFrame(it) }
    }

    private fun frameDrawn() {
        val boundTracker: ColdStartTracker?
        val at = elapsedRealtime()
        synchronized(lock) {
            frameAtMs = at
            boundTracker = tracker
        }
        boundTracker?.onFirstFrame(at)
    }

    override fun onActivityResumed(activity: Activity) {
        synchronized(lock) {
            if (done) return
            done = true
        }
        val decorView: View = activity.window?.decorView ?: return
        val observer: ViewTreeObserver = decorView.viewTreeObserver

        observer.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            private var fired = false
            override fun onDraw() {
                if (fired) return
                fired = true
                val self = this
                decorView.post {
                    if (decorView.viewTreeObserver.isAlive) {
                        decorView.viewTreeObserver.removeOnDrawListener(self)
                    }
                    frameDrawn()
                    activity.application?.unregisterActivityLifecycleCallbacks(this@ColdStartFirstFrameObserver)
                }
            }
        })
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
