package edts.apm.android.sdk.crypto

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SEC-08 — the fail-safe half of the key provider.
 *
 * Robolectric ships no `AndroidKeyStore` provider (`KeyStoreException: AndroidKeyStore not found`),
 * so these tests can only exercise the branch where the Keystore is unavailable. That branch is
 * the one worth locking down anyway: it is what runs on a device whose Keystore is broken, and it
 * must degrade to plaintext rather than take the host app down. Key creation and persistence
 * across relaunch are on the device-only checklist (item 9).
 */
@RunWith(RobolectricTestRunner::class)
class KeystoreKeyProviderTest {

    @Test fun `an unavailable Keystore yields null instead of throwing`() {
        assertNull(KeystoreKeyProvider().getOrCreateKey())
    }

    @Test fun `repeated calls stay null and never throw`() {
        val provider = KeystoreKeyProvider(alias = "apm_test_alias")

        repeat(3) { assertNull(provider.getOrCreateKey()) }
    }

    @Test fun `the default alias is versioned so a format change can rotate the key`() {
        assertTrue(KeystoreKeyProvider.DEFAULT_ALIAS.endsWith("_v1"))
    }
}
