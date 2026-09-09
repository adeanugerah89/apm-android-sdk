package edts.apm.android.sdk.sync

import org.junit.Assert.*
import org.junit.Test

/** Pure decision extracted from SyncWorker.doWork() — no WorkManager/Robolectric needed. */
class SyncWorkerDelayTest {

    @Test fun `retry-after outcome overrides the configured interval`() {
        val delay = SyncWorker.nextDelayFor(SyncOutcome.RetryAfter(90_000L), uploadIntervalMs = 30_000L)
        assertEquals(90_000L, delay)
    }

    @Test fun `idle falls back to the configured upload interval`() {
        val delay = SyncWorker.nextDelayFor(SyncOutcome.Idle, uploadIntervalMs = 30_000L)
        assertEquals(30_000L, delay)
    }

    @Test fun `disabled falls back to the configured upload interval`() {
        val delay = SyncWorker.nextDelayFor(SyncOutcome.Disabled, uploadIntervalMs = 45_000L)
        assertEquals(45_000L, delay)
    }

    @Test fun `a leftover split-applied outcome (iteration cap hit) still uses the interval`() {
        val delay = SyncWorker.nextDelayFor(SyncOutcome.SplitApplied, uploadIntervalMs = 30_000L)
        assertEquals(30_000L, delay)
    }
}
