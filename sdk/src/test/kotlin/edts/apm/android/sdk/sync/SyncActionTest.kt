package edts.apm.android.sdk.sync

import org.junit.Assert.*
import org.junit.Test

/** `01` §7 response contract table. */
class SyncActionTest {

    @Test fun `202 deletes the batch`() {
        assertEquals(SyncAction.DeleteBatch, SyncAction.forResponse(202, null, attempt = 1))
    }

    @Test fun `400 drops the batch instead of retrying forever`() {
        val action = SyncAction.forResponse(400, null, attempt = 1)
        assertTrue(action is SyncAction.DropBatch)
    }

    @Test fun `401 disables sending for 24 hours`() {
        val action = SyncAction.forResponse(401, null, attempt = 1) as SyncAction.DisableSending
        assertEquals(24 * 60 * 60 * 1000L, action.durationMs)
    }

    @Test fun `403 disables sending for 24 hours`() {
        val action = SyncAction.forResponse(403, null, attempt = 1) as SyncAction.DisableSending
        assertEquals(24 * 60 * 60 * 1000L, action.durationMs)
    }

    @Test fun `413 splits and retries`() {
        assertEquals(SyncAction.SplitAndRetry, SyncAction.forResponse(413, null, attempt = 1))
    }

    @Test fun `429 with Retry-After header honors the header over exponential backoff`() {
        val action = SyncAction.forResponse(429, retryAfterSeconds = 120, attempt = 1) as SyncAction.Backoff
        assertEquals(120_000L, action.delayMs)
    }

    @Test fun `429 without Retry-After falls back to exponential backoff`() {
        val action = SyncAction.forResponse(429, retryAfterSeconds = null, attempt = 2) as SyncAction.Backoff
        assertEquals(BackoffPolicy.delayFor(2), action.delayMs)
    }

    @Test fun `5xx backs off exponentially by attempt`() {
        val action = SyncAction.forResponse(503, null, attempt = 3) as SyncAction.Backoff
        assertEquals(BackoffPolicy.delayFor(3), action.delayMs)
    }

    @Test fun `unrecognized status code fails safe into backoff, not deletion`() {
        val action = SyncAction.forResponse(418, null, attempt = 1)
        assertTrue("must never delete data on an unrecognized response", action is SyncAction.Backoff)
    }

    @Test fun `transport failure is treated like a 5xx`() {
        val action = SyncAction.forTransportFailure(attempt = 4)
        assertEquals(SyncAction.Backoff(BackoffPolicy.delayFor(4)), action)
    }
}
