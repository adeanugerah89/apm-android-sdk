package edts.apm.android.sdk.manual

import android.content.SharedPreferences
import edts.apm.android.sdk.breadcrumb.BreadcrumbCategory
import edts.apm.android.sdk.breadcrumb.BreadcrumbRingBuffer
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.core.UserIdProvider
import edts.apm.android.sdk.core.UserIdSource
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent
import hostapp.simulated.CallerSimulator
import io.mockk.mockk
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * MOB-11 / MOB-11b / MOB-28.
 *
 * Deliberately plain JUnit, not Robolectric: [edts.apm.android.sdk.internal.CallSiteLocator]
 * walks the real JVM stack trace, and Robolectric's sandboxed classloader alters that stack in
 * ways that break the "skip SDK frames" logic. A relaxed mockk `SharedPreferences` is enough for
 * [UserIdProvider], which is the only Android-framework dependency here.
 */
class ManualApiTest {

    private class RecordingQueue : EventQueue {
        val events = mutableListOf<Event>()
        override fun enqueue(event: Event): Boolean { events.add(event); return true }
        override fun peek(maxCount: Int): List<QueuedEvent> = emptyList()
        override fun delete(ids: List<String>) {}
        override fun stats() = QueueStats(events.size, 0)
    }

    private lateinit var queue: RecordingQueue
    private lateinit var userIdProvider: UserIdProvider
    private lateinit var breadcrumbs: BreadcrumbRingBuffer
    private lateinit var api: ManualApi

    @Before fun setUp() {
        queue = RecordingQueue()
        userIdProvider = UserIdProvider(mockk<SharedPreferences>(relaxed = true))
        breadcrumbs = BreadcrumbRingBuffer()
        api = ManualApi(
            queue = queue,
            userIdProvider = userIdProvider,
            breadcrumbs = breadcrumbs,
            seqProvider = { 1L },
            contextProvider = { EventContext() }
        )
    }

    @Test fun `logError enqueues one ERROR event with the exception type and message`() {
        api.logError(IllegalStateException("boom"))

        val event = queue.events.single()
        assertEquals(EventType.ERROR, event.type)
        assertEquals("java.lang.IllegalStateException", event.attrs.getValue("name").jsonPrimitive.content)
        assertEquals("boom", event.attrs.getValue("message").jsonPrimitive.content)
        assertEquals("true", event.attrs.getValue("handled").jsonPrimitive.content)
    }

    @Test fun `logError fills source_file, source_function and source_line automatically`() {
        // Called from ManualApiTest directly, the locator would correctly walk past this test
        // method too (it's in the same edts.apm.android.sdk package as ManualApi) and land on
        // JDK reflection frames with no file info — accurate, but useless as a test signal.
        // CallerSimulator stands in for real host app code living outside the SDK's package.
        CallerSimulator.reportErrorFromHere(api, RuntimeException("x"))

        val event = queue.events.single()
        assertEquals("CallerSimulator.kt", event.attrs.getValue("source_file").jsonPrimitive.content)
        assertEquals(
            "hostapp.simulated.CallerSimulator.reportErrorFromHere",
            event.attrs.getValue("source_function").jsonPrimitive.content
        )
        assertTrue(event.attrs.getValue("source_line").jsonPrimitive.content.toInt() > 0)
    }

    @Test fun `extra keys are namespaced under custom_`() {
        api.logError(RuntimeException("x"), extra = mapOf("orderId" to "123", "screen" to "checkout"))

        val event = queue.events.single()
        assertEquals("123", event.attrs.getValue("custom.orderId").jsonPrimitive.content)
        assertEquals("checkout", event.attrs.getValue("custom.screen").jsonPrimitive.content)
    }

    @Test fun `extra is capped at 20 keys`() {
        val extra = (1..30).associate { "key$it" to "v$it" }
        api.logError(RuntimeException("x"), extra = extra)

        val event = queue.events.single()
        val customKeys = event.attrs.keys.count { it.startsWith("custom.") }
        assertEquals(20, customKeys)
    }

    @Test fun `extra values are capped at 256 characters`() {
        val longValue = "a".repeat(500)
        api.logError(RuntimeException("x"), extra = mapOf("note" to longValue))

        val event = queue.events.single()
        assertEquals(256, event.attrs.getValue("custom.note").jsonPrimitive.content.length)
    }

    @Test fun `a null-message exception still produces a valid event`() {
        api.logError(RuntimeException())
        assertEquals("", queue.events.single().attrs.getValue("message").jsonPrimitive.content)
    }

    @Test fun `setUser delegates to the UserIdProvider (MOB-28)`() {
        api.setUser("user-42")
        assertEquals("user-42", userIdProvider.userId)
        assertEquals(UserIdSource.HOST, userIdProvider.userIdSource)
    }

    @Test fun `breadcrumb delegates to the ring buffer (MOB-11)`() {
        api.breadcrumb("checkout_tapped", BreadcrumbCategory.USER_ACTION)
        assertTrue(breadcrumbs.snapshot().contains("checkout_tapped"))
    }

    @Test fun `logError attaches the breadcrumb snapshot (MOB-13)`() {
        api.breadcrumb("connectivity_restored", BreadcrumbCategory.NETWORK)
        api.logError(RuntimeException("x"))

        val snapshotAttr = queue.events.single().attrs.getValue("breadcrumbs").jsonPrimitive.content
        assertTrue(snapshotAttr.contains("connectivity_restored"))
        assertTrue(snapshotAttr.contains("\"category\":\"network\""))
    }

    @Test fun `logError still attaches an (empty array) snapshot when no breadcrumbs were recorded`() {
        api.logError(RuntimeException("x"))
        assertEquals("[]", queue.events.single().attrs.getValue("breadcrumbs").jsonPrimitive.content)
    }
}
