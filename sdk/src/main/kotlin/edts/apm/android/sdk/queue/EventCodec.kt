package edts.apm.android.sdk.queue

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.crypto.AesGcmQueueCipher
import edts.apm.android.sdk.crypto.QueueCipher
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Turns an [Event] into the exact bytes stored in a queue file, and back.
 *
 * This is the seam at-rest encryption needed (SEC-08). [EventQueue]'s docs used to promise that
 * encryption would arrive as a decorator around the *queue* interface, the way
 * [edts.apm.android.sdk.scrub.ScrubbingEventQueue] does — but that interface deals in `Event`
 * objects, and a decorator above it never sees the bytes that reach disk. Encryption has to sit
 * where serialization already lives, so it goes here instead.
 *
 * Both directions return `null` on failure. A null from [encode] means nothing is written; a null
 * from [decode] means the file is unreadable and is purged by [DiskEventQueue].
 */
interface EventCodec {
    fun encode(event: Event): ByteArray?
    fun decode(bytes: ByteArray): Event?
}

/** Plain JSON — the format every build before A14 wrote, and the fallback when there is no key. */
class JsonEventCodec(
    private val json: Json = Json { encodeDefaults = true }
) : EventCodec {

    override fun encode(event: Event): ByteArray? = try {
        json.encodeToString(event).toByteArray(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    override fun decode(bytes: ByteArray): Event? = try {
        json.decodeFromString<Event>(bytes.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }
}

/**
 * JSON, then AES-GCM (SEC-08). Writes are always encrypted; reads accept both formats.
 *
 * Reading plaintext is not a weakening — it is the upgrade path. A device that updates to a build
 * with encryption still has files written by the previous build sitting in its queue, and those
 * events are real telemetry. Refusing them would silently discard a queue's worth of data on every
 * upgrade. The two formats are told apart by [AesGcmQueueCipher.looksEncrypted], not guessed at:
 * plain JSON begins with `{`, an encrypted file begins with the version byte 0x01.
 *
 * SEC-09's "crash reports are written unencrypted" exception does **not** apply to this path.
 * That exception exists because a native signal handler cannot safely allocate, and Android's
 * JVM crash path (A9) runs in ordinary JVM context — see [edts.apm.android.sdk.crash.JvmCrashHandler].
 * It will apply to the native path when A9b lands.
 */
class EncryptedEventCodec(
    private val cipher: QueueCipher,
    private val plain: JsonEventCodec = JsonEventCodec()
) : EventCodec {

    override fun encode(event: Event): ByteArray? =
        plain.encode(event)?.let { cipher.encrypt(it) }

    override fun decode(bytes: ByteArray): Event? =
        if (AesGcmQueueCipher.looksEncrypted(bytes)) {
            cipher.decrypt(bytes)?.let { plain.decode(it) }
        } else {
            plain.decode(bytes)   // written before this build encrypted anything
        }
}
