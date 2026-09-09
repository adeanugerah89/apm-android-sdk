package edts.apm.android.sdk.core

import java.util.UUID

/**
 * Manages session_id lifecycle.
 * Rule: session_id resets when app returns to foreground after >30s in background. (§2)
 *
 * Thread-safe via @Volatile on scalar fields and synchronized session rotation.
 * Clock is injectable for unit testing.
 */
class SessionManager(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val SESSION_TIMEOUT_MS = 30_000L

    @Volatile private var _sessionId: String = newId()
    @Volatile private var backgroundedAt: Long? = null

    val sessionId: String get() = _sessionId

    /** Call from ProcessLifecycleOwner.onStop() */
    fun onBackground() {
        backgroundedAt = clock()
    }

    /**
     * Call from ProcessLifecycleOwner.onStart().
     * Returns the current (possibly new) session_id — callers should re-snapshot the envelope.
     */
    fun onForeground(): String {
        val bg = backgroundedAt ?: return _sessionId
        backgroundedAt = null
        if (clock() - bg > SESSION_TIMEOUT_MS) {
            _sessionId = newId()
        }
        return _sessionId
    }

    private fun newId() = UUID.randomUUID().toString()
}
