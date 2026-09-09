package edts.apm.android.sdk.breadcrumb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Test
import org.junit.Assert.*

/** MOB-13. */
class BreadcrumbRingBufferTest {

    @Test fun `empty buffer snapshots as an empty JSON array`() {
        assertEquals("[]", BreadcrumbRingBuffer().snapshot())
    }

    @Test fun `snapshot contains recorded entries with the right wire fields`() {
        val buffer = BreadcrumbRingBuffer()
        buffer.record("checkout_tapped", BreadcrumbCategory.USER_ACTION, BreadcrumbLevel.INFO)

        val array = Json.parseToJsonElement(buffer.snapshot()).let { it as JsonArray }
        assertEquals(1, array.size)
        val entry = array[0].toString()
        assertTrue(entry.contains("\"category\":\"user_action\""))
        assertTrue(entry.contains("\"level\":\"info\""))
        assertTrue(entry.contains("\"message\":\"checkout_tapped\""))
        assertTrue(entry.contains("\"timestamp\":"))
    }

    @Test fun `entries stay in chronological order, oldest first`() {
        val buffer = BreadcrumbRingBuffer()
        buffer.record("first", BreadcrumbCategory.LOG)
        buffer.record("second", BreadcrumbCategory.LOG)
        buffer.record("third", BreadcrumbCategory.LOG)

        val array = Json.parseToJsonElement(buffer.snapshot()) as JsonArray
        assertTrue(array[0].toString().contains("first"))
        assertTrue(array[1].toString().contains("second"))
        assertTrue(array[2].toString().contains("third"))
    }

    @Test fun `evicts the oldest entry once capacity is exceeded`() {
        val buffer = BreadcrumbRingBuffer()
        repeat(BreadcrumbRingBuffer.CAPACITY) { i -> buffer.record("entry-$i", BreadcrumbCategory.LOG) }
        buffer.record("overflow", BreadcrumbCategory.LOG)

        val array = Json.parseToJsonElement(buffer.snapshot()) as JsonArray
        assertEquals(BreadcrumbRingBuffer.CAPACITY, array.size)
        assertFalse("oldest entry (entry-0) must have been evicted", buffer.snapshot().contains("\"entry-0\""))
        assertTrue("newest entry must be present", buffer.snapshot().contains("overflow"))
        assertTrue("last element must be the newest", array.last().toString().contains("overflow"))
    }

    @Test fun `default level is INFO when not specified`() {
        val buffer = BreadcrumbRingBuffer()
        buffer.record("no level given", BreadcrumbCategory.STATE)
        assertTrue(buffer.snapshot().contains("\"level\":\"info\""))
    }
}
