package edts.apm.android.sdk.breadcrumb

/**
 * Automatic app-lifecycle breadcrumbs (MOB-12) — no host action required. A16 wires
 * [onForeground]/[onBackground] to the same `ProcessLifecycleOwner` callbacks as
 * [edts.apm.android.sdk.core.SessionManager]'s onStart()/onStop().
 */
class LifecycleBreadcrumbSource(private val ringBuffer: BreadcrumbRingBuffer) {
    fun onForeground() = ringBuffer.record("foreground", BreadcrumbCategory.LIFECYCLE)
    fun onBackground() = ringBuffer.record("background", BreadcrumbCategory.LIFECYCLE)
}
