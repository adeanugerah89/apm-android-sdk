package edts.apm.android.sdk.crash

import edts.apm.android.sdk.internal.ProtoException
import edts.apm.android.sdk.internal.ProtoReader

/**
 * Parses Android's own crash tombstone into the `01` §4.3.1/§4.3.2 wire shape (A9b, opsi B).
 *
 * The source is `ApplicationExitInfo.getTraceInputStream()` for `REASON_CRASH_NATIVE` — a
 * protobuf-encoded AOSP `Tombstone`. It replaces xCrash entirely: xCrash's dumper failed on every
 * device tested, so it could never supply build IDs, and without those `binary_images[].uuid` is
 * unfillable and native crashes can never be symbolicated. The system tombstone carries build IDs
 * for both frames and mappings, needs no dependency and no signal handler of our own.
 *
 * **Field numbers below were derived from real tombstone bytes**, not from a copy of
 * `Tombstone.proto` — the same discipline the text parser used, and for the same reason: guessing
 * a crash format produces data that looks valid and is wrong. Unknown fields are skipped, so a
 * newer Android adding fields cannot break this.
 *
 * The one thing this costs is reach: `ApplicationExitInfo` is API 30+, while `minSdk` is 24. On
 * API 24–29 there is no native crash capture at all. That is a deliberate trade — see CONSTITUTION.
 */
internal object SystemTombstoneParser {

    // ─── Tombstone ──────────────────────────────────────────────────────────
    private const val F_ARCH = 1
    private const val F_TIMESTAMP = 4
    private const val F_PID = 5
    private const val F_TID = 6
    private const val F_PROCESS_NAME = 9
    private const val F_SIGNAL = 10
    private const val F_CAUSES = 15
    private const val F_THREADS = 16
    private const val F_MEMORY_MAPPINGS = 17

    // ─── Signal ─────────────────────────────────────────────────────────────
    private const val F_SIG_NAME = 2
    private const val F_SIG_CODE_NAME = 4
    private const val F_SIG_FAULT_ADDRESS = 7
    /**
     * proto3 omits fields equal to their default, so a fault address of 0 — the single most common
     * one, a null dereference — is simply absent from the bytes. The tombstone therefore carries a
     * separate boolean saying whether the address is meaningful, and it must be read: without it,
     * "faulted at 0x0" is indistinguishable from "no address recorded".
     */
    private const val F_SIG_HAS_FAULT_ADDRESS = 8

    // ─── Cause ──────────────────────────────────────────────────────────────
    private const val F_CAUSE_HUMAN_READABLE = 1

    // ─── map<uint32, Thread> entry ──────────────────────────────────────────
    private const val F_MAP_KEY = 1
    private const val F_MAP_VALUE = 2

    // ─── Thread ─────────────────────────────────────────────────────────────
    private const val F_THREAD_ID = 1
    private const val F_THREAD_NAME = 2
    private const val F_THREAD_BACKTRACE = 4

    // ─── BacktraceFrame ─────────────────────────────────────────────────────
    private const val F_FRAME_REL_PC = 1
    private const val F_FRAME_PC = 2
    private const val F_FRAME_FUNCTION_NAME = 4
    private const val F_FRAME_FILE_NAME = 6
    private const val F_FRAME_BUILD_ID = 8

    // ─── MemoryMapping ──────────────────────────────────────────────────────
    private const val F_MAP_BEGIN = 1
    private const val F_MAP_END = 2
    private const val F_MAP_EXECUTE = 6
    private const val F_MAP_NAME = 7
    private const val F_MAP_BUILD_ID = 8

    /** Returns null when the bytes are not a tombstone we can read. Never throws. */
    fun parse(bytes: ByteArray, appPackageName: String): SystemTombstone? = try {
        parseTombstone(bytes, appPackageName)
    } catch (e: ProtoException) {
        null
    } catch (e: Exception) {
        null
    }

