package edts.apm.android.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * SDK self-health counters (MOB-27, §2.3).
 * Cumulative per install — sent in every envelope so drop spikes are visible outside the device.
 * Thread-safe: all mutations via AtomicLong / ConcurrentHashMap.
 */
class SdkHealth {
    private val written   = AtomicLong(0)
    private val sent      = AtomicLong(0)
    private val dropped   = AtomicLong(0)
    private val reasons   = ConcurrentHashMap<String, AtomicLong>()

    fun recordWritten()                       { written.incrementAndGet() }
    fun recordSent(count: Int = 1)            { sent.addAndGet(count.toLong()) }
    fun recordDropped(reason: DropReason, count: Int = 1) {
        dropped.addAndGet(count.toLong())
        reasons.getOrPut(reason.key) { AtomicLong(0) }.addAndGet(count.toLong())
    }

    fun snapshot(): SdkHealthSnapshot = SdkHealthSnapshot(
        written     = written.get(),
        sent        = sent.get(),
        dropped     = dropped.get(),
        dropReasons = reasons.mapValues { it.value.get() }
    )
}

/** Known drop reasons — open set on the wire (§2.3 "drop_reasons bersifat terbuka"). */
enum class DropReason(val key: String) {
    QUEUE_FULL("queue_full"),
    REJECTED("rejected"),          // 400 from ingest
    UNDECODABLE("undecodable"),    // file we can't parse (pre-encryption plaintext, A14)
}

/** Serializable snapshot for inclusion in the envelope. */
@Serializable
data class SdkHealthSnapshot(
    val written: Long,
    val sent: Long,
    val dropped: Long,
    @SerialName("drop_reasons") val dropReasons: Map<String, Long>
)
