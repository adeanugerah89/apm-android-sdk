package edts.apm.android.sdk.integrity

import android.content.Context
import edts.apm.android.sdk.core.IntegrityInfo

/**
 * Snapshots `integrity` once per session (MOB-29/30/31) — see [IntegrityInfo] kdoc for why it's
 * a snapshot rather than recomputed per event.
 */
class DeviceIntegrityCollector(private val context: Context) {
    fun collect(): IntegrityInfo = IntegrityInfo(
        isEmulator = EmulatorDetector.isEmulator(),
        isRooted = RootDetector.isRooted(context),
        isDevMode = DevModeDetector.isDevModeEnabled(context),
        debuggerAttached = DevModeDetector.isDebuggerAttached()
    )
}
