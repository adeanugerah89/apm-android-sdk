package edts.apm.android.sdk.queue

import edts.apm.android.sdk.core.DropReason
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.SdkHealth
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Moves the write off the calling thread (`02` §5: "Main thread: **Nol** operasi I/O blocking").
 *
 * A capture call used to run encryption, a directory listing and a file write inline, so an app
 * calling `APM.logError()` from a click listener blocked its own main thread. Measured before this
 * existed: 13 StrictMode violations from one `logError` (A12).
 *
 * **Ordering is preserved** — a single worker thread drains a FIFO, so events reach disk in the
 * order they were captured and `seq` stays meaningful.
 *
 * **What this trades away.** Write-local-first (CONSTITUTION) still holds against the *network* —
 * nothing is uploaded that was not written first. What is no longer guaranteed is that an event is
 * on disk by the time [enqueue] returns, so an event captured microseconds before a hard crash can
 * be lost. That is why this decorator is composed only around the paths that can afford it:
 * [edts.apm.android.sdk.manual.ManualApi], breadcrumbs, network capture and cold start. The crash
 * and termination paths write through the **synchronous** queue, because an event that exists
 * precisely to describe a dying process must not be queued behind a thread that will never run
 * again.
 *
 * The pending queue is bounded. An unbounded one would turn a burst of capture into unbounded heap
 * — the failure mode the 8 MB memory budget exists to prevent — so an overflow is dropped and
 * counted as [DropReason.QUEUE_FULL], visible in `sdk.health` (§2.3) like any other loss.
 */
class AsyncEventQueue(
    private val delegate: EventQueue,
    private val health: SdkHealth,
    maxPending: Int = MAX_PENDING
) : EventQueue {

    private val executor = ThreadPoolExecutor(
        1, 1,
        0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxPending),
        { runnable ->
            Thread(runnable, "apm-capture").apply {
                isDaemon = true              // must never hold the host app's process open
                priority = Thread.MIN_PRIORITY
            }
        },
        // Caller-side drop: the capture thread is behind, and blocking the caller here would
        // reintroduce exactly the stall this class removes.
        ThreadPoolExecutor.DiscardPolicy()
    )

    override fun enqueue(event: Event): Boolean = try {
        // Checked before submitting rather than inferred afterwards: DiscardPolicy drops silently,
        // and a health counter that guesses whether a drop happened is worse than no counter.
        if (executor.queue.remainingCapacity() == 0) {
            health.recordDropped(DropReason.QUEUE_FULL)
            false
        } else {
            executor.execute { delegate.enqueue(event) }
            true
        }
    } catch (e: RejectedExecutionException) {
        health.recordDropped(DropReason.QUEUE_FULL)
        false
    } catch (e: Exception) {
        false
    }

    // Reads and deletions run on the sync worker's own background thread already, and must see the
    // real state of the disk rather than a queued view of it — so they pass straight through.
    override fun peek(maxCount: Int): List<QueuedEvent> = delegate.peek(maxCount)

    override fun delete(ids: List<String>) = delegate.delete(ids)

    override fun stats(): QueueStats = delegate.stats()

    /**
     * Waits for queued writes to reach disk. For tests and for a deliberate flush before exit.
     *
     * Works by submitting a barrier and waiting for it: the executor is single-threaded FIFO, so
     * once the barrier runs, everything queued before it has finished. Polling the executor's own
     * queue instead looked simpler and was wrong — an empty work queue means the task was taken,
     * not that it completed, so the wait returned before the write had happened.
     */
    internal fun awaitDrain(timeoutMs: Long): Boolean = try {
        val barrier = CountDownLatch(1)
        executor.execute { barrier.countDown() }
        barrier.await(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        false
    }

    companion object {
        /** ~1 MB of pending events at a typical size, well inside the 8 MB budget (`02` §5). */
        const val MAX_PENDING = 512
    }
}
