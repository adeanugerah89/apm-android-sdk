package edts.apm.android.sdk.crypto

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM at-rest encryption for queue files (SEC-08).
 *
 * On-disk layout, and why each part is there:
 *
 * ```
 * [0]      version byte 0x01
 * [1..12]  IV (12 bytes, freshly random per file)
 * [13..]   ciphertext || GCM tag (128-bit)
 * ```
 *
 * The **version byte** is what makes this safe to introduce into an SDK that is already running
 * on devices. A queue file written by an older build is plain JSON and therefore starts with
 * `{` (0x7B), which can never be mistaken for 0x01 — so
 * [edts.apm.android.sdk.queue.EncryptedEventCodec] can read both without guessing, and a future
 * format change gets 0x02 rather than a migration.
 *
 * The **IV is per file, never reused**. GCM's security collapses entirely if a key/IV pair is
 * reused across two different plaintexts, and this class writes many files under one key, so a
 * fresh IV is generated for every single [encrypt] and stored alongside the ciphertext. It is not
 * secret — only unique.
 *
 * The IV is generated **by the Cipher, not by this class**. A key from the Android Keystore is
 * created with `setRandomizedEncryptionRequired(true)`, which makes supplying your own IV an
 * error: `init(ENCRYPT_MODE, key, GCMParameterSpec(...))` throws
 * `InvalidAlgorithmParameterException: Caller-provided IV not permitted`. Passing a
 * [SecureRandom] IV here was silently fatal on every real device — [encrypt] returned null, so
 * `DiskEventQueue.enqueue` refused every event and the SDK captured nothing, while the entire JVM
 * test suite stayed green because Robolectric has no Keystore at all. Letting the provider pick
 * the IV is both the required and the stronger option: on a hardware-backed key it is generated
 * inside the secure element. Caught by `KeystoreInstrumentedTest`, which exists for this.
 *
 * GCM is authenticated: a tampered or truncated file fails [decrypt] rather than decoding into
 * something that looks like a valid event.
 */
internal class AesGcmQueueCipher(private val key: SecretKey) : QueueCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            val iv = cipher.iv

            // The on-disk layout reserves exactly IV_LENGTH bytes. Both the AndroidKeyStore and
            // the JDK providers generate 12 for GCM; refuse rather than write a file whose header
            // cannot be parsed back, which would surface later as corruption instead of as a bug
            // here.
            if (iv == null || iv.size != IV_LENGTH) return null

            val body = cipher.doFinal(plaintext)

            ByteArray(HEADER_LENGTH + body.size).also { out ->
                out[0] = VERSION
                System.arraycopy(iv, 0, out, 1, IV_LENGTH)
                System.arraycopy(body, 0, out, HEADER_LENGTH, body.size)
            }
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray? {
        // Too short to hold a header plus a tag, or not our format — refuse before touching crypto.
        if (ciphertext.size <= HEADER_LENGTH || ciphertext[0] != VERSION) return null

        return try {
            val iv = ciphertext.copyOfRange(1, HEADER_LENGTH)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            }
            cipher.doFinal(ciphertext, HEADER_LENGTH, ciphertext.size - HEADER_LENGTH)
        } catch (e: GeneralSecurityException) {
            null   // wrong key, or the file was tampered with — GCM's tag check catches both
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val VERSION: Byte = 0x01
        const val IV_LENGTH = 12               // 96 bits — the size GCM is specified for
        const val TAG_LENGTH_BITS = 128
        const val HEADER_LENGTH = 1 + IV_LENGTH
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** True when [bytes] carry this cipher's header. Cheap, allocation-free, no key needed. */
        fun looksEncrypted(bytes: ByteArray): Boolean =
            bytes.size > HEADER_LENGTH && bytes[0] == VERSION
    }
}
