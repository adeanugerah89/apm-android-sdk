package edts.apm.android.sdk.crash

import edts.apm.android.sdk.internal.Clock
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Turns the timestamp string inside an Android tombstone into the ISO-8601 UTC form every other
 * event's `ts_client` uses.
 *
 * The tombstone writes it as `2026-09-09 11:14:38.641294490+0700` — a space instead of `T`, a
 * numeric offset **without** a colon, and nine fractional digits. None of that is RFC3339, and the
 * backend's `parseLenientTime` accepts only RFC3339[.Nano]: sent verbatim, the crash is stored with
 * a NULL `ts_client` and a corrupt-sample row. (apm-backend `internal/worker/envelope.go`.)
 *
 * Returns `null` for anything that is not exactly that shape rather than guessing — the caller has
 * a better fallback (the OS's own exit time) than a half-parsed string.
 */
internal object TombstoneTimestamp {

    private val SHAPE = Regex("""^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})(?:\.(\d+))?([+-])(\d{2})(\d{2})$""")

    fun toIso8601(raw: String?): String? {
        val m = SHAPE.matchEntire(raw?.trim() ?: return null) ?: return null
        val (date, time, fraction, sign, offsetHours, offsetMinutes) = m.destructured

        val hours = offsetHours.toInt()
        val minutes = offsetMinutes.toInt()
        if (hours > 14 || minutes > 59) return null

        // The wall-clock part is read as if it were UTC, then the stated offset is taken off it.
        val wallClockAsUtc = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }.parse("$date $time")?.time
        } catch (e: java.text.ParseException) {
            null
        } ?: return null

        val offsetMs = (hours * 60 + minutes) * 60_000L * (if (sign == "-") -1 else 1)
        val millis = fraction.take(3).padEnd(3, '0').toLong()   // sub-millisecond digits are dropped, not rounded

        return Clock.iso8601(wallClockAsUtc - offsetMs + millis)
    }
}
