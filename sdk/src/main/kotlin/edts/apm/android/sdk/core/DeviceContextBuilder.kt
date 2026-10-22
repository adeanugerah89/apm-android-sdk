package edts.apm.android.sdk.core

import android.content.Context
import android.os.Build
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the static device and app context captured once per session.
 * All fields produce wire-safe strings with no PII (no IMEI, no serial).
 */
internal object DeviceContextBuilder {

    /** The one place the OS version string comes from, so capture-time and upload-time cannot disagree. */
    fun osVersion(): String = Build.VERSION.RELEASE

    fun buildDeviceInfo(): DeviceInfo = DeviceInfo(
        os        = "Android",
        osVersion = osVersion(),                              // e.g. "14"
        model     = "${Build.MANUFACTURER} ${Build.MODEL}",  // e.g. "samsung SM-G991B"
        locale    = Locale.getDefault().toString(),           // e.g. "id_ID"
        timezone  = TimeZone.getDefault().id                  // e.g. "Asia/Jakarta"
    )

    fun buildAppInfo(context: Context, version: String, build: String): AppInfo = AppInfo(
        id      = context.packageName,
        version = version,
        build   = build
    )
}
