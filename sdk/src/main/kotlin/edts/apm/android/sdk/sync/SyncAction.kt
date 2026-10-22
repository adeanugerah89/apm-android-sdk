package edts.apm.android.sdk.sync

/**
 * What the SDK must do with the current batch, decided purely from the ingest response —
 * the response contract table in `01` §7. No I/O here, so the mapping is fully unit-testable.
 */
internal sealed class SyncAction {
    /** 202 — batch accepted, safe to delete from disk (MOB-09). */
    object DeleteBatch : SyncAction()

    /** 400 — payload will never be accepted. Discard rather than retry forever (`01` §7). */
    data class DropBatch(val reason: String) : SyncAction()

    /** 401/403 — invalid key. Stop trying for [durationMs]; data stays on disk untouched. */
    data class DisableSending(val durationMs: Long) : SyncAction()

    /** 413 — payload too large. Halve the batch size and retry immediately; nothing deleted. */
    object SplitAndRetry : SyncAction()

    /** 429 / 5xx / transport failure / anything unrecognized — wait [delayMs], data untouched. */
    data class Backoff(val delayMs: Long) : SyncAction()

    companion object {
        const val DISABLE_DURATION_MS = 24 * 60 * 60 * 1000L

        /**
         * [retryAfterSeconds] comes from a `Retry-After` header when present (429). [attempt] is
         * the 1-indexed count of consecutive failures, used for exponential backoff (429 without
         * a header, and all 5xx).
         */
        fun forResponse(statusCode: Int, retryAfterSeconds: Long?, attempt: Int): SyncAction = when (statusCode) {
            202 -> DeleteBatch
            400 -> DropBatch("malformed_payload")
            401, 403 -> DisableSending(DISABLE_DURATION_MS)
            413 -> SplitAndRetry
            429 -> Backoff(retryAfterSeconds?.let { it * 1000 } ?: BackoffPolicy.delayFor(attempt))
            in 500..599 -> Backoff(BackoffPolicy.delayFor(attempt))
            // Fail safe on anything the contract doesn't define: keep data, back off.
            else -> Backoff(BackoffPolicy.delayFor(attempt))
        }

        /** A transport-level failure (no HTTP response at all) — treat the same as a 5xx. */
        fun forTransportFailure(attempt: Int): SyncAction = Backoff(BackoffPolicy.delayFor(attempt))
    }
}
