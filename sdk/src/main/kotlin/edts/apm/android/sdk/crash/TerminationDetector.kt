package edts.apm.android.sdk.crash

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.internal.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** The two fields of `ApplicationExitInfo` this detector actually needs — a seam so tests can
 * supply fake exit data directly instead of constructing a real `ApplicationExitInfo` (which
 * has no public constructor and, as of Robolectric 4.13, a broken shadow for doing so). */
internal data class ExitInfo(val pid: Int, val reason: Int, val timestamp: Long = 0L)

/** A detected termination: the mapped [reason] and the [eventId] derived from the exit it came from (A52). */
internal data class DetectedTermination(val reason: String, val eventId: String)

/**
 * MOB-15b / `01` §4.7 — checked once per launch (A16 calls [check] during `APM.start()`).
 * API 24-29 always return null (`ApplicationExitInfo` needs API 30) — documented consequence of
 * the 2026-09-03 minSdk decision, not a bug here.
 *
 * Dedup by `pid` rather than timestamp: `getHistoricalProcessExitReasons` keeps a rolling
 * history, so without tracking what's already been reported, the same past exit would be
 * re-emitted on every subsequent launch. Each process life gets a distinct pid, making it a
 * reliable per-exit key.
 */
internal class TerminationDetector(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val fetchLatestExit: () -> ExitInfo? = { defaultFetchLatestExit(context) }
) {
    /** Never throws (CONSTITUTION rule #1). Returns null if there's nothing new/mappable to report. */
    fun check(): String? = detect()?.reason

    /** [check] plus the deterministic `event_id` for the event to build (A52). Never throws. */
    fun detect(): DetectedTermination? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) detectInternal() else null
    } catch (e: Exception) {
        null
    }

    private fun detectInternal(): DetectedTermination? {
        val latest = fetchLatestExit() ?: return null

        if (latest.pid == prefs.getInt(KEY_LAST_REPORTED_PID, NO_PID)) return null  // already handled
        prefs.edit().putInt(KEY_LAST_REPORTED_PID, latest.pid).apply()

        val reason = TerminationReasonMapper.map(latest.reason) ?: return null
        return DetectedTermination(
            reason = reason,
            eventId = ReportEventId.forExit("termination", context.packageName, latest.pid, latest.timestamp)
        )
    }

    companion object {
        private const val KEY_LAST_REPORTED_PID = "apm_last_reported_exit_pid"
        private const val NO_PID = -1

        // The SDK_INT guard lives right here, in the same function as the gated call — lint
        // can't trace it through the [check] -> [checkInternal] -> [fetchLatestExit] chain,
        // even though [check] already gates the whole path at the public API surface.
        private fun defaultFetchLatestExit(context: Context): ExitInfo? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) realFetchLatestExit(context) else null

        @RequiresApi(Build.VERSION_CODES.R)
        private fun realFetchLatestExit(context: Context): ExitInfo? {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val latest = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
            return ExitInfo(pid = latest.pid, reason = latest.reason, timestamp = latest.timestamp)
        }
    }
}

/** Pure — build the `termination` event once a mapped [reason] is known. */
internal fun buildTerminationEvent(
    reason: String,
    seq: Long,
    ctx: EventContext,
    /** Derived from the exit record (A52); random only when a caller has no exit to derive it from. */
    eventId: String = UUID.randomUUID().toString()
): Event = Event(
    eventId = eventId,
    type = EventType.TERMINATION,
    tsClient = Clock.nowIso8601(),
    seq = seq,
    attrs = buildJsonObject { put("termination_reason", reason) },
    ctx = ctx
)
