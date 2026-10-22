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
 * Cold start to first frame (MOB-19) — emits a `lifecycle` event with `state: cold_start` and
 * `duration_ms` (`01` §4.6).
 *
 * The type is `lifecycle`, not `performance`, to match iOS (`APMKit` `ColdStartTracker.swift`) and the
 * backend's own parity corpus (`apm-backend` `internal/oracle/corpus.go`). §4.6 lists both types and
 * the backend stores `type` opaquely, so nothing reads the difference today — but the contract's
 * promise is identical output across platforms, and a future reader that picks one would otherwise
 * silently see only one platform's cold starts.
 *
 * The clock starts at [processStartElapsedMs] (`android.os.Process.getStartElapsedRealtime()`, API 24)
 * rather than at `APM.start()`. Measuring from our own initialization would quietly exclude
 * everything before it — process fork, class loading, other libraries' `Application.onCreate` —
 * which is exactly the part a slow cold start usually lives in.
 *
 * It stops at the first frame actually drawn, reported by [ColdStartFirstFrameObserver] **with the
 * time it was drawn** — which can be long before this tracker exists (A46: `APM.start()` assembles in
 * the background, and on a slow device the first frame beats it). Both ends are on the
 * `elapsedRealtime` timebase. It used to start from `getStartUptimeMillis()`, which is the *uptime*
 * timebase (it stops during deep sleep); subtracting that from `elapsedRealtime()` adds every second the
 * device has ever slept to every cold start, which on a real phone is hours — past the plausibility cap, so
 * nothing would ever have been reported.
 *
 * Fires at most once per process ([recorded] latches): a second activity being drawn is not a
 * second cold start.
 */
internal class ColdStartTracker(
    private val queue: EventQueue,
    private val seqProvider: () -> Long,
    private val contextProvider: () -> EventContext,
    private val processStartElapsedMs: Long,
    private val isEnabled: () -> Boolean = { true },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) {
    private val recorded = AtomicBoolean(false)

    /**
     * Call when the first frame has been drawn. [frameElapsedRealtimeMs] is when it was drawn, which may be
     * well before this is called. Returns the recorded duration, or null when nothing was emitted (already
     * recorded, disabled, or the measurement was not believable).
     */
    fun onFirstFrame(frameElapsedRealtimeMs: Long = elapsedRealtime()): Long? {
        if (!recorded.compareAndSet(false, true)) return null
        if (!isEnabled()) return null

        val durationMs = frameElapsedRealtimeMs - processStartElapsedMs
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
                type = EventType.LIFECYCLE,
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
