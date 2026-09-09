package edts.apm.android.sdk.internal

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Decides whether debug logging may run (MOB-26: "non-aktif di build release").
 *
 * Two conditions, both required. The config flag alone is not enough: a team that leaves
 * `debugLogging = true` in a config object and ships it would stream every captured event into
 * logcat in production. The requirement says debug mode is off in release builds, so the release
 * build itself has to be what enforces it — not a line in a config file someone has to remember.
 *
 * "Release build" is read from the **host app's** `FLAG_DEBUGGABLE`, not from the SDK's own
 * `BuildConfig.DEBUG`. The SDK ships as a release AAR inside a debuggable app all the time; its own
 * build type says nothing about the app a developer is actually running.
 */
internal object DebugMode {

    fun isEnabled(context: Context, configFlag: Boolean): Boolean =
        configFlag && isHostAppDebuggable(context)

    fun isHostAppDebuggable(context: Context): Boolean = try {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    } catch (e: Exception) {
        false
    }
}
