package edts.apm.android.sdk.sync

import edts.apm.android.sdk.core.DropReason
import edts.apm.android.sdk.core.SdkHealth
import edts.apm.android.sdk.queue.EventQueue

sealed class SyncOutcome {
    /** Queue was empty — nothing to send. */
    object Idle : SyncOutcome()

    /** Still inside a 401/403 cooldown window (`01` §7) — did not attempt an upload. */
    object Disabled : SyncOutcome()

    /** Remote config says `enabled: false` (MOB-21) — did not attempt an upload. */
    object KillSwitched : SyncOutcome()

    /** 202 — batch accepted and deleted from disk (MOB-09). */
    data class Sent(val count: Int) : SyncOutcome()

    /** 400 — batch discarded; it would never have been accepted (`01` §7). */
    data class Dropped(val count: Int) : SyncOutcome()

    /** 429/5xx/transport failure — data stays on disk, caller should retry after [delayMs]. */
    data class RetryAfter(val delayMs: Long) : SyncOutcome()

    /** 413 — batch size halved; nothing was deleted, safe to call [SyncEngine.syncOnce] again. */
    object SplitApplied : SyncOutcome()

    /** True when more work may be immediately pending and the caller should call syncOnce() again. */
    val shouldContinueImmediately: Boolean get() = this is Sent || this is Dropped || this is SplitApplied
}

/**
 * One sync attempt: peek -> upload -> act, per the `01` §7 response contract (MOB-07/09).
 *
 * [uploader] MUST wrap an OkHttpClient that is separate from and uninstrumented by
 * [edts.apm.android.sdk.network.ApmEventListener] (MOB-10) — enforced by construction at the
 * composition root (A16), not by this class.
 *
 * Not safe for concurrent [syncOnce] calls — a scheduler must serialize invocations. Backoff
 * state (`consecutiveFailures`, `disabledUntil`, `batchSize`) is in-memory only and resets on
 * process death, which is acceptable: a fresh process starts optimistic and re-learns backoff
 * from the first response, same as MOB-07 requires no more than.
 */
class SyncEngine(
    private val queue: EventQueue,
    private val uploader: Uploader,
    private val health: SdkHealth,
    private val contextProvider: () -> EnvelopeStaticContext,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Kill switch (MOB-21). Checked on every attempt rather than captured once, so a config
     * refresh takes effect at the next sync without rebuilding the engine. Nothing is deleted
     * while killed: the queue keeps its backlog and flushes it if the switch is turned back on.
     */
    private val isEnabled: () -> Boolean = { true }
) {
    @Volatile private var batchSize: Int = EventQueue.MAX_BATCH
    @Volatile private var consecutiveFailures: Int = 0
    @Volatile private var disabledUntil: Long = 0L

    fun syncOnce(): SyncOutcome {
        if (!isEnabled()) return SyncOutcome.KillSwitched
        if (clock() < disabledUntil) return SyncOutcome.Disabled

        val batch = queue.peek(batchSize)
        if (batch.isEmpty()) return SyncOutcome.Idle

        val envelope = buildEnvelope(contextProvider(), health.snapshot(), batch)
        val action = when (val result = uploader.upload(envelope)) {
            is UploadResult.HttpResponse -> SyncAction.forResponse(
                statusCode = result.statusCode,
                retryAfterSeconds = result.retryAfterSeconds,
                attempt = consecutiveFailures + 1
            )
            is UploadResult.TransportFailure -> SyncAction.forTransportFailure(consecutiveFailures + 1)
        }

        return apply(action, batch.map { it.id })
    }

    private fun apply(action: SyncAction, ids: List<String>): SyncOutcome = when (action) {
        is SyncAction.DeleteBatch -> {
            queue.delete(ids)
            health.recordSent(ids.size)
            onSuccess()
            SyncOutcome.Sent(ids.size)
        }
        is SyncAction.DropBatch -> {
            queue.delete(ids)
            health.recordDropped(DropReason.REJECTED, count = ids.size)
            onSuccess()  // a resolved outcome either way — not a failure to back off from
            SyncOutcome.Dropped(ids.size)
        }
        is SyncAction.DisableSending -> {
            disabledUntil = clock() + action.durationMs
            SyncOutcome.Disabled
        }
        is SyncAction.SplitAndRetry -> {
            batchSize = (batchSize / 2).coerceAtLeast(1)
            SyncOutcome.SplitApplied
        }
        is SyncAction.Backoff -> {
            consecutiveFailures += 1
            SyncOutcome.RetryAfter(action.delayMs)
        }
    }

    private fun onSuccess() {
        consecutiveFailures = 0
        batchSize = EventQueue.MAX_BATCH
    }
}
