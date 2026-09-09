package edts.apm.android.sdk.sync

/**
 * Exponential backoff for `5xx` / unrecognized ingest responses. (MOB-07, `01` §7)
 * Sequence: 30s -> 60s -> 120s -> ... capped at 30 minutes.
 */
object BackoffPolicy {
    const val INITIAL_DELAY_MS = 30_000L
    const val MAX_DELAY_MS = 30 * 60 * 1000L
    private const val MAX_SHIFT = 20  // guards against Long overflow from shifting too far

    /** [attempt] is 1-indexed: the 1st failure yields [INITIAL_DELAY_MS], doubling from there. */
    fun delayFor(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, MAX_SHIFT)
        val exponential = INITIAL_DELAY_MS * (1L shl shift)
        return exponential.coerceAtMost(MAX_DELAY_MS)
    }
}
