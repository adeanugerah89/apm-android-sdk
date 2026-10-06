package edts.apm.android.sdk.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import edts.apm.android.sdk.core.AppStamp
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
    private val maxRecords: Int = MAX_RECORDS,
    /**
     * The session the previous process was in when it died (A28), read from storage before this
     * process created its own. Applied only to the **newest** exit record — the one that is the
     * process that just died. Older unreported records belong to sessions nobody remembers, so they
     * stay unrecorded and fall back to the current session rather than being given a wrong one.
     */
    private val crashedSessionId: String? = null,
    /**
     * The app version/build and OS version the previous process was running (A39), applied to the same
     * newest record as [crashedSessionId] and for the same reason: a crash reported after an app update
     * must be filed under the version that crashed, not the one that is installed now.
     */
    private val crashedApp: AppStamp? = null,
    /**
     * The identity epoch the previous process ended in (A39) — same newest-record rule as [crashedSessionId].
     * **Required, no default**: this runs on a background thread after the graph is published, so a host
     * `setUser` can change the current epoch before it reads the tombstone, and no deterministic test can
     * tell a forgotten argument from a correct one. Making it required turns that into a compile error.
     */
    private val crashedIdentityEpoch: Int?
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

        val newestExit = records.maxOfOrNull { it.timestamp }

        records
            .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
            .filter { it.timestamp > lastReported }
            .sortedBy { it.timestamp }
            .forEach { info ->
                val session = if (info.timestamp == newestExit) crashedSessionId else null
                val app = if (info.timestamp == newestExit) crashedApp else null
                val epoch = if (info.timestamp == newestExit) crashedIdentityEpoch else null
                if (emit(info, session, app, epoch)) emitted++
                if (info.timestamp > newest) newest = info.timestamp
            }

        // Advance the watermark even when nothing could be parsed: a tombstone we cannot read now
        // will not become readable later, and retrying it every launch forever is pure cost.
        if (newest > lastReported) prefs.edit().putLong(KEY_LAST_TIMESTAMP, newest).apply()
        return emitted
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun emit(info: ApplicationExitInfo, sessionId: String?, app: AppStamp?, identityEpoch: Int?): Boolean {
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

        // Deliberately NO `breadcrumbs` here (A27). This runs at the *next* launch: the crashing
        // process's ring buffer died with it, and the one in memory now belongs to the new process.
        // Attaching it would show the developer what happened *after* the crash as if it led up to
        // it — worse than nothing. `01` §4.5.2 treats an absent attribute as "empty", which is
        // honest. Recovering the real trail needs a snapshot persisted before the crash (open
        // decision, see FEATURES.md A27).
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
                tsClient = TombstoneTimestamp.toIso8601(tombstone.timestamp) ?: Clock.iso8601(info.timestamp),
                seq = seqProvider(),
                attrs = attrs,
                ctx = ctx,
                sessionId = sessionId,
                appVersion = app?.version,
                appBuild = app?.build,
                osVersion = app?.osVersion,
                identityEpoch = identityEpoch
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
