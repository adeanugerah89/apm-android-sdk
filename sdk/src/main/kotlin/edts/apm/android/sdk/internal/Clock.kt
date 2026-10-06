package edts.apm.android.sdk.internal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Time utilities. Injectable in tests.
 * Produces ISO-8601 UTC strings matching iOS output format.
 *
 * Uses SimpleDateFormat, not java.time, because Instant/DateTimeFormatter require API 26+
 * and minSdk is 24. A new formatter is built per call since SimpleDateFormat is not
 * thread-safe and Clock is a shared singleton.
 */
object Clock {
    private const val PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

    fun nowIso8601(): String = iso8601(nowMillis())

    /** [epochMillis] as the same UTC string every other event carries. */
    fun iso8601(epochMillis: Long): String = newFormatter().format(Date(epochMillis))

    fun nowMillis(): Long = System.currentTimeMillis()

    private fun newFormatter(): SimpleDateFormat =
        SimpleDateFormat(PATTERN, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}
