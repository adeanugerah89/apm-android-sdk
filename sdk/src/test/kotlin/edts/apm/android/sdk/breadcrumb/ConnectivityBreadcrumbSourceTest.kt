package edts.apm.android.sdk.breadcrumb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/** MOB-12 (0.0.16): connectivity breadcrumbs are written on change only, coalesced, and capped. */
@RunWith(RobolectricTestRunner::class)
class ConnectivityBreadcrumbSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ---- a fake clock + scheduler, so a realistic sequence runs without waiting for real time ----

    private class FakeTime {
        var nowMs = 1_000_000L
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()

        fun schedule(delayMs: Long, work: () -> Unit) { pending += (nowMs + delayMs) to work }

        fun advance(ms: Long) {
            val target = nowMs + ms
            while (true) {
                val due = pending.filter { it.first <= target }.minByOrNull { it.first } ?: break
                pending.remove(due)
                nowMs = due.first
                due.second()
            }
            nowMs = target
        }
    }

    private class Rig {
        val time = FakeTime()
        val buffer = BreadcrumbRingBuffer()
        val source: ConnectivityBreadcrumbSource

        init {
            val ctx: Context = ApplicationProvider.getApplicationContext()
            source = ConnectivityBreadcrumbSource(ctx, buffer, { time.nowMs }, time::schedule)
        }

        /** One state update, [after] ms after the previous one. */
        fun update(state: ConnectivityBreadcrumbSource.State, after: Long = 0) {
            time.advance(after)
            source.recordConnectivity(state)
        }

        val messages: List<String> get() = crumbs().map { it.jsonObject["message"]!!.jsonPrimitive.content }

        fun crumbs(): JsonArray = Json.parseToJsonElement(buffer.snapshot()) as JsonArray
    }

    private val wifi = ConnectivityBreadcrumbSource.State(true, "wifi")
    private val cellular = ConnectivityBreadcrumbSource.State(true, "cellular")
    private val offline = ConnectivityBreadcrumbSource.State(false)

    @Test fun `repeated reachable updates on one interface leave no breadcrumb`() {
        val rig = Rig()
        rig.update(wifi) // first report
        for (gap in listOf(3_000L, 7_000, 30_000, 4_000, 12_000, 30_000)) rig.update(wifi, gap)
        rig.time.advance(60_000)
        assertTrue(rig.messages.isEmpty())
    }

    @Test fun `the first report is not a change when online and is worth a breadcrumb when offline`() {
        val online = Rig()
        online.update(wifi)
        assertTrue(online.messages.isEmpty())

        val off = Rig()
        off.update(offline)
        assertEquals(listOf("connectivity_lost"), off.messages)
    }

    @Test fun `losing and regaining the network records lost then restored and an interface change as its own entry`() {
        val rig = Rig()
        rig.update(wifi)
        rig.update(offline, 20_000)
        rig.update(cellular, 20_000)  // back, on another interface: one entry, "restored"
        rig.update(wifi, 20_000)      // cellular -> wifi while reachable
        assertEquals(listOf("connectivity_lost", "connectivity_restored", "connectivity_changed_to_wifi"), rig.messages)
    }

    @Test fun `wifi to cellular is recorded once and a later cellular to wifi once more`() {
        val rig = Rig()
        rig.update(wifi)
        rig.update(cellular, 10_000)
        rig.update(cellular, 10_000)  // same again: nothing
        rig.update(wifi, 10_000)
        assertEquals(listOf("connectivity_changed_to_cellular", "connectivity_changed_to_wifi"), rig.messages)
    }

    @Test fun `all four interface names are used`() {
        val rig = Rig()
        rig.update(wifi)
        rig.update(ConnectivityBreadcrumbSource.State(true, "cellular"), 10_000)
        rig.update(ConnectivityBreadcrumbSource.State(true, "wired"), 10_000)
        rig.update(ConnectivityBreadcrumbSource.State(true, "other"), 10_000)
        assertEquals(
            listOf("connectivity_changed_to_cellular", "connectivity_changed_to_wired", "connectivity_changed_to_other"),
            rig.messages
        )
    }

    @Test fun `a burst of flapping inside the window becomes one entry for the first change and one for where it settles`() {
        val rig = Rig()
        rig.update(wifi)
        rig.update(offline, 10_000)   // recorded at once: a crash right now must see it
        rig.update(wifi, 1_000)
        rig.update(offline, 1_000)
        rig.update(wifi, 1_000)       // settles reachable
        assertEquals(listOf("connectivity_lost"), rig.messages)
        rig.time.advance(10_000)      // the window ends
        assertEquals(listOf("connectivity_lost", "connectivity_restored"), rig.messages)
    }

    @Test fun `a burst that ends where the trail already is adds nothing when the window ends`() {
        val rig = Rig()
        rig.update(wifi)
        rig.update(offline, 10_000)
        rig.update(wifi, 1_000)
        rig.update(offline, 1_000)
        rig.time.advance(30_000)
        assertEquals(listOf("connectivity_lost"), rig.messages)
    }

    @Test fun `a change exactly the window after the last crumb is written at once`() {
        val rig = Rig()
        rig.update(wifi)
        rig.update(offline, 10_000)
        rig.update(wifi, ConnectivityBreadcrumbSource.COALESCING_WINDOW_MS)
        assertEquals(listOf("connectivity_lost", "connectivity_restored"), rig.messages)
    }

    @Test fun `lost is a warning and the others are info, all in the network category`() {
        val rig = Rig()
        rig.update(wifi)
        rig.update(offline, 10_000)
        rig.update(cellular, 10_000)
        val crumbs = rig.crumbs().map { it.jsonObject }
        assertEquals(listOf("warning", "info"), crumbs.map { it["level"]!!.jsonPrimitive.content })
        assertTrue(crumbs.all { it["category"]!!.jsonPrimitive.content == "network" })
    }

    @Test fun `an offline state cannot carry an interface`() {
        assertThrows(IllegalArgumentException::class.java) { ConnectivityBreadcrumbSource.State(false, "wifi") }
    }

    // ---- ring buffer quota ----

    @Test fun `the ring buffer keeps network entries to half of its capacity and never evicts the host's own`() {
        val buffer = BreadcrumbRingBuffer()
        repeat(30) { buffer.record("screen-$it", BreadcrumbCategory.NAVIGATION) }
        repeat(200) { buffer.record("net-$it", BreadcrumbCategory.NETWORK) }
        val crumbs = (Json.parseToJsonElement(buffer.snapshot()) as JsonArray).map { it.jsonObject }
        assertEquals(BreadcrumbRingBuffer.NETWORK_LIMIT, crumbs.count { it["category"]!!.jsonPrimitive.content == "network" })
        assertEquals(30, crumbs.count { it["category"]!!.jsonPrimitive.content == "navigation" })
        assertEquals("net-199", crumbs.last()["message"]!!.jsonPrimitive.content) // the newest one survives
        assertEquals("net-150", crumbs.first { it["category"]!!.jsonPrimitive.content == "network" }["message"]!!.jsonPrimitive.content)
    }

    @Test fun `network entries do not stop the host filling the rest of the buffer`() {
        val buffer = BreadcrumbRingBuffer()
        repeat(60) { buffer.record("net-$it", BreadcrumbCategory.NETWORK) }
        repeat(BreadcrumbRingBuffer.CAPACITY) { buffer.record("act-$it", BreadcrumbCategory.USER_ACTION) }
        val crumbs = (Json.parseToJsonElement(buffer.snapshot()) as JsonArray).map { it.jsonObject }
        assertEquals(BreadcrumbRingBuffer.CAPACITY, crumbs.size)
        assertTrue(crumbs.count { it["category"]!!.jsonPrimitive.content == "network" } <= BreadcrumbRingBuffer.NETWORK_LIMIT)
    }

    // ---- the system callback ----

    private fun cm() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun lastRegisteredCallback(): ConnectivityManager.NetworkCallback =
        shadowOf(cm()).networkCallbacks.last()

    private var nextNetId = 100

    /** A real [Network] whose capabilities the system will report as [transport] (or unknown when null). */
    private fun network(transport: Int? = null): Network {
        val n = ShadowNetwork.newInstance(nextNetId++)
        if (transport != null) shadowOf(cm()).setNetworkCapabilities(n, caps(transport))
        return n
    }

    private fun caps(transport: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addTransportType(transport) }

    @Test fun `onAvailable whose capabilities are not known yet keeps the interface and leaves no breadcrumb`() {
        val buffer = BreadcrumbRingBuffer()
        ConnectivityBreadcrumbSource(context, buffer).start()
        val before = buffer.snapshot()

        lastRegisteredCallback().onAvailable(network())

        assertEquals(before, buffer.snapshot())
    }

    @Test fun `onLost records connectivity_lost at warning level`() {
        val buffer = BreadcrumbRingBuffer()
        ConnectivityBreadcrumbSource(context, buffer).start()

        val net = network()
        lastRegisteredCallback().onAvailable(net)
        lastRegisteredCallback().onLost(net)

        val snapshot = buffer.snapshot()
        assertTrue(snapshot.contains("connectivity_lost"))
        assertTrue(snapshot.contains("\"level\":\"warning\""))
    }

    @Test fun `a default network switch is a change of interface, not a loss`() {
        val rig = Rig()
        rig.source.start()
        val cb = lastRegisteredCallback()
        val wifiNet = network(NetworkCapabilities.TRANSPORT_WIFI)
        val cellNet = network(NetworkCapabilities.TRANSPORT_CELLULAR)
        cb.onAvailable(wifiNet)
        val settled = rig.messages   // whatever the seed said about the start, then wifi

        // The system announces the new default first and loses the old one after it.
        rig.time.advance(10_000)
        cb.onAvailable(cellNet)
        cb.onCapabilitiesChanged(cellNet, caps(NetworkCapabilities.TRANSPORT_CELLULAR))
        cb.onLost(wifiNet)
        // A wrongly counted loss would sit in the coalescing window and only show when it ends.
        rig.time.advance(30_000)

        assertEquals(settled + "connectivity_changed_to_cellular", rig.messages)
        assertFalse(rig.messages.contains("connectivity_lost"))
    }

    @Test fun `losing the network that is the default records connectivity_lost`() {
        val rig = Rig()
        rig.source.start()
        val cb = lastRegisteredCallback()
        val wifiNet = network(NetworkCapabilities.TRANSPORT_WIFI)
        cb.onAvailable(wifiNet)
        val settled = rig.messages

        rig.time.advance(10_000)
        cb.onLost(wifiNet)

        assertEquals(settled + "connectivity_lost", rig.messages)
    }

    @Test fun `a capability update for a network that is not the default is ignored`() {
        val rig = Rig()
        rig.source.start()
        val cb = lastRegisteredCallback()
        val current = network(NetworkCapabilities.TRANSPORT_CELLULAR)
        cb.onAvailable(current)
        rig.time.advance(10_000)
        val before = rig.messages   // the trail says cellular, whatever the seed was

        // Another network reports wifi capabilities: it is not the default, so the trail does not change.
        val other = network(NetworkCapabilities.TRANSPORT_WIFI)
        cb.onCapabilitiesChanged(other, caps(NetworkCapabilities.TRANSPORT_WIFI))
        rig.time.advance(30_000)
        assertEquals(before, rig.messages)

        // The same update for the default network does change it.
        cb.onCapabilitiesChanged(current, caps(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(before + "connectivity_changed_to_wifi", rig.messages)
    }

    @Test fun `interfaceOf maps transports to the four names with wifi first`() {
        fun name(vararg t: Int) = ConnectivityBreadcrumbSource.interfaceOf(mockk {
            every { hasTransport(any()) } answers { firstArg<Int>() in t }
        })
        assertEquals("wifi", name(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals("cellular", name(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertEquals("wired", name(NetworkCapabilities.TRANSPORT_ETHERNET))
        assertEquals("other", name(NetworkCapabilities.TRANSPORT_BLUETOOTH))
        assertEquals("other", name(NetworkCapabilities.TRANSPORT_VPN))
        // a VPN riding on wifi is wifi, the way iOS checks wifi before anything else
        assertEquals("wifi", name(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals("other", ConnectivityBreadcrumbSource.interfaceOf(null))
    }

    @Test fun `stop forgets the state, so a restart treats the first report as a first report again`() {
        val rig = Rig()
        rig.source.start()
        rig.source.stop()
        rig.update(offline, 10_000)
        assertEquals(listOf("connectivity_lost"), rig.messages)
    }

    @Test fun `start is idempotent and stop never crashes even without starting`() {
        val source = ConnectivityBreadcrumbSource(context, BreadcrumbRingBuffer())
        source.start()
        source.start()  // must not register twice or throw
        source.stop()
        source.stop()   // must not throw on a second stop either

        val freshSource = ConnectivityBreadcrumbSource(context, BreadcrumbRingBuffer())
        freshSource.stop()  // stop() before start() must not throw
    }
}
