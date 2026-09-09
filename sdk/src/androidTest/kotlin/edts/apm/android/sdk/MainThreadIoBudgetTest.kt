package edts.apm.android.sdk

import android.os.Looper
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * `02` §5: "Main thread: **Nol** operasi I/O blocking; seluruh pekerjaan di background
 * queue/thread." Also a CONSTITUTION prohibition.
 *
 * StrictMode is the only honest way to check this: it is the platform's own definition of a
 * blocking main-thread operation, it catches disk and network reached through any layer (including
 * inside SharedPreferences, the Keystore, or OkHttp), and it cannot be fooled by a fast emulator
 * the way a stopwatch can.
 *
 * Violations are collected through a penalty listener rather than `penaltyDeath()` so the test can
 * report *what* was violated instead of just dying.
 */
@RunWith(AndroidJUnit4::class)
class MainThreadIoBudgetTest {

    private lateinit var originalPolicy: StrictMode.ThreadPolicy

    @Before fun setUp() {
        originalPolicy = StrictMode.getThreadPolicy()
        // The SDK must actually be running, or every call below is a documented no-op and the test
        // passes without touching a single line of the code it claims to measure.
        startSdkOffTheMainThread()
        assertTrue("SDK must be started for this test to mean anything", APM.isStarted)
    }

    @After fun tearDown() {
        StrictMode.setThreadPolicy(originalPolicy)
    }

    /**
     * The call an adopting app makes most often, and the one most likely to be on the main thread:
     * a handled error reported from a click listener or a catch block in UI code.
     */
    @Test fun logErrorDoesNoBlockingIoOnTheMainThread() {
        val violations = runOnMainThreadWithStrictMode {
            APM.logError(IllegalStateException("budget probe"))
            APM.breadcrumb("budget probe", edts.apm.android.sdk.breadcrumb.BreadcrumbCategory.NAVIGATION)
            APM.setUser("budget-probe-user")
        }

        assertEquals(
            "`02` §5 allows zero blocking main-thread I/O. Violations:\n" +
                violations.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            violations
        )
    }

    /**
     * `APM.start()` is called from `Application.onCreate()`, which **is** the main thread. Whatever
     * it does there counts against `02` §5 like any other main-thread work.
     */
    @Test fun apmStartDoesNoBlockingIoOnTheMainThread() {
        val violations = runOnMainThreadWithStrictMode {
            // Already started in setUp(); a second call is the idempotent path a real app never
            // hits, so start a *fresh* graph instead to measure the real cost.
            edts.apm.android.sdk.internal.ApmRuntime.reset()
            APM.start(
                InstrumentationRegistry.getInstrumentation().targetContext,
                testConfig()
            )
        }

        assertEquals(
            "`02` §5 allows zero blocking main-thread I/O. Violations during APM.start():\n" +
                violations.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            violations
        )
    }

    /**
     * Negative control: a deliberate main-thread disk write from inside an SDK-named frame must be
     * caught. Without this, a green result from the tests above could mean "no violations" or
     * "detector broken", and those are not the same thing.
     */
    @Test fun theDetectorActuallyDetects() {
        val violations = runOnMainThreadWithStrictMode {
            edts.apm.android.sdk.internal.BudgetProbe.writeAFileDeliberately(
                InstrumentationRegistry.getInstrumentation().targetContext
            )
        }
        assertTrue("StrictMode harness detected nothing at all — it cannot be trusted", violations.isNotEmpty())
    }

    private fun testConfig() = ApmConfig(
        appId = "edts.apm.android.sdk.test",
        appVersion = "1.0",
        appBuild = "1",
        ingestUrl = "https://ingest.invalid.example.com/v1/ingest",
        appKey = "budget-probe-key"
    )

    private fun startSdkOffTheMainThread() {
        if (APM.isStarted) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        Thread { APM.start(context, testConfig()); latch.countDown() }.start()
        latch.await(30, TimeUnit.SECONDS)
    }

    /**
     * Runs [block] on the main thread with a StrictMode policy that records — rather than
     * crashes on — disk and network access, and returns the violations it saw.
     */
    private fun runOnMainThreadWithStrictMode(block: () -> Unit): List<String> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val collected = java.util.Collections.synchronizedList(mutableListOf<String>())

        instrumentation.runOnMainSync {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyListener({ r -> r.run() }) { violation ->
                        val frame = violation.stackTrace.orEmpty()
                            .firstOrNull { it.className.startsWith("edts.apm.android.sdk") }
                        collected.add(
                            if (frame != null) {
                                violation::class.java.simpleName + " at " + frame.className + "." +
                                    frame.methodName + "(" + frame.fileName + ":" + frame.lineNumber + ")"
                            } else {
                                "OTHER:" + violation::class.java.simpleName
                            }
                        )
                    }
                    .build()
            )
            assertSame("must actually be the main thread", Looper.getMainLooper().thread, Thread.currentThread())
            block()
        }

        // Violations are delivered on the looper, after the runnable that caused them returns —
        // so the policy must stay installed until they have drained. Restoring it inside the
        // measured runnable dropped every pending violation, which is why the negative control
        // caught nothing on the first attempt.
        Thread.sleep(1_000)
        instrumentation.runOnMainSync { StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX) }

        val sdkViolations = collected.filterNot { it.startsWith("OTHER:") }.distinct()
        android.util.Log.i("APM-BUDGET", "all violations: " + collected.toList())
        return sdkViolations
    }
}
