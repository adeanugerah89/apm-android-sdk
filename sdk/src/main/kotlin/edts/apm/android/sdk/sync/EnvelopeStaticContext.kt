package edts.apm.android.sdk.sync

import edts.apm.android.sdk.core.AppInfo
import edts.apm.android.sdk.core.DeviceInfo
import edts.apm.android.sdk.core.Envelope
import edts.apm.android.sdk.core.IntegrityInfo
import edts.apm.android.sdk.core.SdkHealthSnapshot
import edts.apm.android.sdk.core.SdkInfo
import edts.apm.android.sdk.core.UserIdSource
import edts.apm.android.sdk.queue.QueuedEvent

/**
 * Everything an envelope needs besides the batch of events and the health snapshot — both of
 * which are fresher-than-static and passed separately at build time. (`01` §2)
 */
internal data class EnvelopeStaticContext(
    val sdkName: String,
    val sdkVersion: String,
    val app: AppInfo,
    val device: DeviceInfo,
    val integrity: IntegrityInfo,
    val installId: String,
    val sessionId: String,
    val userId: String,
    val userIdSource: UserIdSource
)

/** Builds one outgoing envelope from a peeked batch — pure, no I/O. */
internal fun buildEnvelope(ctx: EnvelopeStaticContext, health: SdkHealthSnapshot, batch: List<QueuedEvent>): Envelope =
    Envelope(
        sdk = SdkInfo(name = ctx.sdkName, version = ctx.sdkVersion, health = health),
        app = ctx.app,
        device = ctx.device,
        integrity = ctx.integrity,
        installId = ctx.installId,
        sessionId = ctx.sessionId,
        userId = ctx.userId,
        userIdSource = ctx.userIdSource,
        events = batch.map { it.event }
    )
