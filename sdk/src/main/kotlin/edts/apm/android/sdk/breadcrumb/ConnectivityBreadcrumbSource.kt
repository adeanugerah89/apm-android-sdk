package edts.apm.android.sdk.breadcrumb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Automatic connectivity-change breadcrumbs (MOB-12) — separate registration from
 * [edts.apm.android.sdk.sync.ConnectivityWatcher] (A5), which exists to trigger a sync rather
 * than to record history; keeping them independent avoids coupling two unrelated concerns to
 * one callback's lifecycle.
 *
 * A breadcrumb is written only when the state a person would describe changes: reachable or not, or
 * which kind of interface carries it (`wifi`, `cellular`, `wired`, `other`). `ConnectivityManager` calls the
 * default-network callback far more often than that (capabilities, signal, validation, the old network's loss
 * after the new one is up), and writing a crumb for each pushed the screen trail out of the ring buffer.
 *
 * - The first report at start is not a change: online at launch writes nothing, offline at launch writes
 *   `connectivity_lost` (there is no callback for "already offline", so [start] reads the active network).
 * - Changes closer together than [COALESCING_WINDOW_MS] are folded: the first is written at once (a crash right
 *   now must see it), the rest only as the state they settle in when the window ends.
 * - Names: `connectivity_lost`; `connectivity_restored` (back from offline); `connectivity_changed_to_<interface>`
 *   (reachable before and after, on another interface).
 * - The ring buffer holds at most half its capacity of these ([BreadcrumbRingBuffer.NETWORK_LIMIT]).
 *
 * Same state machine as iOS `AutomaticBreadcrumbSource.recordConnectivity` (`02` MOB-12).
 */
internal class ConnectivityBreadcrumbSource internal constructor(
    private val context: Context,
    private val ringBuffer: BreadcrumbRingBuffer,
    private val now: () -> Long,
    private val schedule: (delayMs: Long, work: () -> Unit) -> Unit
) {
    constructor(context: Context, ringBuffer: BreadcrumbRingBuffer) :
        this(context, ringBuffer, System::currentTimeMillis, DelayedExecutor()::schedule)

    /** What the system said, reduced to what a person reading a crash trail can use. */
    internal data class State(val satisfied: Boolean, val interfaceName: String? = null) {
        init {
            require(satisfied || interfaceName == null) { "an offline state has no interface" }
        }
    }

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Guards everything below: callbacks arrive on the system's thread, the flush on the scheduler's. */
    private val lock = Any()
    private var currentNetwork: Network? = null
    private var observed: State? = null
    private var recorded: State? = null
    private var lastCrumbAtMs: Long? = null
    private var flushScheduled = false

    fun start() {
        if (callback != null) return
        // The first report. Seeded before registering so that "online at launch" is silent and the callback's
        // own first onAvailable for the same network repeats the same state.
        seedFromActiveNetwork()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(lock) { currentNetwork = network }
                // Capabilities can be unknown for a moment after a network appears; onCapabilitiesChanged follows
                // with the real ones. Until then keep the interface the trail already has instead of guessing
                // "other" (which would write a change that never happened).
                recordConnectivity(State(true, capabilitiesOf(network)?.let(::interfaceOf) ?: lastKnownInterface()))
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // A capability update for a network that is no longer the default says nothing about the default.
                if (synchronized(lock) { network != currentNetwork }) return
                recordConnectivity(State(true, interfaceOf(capabilities)))
            }

            override fun onLost(network: Network) {
                // When the default network switches (wifi -> cellular) the system announces the new one first
                // and loses the old one after; that late loss is not "offline".
                val wasCurrent = synchronized(lock) {
                    val current = currentNetwork
                    if (current == null || current == network) { currentNetwork = null; true } else false
                }
                if (wasCurrent) recordConnectivity(State(false))
            }
        }
        try {
            connectivityManager?.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: Exception) {
            // Defensive, same as ConnectivityWatcher (A5) — losing this trigger is not fatal.
        }
    }

    fun stop() {
        val cb = callback ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // Already unregistered or manager gone.
        } finally {
            callback = null
            // A restart treats its first report as a first report again.
            synchronized(lock) {
                currentNetwork = null
                observed = null
                recorded = null
                lastCrumbAtMs = null
            }
        }
    }

    private fun seedFromActiveNetwork() {
        try {
            val cm = connectivityManager ?: return
            val active = cm.activeNetwork
            if (active == null) {
                recordConnectivity(State(false))
            } else {
                synchronized(lock) { currentNetwork = active }
                recordConnectivity(State(true, interfaceOf(capabilitiesOf(active))))
            }
        } catch (e: Exception) {
            // No ACCESS_NETWORK_STATE or a broken manager: the first real callback is the first report.
        }
    }

    private fun lastKnownInterface(): String =
        synchronized(lock) { observed?.takeIf { it.satisfied }?.interfaceName } ?: "other"

    private fun capabilitiesOf(network: Network): NetworkCapabilities? =
        try { connectivityManager?.getNetworkCapabilities(network) } catch (e: Exception) { null }

    /**
     * One state update from the system. `internal` so tests can drive a realistic sequence with a fake clock
     * and scheduler instead of forcing real network changes.
     */
    internal fun recordConnectivity(state: State) {
        val nowMs = now()
        var delayMs: Long? = null
        synchronized(lock) {
            val previous = observed
            observed = state
            if (previous == null) {
                // Online at launch needs no crumb; offline at launch is worth one.
                if (state.satisfied) recorded = state else emitLocked(state, nowMs)
            } else if (state != recorded) {
                val last = lastCrumbAtMs
                if (last != null && nowMs - last < COALESCING_WINDOW_MS) {
                    if (!flushScheduled) {
                        flushScheduled = true
                        delayMs = COALESCING_WINDOW_MS - (nowMs - last)
                    }
                } else {
                    emitLocked(state, nowMs)
                }
            }
        }
        delayMs?.let { schedule(it) { flush() } }
    }

    /** End of a coalescing window: if the state the system settled in differs from the trail, say it once. */
    private fun flush() {
        synchronized(lock) {
            flushScheduled = false
            val o = observed
            if (o != null && o != recorded) emitLocked(o, now())
        }
    }

    private fun emitLocked(state: State, nowMs: Long) {
        val previous = recorded
        val message = when {
            !state.satisfied -> "connectivity_lost"
            previous?.satisfied == true && state.interfaceName != null ->
                "connectivity_changed_to_${state.interfaceName}"
            else -> "connectivity_restored"
        }
        recorded = state
        lastCrumbAtMs = nowMs
        ringBuffer.record(
            message,
            BreadcrumbCategory.NETWORK,
            if (state.satisfied) BreadcrumbLevel.INFO else BreadcrumbLevel.WARNING
        )
    }

    /** Runs the coalescing flush on one daemon thread, created on first use. */
    private class DelayedExecutor {
        private var executor: ScheduledExecutorService? = null

        @Synchronized
        fun schedule(delayMs: Long, work: () -> Unit) {
            val e = executor ?: Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "apm-connectivity-breadcrumbs").apply { isDaemon = true }
            }.also { executor = it }
            try { e.schedule({ try { work() } catch (_: Throwable) {} }, delayMs, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        }
    }

    companion object {
        /** Consecutive changes closer together than this become one breadcrumb (`02` MOB-12). */
        const val COALESCING_WINDOW_MS = 5_000L

        /** wifi, cellular, wired, other — the same four names as iOS, in the same precedence. */
        internal fun interfaceOf(caps: NetworkCapabilities?): String = when {
            caps == null -> "other"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wired"
            else -> "other"
        }
    }
}
