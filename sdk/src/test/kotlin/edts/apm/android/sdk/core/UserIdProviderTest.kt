package edts.apm.android.sdk.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** MOB-28, `01` §2.1/§2.2. */
@RunWith(RobolectricTestRunner::class)
class UserIdProviderTest {

    private lateinit var provider: UserIdProvider

    @Before fun setUp() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("test_user_id", Context.MODE_PRIVATE)
        provider = UserIdProvider(prefs)
    }

    @Test fun `defaults to a generated id with source GENERATED`() {
        assertTrue(provider.userId.isNotBlank())
        assertEquals(UserIdSource.GENERATED, provider.userIdSource)
    }

    @Test fun `generated id is stable across reads`() {
        val first = provider.userId
        val second = provider.userId
        assertEquals(first, second)
    }

    @Test fun `generated id survives across provider instances (persisted per install)`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("test_user_id_persist", Context.MODE_PRIVATE)
        val id1 = UserIdProvider(prefs).userId
        val id2 = UserIdProvider(prefs).userId
        assertEquals(id1, id2)
    }

    @Test fun `setUser switches to the host value with source HOST`() {
        provider.setUser("+628123456789")
        assertEquals("+628123456789", provider.userId)
        assertEquals(UserIdSource.HOST, provider.userIdSource)
    }

    @Test fun `setUser does not validate or reject any value (SEC-06)`() {
        provider.setUser("literally anything, including PII")
        assertEquals("literally anything, including PII", provider.userId)
    }

    // A54 — `02` MOB-28/SEC-06: an empty or whitespace-only string is not an identity, so it is ignored.
    @Test fun `setUser ignores empty and whitespace-only ids (A54)`() {
        for (blank in listOf("", " ", "   ", "\t", "\n", " \t\n ", " ", " ")) {
            val epochBefore = provider.currentEpoch
            assertFalse("`${blank.toCharArray().map { it.code }}` must be rejected", provider.setUser(blank))
            assertEquals(UserIdSource.GENERATED, provider.userIdSource)
            assertEquals("a rejected id must not start a new identity epoch", epochBefore, provider.currentEpoch)
        }
    }

    @Test fun `a blank setUser leaves the current host user in place (A54)`() {
        provider.setUser("alice")
        val epoch = provider.currentEpoch
        assertFalse(provider.setUser("  "))
        assertEquals("alice", provider.userId)
        assertEquals(UserIdSource.HOST, provider.userIdSource)
        assertEquals(epoch, provider.currentEpoch)
    }

    @Test fun `setUser accepts values that merely look like placeholders (A54, SEC-06)`() {
        // The spec leaves "0", "null", "guest" to the host: they pass validation.
        for (v in listOf("0", "-1", "null", "guest")) {
            assertTrue(provider.setUser(v))
            assertEquals(v, provider.userId)
        }
    }

    @Test fun `an id is stored as given, not trimmed (A54)`() {
        assertTrue(provider.setUser("  bob  "))
        assertEquals("  bob  ", provider.userId)
    }

    @Test fun `a blank setUser is never persisted (A54)`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("test_user_id_blank", Context.MODE_PRIVATE)
        val p = UserIdProvider(prefs, persistence = java.util.concurrent.Executor { it.run() })
        p.setUser("   ")
        assertFalse(prefs.contains("apm_host_user_id"))
        assertEquals(UserIdSource.GENERATED, UserIdProvider(prefs).userIdSource)
    }
}
