package edts.apm.android.sdk

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.core.SdkHealth
import edts.apm.android.sdk.crypto.AesGcmQueueCipher
import edts.apm.android.sdk.crypto.KeystoreKeyProvider
import edts.apm.android.sdk.queue.DiskEventQueue
import edts.apm.android.sdk.queue.EncryptedEventCodec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * SEC-08 on a real device — the half of A14 that unit tests structurally cannot reach.
 *
 * Robolectric ships no `AndroidKeyStore` provider, so every JVM test of the queue runs the
 * plaintext fallback. Everything asserted here is therefore untested anywhere else: that a key can
 * actually be created, that it survives being looked up again, and that a queue built on it can
 * still read back what it wrote. A silent failure in any of those would destroy every event on
 * every real device while the whole unit suite stayed green.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreInstrumentedTest {

    private lateinit var context: Context
    private val alias = "apm_instrumented_test_key"

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test fun keyIsActuallyCreatedOnDevice() {
        val key = KeystoreKeyProvider(alias).getOrCreateKey()

        assertNotNull("the real Keystore must produce a key", key)
        assertEquals("AES", key!!.algorithm)
    }

    @Test fun theSameKeyComesBackOnASecondLookup() {
        val provider = KeystoreKeyProvider(alias)
        val first = provider.getOrCreateKey()
        val second = KeystoreKeyProvider(alias).getOrCreateKey()

        assertNotNull(first)
        assertNotNull(second)
        // Keystore handles are opaque; equality of what they encrypt is the property that matters.
        val cipher = AesGcmQueueCipher(first!!)
        val encrypted = cipher.encrypt("round trip".toByteArray())
        assertNotNull("encryption must work with a Keystore-held key", encrypted)
        assertArrayEquals(
            "a key looked up again must decrypt what the first handle wrote",
            "round trip".toByteArray(),
            AesGcmQueueCipher(second!!).decrypt(encrypted!!)
        )
    }

    @Test fun theEncryptedQueueRoundTripsWithARealKeystoreKey() {
        val key = KeystoreKeyProvider(alias).getOrCreateKey()!!
        val dir = File(context.filesDir, "instrumented-queue").apply { deleteRecursively() }
        val queue = DiskEventQueue(dir, SdkHealth(), EncryptedEventCodec(AesGcmQueueCipher(key)))

        val written = queue.enqueue(event("SENSITIVE-NEEDLE"))

        assertTrue("enqueue must succeed with a real Keystore key", written)
        val files = dir.listFiles()!!.filter { it.name.endsWith(".evt") }
        assertEquals(1, files.size)

        val bytes = files.single().readBytes()
        assertTrue("must be written in the encrypted format", AesGcmQueueCipher.looksEncrypted(bytes))
        assertFalse(
            "the payload must not be readable on disk",
            String(bytes, Charsets.ISO_8859_1).contains("SENSITIVE-NEEDLE")
        )

        val read = queue.peek(10)
        assertEquals("what was written must still be readable — a lost key purges silently", 1, read.size)
        assertTrue(read.single().event.attrs.toString().contains("SENSITIVE-NEEDLE"))

        dir.deleteRecursively()
    }

    private fun event(marker: String) = Event(
        eventId = UUID.randomUUID().toString(),
        type = EventType.ERROR,
        tsClient = "2026-09-07T00:00:00.000Z",
        seq = 1,
        attrs = buildJsonObject { put("message", marker) },
        ctx = EventContext()
    )
}
