package edts.apm.android.sdk.crash

import android.app.ApplicationExitInfo

/**
 * `ApplicationExitInfo.reason` -> `termination_reason` enum (`01` §4.7). CONSTITUTION
 * 2026-09-07 (D1): only `REASON_LOW_MEMORY` has a clear Android equivalent; everything else
 * (including plain `REASON_CRASH`, which our own [JvmCrashHandler] already reports separately
 * as a `crash` event) maps to `null`, i.e. don't emit — same treatment as spec's `unexplained`.
 */
object TerminationReasonMapper {
    fun map(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "memory_pressure"
        else -> null
    }
}
