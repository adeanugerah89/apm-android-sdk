package edts.apm.android.sdk.integrity

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBuild

/** MOB-29. Each test sets the exact Build fields it needs — no shared default assumed. */
@RunWith(RobolectricTestRunner::class)
class EmulatorDetectorTest {

    @Test fun `a real-looking device is not flagged as an emulator`() {
        ShadowBuild.setFingerprint("samsung/beyond1lte/beyond1:11/RP1A.200720.012/G973FXXSF9DVE1:user/release-keys")
        ShadowBuild.setHardware("qcom")
        ShadowBuild.setModel("SM-G973F")
        ShadowBuild.setManufacturer("samsung")
        ShadowBuild.setBrand("samsung")
        ShadowBuild.setDevice("beyond1")
        ShadowBuild.setProduct("beyond1ltexx")
        assertFalse(EmulatorDetector.isEmulator())
    }

    @Test fun `generic fingerprint is detected as an emulator`() {
        ShadowBuild.setFingerprint("generic/sdk/generic:11/RSR1.201013.001/6903271:userdebug/test-keys")
        assertTrue(EmulatorDetector.isEmulator())
    }

    @Test fun `goldfish hardware is detected as an emulator`() {
        ShadowBuild.setFingerprint("some/real/looking/fingerprint")
        ShadowBuild.setHardware("goldfish")
        assertTrue(EmulatorDetector.isEmulator())
    }

    @Test fun `Android SDK built for x86 model is detected as an emulator`() {
        ShadowBuild.setFingerprint("some/real/looking/fingerprint")
        ShadowBuild.setHardware("qcom")
        ShadowBuild.setModel("Android SDK built for x86")
        assertTrue(EmulatorDetector.isEmulator())
    }
}
