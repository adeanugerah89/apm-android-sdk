package edts.apm.android.sdk.crash

import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MOB-15b / `01` §4.7.
 *
 * Uses the [ExitInfo] seam rather than Robolectric's `addApplicationExitInfo` shadow — that
 * shadow throws IllegalAccessError on this Robolectric version (4.13) when it tries to
 * reflectively construct a real `ApplicationExitInfo`, a JDK module-access issue inside
 * Robolectric itself, unrelated to this code.
 *
 * `@Config(sdk = [30])` is pinned explicitly on every "should report" test — [TerminationDetector]
 * gates on `Build.VERSION.SDK_INT >= R`, and this project sets no `targetSdk`, so Robolectric's
 * un-pinned default isn't something to rely on being >= 30.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TerminationDetectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test fun `no exit info returns null`() {
        val detector = TerminationDetector(context, prefs("td_1"), fetchLatestExit = { null })
        assertNull(detector.check())
    }

    @Test fun `REASON_LOW_MEMORY is reported as memory_pressure`() {
        val detector = TerminationDetector(
            context, prefs("td_2"),
            fetchLatestExit = { ExitInfo(pid = 111, reason = ApplicationExitInfo.REASON_LOW_MEMORY) }
        )
        assertEquals("memory_pressure", detector.check())
    }

    @Test fun `an unmapped reason is not reported`() {
        val detector = TerminationDetector(
            context, prefs("td_3"),
            fetchLatestExit = { ExitInfo(pid = 222, reason = ApplicationExitInfo.REASON_USER_REQUESTED) }
        )
        assertNull(detector.check())
    }

    @Test fun `the same exit (same pid) is not reported twice across launches`() {
        val sharedPrefs = prefs("td_4")
        val fetch = { ExitInfo(pid = 333, reason = ApplicationExitInfo.REASON_LOW_MEMORY) }

        assertEquals("memory_pressure", TerminationDetector(context, sharedPrefs, fetch).check())
        // A fresh detector instance simulates the next app launch reading the same persisted state.
        assertNull(
            "must not re-report the same pid's exit on a later launch",
            TerminationDetector(context, sharedPrefs, fetch).check()
        )
    }

    @Test fun `a different pid after a reported one is reported again`() {
        val sharedPrefs = prefs("td_5")
        TerminationDetector(
            context, sharedPrefs,
            fetchLatestExit = { ExitInfo(pid = 1, reason = ApplicationExitInfo.REASON_LOW_MEMORY) }
        ).check()

        val second = TerminationDetector(
            context, sharedPrefs,
            fetchLatestExit = { ExitInfo(pid = 2, reason = ApplicationExitInfo.REASON_LOW_MEMORY) }
        ).check()

        assertEquals("memory_pressure", second)
    }

    @Config(sdk = [28])
    @Test fun `below API 30, always returns null regardless of exit info (ApplicationExitInfo unavailable)`() {
        val detector = TerminationDetector(
            context, prefs("td_6"),
            fetchLatestExit = { ExitInfo(pid = 444, reason = ApplicationExitInfo.REASON_LOW_MEMORY) }
        )
        assertNull(detector.check())
    }

    // ─── A52 ────────────────────────────────────────────────────────────────

    @Test @Config(sdk = [30]) fun `detect carries an event_id derived from the exit, stable across repeats (A52)`() {
        val fetch = { ExitInfo(pid = 555, reason = ApplicationExitInfo.REASON_LOW_MEMORY, timestamp = 1_788_000_000_000L) }
        val first = TerminationDetector(context, prefs("td_a52_1"), fetch).detect()!!
        // A cleared state file: the same exit is seen again.
        val again = TerminationDetector(context, prefs("td_a52_2"), fetch).detect()!!

        assertEquals("memory_pressure", first.reason)
        assertEquals(first.eventId, again.eventId)
        assertEquals(ReportEventId.forExit("termination", context.packageName, 555, 1_788_000_000_000L), first.eventId)
    }

    @Test @Config(sdk = [30]) fun `a recycled pid at another time is a different termination (A52)`() {
        val a = TerminationDetector(context, prefs("td_a52_3")) { ExitInfo(7, ApplicationExitInfo.REASON_LOW_MEMORY, 1_000L) }.detect()!!
        val b = TerminationDetector(context, prefs("td_a52_4")) { ExitInfo(7, ApplicationExitInfo.REASON_LOW_MEMORY, 2_000L) }.detect()!!
        assertNotEquals(a.eventId, b.eventId)
    }
}
