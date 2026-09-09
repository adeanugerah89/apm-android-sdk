package edts.apm.android.sdk.crash

import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.Assert.*

class BuildTerminationEventTest {

    @Test fun `builds a TERMINATION event carrying the mapped reason`() {
        val event = buildTerminationEvent("memory_pressure", seq = 5L, ctx = EventContext())

        assertEquals(EventType.TERMINATION, event.type)
        assertEquals(5L, event.seq)
        assertEquals("memory_pressure", event.attrs.getValue("termination_reason").jsonPrimitive.content)
    }
}
