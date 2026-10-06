package edts.apm.android.sdk.queue

import edts.apm.android.sdk.core.AppStamp
import edts.apm.android.sdk.core.Event

/**
 * Records, at the moment of capture, which session an event belongs to (A28).
 *
 * The backend stamps `session_id` from the **envelope** onto every event it contains
 * (`apm-backend` `worker.go`), and an envelope is built when events are *uploaded*, not when they
 * were captured. Without this, anything captured in one session and uploaded in a later one — an
 * offline backlog, a crash reported at the next launch — is filed under the wrong session. Stamping
 * here lets [edts.apm.android.sdk.sync.SyncEngine] build one envelope per session.
 *
 * Must sit **outermost**, on the caller's thread: behind [AsyncEventQueue] the write happens later,
 * possibly after the session has rolled over. An event that already carries a session — one
 * recovered from a previous process — is left exactly as it is.
 */
class SessionStampingEventQueue(
    private val delegate: EventQueue,
    /** App and OS identity right now (A39). Defaults to "unknown", which records nothing. */
    private val currentApp: () -> AppStamp = { AppStamp(null, null, null) },
    /** The identity epoch right now (A39). Defaults to "not recorded". */
    private val currentIdentityEpoch: () -> Int? = { null },
    // Last on purpose: callers pass the session as a trailing lambda.
    private val currentSession: () -> String
) : EventQueue {

    override fun enqueue(event: Event): Boolean {
        // Each fact is filled on its own: an event recovered from a previous process arrives with all of
        // them already set and is left exactly as it is; one that knows only some keeps those.
        val app = if (event.appVersion == null || event.appBuild == null || event.osVersion == null) currentApp() else null
        val stamped = event.copy(
            sessionId = event.sessionId ?: currentSession(),
            appVersion = event.appVersion ?: app?.version,
            appBuild = event.appBuild ?: app?.build,
            osVersion = event.osVersion ?: app?.osVersion,
            identityEpoch = event.identityEpoch ?: currentIdentityEpoch()
        )
        return delegate.enqueue(stamped)
    }

    override fun peek(maxCount: Int): List<QueuedEvent> = delegate.peek(maxCount)

    override fun delete(ids: List<String>) = delegate.delete(ids)

    override fun stats(): QueueStats = delegate.stats()
}
