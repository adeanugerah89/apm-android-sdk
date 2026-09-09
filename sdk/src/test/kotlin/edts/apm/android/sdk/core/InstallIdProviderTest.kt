package edts.apm.android.sdk.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pre-existing A1 component that had no test coverage yet — added alongside A6's
 * UserIdProviderTest since it's the same pattern (stable id persisted in SharedPreferences).
 */
@RunWith(RobolectricTestRunner::class)
class InstallIdProviderTest {

    private fun prefs(name: String) = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test fun `generates a non-blank id`() {
        val provider = InstallIdProvider(prefs("test_install_id_1"))
        assertTrue(provider.installId.isNotBlank())
    }

    @Test fun `id is stable across reads on the same instance`() {
        val provider = InstallIdProvider(prefs("test_install_id_2"))
        assertEquals(provider.installId, provider.installId)
    }

    @Test fun `id survives across provider instances sharing the same prefs (persisted per install)`() {
        val sharedPrefs = prefs("test_install_id_3")
        val first = InstallIdProvider(sharedPrefs).installId
        val second = InstallIdProvider(sharedPrefs).installId
        assertEquals(first, second)
    }

    @Test fun `different prefs produce different ids`() {
        val a = InstallIdProvider(prefs("test_install_id_4")).installId
        val b = InstallIdProvider(prefs("test_install_id_5")).installId
        assertNotEquals(a, b)
    }
}
