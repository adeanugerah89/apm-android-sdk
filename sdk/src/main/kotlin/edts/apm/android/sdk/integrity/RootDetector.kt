package edts.apm.android.sdk.integrity

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * `integrity.is_rooted` — MOB-30. Heuristic, not a security gate. Package checks target
 * specific known package names declared in `<queries>` (AndroidManifest.xml) so they still
 * work under API 30+ package visibility filtering without needing QUERY_ALL_PACKAGES.
 */
object RootDetector {

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/app/Superuser.apk", "/data/local/xbin/su", "/data/local/bin/su",
        "/system/sd/xbin/su", "/data/local/su", "/su/bin/su"
    )
    private val ROOT_PACKAGES = listOf(
        "com.topjohnwu.magisk", "eu.chainfire.supersu", "com.noshufou.android.su"
    )

    fun isRooted(
        context: Context,
        pathExists: (String) -> Boolean = { File(it).exists() },
        systemPartitionWritable: () -> Boolean = { File("/system").canWrite() }
    ): Boolean = try {
        hasSuBinary(pathExists) ||
            hasTestKeysBuildTag() ||
            hasRootPackage(context) ||
            systemPartitionWritable()
    } catch (e: Exception) {
        false  // CONSTITUTION rule #1
    }

    private fun hasSuBinary(pathExists: (String) -> Boolean): Boolean =
        SU_PATHS.any { pathExists(it) }

    private fun hasTestKeysBuildTag(): Boolean =
        Build.TAGS?.contains("test-keys") == true

    private fun hasRootPackage(context: Context): Boolean =
        ROOT_PACKAGES.any { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            } catch (e: Exception) {
                false  // package visibility restriction or other lookup failure — not rooted-by-this-signal
            }
        }
}
