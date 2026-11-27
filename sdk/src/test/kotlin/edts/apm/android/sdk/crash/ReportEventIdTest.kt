package edts.apm.android.sdk.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** A52 — `01` §3 `event_id`: deterministic per report, still shaped like a UUID v4. */
class ReportEventIdTest {

    private val pkg = "com.example.app"

    @Test fun `the same exit always yields the same id`() {
        assertEquals(
            ReportEventId.forExit("crash", pkg, 4321, 1_788_000_000_000L),
            ReportEventId.forExit("crash", pkg, 4321, 1_788_000_000_000L)
        )
    }

    @Test fun `any difference in kind, package, pid or timestamp changes the id`() {
        val base = ReportEventId.forExit("crash", pkg, 4321, 1_788_000_000_000L)
        assertNotEquals(base, ReportEventId.forExit("termination", pkg, 4321, 1_788_000_000_000L))
        assertNotEquals(base, ReportEventId.forExit("crash", "com.example.other", 4321, 1_788_000_000_000L))
        assertNotEquals(base, ReportEventId.forExit("crash", pkg, 4322, 1_788_000_000_000L))
        // A recycled pid at a different time is a different process life — it must not be deduped away.
        assertNotEquals(base, ReportEventId.forExit("crash", pkg, 4321, 1_788_000_000_001L))
    }

    @Test fun `the id has the shape of a UUID v4, as 01 section 3 states`() {
        val id = ReportEventId.forExit("crash", pkg, 4321, 1_788_000_000_000L)
        assertTrue("36 characters, lower-case hex: $id", Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id))
        val parsed = UUID.fromString(id)
        assertEquals(4, parsed.version())
        assertEquals(2, parsed.variant())
    }

    @Test fun `ids do not collide across a range of pids and times`() {
        val ids = HashSet<String>()
        for (pid in 1000..1100) for (t in 0 until 20) ids += ReportEventId.forExit("crash", pkg, pid, 1_788_000_000_000L + t * 1000)
        assertEquals(101 * 20, ids.size)
    }
}
