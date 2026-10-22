package edts.apm.android.sdk.breadcrumb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Automatic connectivity-change breadcrumbs (MOB-12) — separate registration from
 * [edts.apm.android.sdk.sync.ConnectivityWatcher] (A5), which exists to trigger a sync rather
 * than to record history; keeping them independent avoids coupling two unrelated concerns to
 * one callback's lifecycle.
 */
internal class ConnectivityBreadcrumbSource(
    private val context: Context,
    private val ringBuffer: BreadcrumbRingBuffer
) {
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ringBuffer.record("connectivity_restored", BreadcrumbCategory.NETWORK)
            }
            override fun onLost(network: Network) {
                ringBuffer.record("connectivity_lost", BreadcrumbCategory.NETWORK, BreadcrumbLevel.WARNING)
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
        }
    }
}
