package edts.apm.android.sdk.core

import org.junit.Assert.*
import org.junit.Test

/**
 * A1 parity proof: session_id resets after >30s background exactly — not 29s, not 31s.
 * Clock is injected so tests are deterministic and fast (no sleep).
 */
class SessionManagerTest {

    @Test fun `session_id is stable while app is active`() {
        val mgr = SessionManager()
        val id1 = mgr.sessionId
        val id2 = mgr.sessionId
        assertEquals(id1, id2)
    }

    @Test fun `short background (under 30s) preserves session_id`() {
        var now = 0L
        val mgr = SessionManager(clock = { now })

        val id = mgr.sessionId
        mgr.onBackground()
        now = 29_000L   // 29 seconds — must NOT rotate
        val idAfter = mgr.onForeground()

        assertEquals("Session must not rotate after 29s", id, idAfter)
    }

    @Test fun `background over 30s rotates session_id`() {
        var now = 0L
        val mgr = SessionManager(clock = { now })

        val id = mgr.sessionId
        mgr.onBackground()
        now = 31_000L   // 31 seconds — must rotate
        val idAfter = mgr.onForeground()

        assertNotEquals("Session must rotate after 31s", id, idAfter)
    }

    @Test fun `exactly 30s does NOT rotate (boundary is strict greater-than)`() {
        var now = 0L
        val mgr = SessionManager(clock = { now })

        val id = mgr.sessionId
        mgr.onBackground()
        now = 30_000L   // exactly 30s — spec says >30s
        val idAfter = mgr.onForeground()

        assertEquals("At exactly 30s session must NOT rotate", id, idAfter)
    }

    @Test fun `foreground without prior background is a no-op`() {
        val mgr = SessionManager()
        val id = mgr.sessionId
        val idAfter = mgr.onForeground()
        assertEquals(id, idAfter)
    }

    @Test fun `new session_id is a valid UUID`() {
        var now = 0L
        val mgr = SessionManager(clock = { now })
        mgr.onBackground()
        now = 60_000L
        val newId = mgr.onForeground()

        // UUID format: 8-4-4-4-12 hex chars
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        assertTrue("New session_id must be UUID format: $newId", uuidRegex.matches(newId))
    }

    // ─── A28 · persisting the session so a later launch can recover it ──────────

    @Test fun `every new session id is announced, including the first`() {
        var now = 0L
        val seen = mutableListOf<String>()
        val mgr = SessionManager(clock = { now }, onSessionStarted = { seen += it })

        assertEquals(listOf(mgr.sessionId), seen)

        mgr.onBackground(); now = 31_000L; mgr.onForeground()

        assertEquals(2, seen.size)
        assertEquals(mgr.sessionId, seen.last())
    }

    @Test fun `no announcement when the session does not change`() {
        var now = 0L
        val seen = mutableListOf<String>()
        val mgr = SessionManager(clock = { now }, onSessionStarted = { seen += it })

        mgr.onBackground(); now = 10_000L; mgr.onForeground()

        assertEquals(1, seen.size)
    }

    @Test fun `a callback that throws cannot break session handling`() {
        var now = 0L
        val mgr = SessionManager(clock = { now }, onSessionStarted = { throw IllegalStateException("prefs gone") })
        val first = mgr.sessionId

        mgr.onBackground(); now = 31_000L

        assertNotEquals(first, mgr.onForeground())
    }
}
