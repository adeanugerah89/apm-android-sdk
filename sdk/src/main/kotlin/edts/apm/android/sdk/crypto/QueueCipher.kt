package edts.apm.android.sdk.crypto

/**
 * Byte-level encryption for queue files (SEC-08).
 *
 * Both directions return `null` on failure rather than throwing. Nothing in the SDK may take the
 * host app down (CONSTITUTION rule #1), and every caller here already has a sane answer for
 * "couldn't do it": a failed encrypt means the event is not written, a failed decrypt means the
 * file is unreadable and gets purged as [edts.apm.android.sdk.core.DropReason.UNDECODABLE].
 */
internal interface QueueCipher {
    fun encrypt(plaintext: ByteArray): ByteArray?
    fun decrypt(ciphertext: ByteArray): ByteArray?
}
