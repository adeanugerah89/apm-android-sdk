package edts.apm.android.sdk.internal

/**
 * Just enough protobuf wire format to read an Android system tombstone
 * ([edts.apm.android.sdk.crash.SystemTombstoneParser]).
 *
 * Hand-written rather than generated because the alternative is a protobuf runtime plus a codegen
 * plugin, and CONSTITUTION's dependency list is deliberately short. The wire format is small and
 * stable — tag, wire type, value — and only four of the six wire types have ever existed. Reading
 * a message this way needs no schema at all, which is also why the field numbers this SDK relies
 * on were derived from real tombstone bytes rather than from a copy of `Tombstone.proto`.
 *
 * Unknown fields are skipped, so a newer Android adding fields cannot break parsing. Malformed
 * input raises [ProtoException], which callers turn into "no crash report" rather than a crash.
 */
internal class ProtoReader(private val bytes: ByteArray, private var pos: Int = 0, private val end: Int = bytes.size) {

    fun hasMore(): Boolean = pos < end

    /** Reads the next tag. Returns field number; [lastWireType] holds its wire type. */
    fun readTag(): Int {
        val key = readVarint().toInt()
        lastWireType = key and 0x7
        val field = key ushr 3
        if (field == 0) throw ProtoException("field number 0")
        return field
    }

    var lastWireType: Int = 0
        private set

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw ProtoException("truncated varint")
            if (shift > 63) throw ProtoException("varint too long")
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    /** Length-delimited payload — a nested message, a string, or raw bytes. */
    fun readLengthDelimited(): ByteArray {
        val len = readVarint().toInt()
        if (len < 0 || pos + len > end) throw ProtoException("truncated length-delimited field")
        val out = bytes.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun readString(): String = String(readLengthDelimited(), Charsets.UTF_8)

    /** A nested message, read in place without copying. */
    fun readMessage(): ProtoReader {
        val len = readVarint().toInt()
        if (len < 0 || pos + len > end) throw ProtoException("truncated message")
        val sub = ProtoReader(bytes, pos, pos + len)
        pos += len
        return sub
    }

    /** Skips a field of the wire type just read. Required for forward compatibility. */
    fun skipValue() {
        when (lastWireType) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> advance(8)
            WIRE_LENGTH -> advance(readVarint().toInt())
            WIRE_FIXED32 -> advance(4)
            else -> throw ProtoException("unsupported wire type $lastWireType")
        }
    }

    private fun advance(n: Int) {
        if (n < 0 || pos + n > end) throw ProtoException("truncated field")
        pos += n
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5
    }
}

internal class ProtoException(message: String) : Exception(message)
