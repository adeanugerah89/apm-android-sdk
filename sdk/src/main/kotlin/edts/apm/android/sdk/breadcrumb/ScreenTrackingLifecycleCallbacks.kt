package edts.apm.android.sdk.breadcrumb

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Automatic screen-transition breadcrumbs via `ActivityLifecycleCallbacks` (MOB-12) — Android
 * can do this without method swizzling, unlike iOS (host-invoked there; see CONSTITUTION).
 * Output must match iOS's shape: a `navigation`-category breadcrumb.
 *
 * A16 registers an instance via `application.registerActivityLifecycleCallbacks(...)`.
 */
internal class ScreenTrackingLifecycleCallbacks(
    private val ringBuffer: BreadcrumbRingBuffer
) : Application.ActivityLifecycleCallbacks {

    override fun onActivityResumed(activity: Activity) {
        ringBuffer.record(activity::class.java.simpleName, BreadcrumbCategory.NAVIGATION)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
