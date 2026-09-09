package edts.apm.android.sdk.stability

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver

/**
 * Detects the first frame drawn and hands it to [ColdStartTracker] (MOB-19).
 *
 * Registered as an `ActivityLifecycleCallbacks` at startup, same automatic-integration approach as
 * [edts.apm.android.sdk.breadcrumb.ScreenTrackingLifecycleCallbacks] — the host app does nothing.
 *
 * First draw is taken from `ViewTreeObserver.OnDrawListener` on the first resumed activity's
 * decor view. `onResume` alone is too early: it means the activity is ready to draw, not that a
 * frame reached the screen, and the gap between the two is where slow inflation shows up.
 *
 * The listener removes itself after firing — via a `post`, because `OnDrawListener` may not be
 * removed from inside `onDraw` itself (Android throws `IllegalStateException` for that) — and the
 * callbacks unregister after the first frame so this costs nothing for the rest of the process.
 */
class ColdStartFirstFrameObserver(
    private val tracker: ColdStartTracker
) : Application.ActivityLifecycleCallbacks {

    private var done = false

    override fun onActivityResumed(activity: Activity) {
        if (done) return
        done = true
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
                    tracker.onFirstFrame()
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
