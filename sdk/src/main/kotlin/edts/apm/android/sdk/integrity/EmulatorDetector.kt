package edts.apm.android.sdk.integrity

import android.os.Build
import java.io.File

/**
 * `integrity.is_emulator` — MOB-29. Heuristic, not a security gate (§ "Batas keandalan").
 * No permission required: build props + a well-known QEMU pipe file.
 *
 * D3 (2026-09-07): emulators legitimately flag `is_rooted=true` too (AVDs ship `su` +
 * test-keys) — that's expected noise, not a bug. This flag exists precisely so backends/
 * dashboards can filter emulator sessions out of root-rate metrics instead of the SDK trying
 * to suppress the (accurate) root signal on emulators.
 */
object EmulatorDetector {

    fun isEmulator(): Boolean = try {
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
            Build.PRODUCT == "google_sdk" ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu") ||
            File("/dev/qemu_pipe").exists()
    } catch (e: Exception) {
        false  // CONSTITUTION rule #1: never throw — under-detecting is safe, crashing isn't
    }
}
