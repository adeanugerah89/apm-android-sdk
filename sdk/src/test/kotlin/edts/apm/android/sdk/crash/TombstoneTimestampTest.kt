package edts.apm.android.sdk.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A35 — the tombstone's timestamp must reach the backend as RFC3339, which the real value is not.
 * `apm-backend` parses `ts_client` with `time.Parse(RFC3339Nano)` and stores NULL (plus a
 * corrupt-sample row) for anything else.
 */
class TombstoneTimestampTest {

    /** Byte-for-byte what the fixture tombstone contains. */
    private val real = "2026-09-09 11:14:38.641294490+0700"

    @Test fun `the real tombstone value becomes ISO-8601 UTC with the offset applied`() {
        assertEquals("2026-09-09T04:14:38.641Z", TombstoneTimestamp.toIso8601(real))
    }

    @Test fun `the fixture tombstone, parsed from its bytes, converts to the expected instant`() {
        val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream("tombstones/system-tombstone-api33-segv.pb")) {
            "missing tombstone fixture"
        }.readBytes()
        val raw = SystemTombstoneParser.parse(bytes, "edts.apm.android.verifier")!!.timestamp

        assertEquals("the fixture really carries the non-RFC3339 form", real, raw)
        assertEquals("2026-09-09T04:14:38.641Z", TombstoneTimestamp.toIso8601(raw))
    }

    @Test fun `output matches the strict shape every other ts_client has`() {
        val out = TombstoneTimestamp.toIso8601(real)!!
        assertTrue(out, Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""").matches(out))
    }

    @Test fun `a negative offset is added back, and the date can roll over`() {
        assertEquals("2026-09-10T02:30:00.000Z", TombstoneTimestamp.toIso8601("2026-09-09 21:00:00-0530"))
    }

    @Test fun `a UTC tombstone and a missing fraction are handled`() {
        assertEquals("2026-01-01T00:00:00.000Z", TombstoneTimestamp.toIso8601("2026-01-01 00:00:00+0000"))
    }

    @Test fun `sub-millisecond digits are truncated, not rounded up into the next millisecond`() {
        assertEquals("2026-09-09T04:14:38.999Z", TombstoneTimestamp.toIso8601("2026-09-09 11:14:38.999999999+0700"))
    }

    @Test fun `anything that is not exactly the tombstone shape is null, never a guess`() {
        assertNull(TombstoneTimestamp.toIso8601(null))
        assertNull(TombstoneTimestamp.toIso8601(""))
        assertNull(TombstoneTimestamp.toIso8601("2026-09-09T04:14:38.641Z"))        // already RFC3339: not this form
        assertNull(TombstoneTimestamp.toIso8601("2026-09-09 11:14:38.641+07:00"))   // colon in the offset
        assertNull(TombstoneTimestamp.toIso8601("2026-13-45 11:14:38+0700"))        // impossible date
        assertNull(TombstoneTimestamp.toIso8601("2026-09-09 11:14:38+9900"))        // impossible offset
        assertNull(TombstoneTimestamp.toIso8601("yesterday"))
    }
}
