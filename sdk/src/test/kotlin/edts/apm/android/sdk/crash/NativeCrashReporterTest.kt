package edts.apm.android.sdk.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowApplicationExitInfo
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream

/**
 * A35 — the real [NativeCrashReporter], fed the **real** system tombstone fixture through
 * `ApplicationExitInfo`, must emit a `crash` whose `ts_client` the backend can parse.
 *
 * The parser and the timestamp converter each had tests; nothing exercised the reporter that joins
 * them, which is how the raw `2026-09-09 11:14:38.641294490+0700` could be sent as `ts_client`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NativeCrashReporterTest {

    private class RecordingQueue : EventQueue {
        val events = mutableListOf<Event>()
        override fun enqueue(event: Event): Boolean { events.add(event); return true }
        override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
        override fun delete(ids: List<String>) {}
        override fun stats() = QueueStats(events.size, 0)
    }

    private lateinit var context: Context
    private lateinit var queue: RecordingQueue

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        queue = RecordingQueue()
        context.getSharedPreferences("native-crash-test", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun tombstoneBytes(): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("tombstones/system-tombstone-api33-segv.pb")) {
            "missing tombstone fixture"
        }.readBytes()

    /** Registers one native-crash exit record whose trace stream is the real fixture. */
    private fun registerNativeCrash(trace: ByteArray, pid: Int = 4321, exitTimeMs: Long = 1_788_000_000_000L) {
        val am = context.getSystemService(ActivityManager::class.java)
        Shadow.extract<ShadowActivityManager>(am).addApplicationExitInfo(
            context.packageName, pid, ApplicationExitInfo.REASON_CRASH_NATIVE, 11
        )
        val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 10).single { it.pid == pid }
        Shadow.extract<ShadowApplicationExitInfo>(info).setTraceInputStream(ByteArrayInputStream(trace))
        // The shadow leaves the exit time at 0, which the reporter's dedup watermark treats as already reported.
        ReflectionHelpers.setField(info, "mTimestamp", exitTimeMs)
    }

    private fun reporter(crashedSessionId: String? = null) = NativeCrashReporter(
        context = context,
        prefs = context.getSharedPreferences("native-crash-test", Context.MODE_PRIVATE),
        queue = queue,
        seqProvider = { 1L },
        contextProvider = { EventContext() },
        crashedSessionId = crashedSessionId,
        crashedIdentityEpoch = null
    )

    @Test fun `a native crash is emitted with a ts_client the backend can parse`() {
        registerNativeCrash(tombstoneBytes())

        assertEquals(1, reporter().reportPending())

        val event = queue.events.single()
        assertEquals(EventType.CRASH, event.type)
        assertEquals("SIGSEGV", event.attrs.getValue("name").jsonPrimitive.content)
        // The tombstone's own time, converted: not the raw "2026-09-09 11:14:38.641294490+0700".
        assertEquals("2026-09-09T04:14:38.641Z", event.tsClient)
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""").matches(event.tsClient))
    }

    @Test fun `a native crash never carries the new process's breadcrumbs (A27)`() {
        registerNativeCrash(tombstoneBytes())

        reporter().reportPending()

        assertFalse(queue.events.single().attrs.containsKey("breadcrumbs"))
    }

    // ─── A28 · which session the crash belonged to ──────────────────────────────

    @Test fun `the newest exit record is filed under the session the previous process was in`() {
        registerNativeCrash(tombstoneBytes())

        reporter(crashedSessionId = "session-that-crashed").reportPending()

        assertEquals("session-that-crashed", queue.events.single().sessionId)
    }

    @Test fun `with no remembered session the crash is left unstamped, to fall back to the current one`() {
        registerNativeCrash(tombstoneBytes())

        reporter(crashedSessionId = null).reportPending()

        assertEquals(null, queue.events.single().sessionId)
    }

    @Test fun `an older unreported crash is NOT given the previous session - nobody remembers its own`() {
        registerNativeCrash(tombstoneBytes(), pid = 100, exitTimeMs = 1_788_000_000_000L)   // older
        registerNativeCrash(tombstoneBytes(), pid = 200, exitTimeMs = 1_788_000_900_000L)   // the process that just died

        assertEquals(2, reporter(crashedSessionId = "previous").reportPending())

        val bySession = queue.events.map { it.sessionId }
        assertEquals("exactly one crash carries the remembered session", 1, bySession.count { it == "previous" })
        assertEquals("the other stays unrecorded rather than wrong", 1, bySession.count { it == null })
    }

    // ─── A39 · which app version crashed ────────────────────────────────────────

    @Test fun `the newest crash is filed under the app version and OS the previous process was running`() {
        registerNativeCrash(tombstoneBytes())
        val previous = edts.apm.android.sdk.core.AppStamp("1.0", "100", "13")

        NativeCrashReporter(
            context = context, prefs = context.getSharedPreferences("native-crash-test", Context.MODE_PRIVATE),
            queue = queue, seqProvider = { 1L }, contextProvider = { EventContext() }, crashedApp = previous, crashedIdentityEpoch = null
        ).reportPending()

        val e = queue.events.single()
        assertEquals(Triple("1.0", "100", "13"), Triple(e.appVersion, e.appBuild, e.osVersion))
    }

    @Test fun `with no remembered app facts the crash records none and falls back to the current ones`() {
        registerNativeCrash(tombstoneBytes())

        reporter().reportPending()

        assertEquals(null, queue.events.single().appVersion)
    }

    @Test fun `the newest crash is filed under the identity epoch the previous process ended in (A39)`() {
        registerNativeCrash(tombstoneBytes())

        NativeCrashReporter(
            context = context, prefs = context.getSharedPreferences("native-crash-test", Context.MODE_PRIVATE),
            queue = queue, seqProvider = { 1L }, contextProvider = { EventContext() }, crashedIdentityEpoch = 5
        ).reportPending()

        assertEquals(5, queue.events.single().identityEpoch)
    }
}
