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

    // ─── A28 · the session an event was captured in ─────────────────────────────

    @Test fun `the session survives the disk round-trip`() {
        val codec = JsonEventCodec()
        val decoded = codec.decode(codec.encode(event().copy(sessionId = "session-A"))!!)!!

        assertEquals("session-A", decoded.sessionId)
        assertEquals(event().copy(sessionId = "session-A"), decoded)
    }

    @Test fun `a file written before sessions were recorded still reads, with no session`() {
        // The bare-event shape every build before A28 wrote.
        val legacy = kotlinx.serialization.json.Json { encodeDefaults = true }
            .encodeToString(Event.serializer(), event()).toByteArray()

        val decoded = JsonEventCodec().decode(legacy)!!

        assertEquals(event(), decoded)
        assertNull(decoded.sessionId)
    }

    @Test fun `the session is NOT part of the wire event`() {
        val wire = kotlinx.serialization.json.Json { encodeDefaults = true }
            .encodeToString(Event.serializer(), event().copy(sessionId = "session-A"))

        assertFalse("01 section 3 defines no per-event session_id: $wire", wire.contains("session"))
    }

    @Test fun `an encrypted file keeps the session too, and encryption is not weakened`() {
        val codec = EncryptedEventCodec(AesGcmQueueCipher(key()))
        val bytes = codec.encode(event("SENSITIVE-NEEDLE").copy(sessionId = "session-A"))!!

        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("session-A"))   // not readable at rest
        assertEquals("session-A", codec.decode(bytes)!!.sessionId)
    }

    // ─── A39 · app and OS identity at capture ───────────────────────────────────

    @Test fun `app version, build and OS version survive the disk round-trip`() {
        val e = event().copy(sessionId = "s", appVersion = "2.1.0", appBuild = "210", osVersion = "14")
        val codec = JsonEventCodec()

        assertEquals(e, codec.decode(codec.encode(e)!!))
    }

    @Test fun `none of the capture-time facts is part of the wire event`() {
        val wire = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(
            Event.serializer(), event().copy(sessionId = "s", appVersion = "2.1.0", appBuild = "210", osVersion = "14")
        )

        assertFalse(wire, listOf("2.1.0", "app_version", "app_build", "os_version", "session").any { wire.contains(it) })
    }

    @Test fun `a file written by A28, with a session but no app facts, still reads`() {
        val a28 = """{"event":${kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(Event.serializer(), event())},"session_id":"s-old"}"""

        val decoded = JsonEventCodec().decode(a28.toByteArray())!!

        assertEquals("s-old", decoded.sessionId)
        assertNull(decoded.appVersion)
    }

    @Test fun `the identity epoch survives the disk round-trip and is not on the wire`() {
        val e = event().copy(identityEpoch = 7)
        val codec = JsonEventCodec()

        assertEquals(7, codec.decode(codec.encode(e)!!)!!.identityEpoch)
        val wire = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(Event.serializer(), e)
        assertFalse(wire, wire.contains("epoch"))
    }

    // ─── review finding 8 · telling the two file shapes apart without a second parse ───────────────

    @Test fun `a legacy bare event whose attrs contain a key named event is still read as a bare event`() {
        val legacy = event().copy(attrs = buildJsonObject { put("event", "not the wrapper") })
        val bytes = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(Event.serializer(), legacy).toByteArray()

        val decoded = JsonEventCodec().decode(bytes)

        assertEquals(legacy, decoded)
        assertNull(decoded!!.sessionId)
    }

    @Test fun `a written file starts with the event key, which is what the cheap check relies on`() {
        val bytes = JsonEventCodec().encode(event().copy(sessionId = "s"))!!

        assertTrue(String(bytes, Charsets.UTF_8).startsWith("{\"event\":"))
    }

    @Test fun `a truncated file is unreadable rather than half-read`() {
        val bytes = JsonEventCodec().encode(event().copy(sessionId = "s"))!!

        assertNull(JsonEventCodec().decode(bytes.copyOf(bytes.size / 2)))
    }
}
