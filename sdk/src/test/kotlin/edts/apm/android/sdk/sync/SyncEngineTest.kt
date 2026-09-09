package edts.apm.android.sdk.sync

import edts.apm.android.sdk.core.AppInfo
import edts.apm.android.sdk.core.DeviceInfo
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.core.IntegrityInfo
import edts.apm.android.sdk.core.SdkHealth
import edts.apm.android.sdk.core.UserIdSource
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** SyncEngine orchestration: peek -> upload -> act (MOB-07/09/10). */
class SyncEngineTest {

    private class FakeQueue(initial: Int) : EventQueue {
        val events = (1..initial).map {
            QueuedEvent(
                id = "id-$it",
                event = Event(
                    eventId = UUID.randomUUID().toString(),
                    type = EventType.ERROR,
                    tsClient = "2026-09-07T00:00:00.000Z",
                    seq = it.toLong(),
                    attrs = JsonObject(emptyMap()),
                    ctx = EventContext()
                ),
                sizeBytes = 10
            )
        }.toMutableList()

        var lastPeekMaxCount: Int? = null
        val deletedIds = mutableListOf<List<String>>()

        override fun enqueue(event: Event) = true
        override fun peek(maxCount: Int): List<QueuedEvent> {
            lastPeekMaxCount = maxCount
            return events.take(maxCount)
        }
        override fun delete(ids: List<String>) {
            deletedIds.add(ids)
            events.removeAll { it.id in ids }
        }
        override fun stats() = QueueStats(events.size, 0)
    }

    private class FakeUploader(private val results: MutableList<UploadResult>) : Uploader {
        val callCount = AtomicInteger(0)
        override fun upload(envelope: edts.apm.android.sdk.core.Envelope): UploadResult {
            callCount.incrementAndGet()
            return results.removeAt(0)
        }
    }

    private fun staticContext() = EnvelopeStaticContext(
        sdkName = "apm-android-sdk",
        sdkVersion = "0.1.0",
        app = AppInfo("edts.apm.android.sdk", "1.0", "100"),
        device = DeviceInfo(osVersion = "14", model = "Pixel 9", locale = "id_ID", timezone = "Asia/Jakarta"),
        integrity = IntegrityInfo(),
        installId = "install-1",
        sessionId = "session-1",
        userId = "user-1",
        userIdSource = UserIdSource.HOST
    )

    private var fakeNow = 0L

    private fun engine(queue: FakeQueue, results: MutableList<UploadResult>, health: SdkHealth = SdkHealth()) =
        Triple(
            SyncEngine(queue, FakeUploader(results), health, ::staticContext, clock = { fakeNow }),
            queue,
            health
        )

    @Test fun `the kill switch suppresses the upload without touching the queue`() {
        val queue = FakeQueue(3)
        val uploader = FakeUploader(mutableListOf(UploadResult.HttpResponse(202, null)))
        val health = SdkHealth()
        val killed = SyncEngine(queue, uploader, health, ::staticContext, clock = { fakeNow }, isEnabled = { false })

        assertEquals(SyncOutcome.KillSwitched, killed.syncOnce())

        assertEquals(3, queue.events.size)          // backlog untouched — MOB-21 stops sending, not data
        assertTrue(queue.deletedIds.isEmpty())
        assertEquals(0L, health.snapshot().sent)
        assertEquals(0L, health.snapshot().dropped)  // a deliberate kill is not a data loss (§2.3)
    }

    @Test fun `the kill switch is re-read per attempt so re-enabling flushes the backlog`() {
        val queue = FakeQueue(2)
        val uploader = FakeUploader(mutableListOf(UploadResult.HttpResponse(202, null)))
        var enabled = false
        val engine = SyncEngine(queue, uploader, SdkHealth(), ::staticContext, clock = { fakeNow }, isEnabled = { enabled })

        assertEquals(SyncOutcome.KillSwitched, engine.syncOnce())
        enabled = true
        assertEquals(SyncOutcome.Sent(2), engine.syncOnce())
    }

    @Test fun `empty queue is idle and never calls the uploader`() {
        val (engine, _, _) = engine(FakeQueue(0), mutableListOf())
        assertEquals(SyncOutcome.Idle, engine.syncOnce())
    }

    @Test fun `202 deletes the batch and records sent count`() {
        val (engine, queue, health) = engine(
            FakeQueue(3),
            mutableListOf(UploadResult.HttpResponse(202, null))
        )

        val outcome = engine.syncOnce()

        assertEquals(SyncOutcome.Sent(3), outcome)
        assertEquals(listOf(listOf("id-1", "id-2", "id-3")), queue.deletedIds)
        assertEquals(3L, health.snapshot().sent)
        assertTrue(queue.events.isEmpty())
    }

