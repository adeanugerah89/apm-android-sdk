package edts.apm.android.sdk.integrity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
class DeviceIntegrityCollectorTest {

    @Test fun `collect composes all four signals without crashing`() {
        ShadowBuild.setFingerprint("generic/sdk/generic:11/RSR1.201013.001/6903271:userdebug/test-keys")
        val collector = DeviceIntegrityCollector(ApplicationProvider.getApplicationContext<Context>())

        val info = collector.collect()

        assertTrue("fingerprint was set to a generic one", info.isEmulator)
        // is_rooted/is_dev_mode/debugger_attached just need to resolve without throwing —
        // their own detectors are covered in detail by their dedicated test classes.
        assertNotNull(info.isRooted)
        assertNotNull(info.isDevMode)
        assertNotNull(info.debuggerAttached)
    }
}
