package edts.apm.android.sdk.crash

import android.app.ApplicationExitInfo
import org.junit.Test
import org.junit.Assert.*

/** `01` §4.7 — CONSTITUTION 2026-09-07 D1 mapping. */
class TerminationReasonMapperTest {

    @Test fun `REASON_LOW_MEMORY maps to memory_pressure`() {
        assertEquals("memory_pressure", TerminationReasonMapper.map(ApplicationExitInfo.REASON_LOW_MEMORY))
    }

    @Test fun `REASON_CRASH is not mapped (own JvmCrashHandler already reports it as a crash)`() {
        assertNull(TerminationReasonMapper.map(ApplicationExitInfo.REASON_CRASH))
    }

    @Test fun `REASON_USER_REQUESTED is not mapped (normal user action, not a resource kill)`() {
        assertNull(TerminationReasonMapper.map(ApplicationExitInfo.REASON_USER_REQUESTED))
    }

    @Test fun `REASON_ANR is not mapped (handled separately by A10, not termination)`() {
        assertNull(TerminationReasonMapper.map(ApplicationExitInfo.REASON_ANR))
    }

    @Test fun `an unrecognized reason code is not mapped`() {
        assertNull(TerminationReasonMapper.map(9999))
    }
}