    private fun parseTombstone(bytes: ByteArray, appPackageName: String): SystemTombstone {
        val reader = ProtoReader(bytes)
        var arch: Long? = null
        var timestamp: String? = null
        var crashedTid: Long = -1
        var processName: String? = null
        var signalName: String? = null
        var signalCode: String? = null
        var faultAddress: Long? = null
        var hasFaultAddress = false
        var cause: String? = null
        val threads = mutableMapOf<Long, RawThread>()
        val mappings = mutableListOf<RawMapping>()

        while (reader.hasMore()) {
            when (val field = reader.readTag()) {
                F_ARCH -> arch = reader.readVarint()
                F_TIMESTAMP -> timestamp = reader.readString()
                F_TID -> crashedTid = reader.readVarint()
                F_PROCESS_NAME -> processName = reader.readString()
                F_SIGNAL -> reader.readMessage().let { sig ->
                    while (sig.hasMore()) {
                        when (sig.readTag()) {
                            F_SIG_NAME -> signalName = sig.readString()
                            F_SIG_CODE_NAME -> signalCode = sig.readString()
                            F_SIG_FAULT_ADDRESS -> faultAddress = sig.readVarint()
                            F_SIG_HAS_FAULT_ADDRESS -> hasFaultAddress = sig.readVarint() != 0L
                            else -> sig.skipValue()
                        }
                    }
                }
                F_CAUSES -> reader.readMessage().let { c ->
                    while (c.hasMore()) {
                        when (c.readTag()) {
                            F_CAUSE_HUMAN_READABLE -> if (cause == null) cause = c.readString()
                            else -> c.skipValue()
                        }
                    }
                }
                F_THREADS -> parseThreadEntry(reader.readMessage())?.let { threads[it.id] = it }
                F_MEMORY_MAPPINGS -> mappings += parseMapping(reader.readMessage())
                else -> {
                    if (field == F_PID) reader.readVarint() else reader.skipValue()
                }
            }
        }

        // An empty buffer parses "successfully" into a message with nothing in it. Returning that
        // would hand the caller a crash report describing no crash.
        if (signalName == null && threads.isEmpty()) throw ProtoException("no signal and no threads")

        return SystemTombstone(
            timestamp = timestamp,
            processName = processName,
            signalName = signalName,
            signalCode = signalCode,
            faultAddress = if (hasFaultAddress) "0x" + (faultAddress ?: 0L).toString(16) else null,
            cause = cause,
            architecture = ARCHITECTURES[arch?.toInt() ?: -1],
            crashedTid = crashedTid,
            threads = threads.values.toList(),
            mappings = mappings,
            appPackageName = appPackageName
        )
    }

    private fun parseThreadEntry(entry: ProtoReader): RawThread? {
        var key = -1L
        var thread: RawThread? = null
        while (entry.hasMore()) {
            when (entry.readTag()) {
                F_MAP_KEY -> key = entry.readVarint()
                F_MAP_VALUE -> thread = parseThread(entry.readMessage())
                else -> entry.skipValue()
            }
        }
        return thread?.copy(id = if (thread.id >= 0) thread.id else key)
    }

    private fun parseThread(t: ProtoReader): RawThread {
        var id = -1L
        var name: String? = null
        val frames = mutableListOf<RawFrame>()
        while (t.hasMore()) {
            when (t.readTag()) {
                F_THREAD_ID -> id = t.readVarint()
                F_THREAD_NAME -> name = t.readString()
                F_THREAD_BACKTRACE -> frames += parseFrame(t.readMessage())
                else -> t.skipValue()
            }
        }
        return RawThread(id, name, frames)
    }

    private fun parseFrame(f: ProtoReader): RawFrame {
        var relPc: Long? = null
        var pc: Long? = null
        var function: String? = null
        var file: String? = null
        var buildId: String? = null
        while (f.hasMore()) {
            when (f.readTag()) {
                F_FRAME_REL_PC -> relPc = f.readVarint()
                F_FRAME_PC -> pc = f.readVarint()
                F_FRAME_FUNCTION_NAME -> function = f.readString()
                F_FRAME_FILE_NAME -> file = f.readString()
                F_FRAME_BUILD_ID -> buildId = f.readString()
                else -> f.skipValue()
            }
        }
        return RawFrame(relPc, pc, function, file, buildId)
    }

    private fun parseMapping(m: ProtoReader): RawMapping {
        var begin: Long? = null
        var end: Long? = null
        var execute = false
        var name: String? = null
        var buildId: String? = null
        while (m.hasMore()) {
            when (m.readTag()) {
                F_MAP_BEGIN -> begin = m.readVarint()
                F_MAP_END -> end = m.readVarint()
                F_MAP_EXECUTE -> execute = m.readVarint() != 0L
                F_MAP_NAME -> name = m.readString()
                F_MAP_BUILD_ID -> buildId = m.readString()
                else -> m.skipValue()
            }
        }
        return RawMapping(begin, end, execute, name, buildId)
    }

    /** `Architecture` enum in the proto, in declaration order. */
    private val ARCHITECTURES = mapOf(
        0 to "arm32", 1 to "arm64", 2 to "x86", 3 to "x86_64", 4 to "riscv64"
    )
}

internal data class RawThread(val id: Long, val name: String?, val frames: List<RawFrame>)

internal data class RawFrame(
    val relPc: Long?,
    val pc: Long?,
    val functionName: String?,
    val fileName: String?,
    val buildId: String?
)

internal data class RawMapping(
    val begin: Long?,
    val end: Long?,
    val execute: Boolean,
    val name: String?,
    val buildId: String?
)
