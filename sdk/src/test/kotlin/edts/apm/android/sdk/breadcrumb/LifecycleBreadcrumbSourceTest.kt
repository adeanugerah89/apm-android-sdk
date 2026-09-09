package edts.apm.android.sdk.breadcrumb

import org.junit.Test
import org.junit.Assert.*

/** MOB-12. */
class LifecycleBreadcrumbSourceTest {

    @Test fun `onForeground records a lifecycle breadcrumb`() {
        val ringBuffer = BreadcrumbRingBuffer()
        LifecycleBreadcrumbSource(ringBuffer).onForeground()

        val snapshot = ringBuffer.snapshot()
        assertTrue(snapshot.contains("\"category\":\"lifecycle\""))
        assertTrue(snapshot.contains("foreground"))
    }

    @Test fun `onBackground records a lifecycle breadcrumb`() {
        val ringBuffer = BreadcrumbRingBuffer()
        LifecycleBreadcrumbSource(ringBuffer).onBackground()

        val snapshot = ringBuffer.snapshot()
        assertTrue(snapshot.contains("\"category\":\"lifecycle\""))
        assertTrue(snapshot.contains("background"))
    }
}
