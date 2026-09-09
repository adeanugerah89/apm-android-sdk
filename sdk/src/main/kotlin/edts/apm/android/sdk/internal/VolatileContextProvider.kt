package edts.apm.android.sdk.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.PowerManager
import edts.apm.android.sdk.core.EventContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Produces the per-event `ctx` block (`01` §4, the `EventContext` every event carries).
 *
 * Until A16 nothing built this: every capture component takes a `contextProvider: () -> EventContext`
 * and each one was wired to a stub in its own tests. This is the real implementation.
 *
 * Screen name and app state come from [Application.ActivityLifecycleCallbacks], the same mechanism
 * screen-tracking breadcrumbs use (MOB-12) — registered separately here rather than by extending
 * [edts.apm.android.sdk.breadcrumb.ScreenTrackingLifecycleCallbacks], so the breadcrumb trail and
 * the event context cannot drift into fighting over one object's state.
 *
 * Connectivity and power-save are read on demand instead of cached: both change without warning,
 * and a stale value in an event is worse than the cost of one system-service call. Both reads are
 * cheap and neither touches disk or network, so this is safe from any thread — including the
 * crash handler, which calls it while the process is already dying.
 *
 * Every accessor is defensive (CONSTITUTION rule #1): an unavailable system service yields `null`
 * for that field, never an exception into a capture path.
 */
class VolatileContextProvider(
    private val context: Context,
    /**
     * Fired on the 0→1 and 1→0 started-activity transitions. These exist so `SessionManager` and
     * the lifecycle breadcrumbs can be driven from the same counter that produces `app_state`,
     * instead of from `ProcessLifecycleOwner` — which lives in `androidx.lifecycle:lifecycle-process`,
     * a dependency this SDK does not have and cannot add without a dated decision (CONSTITUTION:
     * "dependensi minimal by design"). One counter also means the two can never disagree.
     */
    private val onEnterForeground: () -> Unit = {},
    private val onEnterBackground: () -> Unit = {}
) : Application.ActivityLifecycleCallbacks {

    @Volatile private var currentScreen: String? = null
    private val startedActivities = AtomicInteger(0)

    private val connectivityManager: ConnectivityManager? =
        try {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (e: Exception) {
            null
        }

    private val powerManager: PowerManager? =
        try {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        } catch (e: Exception) {
            null
        }

    /** The snapshot handed to every event at capture time. Never throws. */
    fun current(): EventContext = try {
        EventContext(
            connectivity = connectivity(),
            screen = currentScreen,
            appState = if (startedActivities.get() > 0) FOREGROUND else BACKGROUND,
            lowPower = isPowerSaveMode()
        )
    } catch (e: Exception) {
        EventContext()
    }

    /**
     * Wire vocabulary matches iOS: `wifi` / `cellular` / `ethernet` / `offline` (`01` §4 example
     * uses `"wifi"`). `null` means "could not tell", which is different from `offline` and must
     * not be flattened into it — an event that cannot report connectivity is not evidence of a
     * network outage.
     */
    private fun connectivity(): String? {
        val manager = connectivityManager ?: return null
        return try {
            val network = manager.activeNetwork ?: return OFFLINE
            val capabilities = manager.getNetworkCapabilities(network) ?: return OFFLINE
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ETHERNET
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isPowerSaveMode(): Boolean = try {
        powerManager?.isPowerSaveMode ?: false
    } catch (e: Exception) {
        false
    }

    // ─── lifecycle ──────────────────────────────────────────────────────────
    //
    // Counting started activities, rather than flipping a boolean on resume/pause, is what keeps
    // app_state correct across a configuration change: a rotation stops the old activity after
    // starting the new one, and a boolean would report "background" for that window.

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities.getAndIncrement() == 0) safely(onEnterForeground)
    }

    override fun onActivityStopped(activity: Activity) {
        val remaining = startedActivities.updateAndGet { if (it > 0) it - 1 else 0 }
        if (remaining == 0) safely(onEnterBackground)
    }

    /** A listener that throws must not propagate into the host app's lifecycle callbacks. */
    private fun safely(action: () -> Unit) = try {
        action()
    } catch (e: Exception) {
        Unit
    }

    override fun onActivityResumed(activity: Activity) {
        currentScreen = activity::class.java.simpleName
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val FOREGROUND = "foreground"
        const val BACKGROUND = "background"
        const val WIFI = "wifi"
        const val CELLULAR = "cellular"
        const val ETHERNET = "ethernet"
        const val OFFLINE = "offline"
    }
}
