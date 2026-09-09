package edts.apm.android.sdk.integrity

import android.content.Context
import android.os.Debug
import android.provider.Settings

/**
 * `integrity.is_dev_mode` and `integrity.debugger_attached` — MOB-31.
 * Two independent booleans, deliberately not OR'd together — see docs/02-Mobile-SDK.md §3.2
 * for why (a QA build without a debugger, vs. a developer actively debugging, mean different
 * things when filtering non-real sessions).
 */
object DevModeDetector {

    /** Non-production environment: Developer Options or ADB enabled. */
    fun isDevModeEnabled(context: Context): Boolean = try {
        val devSettingsOn = Settings.Global.getInt(
            context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) == 1
        val adbOn = Settings.Global.getInt(
            context.contentResolver, Settings.Global.ADB_ENABLED, 0
        ) == 1
        devSettingsOn || adbOn
    } catch (e: Exception) {
        false  // CONSTITUTION rule #1
    }

    /**
     * A debugger is attached right now. [debuggerConnected] defaults to the real platform
     * check — overridable because Robolectric doesn't shadow `Debug.isDebuggerConnected()`,
     * so tests need a seam to simulate "attached" without a real debugger.
     */
    fun isDebuggerAttached(debuggerConnected: () -> Boolean = Debug::isDebuggerConnected): Boolean = try {
        debuggerConnected()
    } catch (e: Exception) {
        false
    }
}