    @Test fun `400 drops the batch and records it as rejected`() {
        val (engine, queue, health) = engine(
            FakeQueue(2),
            mutableListOf(UploadResult.HttpResponse(400, null))
        )

        val outcome = engine.syncOnce()

        assertEquals(SyncOutcome.Dropped(2), outcome)
        assertTrue(queue.events.isEmpty())
        assertEquals(2L, health.snapshot().dropped)
        assertEquals(2L, health.snapshot().dropReasons["rejected"])
    }

    @Test fun `401 disables sending and does not touch the queue`() {
        val (engine, queue, _) = engine(
            FakeQueue(1),
            mutableListOf(UploadResult.HttpResponse(401, null))
        )

        val outcome = engine.syncOnce()

        assertEquals(SyncOutcome.Disabled, outcome)
        assertTrue(queue.deletedIds.isEmpty())
        assertEquals(1, queue.events.size)
    }

    @Test fun `stays disabled until the cooldown elapses, without calling the uploader again`() {
        val results = mutableListOf<UploadResult>(UploadResult.HttpResponse(401, null))
        val queue = FakeQueue(1)
        val uploader = FakeUploader(results)
        val health = SdkHealth()
        val syncEngine = SyncEngine(queue, uploader, health, ::staticContext, clock = { fakeNow })

        assertEquals(SyncOutcome.Disabled, syncEngine.syncOnce())
        assertEquals(1, uploader.callCount.get())

        fakeNow += 60_000L  // still well within the 24h cooldown
        assertEquals(SyncOutcome.Disabled, syncEngine.syncOnce())
        assertEquals("must not re-attempt while disabled", 1, uploader.callCount.get())

        fakeNow += SyncAction.DISABLE_DURATION_MS  // cooldown elapsed
        results.add(UploadResult.HttpResponse(202, null))
        assertEquals(SyncOutcome.Sent(1), syncEngine.syncOnce())
    }

    @Test fun `413 halves the batch size for the next peek and deletes nothing`() {
        val (engine, queue, _) = engine(
            FakeQueue(10),
            mutableListOf(UploadResult.HttpResponse(413, null), UploadResult.HttpResponse(202, null))
        )

        val outcome = engine.syncOnce()

        assertEquals(SyncOutcome.SplitApplied, outcome)
        assertTrue(outcome.shouldContinueImmediately)
        assertTrue(queue.deletedIds.isEmpty())
        assertEquals(10, queue.events.size)
        assertEquals(EventQueue.MAX_BATCH, queue.lastPeekMaxCount)  // this call's peek used the pre-split size

        engine.syncOnce()  // caller retries immediately per shouldContinueImmediately

        assertEquals(EventQueue.MAX_BATCH / 2, queue.lastPeekMaxCount)  // the halved size takes effect here
    }

    @Test fun `5xx backs off exponentially across consecutive failures`() {
        val (engine, _, _) = engine(
            FakeQueue(1),
            mutableListOf(
                UploadResult.HttpResponse(503, null),
                UploadResult.HttpResponse(503, null)
            )
        )

        val first = engine.syncOnce() as SyncOutcome.RetryAfter
        assertEquals(BackoffPolicy.delayFor(1), first.delayMs)
        assertFalse(first.shouldContinueImmediately)

        val second = engine.syncOnce() as SyncOutcome.RetryAfter
        assertEquals(BackoffPolicy.delayFor(2), second.delayMs)
    }

    @Test fun `a successful send after failures resets backoff and batch size`() {
        val (engine, queue, _) = engine(
            FakeQueue(1),
            mutableListOf(
                UploadResult.HttpResponse(503, null),
                UploadResult.HttpResponse(202, null),
                UploadResult.HttpResponse(503, null)
            )
        )

        engine.syncOnce()  // 503 -> consecutiveFailures = 1
        engine.syncOnce()  // 202 -> resets

        queue.events.add(
            QueuedEvent(
                id = "id-new",
                event = Event("e2", EventType.ERROR, "2026-09-07T00:00:01.000Z", 2L, JsonObject(emptyMap()), EventContext()),
                sizeBytes = 5
            )
        )
        val third = engine.syncOnce() as SyncOutcome.RetryAfter  // fresh 503, should be attempt 1 again
        assertEquals(BackoffPolicy.delayFor(1), third.delayMs)
    }
}
