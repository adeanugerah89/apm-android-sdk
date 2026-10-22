package edts.apm.android.sdk.queue

import edts.apm.android.sdk.config.RemoteConfig
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventType

/**
 * Kill-switch and per-feature gate at the **capture** end of the pipeline (MOB-21).
 *
 * Compose it OUTSIDE the scrubbing queue —
 * `GatedEventQueue(ScrubbingEventQueue(DiskEventQueue(...)))` — so a rejected event is discarded
 * before it is scrubbed or serialized rather than after. That ordering keeps the fixed pipeline
 * Capture → Scrub → Disk → Sync intact: this decorator only ever removes work from the front of
 * it, never reorders it.
 *
 * A gated-out event is **not** counted as a drop in `sdk.health`. `dropped` means "the SDK lost
 * data it meant to keep" (§2.3) and is read as an integration warning; an operator deliberately
 * turning capture off is not a fault, and counting it would make every killed app look broken.
 *
 * [peek]/[delete]/[stats] pass through unconditionally. Events already on disk when the switch
 * flipped stay there: upload suppression is the sync side's job (see
 * [edts.apm.android.sdk.sync.SyncEngine]), and if the switch flips back on, that backlog is
 * still valid data that was captured while capture was legitimately enabled.
 */
internal class GatedEventQueue(
    private val delegate: EventQueue,
    private val configProvider: () -> RemoteConfig
) : EventQueue {

    override fun enqueue(event: Event): Boolean {
        val config = try {
            configProvider()
        } catch (e: Exception) {
            RemoteConfig.DEFAULT   // never let a gate failure stop capture (CONSTITUTION rule #1)
        }
        if (!config.enabled) return false
        featureFor(event.type)?.let { if (config.isDisabled(it)) return false }
        return delegate.enqueue(event)
    }

    override fun peek(maxCount: Int): List<QueuedEvent> = delegate.peek(maxCount)

    override fun delete(ids: List<String>) = delegate.delete(ids)

    override fun stats(): QueueStats = delegate.stats()

    private companion object {
        /**
         * Event types with no `disabled_features` name map to null and are always allowed:
         * `error` is an explicit host-app call, and `lifecycle`/`termination` are the low-volume
         * signals you least want to lose. Only [RemoteConfig.Feature] names can gate anything.
         */
        fun featureFor(type: EventType): RemoteConfig.Feature? = when (type) {
            EventType.NETWORK, EventType.NETWORK_FAILURE -> RemoteConfig.Feature.NETWORK
            EventType.BREADCRUMB -> RemoteConfig.Feature.BREADCRUMB
            EventType.CRASH -> RemoteConfig.Feature.CRASH
            EventType.ERROR, EventType.LIFECYCLE, EventType.PERFORMANCE, EventType.TERMINATION -> null
        }
    }
}
