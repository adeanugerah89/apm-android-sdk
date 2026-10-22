package edts.apm.android.sdk.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Triggers a sync as soon as connectivity comes back (MOB-08). Registering/unregistering the
 * callback is idempotent so a defensive double-call from the composition root (A16) never
 * throws — CONSTITUTION rule #1: the SDK must never crash the host app.
 */
internal class ConnectivityWatcher(private val context: Context) {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                SyncScheduler.triggerNow(context)
            }
        }
        try {
            connectivityManager?.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: Exception) {
            // Defensive: a handful of OEMs throw on registerDefaultNetworkCallback in odd states.
            // Losing the connectivity-recovery trigger still leaves the periodic timer (MOB-08).
        }
    }

    fun stop() {
        val cb = callback ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // Already unregistered or manager gone — nothing to clean up.
        } finally {
            callback = null
        }
    }
}
