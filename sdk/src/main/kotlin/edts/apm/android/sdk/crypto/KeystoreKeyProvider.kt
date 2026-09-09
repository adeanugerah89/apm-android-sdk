package edts.apm.android.sdk.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Get-or-create the AES key that protects the queue, held in the Android Keystore (SEC-08).
 *
 * The key material never leaves the Keystore — this returns a handle, not bytes — so a queue file
 * copied off the device is useless without the device.
 *
 * Deliberately **not** `setUserAuthenticationRequired(true)`: the queue is written from background
 * threads and drained by a WorkManager job, both of which routinely run on a locked device. A key
 * that requires a recent unlock would make telemetry silently stop for exactly the users whose
 * sessions are most worth seeing.
 *
 * Every failure returns `null` instead of throwing, and a null key means the queue falls back to
 * plaintext (see [edts.apm.android.sdk.queue.EncryptedEventCodec]). That trade-off is deliberate
 * and is recorded in CONSTITUTION 2026-09-07 (A14): SEC-08 is P1, losing all telemetry is worse
 * than storing already-scrubbed data unencrypted, and the SDK may never be the reason an app dies.
 *
 * Not unit-testable on the happy path: Robolectric has no `AndroidKeyStore` provider at all
 * (`KeyStoreException: AndroidKeyStore not found`), so under test this class exercises exactly the
 * fallback branch. Key persistence across relaunch is on the device-only checklist.
 */
class KeystoreKeyProvider(private val alias: String = DEFAULT_ALIAS) {

    /** Returns the existing key, creating it on first call. `null` if the Keystore is unusable. */
    fun getOrCreateKey(): SecretKey? = try {
        loadExisting() ?: generate()
    } catch (e: Exception) {
        null
    }

    private fun loadExisting(): SecretKey? = try {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        keyStore.getKey(alias, null) as? SecretKey
    } catch (e: Exception) {
        null
    }

    private fun generate(): SecretKey? = try {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    // Randomized encryption required = the caller must supply a fresh IV per
                    // operation, which AesGcmQueueCipher does. Leaving it on is what stops a
                    // future caller from quietly introducing a fixed IV.
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    } catch (e: Exception) {
        null
    }

    companion object {
        const val DEFAULT_ALIAS = "apm_queue_key_v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
    }
}
