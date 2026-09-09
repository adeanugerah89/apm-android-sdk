package edts.apm.android.sdk.integrity

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** MOB-31 — two independent booleans, never OR'd together. */
@RunWith(RobolectricTestRunner::class)
class DevModeDetectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `dev mode off and debugger absent by default`() {
        assertFalse(DevModeDetector.isDevModeEnabled(context))
        assertFalse(DevModeDetector.isDebuggerAttached())
    }

    @Test fun `DEVELOPMENT_SETTINGS_ENABLED flips is_dev_mode`() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        assertTrue(DevModeDetector.isDevModeEnabled(context))
    }

    @Test fun `ADB_ENABLED alone also flips is_dev_mode`() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
        assertTrue(DevModeDetector.isDevModeEnabled(context))
    }

    @Test fun `debugger_attached and is_dev_mode are independent, not OR'd`() {
        // QA build: dev mode off, but a debugger happens to be attached — must still read
        // correctly as two separate signals, not collapsed into one. Robolectric doesn't shadow
        // Debug.isDebuggerConnected(), so the "attached" state is simulated via the injected seam.
        assertFalse(DevModeDetector.isDevModeEnabled(context))
        assertTrue(DevModeDetector.isDebuggerAttached(debuggerConnected = { true }))
    }
}
