package edts.apm.android.sdk.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A36 — the name the SDK sends is a wire-contract value; pin it so changing it is a deliberate act. */
class SdkIdentityTest {

    @Test fun `the SDK names itself apm-android-sdk, not apmkit-android`() {
        assertEquals("apm-android-sdk", SdkIdentity.NAME)
        assertNotEquals("apmkit-android", SdkIdentity.NAME)
    }

    @Test fun `the envelope carries that name as sdk name`() {
        val info = SdkInfo(SdkIdentity.NAME, "0.1.0", SdkHealthSnapshot(0, 0, 0, emptyMap()))

        val wire = Json { encodeDefaults = true }.encodeToString(SdkInfo.serializer(), info)

        assertEquals(true, wire.contains("\"name\":\"apm-android-sdk\""))
    }
}
