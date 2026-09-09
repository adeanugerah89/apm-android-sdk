package edts.apm.android.sdk.sync

import org.junit.Assert.*
import org.junit.Test

class BackoffPolicyTest {

    @Test fun `first attempt is 30 seconds`() {
        assertEquals(30_000L, BackoffPolicy.delayFor(1))
    }

    @Test fun `second attempt doubles to 60 seconds`() {
        assertEquals(60_000L, BackoffPolicy.delayFor(2))
    }

    @Test fun `third attempt doubles to 120 seconds`() {
        assertEquals(120_000L, BackoffPolicy.delayFor(3))
    }

    @Test fun `caps at 30 minutes`() {
        assertEquals(30 * 60 * 1000L, BackoffPolicy.delayFor(10))
        assertEquals(30 * 60 * 1000L, BackoffPolicy.delayFor(1000))
    }

    @Test fun `never exceeds max even for huge attempt numbers (no overflow)`() {
        val delay = BackoffPolicy.delayFor(Int.MAX_VALUE)
        assertTrue(delay in 1..BackoffPolicy.MAX_DELAY_MS)
    }

    @Test fun `attempt zero or negative behaves like attempt one`() {
        assertEquals(30_000L, BackoffPolicy.delayFor(0))
        assertEquals(30_000L, BackoffPolicy.delayFor(-5))
    }
}
