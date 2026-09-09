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
}
