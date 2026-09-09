package edts.apm.android.sdk.stability

import android.os.SystemClock
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.crash.JvmStackTraceMapper
import edts.apm.android.sdk.internal.Clock
import edts.apm.android.sdk.queue.EventQueue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ANR detection (MOB-18) — emits a `crash` event with `crash_type: anr` (`01` §4.3).
 *
 * Mechanism: a daemon thread posts a sentinel to the main thread every [pollIntervalMs]; the
 * sentinel stamps the time it ran. If the newest stamp is older than [thresholdMs], the main
 * thread has not drained one trivial Runnable in that window and is, by Android's own definition
 * of an ANR, not responding. This detects the block **while it is happening**, which is what makes
 * the captured stack useful — `ApplicationExitInfo.REASON_ANR` only tells you afterwards that one
 * occurred.
 *
 * **The poll interval is deliberately shorter than the threshold** (A10b). The first version slept
 * for the full [thresholdMs] between checks, which meant a block that started and ended inside one
 * cycle was never seen: the effective threshold was *twice* the configured one. That was not
 * theory — on an API 36 emulator a 7 s block with a 5 s threshold was repeatably missed, while a
 * 12 s block was caught. Sampling faster than the thing being measured is the fix; the cost is one
 * `Handler.post` per second on a `MIN_PRIORITY` daemon thread.
 *
 * Nothing here matches a specific sentinel to a specific poll. Several sentinels can pile up
 * behind a blocked main thread and all run at once when it drains — they simply overwrite the same
 * timestamp with increasing values, so the newest wins and the pile-up is harmless.
 *
 * Distinct from [edts.apm.android.sdk.crash.JvmCrashHandler] in two ways that matter on the wire:
 *  - `is_fatal = false`. A watchdog ANR is an observed block, not a dead process. The app very
 *    often recovers; reporting it as fatal would inflate the crash-free-session rate's denominator
 *    with sessions that never actually died.
 *  - Reported **at detection time** through the normal queue, not on the next launch (MOB-16 is
 *    about crash reports, which cannot be uploaded from a dying process — this one can).
 *
 * One block produces one event: [reported] latches until the main thread responds again, so a
 * ten-second freeze is one ANR, not one per poll.
 */
class AnrWatchdog(
    private val queue: EventQueue,
    private val seqProvider: () -> Long,
    private val contextProvider: () -> EventContext,
    private val appPackagePrefix: String,
    private val launchElapsedRealtimeMs: Long,
    private val probe: MainThreadProbe,
    private val thresholdMs: Long = DEFAULT_THRESHOLD_MS,
    private val isAnrEnabled: () -> Boolean = { true },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
) {
    private val json = Json { encodeDefaults = true }
    private val running = AtomicBoolean(false)
    private var reported = false

    /** When the main thread last ran one of our sentinels. Written from the main thread. */
    @Volatile private var lastResponseMs: Long = elapsedRealtime()

    @Volatile private var thread: Thread? = null

    /** Starts the watchdog thread. Idempotent — a second call while running is a no-op. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "apm-anr-watchdog").apply {
            isDaemon = true          // must never keep the host app's process alive
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Stops the watchdog. Safe to call when not started. */
    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (running.get()) {
            try {
                tick()
            } catch (e: InterruptedException) {
                return                // stop() interrupted the sleep — exit quietly
            } catch (e: Throwable) {
                // A watchdog that crashes the app it is watching is worse than no watchdog.
            }
        }
    }

    /**
     * One poll cycle: ping, wait, judge. Returns true when this cycle emitted an ANR event.
     * Separated from [loop] so the detection logic is testable with a fake probe and a fake sleep.
     */
    internal fun tick(): Boolean {
        val posted = probe.post { lastResponseMs = elapsedRealtime() }
        if (!posted) return false     // could not even enqueue — no evidence either way

        sleeper(pollIntervalMs)

        val blockedForMs = elapsedRealtime() - lastResponseMs
        if (blockedForMs < thresholdMs) {
            reported = false          // responsive enough — arm for the next block
            return false
        }

        if (reported) return false    // same ongoing block, already reported once

        if (!isAnrEnabled()) {
            reported = true           // still latch, so re-enabling mid-block doesn't backfill
            return false
        }

        writeAnrEvent(blockedForMs)
        reported = true
        return true
    }

    private fun writeAnrEvent(blockedForMs: Long) {
        val mainThread = probe.mainThread
        val threads = JvmStackTraceMapper.map(probe.allStackTraces(), mainThread, appPackagePrefix)
        val ctx = contextProvider()

        val attrs = buildJsonObject {
            put("crash_type", "anr")
            put("name", "ApplicationNotResponding")
            put("reason", "Main thread unresponsive for at least ${blockedForMs}ms")
            put("is_fatal", false)
            put("threads", json.encodeToJsonElement(threads))
            put("binary_images", JsonArray(emptyList()))   // JVM frames need no symbolication
            ctx.appState?.let { put("app_state", it) }
            put("time_since_launch_ms", elapsedRealtime() - launchElapsedRealtimeMs)
        }

        queue.enqueue(
            Event(
                eventId = UUID.randomUUID().toString(),
                type = EventType.CRASH,
                tsClient = Clock.nowIso8601(),
                seq = seqProvider(),
                attrs = attrs,
                ctx = ctx
            )
        )
    }

    companion object {
        /**
         * 5 s — Android's own input-dispatch ANR threshold. `02` §3.5's "> 2 detik" qualifies the
         * iOS *hang* detector, which has no OS-level equivalent to anchor to; on Android there is
         * one, and matching it means a reported ANR is an ANR the platform would also call one.
         */
        const val DEFAULT_THRESHOLD_MS = 5_000L

        /**
         * How often the main thread is sampled. Must stay well below [DEFAULT_THRESHOLD_MS] — the
         * worst-case detection delay is threshold + one poll, so this is the precision of the
         * whole detector. 1 s keeps that within 20% while costing one trivial Runnable per second.
         */
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L
    }
}
