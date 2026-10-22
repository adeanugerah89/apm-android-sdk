package edts.apm.android.sdk.internal

/** `source_file`/`source_function`/`source_line` for a manually-reported error (MOB-11b). */
internal data class CallSite(val file: String, val function: String, val line: Int) {
    companion object {
        val UNKNOWN = CallSite(file = "unknown", function = "unknown", line = -1)
    }
}

/**
 * Finds the first stack frame outside the SDK's own package — the actual host app call site —
 * via a stack walk. No developer action needed, same intent as iOS's `#fileID`/`#function`/`#line`
 * default-parameter approach, but Android has no compile-time equivalent.
 *
 * `StackTraceElement.fileName` is a bare filename (e.g. "LoginActivity.kt"), never an absolute
 * path — unlike iOS's rejected `#file`, there is no build-machine username to leak here.
 */
internal object CallSiteLocator {
    // Package prefix for this SDK's own compiled classes — frames here are skipped so the
    // located frame is always the caller, not internal plumbing (APM.logError, this object, ...).
    private const val SDK_PACKAGE_PREFIX = "edts.apm.android.sdk"

    // getStackTrace() itself always shows up as the top frame(s) under these — not real callers.
    private val VM_FRAME_PREFIXES = listOf("java.lang.Thread", "dalvik.system", "vm")

    fun locateCaller(): CallSite {
        val stack = try {
            Thread.currentThread().stackTrace
        } catch (e: Exception) {
            return CallSite.UNKNOWN  // CONSTITUTION rule #1: never throw to the host
        }

        val frame = stack.firstOrNull { element ->
            !element.className.startsWith(SDK_PACKAGE_PREFIX) &&
                VM_FRAME_PREFIXES.none { element.className.startsWith(it) }
        } ?: return CallSite.UNKNOWN  // no real caller could be identified — fail safe

        return CallSite(
            file = frame.fileName ?: "unknown",
            function = "${frame.className}.${frame.methodName}",
            line = frame.lineNumber
        )
    }
}
