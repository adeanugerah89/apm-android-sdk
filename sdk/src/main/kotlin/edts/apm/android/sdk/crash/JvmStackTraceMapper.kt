package edts.apm.android.sdk.crash

/**
 * Maps JVM stack traces to the `01` §4.3.1 wire shape — pure, no I/O, safe to call from an
 * uncaught-exception handler. See CONSTITUTION 2026-09-07 (D1) for why address fields are
 * omitted and symbol info is filled immediately for Java frames.
 */
object JvmStackTraceMapper {

    /**
     * [allStacks] is typically `Thread.getAllStackTraces()`. The crashed thread is always
     * emitted first (index 0) with `crashed=true`; the rest follow in name order for
     * deterministic output.
     */
    fun map(
        allStacks: Map<Thread, Array<StackTraceElement>>,
        crashedThread: Thread,
        appPackagePrefix: String
    ): List<CrashThread> {
        val ordered = allStacks.keys.sortedBy { it.name }
            .let { others -> listOf(crashedThread) + others.filter { it !== crashedThread } }

        return ordered.mapIndexedNotNull { index, thread ->
            val frames = allStacks[thread] ?: return@mapIndexedNotNull null
            CrashThread(
                index = index,
                crashed = thread === crashedThread,
                name = thread.name,
                frames = mapFrames(frames, appPackagePrefix)
            )
        }
    }

    private fun mapFrames(frames: Array<StackTraceElement>, appPackagePrefix: String): List<CrashFrame> =
        frames.mapIndexed { index, element ->
            CrashFrame(
                index = index,
                objectName = element.className,
                objectAddr = null,        // no native memory address for a JVM frame
                instructionAddr = null,
                isApp = element.className.startsWith(appPackagePrefix),
                symbolName = element.methodName,   // filled now — Java needs no remote symbolication
                file = element.fileName,
                line = element.lineNumber.takeIf { it >= 0 }
            )
        }
}
