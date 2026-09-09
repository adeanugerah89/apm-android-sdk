package edts.apm.android.sdk.crash

/**
 * A parsed Android system tombstone, projected onto `01` §4.3 (A9b, opsi B).
 *
 * Unlike the xCrash path this replaces, [binaryImages] is genuinely populated: the system
 * tombstone carries a build ID for each mapped object, which is exactly the `uuid` §4.3.2 marks
 * mandatory and the thing that lets a backend match a crash to the right symbol file.
 */
internal data class SystemTombstone(
    val timestamp: String?,
    val processName: String?,
    val signalName: String?,
    val signalCode: String?,
    val faultAddress: String?,
    /** e.g. "null pointer dereference" — the kernel's own explanation, when it gives one. */
    val cause: String?,
    val architecture: String?,
    val crashedTid: Long,
    internal val threads: List<RawThread>,
    internal val mappings: List<RawMapping>,
    private val appPackageName: String
) {

    /**
     * `01` §4.3.1. The crashing thread is emitted first with `crashed = true`; the rest follow in
     * thread-id order so two reports of the same crash are byte-comparable.
     */
    fun toCrashThreads(): List<CrashThread> {
        val ordered = threads.sortedBy { it.id }
            .let { all ->
                val crashed = all.filter { it.id == crashedTid }
                crashed + all.filterNot { it.id == crashedTid }
            }
            .take(MAX_THREADS)

        return ordered.mapIndexed { index, thread ->
            val isCrashed = thread.id == crashedTid
            CrashThread(
                index = index,
                crashed = isCrashed,
                name = thread.name ?: "tid-${thread.id}",
                frames = thread.frames
                    .take(if (isCrashed) MAX_FRAMES_CRASHED_THREAD else MAX_FRAMES_OTHER_THREAD)
                    .mapIndexed { frameIndex, frame -> toCrashFrame(frameIndex, frame) }
            )
        }
    }

    private fun toCrashFrame(index: Int, frame: RawFrame): CrashFrame {
        val path = frame.fileName
        return CrashFrame(
            index = index,
            objectName = shortObjectName(path),
            // `pc - rel_pc` is the object's load address, by definition of rel_pc. Preferred over
            // looking the address up in `memory_mappings` because it is exact, always present, and
            // needs no name matching — a library loaded straight out of the APK is mapped under
            // the APK's path, so matching by name would miss it entirely.
            objectAddr = loadAddressOf(frame)?.let { hex(it) },
            instructionAddr = frame.pc?.let { hex(it) },
            isApp = isAppPath(path),
            // Present for exported symbols even before symbolication; §4.3.1 expects the backend
            // to fill the rest from the symbol file matched by uuid.
            symbolName = frame.functionName,
            file = null,
            line = null
        )
    }

    private fun loadAddressOf(frame: RawFrame): Long? {
        val pc = frame.pc ?: return null
        val relPc = frame.relPc ?: return null
        return pc - relPc
    }

    /**
     * `01` §4.3.2 — one entry per object that actually appears on a stack, keyed by build ID.
     *
     * Built from the **frames**, not from `memory_mappings`. Two reasons, both learned from the
     * real tombstone: a library loaded straight out of the APK is mapped under the APK's own path
     * (`…/base.apk`), so the mapping cannot tell you the library's name — only the frame can; and
     * the mapping list runs to two thousand entries, almost none of which the crash touched.
     * Symbolication needs the objects on the stack, and nothing else.
     *
     * Size still comes from the mappings, matched by build ID, because a frame does not carry it.
     * An object with no build ID is omitted: `uuid` is what matches a crash to a symbol file, and
     * listing an image that can never be symbolicated only implies a capability that is not there.
     */
    fun toBinaryImages(): List<BinaryImage> {
        val mappingsByBuildId = mappings
            .filter { it.buildId != null && it.begin != null }
            .groupBy { it.buildId!! }

        return threads
            .flatMap { it.frames }
            .filter { it.buildId != null && it.fileName != null }
            .groupBy { it.buildId!! }
            .map { (buildId, frames) ->
                val base = frames.mapNotNull { loadAddressOf(it) }.minOrNull()
                val mapped = mappingsByBuildId[buildId].orEmpty()
                val mappedBase = mapped.mapNotNull { it.begin }.minOrNull()
                val mappedTop = mapped.mapNotNull { it.end }.maxOrNull()
                val resolvedBase = base ?: mappedBase ?: 0L

                BinaryImage(
                    name = shortObjectName(frames.first().fileName)!!,
                    uuid = buildId,
                    baseAddr = hex(resolvedBase),
                    // 0 when the object has no mapping of its own — honest about not knowing,
                    // rather than inventing a span.
                    size = mappedTop?.let { it - (mappedBase ?: resolvedBase) } ?: 0L,
                    arch = architecture ?: "unknown",
                    isApp = isAppPath(frames.first().fileName)
                )
            }
            .sortedBy { it.name }
    }

    /**
     * `is_app` per §4.3.1 — filled by the SDK because only the SDK knows which binary is the app's.
     * Decided by path: an app's own code lives under its install directory or its data directory.
     * `/system`, `/apex` and `/vendor` are never the app's, and an unknown path is not claimed
     * either way — a system frame wrongly marked as app code breaks exactly the highlighting FE-06
     * exists to provide.
     */
    private fun isAppPath(path: String?): Boolean {
        val p = path ?: return false
        return p.startsWith("/data/app/") || p.contains("/$appPackageName/") || p.contains("!$appPackageName")
    }

    /**
     * A library packed inside the APK is reported as `/data/app/…/base.apk!libfoo.so` — the `!`
     * separates the container from the entry and there is no slash after it, so taking the text
     * after the last `/` yields `base.apk!libfoo.so`. Both separators have to be handled or every
     * uncompressed-in-APK native library gets the wrong name, which is most of them.
     */
    private fun shortObjectName(path: String?): String? =
        path?.substringAfterLast('/')?.substringAfterLast('!')

    private fun hex(value: Long): String = "0x" + java.lang.Long.toHexString(value)

    companion object {
        /**
         * Caps, and why they exist: an uncapped report from this tombstone serialised to **~90 KB**
         * — 27 threads with full backtraces each. At `MAX_BATCH` 200 events an upload would be
         * megabytes and take a `413` (`01` §7), and the disk queue's 20 MB ceiling (`02` §5) would
         * hold only a couple of hundred crashes.
         *
         * The crashing thread keeps a deep stack because that is the report; other threads are kept
         * only to show what the process was doing, which the top frames already say.
         */
        const val MAX_THREADS = 24
        const val MAX_FRAMES_CRASHED_THREAD = 96
        const val MAX_FRAMES_OTHER_THREAD = 12
    }
}
