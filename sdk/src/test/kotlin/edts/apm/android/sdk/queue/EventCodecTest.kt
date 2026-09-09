package edts.apm.android.sdk.queue

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.crypto.AesGcmQueueCipher
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** SEC-08 — the serialization seam, plain and encrypted. */
class EventCodecTest {

    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun event(marker: String = "SENSITIVE-NEEDLE") = Event(
        eventId = "e-1",
        type = EventType.ERROR,
        tsClient = "2026-09-07T00:00:00.000Z",
        seq = 42,
        attrs = buildJsonObject { put("message", marker) },
        ctx = EventContext(screen = "LoginActivity")
    )

    @Test fun `JsonEventCodec round-trips an event`() {
        val codec = JsonEventCodec()

        assertEquals(event(), codec.decode(codec.encode(event())!!))
    }

    @Test fun `JsonEventCodec returns null on bytes that are not an event`() {
        assertNull(JsonEventCodec().decode("not json at all".toByteArray()))
        assertNull(JsonEventCodec().decode("""{"unrelated":1}""".toByteArray()))
    }

    @Test fun `EncryptedEventCodec round-trips an event`() {
        val codec = EncryptedEventCodec(AesGcmQueueCipher(key()))

        assertEquals(event(), codec.decode(codec.encode(event())!!))
    }

    @Test fun `EncryptedEventCodec output is encrypted, not readable JSON`() {
        val bytes = EncryptedEventCodec(AesGcmQueueCipher(key())).encode(event())!!

        assertTrue(AesGcmQueueCipher.looksEncrypted(bytes))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("SENSITIVE-NEEDLE"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("LoginActivity"))
        assertNull("plain JSON decoding must not succeed on it", JsonEventCodec().decode(bytes))
    }

    @Test fun `EncryptedEventCodec still reads plaintext files written by an older build`() {
        val legacy = JsonEventCodec().encode(event())!!
        val codec = EncryptedEventCodec(AesGcmQueueCipher(key()))

        assertEquals("upgrading must not discard the existing queue", event(), codec.decode(legacy))
    }

    @Test fun `EncryptedEventCodec returns null when the key no longer matches`() {
        val written = EncryptedEventCodec(AesGcmQueueCipher(key())).encode(event())!!

        assertNull(EncryptedEventCodec(AesGcmQueueCipher(key())).decode(written))
    }

    @Test fun `EncryptedEventCodec returns null when encryption itself fails`() {
        val brokenCipher = object : edts.apm.android.sdk.crypto.QueueCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray? = null
            override fun decrypt(ciphertext: ByteArray): ByteArray? = null
        }

        assertNull(EncryptedEventCodec(brokenCipher).encode(event()))
    }
}
