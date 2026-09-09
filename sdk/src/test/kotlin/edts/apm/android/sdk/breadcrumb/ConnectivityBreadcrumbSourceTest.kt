package edts.apm.android.sdk.breadcrumb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** MOB-12. */
@RunWith(RobolectricTestRunner::class)
class ConnectivityBreadcrumbSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun lastRegisteredCallback(): ConnectivityManager.NetworkCallback {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return shadowOf(cm).networkCallbacks.last()
    }

    @Test fun `onAvailable records connectivity_restored`() {
        val ringBuffer = BreadcrumbRingBuffer()
        ConnectivityBreadcrumbSource(context, ringBuffer).start()

        lastRegisteredCallback().onAvailable(mockk<Network>())

        val snapshot = ringBuffer.snapshot()
        assertTrue(snapshot.contains("connectivity_restored"))
        assertTrue(snapshot.contains("\"category\":\"network\""))
        assertTrue(snapshot.contains("\"level\":\"info\""))
    }

    @Test fun `onLost records connectivity_lost at warning level`() {
        val ringBuffer = BreadcrumbRingBuffer()
        ConnectivityBreadcrumbSource(context, ringBuffer).start()

        lastRegisteredCallback().onLost(mockk<Network>())

        val snapshot = ringBuffer.snapshot()
        assertTrue(snapshot.contains("connectivity_lost"))
        assertTrue(snapshot.contains("\"level\":\"warning\""))
    }

    @Test fun `start is idempotent and stop never crashes even without starting`() {
        val source = ConnectivityBreadcrumbSource(context, BreadcrumbRingBuffer())
        source.start()
        source.start()  // must not register twice or throw
        source.stop()
        source.stop()   // must not throw on a second stop either

        val freshSource = ConnectivityBreadcrumbSource(context, BreadcrumbRingBuffer())
        freshSource.stop()  // stop() before start() must not throw
    }
}
