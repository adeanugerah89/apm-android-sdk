package edts.apm.android.sdk.breadcrumb

import android.app.Activity
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** MOB-12 — screen tracking is automatic on Android via ActivityLifecycleCallbacks, no swizzling. */
@RunWith(RobolectricTestRunner::class)
class ScreenTrackingLifecycleCallbacksTest {

    @Test fun `onActivityResumed records a navigation breadcrumb with the activity's simple name`() {
        val ringBuffer = BreadcrumbRingBuffer()
        val callbacks = ScreenTrackingLifecycleCallbacks(ringBuffer)
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        callbacks.onActivityResumed(activity)

        val snapshot = ringBuffer.snapshot()
        assertTrue(snapshot.contains("\"category\":\"navigation\""))
        assertTrue(snapshot.contains("Activity"))
    }

    @Test fun `other lifecycle callbacks are no-ops and never crash`() {
        val callbacks = ScreenTrackingLifecycleCallbacks(BreadcrumbRingBuffer())
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        callbacks.onActivityCreated(activity, null)
        callbacks.onActivityStarted(activity)
        callbacks.onActivityPaused(activity)
        callbacks.onActivityStopped(activity)
        callbacks.onActivityDestroyed(activity)
        // No exception is the assertion.
    }
}
