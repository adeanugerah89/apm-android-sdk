package edts.apm.android.sdk.crash

import android.os.SystemClock
import edts.apm.android.sdk.breadcrumb.putBreadcrumbSnapshot
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.internal.Clock
import edts.apm.android.sdk.queue.EventQueue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * MOB-15/16/17 — Java/JVM crash path only (see CONSTITUTION 2026-09-07 D1 for why native/xCrash
 * crashes are out of scope this round).
 *
 * Unlike a native signal handler, `uncaughtException` runs in normal JVM context — no
 * async-signal-safety constraint — so it's safe to run this through the full normal pipeline
 * (JSON encode, Scrubber via [queue], disk write) rather than a raw/unencrypted fast path.
 * [queue] MUST be the scrubbing-wrapped queue (SEC-01/05b), same requirement as [ManualApi].
 *
 * Always chains to [defaultHandler] in a `finally` block — this class must never be the reason
 * the process fails to terminate normally after a real crash (CONSTITUTION rule #1's one
 * necessary exception: the crash handler runs precisely because something already went wrong).
 */
internal class JvmCrashHandler(
    private val queue: EventQueue,
    private val seqProvider: () -> Long,
    private val contextProvider: () -> EventContext,
    private val appPackagePrefix: String,
    private val launchElapsedRealtimeMs: Long,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler(),
    /**
     * MOB-13: returns the ring-buffer snapshot to attach as `breadcrumbs` (`01` §4.5.1). The buffer
     * lives in this process's memory, so it is only meaningful here — the process that crashed is
     * the one writing the event. `null` (the default) attaches nothing.
     */
    private val breadcrumbsProvider: (() -> String)? = null
) : Thread.UncaughtExceptionHandler {

    private val json = Json { encodeDefaults = true }

    /** Call once at startup, after construction — installs this as the process-wide handler. */
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            writeCrashEvent(thread, throwable)
        } catch (e: Throwable) {
            // Never let our own reporting stand between the app and its normal crash handling.
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashEvent(thread: Thread, throwable: Throwable) {
        val threads = JvmStackTraceMapper.map(Thread.getAllStackTraces(), thread, appPackagePrefix)
        val ctx = contextProvider()
        // A failing snapshot must cost us the breadcrumbs, never the crash report itself.
        val crumbs = try { breadcrumbsProvider?.invoke() } catch (e: Throwable) { null }

        val attrs = buildJsonObject {
            put("crash_type", "exception")
            put("name", throwable::class.java.name)
            put("reason", throwable.message ?: "")
            put("is_fatal", true)
            put("threads", json.encodeToJsonElement(threads))
            put("binary_images", JsonArray(emptyList()))  // nothing to symbolicate for a JVM crash
            ctx.appState?.let { put("app_state", it) }
            put("time_since_launch_ms", elapsedRealtime() - launchElapsedRealtimeMs)
            crumbs?.let { putBreadcrumbSnapshot(it) }
        }

        val event = Event(
            eventId = UUID.randomUUID().toString(),
            type = EventType.CRASH,
            tsClient = Clock.nowIso8601(),
            seq = seqProvider(),
            attrs = attrs,
            ctx = ctx
        )
        queue.enqueue(event)
    }
}
