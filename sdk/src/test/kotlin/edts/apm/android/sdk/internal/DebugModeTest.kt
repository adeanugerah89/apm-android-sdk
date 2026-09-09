package edts.apm.android.sdk.internal

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** MOB-26 — debug logging must be off in a release build, whatever the config says. */
@RunWith(RobolectricTestRunner::class)
class DebugModeTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun withDebuggable(debuggable: Boolean): Context = context.also {
        it.applicationInfo.flags =
            if (debuggable) it.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
            else it.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }

    @Test fun `both the flag and a debuggable host app are required`() {
        assertTrue(DebugMode.isEnabled(withDebuggable(true), configFlag = true))
    }

    @Test fun `config flag alone is not enough — a release build wins`() {
        assertFalse(
            "shipping debugLogging = true must not stream events into logcat in production",
            DebugMode.isEnabled(withDebuggable(false), configFlag = true)
        )
    }

    @Test fun `a debuggable build with the flag off still logs nothing`() {
        assertFalse(DebugMode.isEnabled(withDebuggable(true), configFlag = false))
    }

    @Test fun `neither condition means off`() {
        assertFalse(DebugMode.isEnabled(withDebuggable(false), configFlag = false))
    }
}
