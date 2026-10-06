package edts.apm.android.sdk

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edts.apm.android.sdk.core.UserIdProvider
import edts.apm.android.sdk.core.UserIdSource
import edts.apm.android.sdk.crypto.AesGcmQueueCipher
import edts.apm.android.sdk.crypto.KeystoreKeyProvider
import edts.apm.android.sdk.internal.NoBackupPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executor

/**
 * A41 / A39 on a real device — the stored host `user_id` is encrypted with the real Android Keystore.
 *
 * Robolectric has no `AndroidKeyStore`, so every JVM test of `UserIdProvider` uses either a software AES
 * key or no cipher at all. What only a device can show is that the value written with a **Keystore** key is
 * actually unreadable at rest and still comes back after a "restart" (a second provider on the same file).
 * CONSTITUTION 2026-09-07 (A17) requires this for anything that depends on a system service Robolectric
 * does not provide.
 */
@RunWith(AndroidJUnit4::class)
class IdentityKeystoreInstrumentedTest {

    private lateinit var context: Context
    private lateinit var file: File
    private val inline = Executor { it.run() }
    private val alias = "apm_identity_instrumented_test_key"

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "identity-instrumented.json").also { it.delete() }
    }

    @After fun tearDown() {
        file.delete()
    }

    private fun cipher() = AesGcmQueueCipher(checkNotNull(KeystoreKeyProvider(alias).getOrCreateKey()) { "no Keystore key on this device" })

    // Persistence is a background step in production (the Keystore is never touched on the caller's thread); here it runs
    // inline so the test can read the file straight after setUser.
    private fun provider() = UserIdProvider(NoBackupPrefs(file, inline), cipher(), inline)

    @Test fun theStoredHostIdIsEncryptedAtRest() {
        provider().setUser("+628123456789")

        val onDisk = file.readText()

        assertTrue(onDisk, onDisk.contains("e1:"))
        assertFalse("the raw id must not be readable in the state file: $onDisk", onDisk.contains("628123456789"))
    }

    @Test fun theIdentitySurvivesARestartWithTheRealKey() {
        provider().setUser("+628123456789")

        val afterRestart = provider()

        assertEquals("+628123456789", afterRestart.userId)
        assertEquals(UserIdSource.HOST, afterRestart.userIdSource)
    }

    @Test fun anEarlierEraStaysWithItsUserAfterARestartAndAChange() {
        val first = provider(); first.setUser("user-X"); val epochX = first.currentEpoch
        val afterRestart = provider()
        afterRestart.setUser("user-Y")

        assertNotNull(afterRestart.identityFor(epochX))
        assertEquals("user-X", afterRestart.identityFor(epochX).id)
        assertEquals("user-Y", afterRestart.identityFor(afterRestart.currentEpoch).id)
    }
}
