package edts.apm.android.sdk.internal

import hostapp.simulated.CallerSimulator
import org.junit.Assert.*
import org.junit.Test

/** MOB-11b — the located frame must be the external caller, never SDK internals. */
class CallSiteLocatorTest {

    @Test fun `finds the external caller, not the SDK's own frame`() {
        val site = CallerSimulator.callFromHere()

        assertEquals("CallerSimulator.kt", site.file)
        assertEquals("hostapp.simulated.CallerSimulator.callFromHere", site.function)
        assertTrue("line must be a real positive line number", site.line > 0)
    }

    @Test fun `called directly from a test still skips SDK and VM frames without crashing`() {
        // This test class lives under edts.apm.android.sdk.internal, so the locator has to
        // walk past its own frame plus JVM/reflection plumbing to land somewhere sane — it
        // must never throw regardless of what it finds (CONSTITUTION rule #1).
        val site = CallSiteLocator.locateCaller()
        assertNotEquals("edts.apm.android.sdk.internal.CallSiteLocator.locateCaller", site.function)
        assertNotNull(site.file)
    }
}
