package edts.apm.android.sdk.crypto

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** SEC-08 — AES-GCM at-rest encryption for queue files. */
class AesGcmQueueCipherTest {

    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val plaintext = """{"event_id":"e-1","marker":"SENSITIVE-NEEDLE"}""".toByteArray()

    @Test fun `round-trips a payload unchanged`() {
        val cipher = AesGcmQueueCipher(key())

        val decrypted = cipher.decrypt(cipher.encrypt(plaintext)!!)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test fun `the ciphertext does not contain the plaintext`() {
        val encrypted = AesGcmQueueCipher(key()).encrypt(plaintext)!!

        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("SENSITIVE-NEEDLE"))
    }

    @Test fun `output carries the version byte and a 12-byte IV`() {
        val encrypted = AesGcmQueueCipher(key()).encrypt(plaintext)!!

        assertEquals(AesGcmQueueCipher.VERSION, encrypted[0])
        assertEquals(12, AesGcmQueueCipher.IV_LENGTH)
        // header + ciphertext + 128-bit tag
        assertEquals(AesGcmQueueCipher.HEADER_LENGTH + plaintext.size + 16, encrypted.size)
    }

    @Test fun `a fresh IV is drawn per call, so the same input never yields the same bytes`() {
        val cipher = AesGcmQueueCipher(key())

        val ivs = (1..50).map { cipher.encrypt(plaintext)!!.copyOfRange(1, AesGcmQueueCipher.HEADER_LENGTH).toList() }

        assertEquals("IV reuse under one key breaks GCM entirely", 50, ivs.toSet().size)
    }

    @Test fun `a tampered byte fails the GCM tag check instead of decoding to garbage`() {
        val cipher = AesGcmQueueCipher(key())
        val encrypted = cipher.encrypt(plaintext)!!
        encrypted[encrypted.size - 5] = (encrypted[encrypted.size - 5] + 1).toByte()

        assertNull(cipher.decrypt(encrypted))
    }

    @Test fun `a flipped IV byte also fails, not just a flipped ciphertext byte`() {
        val cipher = AesGcmQueueCipher(key())
        val encrypted = cipher.encrypt(plaintext)!!
        encrypted[3] = (encrypted[3] + 1).toByte()

        assertNull(cipher.decrypt(encrypted))
    }

    @Test fun `the wrong key returns null rather than throwing`() {
        val encrypted = AesGcmQueueCipher(key()).encrypt(plaintext)!!

        assertNull(AesGcmQueueCipher(key()).decrypt(encrypted))
    }

    @Test fun `truncated, empty and foreign input are refused`() {
        val cipher = AesGcmQueueCipher(key())
        val encrypted = cipher.encrypt(plaintext)!!

        assertNull(cipher.decrypt(ByteArray(0)))
        assertNull(cipher.decrypt(encrypted.copyOfRange(0, AesGcmQueueCipher.HEADER_LENGTH)))
        assertNull(cipher.decrypt("""{"plain":"json"}""".toByteArray()))
    }

    @Test fun `looksEncrypted separates our format from plain JSON`() {
        val encrypted = AesGcmQueueCipher(key()).encrypt(plaintext)!!

        assertTrue(AesGcmQueueCipher.looksEncrypted(encrypted))
        assertFalse(AesGcmQueueCipher.looksEncrypted(plaintext))
        assertFalse(AesGcmQueueCipher.looksEncrypted(ByteArray(0)))
    }
}
