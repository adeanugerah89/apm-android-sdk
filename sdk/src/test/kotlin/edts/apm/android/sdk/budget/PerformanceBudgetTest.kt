package edts.apm.android.sdk.budget

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.core.SdkHealth
import edts.apm.android.sdk.queue.DiskEventQueue
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.sync.SyncScheduler
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * `02` §5 — the performance budget lines that can be checked without a device.
 *
 * The size budget is enforced by the `:sdk:checkPerformanceBudget` Gradle task, not here.
 * Cold start p95, CPU, resident memory and "zero blocking I/O on the main thread" are **not**
 * covered anywhere yet: each needs the SDK running on a device under load, and approximating
 * them on a JVM would produce a green check that means nothing. They are named as open gaps on
 * A12 in FEATURES.md.
 */
@RunWith(RobolectricTestRunner::class)
class PerformanceBudgetTest {

    @get:Rule val tmp = TemporaryFolder()

    // ─── Disk ≤ 20 MB ───────────────────────────────────────────────────────

    @Test fun `the disk budget constant matches the documented 20 MB`() {
        assertEquals(20L * 1024 * 1024, EventQueue.MAX_BYTES)
    }

    @Test fun `the queue enforces its byte ceiling rather than merely declaring it`() {
        val queueDir = tmp.newFolder("queue")
        val health = SdkHealth()
        // A ceiling small enough to hit quickly, exercising the same eviction path as 20 MB.
        val queue = DiskEventQueue(queueDir, health, maxBytes = 4_096L)

        repeat(200) { queue.enqueue(event(it.toLong())) }

        val bytesOnDisk = queueDir.listFiles()!!.filter { it.name.endsWith(".evt") }.sumOf { it.length() }
        assertTrue("queue grew past its ceiling: $bytesOnDisk", bytesOnDisk <= 4_096L)
        assertTrue("eviction must be visible in sdk.health", health.snapshot().dropped > 0)
    }

    // ─── Offline retention ≥ 7 days ─────────────────────────────────────────

    @Test fun `nothing is ever deleted for being old — retention is bounded by capacity, not time`() {
        val queueDir = tmp.newFolder("queue")
        val queue = DiskEventQueue(queueDir, SdkHealth())
        queue.enqueue(event(1))

        // Backdate the file well past the 7-day floor `02` §5 requires.
        val eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        queueDir.listFiles()!!.single { it.name.endsWith(".evt") }.setLastModified(eightDaysAgo)

        // A fresh queue over the same directory is what a relaunch looks like.
        val reopened = DiskEventQueue(queueDir, SdkHealth())
        assertEquals("an 8-day-old event must survive", 1, reopened.peek(10).size)
        assertEquals(1, reopened.stats().eventCount)
    }

    // ─── Battery: no dedicated wakeup ───────────────────────────────────────

    /**
     * The SDK's **own** manifest asks for nothing beyond what an uploader needs. Asserted against
     * the source manifest, because the merged one also carries WorkManager's permissions — see the
     * next test, which is where that fact is pinned down rather than hidden.
     */
    @Test fun `the SDK itself declares only INTERNET and ACCESS_NETWORK_STATE`() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("expected to run with the module dir as CWD", manifest.exists())

        val declared = Regex("android:name=\"(android\\.permission\\.[A-Z_]+)\"")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"),
            declared
        )
    }

    /**
     * Pins the permissions the SDK forces onto every host app **transitively**, via WorkManager.
     *
     * This is not an approval of them. `02` §5 asks for no dedicated background wakeup, and
     * `RECEIVE_BOOT_COMPLETED` is precisely such a mechanism — WorkManager uses it to restore
     * pending jobs after a reboot. The SDK never asks for it and never uses it, but a host app's
     * store listing will show it, and adopters will ask.
     *
     * The test exists so that set can never grow without someone noticing in CI. If it fails,
     * the answer is a decision in CONSTITUTION.md about the dependency — not a wider expectation.
     */
    @Test fun `WorkManager's transitive permissions are exactly the known set, and no more`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            ?: emptySet()

        // Only platform permissions matter here; androidx test infra injects an app-scoped one.
        val transitive = requested.filter { it.startsWith("android.permission.") }.toSet() - setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE"
        )

        assertEquals(
            "WorkManager's permission footprint changed — decide, do not widen this expectation",
            setOf(
                "android.permission.WAKE_LOCK",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.REORDER_TASKS"
            ),
            transitive
        )
    }

    /**
     * A source-level guard, and honest about being one: it proves no scheduling API of this kind is
     * *written* in the SDK, not that none is reached at runtime. That is still the check worth
     * having — every one of these arrives by someone typing it, and it would otherwise be caught
     * only by a reviewer noticing.
     *
     * Comments are stripped first. Without that it flags [SyncScheduler], whose KDoc explains why
     * `PeriodicWorkRequest`'s 15-minute floor is unusable here — prose about avoiding a thing read
     * as use of it.
     */
    @Test fun `the SDK schedules no periodic or exact-alarm work of its own`() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("expected to run with the module dir as CWD", sources.isNotEmpty())

        val banned = listOf("AlarmManager", "PeriodicWorkRequest", "setExactAndAllowWhileIdle")
        val offenders = sources
            .filter { file -> banned.any { stripComments(file.readText()).contains(it) } }
            .map { it.path }

        assertEquals(
            "`02` §5: upload rides the app's lifecycle, no dedicated wakeup",
            emptyList<String>(),
            offenders
        )
    }

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\\*.*?\\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test fun `sync work rides the app's lifecycle — network-constrained, never idle-or-charging forced`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).build()
        )

        SyncScheduler.scheduleNext(context, delayMs = 60_000L)

        val info = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK_NAME)
            .get()
            .single()

        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertFalse("charging-only would stall telemetry, not save battery", info.constraints.requiresCharging())
        assertFalse(info.constraints.requiresDeviceIdle())
    }

    private fun event(seq: Long) = Event(
        eventId = UUID.randomUUID().toString(),
        type = EventType.ERROR,
        tsClient = "2026-09-07T00:00:00.000Z",
        seq = seq,
        attrs = JsonObject(emptyMap()),
        ctx = EventContext()
    )
}
