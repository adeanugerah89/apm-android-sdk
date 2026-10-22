package edts.apm.android.sdk.stability

import android.os.Handler
import android.os.Looper

/**
 * The main thread, as far as [AnrWatchdog] needs to know about it. Extracted as an interface
 * purely so the watchdog's decision logic is testable without a real Looper — Robolectric's main
 * looper is driven by the test thread, so a genuinely blocked main thread cannot be simulated
 * through it.
 */
internal interface MainThreadProbe {
    val mainThread: Thread

    /** Enqueues [action] on the main thread. Returns false when the queue refused it. */
    fun post(action: () -> Unit): Boolean

    /** Stack traces of all live threads, used to explain what the main thread is stuck on. */
    fun allStackTraces(): Map<Thread, Array<StackTraceElement>> = Thread.getAllStackTraces()
}

/** Real implementation, backed by the main [Looper]. */
internal class HandlerMainThreadProbe(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    override val mainThread: Thread = Looper.getMainLooper().thread
) : MainThreadProbe {
    override fun post(action: () -> Unit): Boolean = handler.post(action)
}
