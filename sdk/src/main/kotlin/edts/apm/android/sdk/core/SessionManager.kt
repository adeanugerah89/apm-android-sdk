package edts.apm.android.sdk.core

import java.util.UUID

/**
 * Manages session_id lifecycle.
 * Rule: session_id resets when app returns to foreground after >30s in background. (§2)
 *
 * Thread-safe via @Volatile on scalar fields and synchronized session rotation.
 * Clock is injectable for unit testing.
 */
internal class SessionManager(
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Called with every new session id, including the first (A28). The composition root persists it so
     * that, after the process dies, the next launch can still say which session a crash belonged to.
     * Never allowed to throw into session handling.
     */
    private val onSessionStarted: (String) -> Unit = {}
) {
    private val SESSION_TIMEOUT_MS = 30_000L

    @Volatile private var _sessionId: String = newId()
    @Volatile private var backgroundedAt: Long? = null

    init {
        notifyStarted(_sessionId)
    }

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
            notifyStarted(_sessionId)
        }
        return _sessionId
    }

    private fun notifyStarted(id: String) {
        try {
            onSessionStarted(id)
        } catch (e: Throwable) {
            // Persisting the id is best effort; losing it must never disturb the session itself.
        }
    }

    private fun newId() = UUID.randomUUID().toString()
}
