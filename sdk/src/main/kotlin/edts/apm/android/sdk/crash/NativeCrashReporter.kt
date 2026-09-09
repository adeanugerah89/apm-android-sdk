package edts.apm.android.sdk.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.internal.Clock
import edts.apm.android.sdk.queue.EventQueue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Reports native crashes from the previous process at the next launch (MOB-15 native, MOB-16).
 *
 * The source is Android's own tombstone, via `ApplicationExitInfo.getTraceInputStream()`. This
 * replaced xCrash: xCrash's dumper failed on every device tested (API 33/34/36, emulator and real
 * hardware, injected and genuine faults), so it could never produce build IDs, and without those
 * `binary_images[].uuid` is unfillable and a native crash can never be symbolicated. The system
 * tombstone carries build IDs, needs no dependency, and installs no signal handler in this process
 * at all — the SDK does nothing while the app is dying, which is a stronger version of what SEC-09
 * was asking for.
 *
 * **Requires API 30+.** Below that `ApplicationExitInfo` does not exist and there is no native
 * crash capture. `minSdk` is 24, so this is a real gap, recorded as such rather than papered over.
 *
 * Deduplication is by process timestamp in [SharedPreferences]: exit records live in the system,
 * not in our storage, so the same crash is offered again on every launch until it ages out of the
 * system's history.
 */
class NativeCrashReporter(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val queue: EventQueue,
    private val seqProvider: () -> Long,
    private val contextProvider: () -> EventContext,
    private val maxRecords: Int = MAX_RECORDS
) {
    private val json = Json { encodeDefaults = true }

    /** Returns how many crash events were emitted. Never throws (CONSTITUTION rule #1). */
    fun reportPending(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) reportInternal() else 0
    } catch (e: Throwable) {
        0
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reportInternal(): Int {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0
        val records = am.getHistoricalProcessExitReasons(context.packageName, 0, maxRecords)
        val lastReported = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
        var newest = lastReported
        var emitted = 0

        records
            .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
            .filter { it.timestamp > lastReported }
            .sortedBy { it.timestamp }
            .forEach { info ->
                if (emit(info)) emitted++
                if (info.timestamp > newest) newest = info.timestamp
            }

        // Advance the watermark even when nothing could be parsed: a tombstone we cannot read now
        // will not become readable later, and retrying it every launch forever is pure cost.
        if (newest > lastReported) prefs.edit().putLong(KEY_LAST_TIMESTAMP, newest).apply()
        return emitted
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun emit(info: ApplicationExitInfo): Boolean {
        // Raw bytes: the trace is protobuf, and reading it through a Reader would decode it as
        // UTF-8 and silently destroy every byte that is not valid UTF-8.
        val bytes = try {
            info.traceInputStream?.use { it.readBytes() }
        } catch (e: Throwable) {
            null
        } ?: return false

        val tombstone = SystemTombstoneParser.parse(bytes, context.packageName) ?: return false
        val ctx = contextProvider()
        val threads = tombstone.toCrashThreads()
        val images = tombstone.toBinaryImages()

        val attrs = buildJsonObject {
            put("crash_type", "signal")
            put("name", tombstone.signalName ?: "unknown")
            put("reason", buildReason(tombstone))
            put("is_fatal", true)
            put("threads", json.encodeToJsonElement(threads))
            put("binary_images", json.encodeToJsonElement(images))
            ctx.appState?.let { put("app_state", it) }
            tombstone.architecture?.let { put("abi", it) }
        }

        return queue.enqueue(
            Event(
                eventId = UUID.randomUUID().toString(),
                type = EventType.CRASH,
                // The tombstone's own crash time. This is reported at the next launch, and stamping
                // it with the launch time would place every native crash at the wrong moment.
                tsClient = tombstone.timestamp ?: Clock.nowIso8601(),
                seq = seqProvider(),
                attrs = attrs,
                ctx = ctx
            )
        )
    }

    private fun buildReason(t: SystemTombstone): String = buildString {
        append(t.signalName ?: "unknown signal")
        t.signalCode?.let { append(" (").append(it).append(")") }
        t.faultAddress?.let { append(" at ").append(it) }
        t.cause?.let { append(" — ").append(it) }
    }

    companion object {
        private const val KEY_LAST_TIMESTAMP = "apm_last_native_crash_ts"
        private const val MAX_RECORDS = 20
    }
}
