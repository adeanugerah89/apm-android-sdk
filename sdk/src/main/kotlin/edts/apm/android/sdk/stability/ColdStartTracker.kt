package edts.apm.android.sdk.stability

import android.os.SystemClock
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.internal.Clock
import edts.apm.android.sdk.queue.EventQueue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cold start to first frame (MOB-19) — emits a `performance` event with `state: cold_start` and
 * `duration_ms` (`01` §4.6).
 *
 * The clock starts at [processStartUptimeMs] (`android.os.Process.getStartUptimeMillis()`, API 24)
 * rather than at `APM.start()`. Measuring from our own initialization would quietly exclude
 * everything before it — process fork, class loading, other libraries' `Application.onCreate` —
 * which is exactly the part a slow cold start usually lives in.
 *
 * It stops at the first frame actually drawn, reported by [ColdStartFirstFrameObserver]. Both
 * ends are on the same `elapsedRealtime` timebase, so the difference survives clock changes.
 *
 * Fires at most once per process ([recorded] latches): a second activity being drawn is not a
 * second cold start.
 */
class ColdStartTracker(
    private val queue: EventQueue,
    private val seqProvider: () -> Long,
    private val contextProvider: () -> EventContext,
    private val processStartUptimeMs: Long,
    private val isEnabled: () -> Boolean = { true },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) {
    private val recorded = AtomicBoolean(false)

    /**
     * Call when the first frame has been drawn. Returns the recorded duration, or null when
     * nothing was emitted (already recorded, disabled, or the measurement was not believable).
     */
    fun onFirstFrame(): Long? {
        if (!recorded.compareAndSet(false, true)) return null
        if (!isEnabled()) return null

        val durationMs = elapsedRealtime() - processStartUptimeMs
        // A process forked in the background — by a broadcast, a job, a content provider — can sit
        // for minutes before anything is drawn. That number is real but it is not a cold start,
        // and shipping it would poison the p95 with values no user ever waited through.
        if (durationMs < 0 || durationMs > MAX_PLAUSIBLE_COLD_START_MS) return null

        val ctx = contextProvider()
        val attrs = buildJsonObject {
            put("state", "cold_start")
            put("duration_ms", durationMs)
        }

        queue.enqueue(
            Event(
                eventId = UUID.randomUUID().toString(),
                type = EventType.PERFORMANCE,
                tsClient = Clock.nowIso8601(),
                seq = seqProvider(),
                attrs = attrs,
                ctx = ctx
            )
        )
        return durationMs
    }

    companion object {
        /** 60 s. Above this the process was almost certainly started headless — see [onFirstFrame]. */
        const val MAX_PLAUSIBLE_COLD_START_MS = 60_000L
    }
}
