package edts.apm.android.sdk.sync

import edts.apm.android.sdk.core.AppInfo
import edts.apm.android.sdk.core.DeviceInfo
import edts.apm.android.sdk.core.Envelope
import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import edts.apm.android.sdk.core.IntegrityInfo
import edts.apm.android.sdk.core.SdkHealth
import edts.apm.android.sdk.core.UserIdSource
import edts.apm.android.sdk.queue.DiskEventQueue
import edts.apm.android.sdk.queue.EventQueue
import edts.apm.android.sdk.queue.JsonEventCodec
import edts.apm.android.sdk.queue.QueueStats
import edts.apm.android.sdk.queue.QueuedEvent
import edts.apm.android.sdk.queue.SessionStampingEventQueue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * A28 — events must reach the backend under the session they were captured in.
 *
 * `apm-backend` writes the **envelope's** `session_id` onto every event it contains, and an envelope
 * used to be stamped with whatever session was current at upload time. So a backlog from an earlier
 * session, or a crash recovered at the next launch, was filed under the wrong session.
 */
class SessionAttributionTest {

    @get:Rule val tmp = TemporaryFolder()

    private class CapturingUploader : Uploader {
        val envelopes = mutableListOf<Envelope>()
        override fun upload(envelope: Envelope): UploadResult { envelopes += envelope; return UploadResult.HttpResponse(202, null) }
    }

    private fun context(currentSession: String) = EnvelopeStaticContext(
        sdkName = "apm-android-sdk", sdkVersion = "0.1.0",
        app = AppInfo("edts.apm.android.sdk", "1.0", "100"),
        device = DeviceInfo(osVersion = "14", model = "Pixel 9", locale = "id_ID", timezone = "Asia/Jakarta"),
        integrity = IntegrityInfo(), installId = "install-1", sessionId = currentSession,
        userId = "user-1", userIdSource = UserIdSource.HOST
    )

    private var seq = 0L
    private fun event(label: String, sessionId: String? = null) = Event(
        eventId = UUID.randomUUID().toString(), type = EventType.ERROR, tsClient = "2026-09-07T00:00:00.000Z",
        seq = ++seq, attrs = JsonObject(mapOf("label" to kotlinx.serialization.json.JsonPrimitive(label))),
        ctx = EventContext(), sessionId = sessionId
    )

    private fun labels(e: Envelope) = e.events.map { it.attrs.getValue("label").toString().trim('"') }

    // ─── SyncEngine, with a plain in-memory queue ───────────────────────────────

    private class ListQueue(val items: MutableList<QueuedEvent>) : EventQueue {
        override fun enqueue(event: Event) = true
        override fun peek(maxCount: Int) = items.take(maxCount)
        override fun delete(ids: List<String>) { items.removeAll { it.id in ids } }
        override fun stats() = QueueStats(items.size, 0)
    }

    private fun queued(vararg events: Event) =
        ListQueue(events.mapIndexed { i, e -> QueuedEvent("id-$i", e, 10) }.toMutableList())

    @Test fun `a batch spanning two sessions goes out as two envelopes, each under its own session`() {
        val queue = queued(event("a1", "A"), event("a2", "A"), event("b1", "B"))
        val uploader = CapturingUploader()
        val engine = SyncEngine(queue, uploader, SdkHealth(), { context("B-current") })

        assertEquals(SyncOutcome.Sent(2), engine.syncOnce())
        assertEquals(SyncOutcome.Sent(1), engine.syncOnce())

        assertEquals(listOf("A", "B"), uploader.envelopes.map { it.sessionId })
        assertEquals(listOf(listOf("a1", "a2"), listOf("b1")), uploader.envelopes.map(::labels))
        assertTrue(queue.items.isEmpty())
    }

    @Test fun `only the leading run is uploaded and deleted, the rest stays on disk`() {
        val queue = queued(event("a1", "A"), event("b1", "B"))
        val engine = SyncEngine(queue, CapturingUploader(), SdkHealth(), { context("B") })

        engine.syncOnce()

        assertEquals(listOf("id-1"), queue.items.map { it.id })   // b1 untouched until its own envelope
    }

    @Test fun `the caller is told to continue immediately, so the second session is not left for the next cycle`() {
        val queue = queued(event("a1", "A"), event("b1", "B"))
        val outcome = SyncEngine(queue, CapturingUploader(), SdkHealth(), { context("B") }).syncOnce()

        assertTrue(outcome.shouldContinueImmediately)
    }

    @Test fun `an event with no recorded session keeps the old behaviour and uses the current session`() {
        val queue = queued(event("legacy-1"), event("legacy-2"))
        val uploader = CapturingUploader()

        SyncEngine(queue, uploader, SdkHealth(), { context("current") }).syncOnce()

        assertEquals("current", uploader.envelopes.single().sessionId)
        assertEquals(2, uploader.envelopes.single().events.size)
    }

    @Test fun `a single-session batch is still one envelope, so the common case costs no extra request`() {
        val queue = queued(event("a1", "A"), event("a2", "A"), event("a3", "A"))
        val uploader = CapturingUploader()

        SyncEngine(queue, uploader, SdkHealth(), { context("A") }).syncOnce()

        assertEquals(1, uploader.envelopes.size)
        assertEquals(3, uploader.envelopes.single().events.size)
    }

    // ─── The joined path: real stamping queue + real disk queue + real SyncEngine ─

    @Test fun `end to end - events captured in session A and uploaded in session B are filed under A`() {
        var current = "session-A"
        val disk = DiskEventQueue(tmp.newFolder("queue"), SdkHealth(), JsonEventCodec())
        val capture = SessionStampingEventQueue(disk) { current }

        capture.enqueue(event("offline-1")); Thread.sleep(5)
        capture.enqueue(event("offline-2")); Thread.sleep(5)
        current = "session-B"                                    // the app was backgrounded >30 s and came back
        capture.enqueue(event("fresh-1"))

        val uploader = CapturingUploader()
        // The engine builds its context at upload time, when the current session is B.
        val engine = SyncEngine(disk, uploader, SdkHealth(), { context(current) })
        while (engine.syncOnce().shouldContinueImmediately) { /* drain */ }

        assertEquals(listOf("session-A", "session-B"), uploader.envelopes.map { it.sessionId })
        assertEquals(listOf(listOf("offline-1", "offline-2"), listOf("fresh-1")), uploader.envelopes.map(::labels))
        assertEquals(0, disk.stats().eventCount)
    }

    @Test fun `end to end - a recovered crash keeps the previous session even though newer events sit behind it`() {
        val disk = DiskEventQueue(tmp.newFolder("queue2"), SdkHealth(), JsonEventCodec())
        val capture = SessionStampingEventQueue(disk) { "session-NEW" }

        // What the next launch does: the crash comes from the previous process and is pre-stamped.
        capture.enqueue(event("native-crash", sessionId = "session-PREVIOUS")); Thread.sleep(5)
        capture.enqueue(event("first-event-of-new-run"))

        val uploader = CapturingUploader()
        val engine = SyncEngine(disk, uploader, SdkHealth(), { context("session-NEW") })
        while (engine.syncOnce().shouldContinueImmediately) { /* drain */ }

        assertEquals(listOf("session-PREVIOUS", "session-NEW"), uploader.envelopes.map { it.sessionId })
    }

    @Test fun `on the wire the per-event session never appears, only the envelope's`() {
        val queue = queued(event("a1", "A"))
        val uploader = CapturingUploader()
        SyncEngine(queue, uploader, SdkHealth(), { context("A") }).syncOnce()

        val wire = Json { encodeDefaults = true }.encodeToJsonElement(Envelope.serializer(), uploader.envelopes.single()).jsonObject

        assertEquals("A", wire.getValue("session_id").toString().trim('"'))
        val events: JsonArray = wire.getValue("events").jsonArray
        assertFalse("01 section 3 defines no per-event session_id", events.any { it.jsonObject.containsKey("session_id") })
    }

    // ─── A39 · the app version and OS an event was captured under ───────────────

    private fun eventAt(label: String, version: String?, build: String?, os: String?, session: String? = "S") =
        event(label, session).copy(appVersion = version, appBuild = build, osVersion = os)

    @Test fun `a crash captured on the old version goes out under the old version, after an update`() {
        val queue = queued(eventAt("old-crash", "1.0", "100", "13"), eventAt("new-event", "2.0", "200", "14"))
        val uploader = CapturingUploader()
        // The engine's own context describes the app as it is NOW, at upload: 2.0 / 200 / Android 14.
        val engine = SyncEngine(queue, uploader, SdkHealth(), { context("S").copy(app = AppInfo("edts.apm.android.sdk", "2.0", "200")) })

        while (engine.syncOnce().shouldContinueImmediately) { /* drain */ }

        assertEquals(listOf("1.0" to "100", "2.0" to "200"), uploader.envelopes.map { it.app.version to it.app.build })
        assertEquals(listOf("13", "14"), uploader.envelopes.map { it.device.osVersion })
        assertEquals(listOf(listOf("old-crash"), listOf("new-event")), uploader.envelopes.map(::labels))
    }

    @Test fun `events from one version in one session stay in a single envelope`() {
        val queue = queued(eventAt("a", "1.0", "100", "13"), eventAt("b", "1.0", "100", "13"), eventAt("c", "1.0", "100", "13"))
        val uploader = CapturingUploader()

        SyncEngine(queue, uploader, SdkHealth(), { context("S") }).syncOnce()

        assertEquals(1, uploader.envelopes.size)
        assertEquals(3, uploader.envelopes.single().events.size)
    }

    @Test fun `an OS update alone also splits the run`() {
        val queue = queued(eventAt("a", "1.0", "100", "13"), eventAt("b", "1.0", "100", "14"))
        val uploader = CapturingUploader()
        val engine = SyncEngine(queue, uploader, SdkHealth(), { context("S") })

        while (engine.syncOnce().shouldContinueImmediately) { /* drain */ }

        assertEquals(listOf("13", "14"), uploader.envelopes.map { it.device.osVersion })
    }

    @Test fun `events with no recorded app facts use the upload-time context, as before`() {
        val queue = queued(event("legacy"))
        val uploader = CapturingUploader()

        SyncEngine(queue, uploader, SdkHealth(), { context("S").copy(app = AppInfo("edts.apm.android.sdk", "9.9", "999")) }).syncOnce()

        assertEquals("9.9" to "999", uploader.envelopes.single().app.let { it.version to it.build })
    }

    @Test fun `end to end - events captured before an app update are filed under the old version`() {
        var app = edts.apm.android.sdk.core.AppStamp("1.0", "100", "13")
        val disk = DiskEventQueue(tmp.newFolder("queue-a39"), SdkHealth(), JsonEventCodec())
        val capture = SessionStampingEventQueue(disk, { app }) { "session-A" }

        capture.enqueue(event("before-update")); Thread.sleep(5)
        app = edts.apm.android.sdk.core.AppStamp("2.0", "200", "14")   // the user updated the app; same session id here on purpose
        capture.enqueue(event("after-update"))

        val uploader = CapturingUploader()
        val engine = SyncEngine(disk, uploader, SdkHealth(), { context("session-A").copy(app = AppInfo("edts.apm.android.sdk", "2.0", "200")) })
        while (engine.syncOnce().shouldContinueImmediately) { /* drain */ }

        assertEquals(listOf("1.0", "2.0"), uploader.envelopes.map { it.app.version })
        assertEquals(listOf(listOf("before-update"), listOf("after-update")), uploader.envelopes.map(::labels))
    }

    @Test fun `on the wire the per-event app facts never appear`() {
        val queue = queued(eventAt("a", "1.0", "100", "13"))
        val uploader = CapturingUploader()
        SyncEngine(queue, uploader, SdkHealth(), { context("S") }).syncOnce()

        val wire = Json { encodeDefaults = true }.encodeToJsonElement(Envelope.serializer(), uploader.envelopes.single()).jsonObject
        val events = wire.getValue("events").jsonArray

        assertFalse(events.any { e -> e.jsonObject.keys.any { it.contains("version") || it.contains("build") || it == "os_version" } })
        assertEquals("1.0", wire.getValue("app").jsonObject.getValue("version").toString().trim('"'))
    }

    // ─── A39 · the user an event was captured under ─────────────────────────────

    private fun identityProvider(): edts.apm.android.sdk.core.UserIdProvider =
        edts.apm.android.sdk.core.UserIdProvider(
            edts.apm.android.sdk.internal.NoBackupPrefs(tmp.newFile("id-${System.nanoTime()}.json").also { it.delete() }, java.util.concurrent.Executor { it.run() }),
            persistence = java.util.concurrent.Executor { it.run() }
        )

    private fun asUser(epoch: Int?, label: String) = event(label, "S").copy(identityEpoch = epoch)

    private fun engineFor(queue: EventQueue, ids: edts.apm.android.sdk.core.UserIdProvider, uploader: CapturingUploader) =
        SyncEngine(queue, uploader, SdkHealth(),
            { context("S").copy(userId = ids.userId, userIdSource = ids.userIdSource) },
            identityFor = { ids.identityFor(it) })

    @Test fun `a backlog from user X is not uploaded as user Y after Y signs in - the bug A39 fixes`() {
        val ids = identityProvider()
        ids.setUser("user-X"); val epochX = ids.currentEpoch
        ids.setUser("user-Y"); val epochY = ids.currentEpoch
        val queue = queued(asUser(epochX, "x-1"), asUser(epochX, "x-2"), asUser(epochY, "y-1"))
        val uploader = CapturingUploader()

        val engine = engineFor(queue, ids, uploader)
        while (engine.syncOnce().shouldContinueImmediately) { /* drain */ }

        assertEquals(listOf("user-X", "user-Y"), uploader.envelopes.map { it.userId })
        assertEquals(listOf(listOf("x-1", "x-2"), listOf("y-1")), uploader.envelopes.map(::labels))
        assertEquals(listOf(UserIdSource.HOST, UserIdSource.HOST), uploader.envelopes.map { it.userIdSource })
    }

    @Test fun `events captured after a logout go out anonymous, with source generated`() {
        val ids = identityProvider()
        ids.setUser("user-X"); val epochX = ids.currentEpoch
        ids.clearUser(); val loggedOut = ids.currentEpoch
        val queue = queued(asUser(epochX, "while-signed-in"), asUser(loggedOut, "after-logout"))
        val uploader = CapturingUploader()

        val engine = engineFor(queue, ids, uploader)
        while (engine.syncOnce().shouldContinueImmediately) { /* drain */ }

        assertEquals("user-X", uploader.envelopes[0].userId)
        assertEquals(UserIdSource.GENERATED, uploader.envelopes[1].userIdSource)
        assertNotEquals("user-X", uploader.envelopes[1].userId)
    }

    @Test fun `after a restart, an event from an era the new process never saw is never sent as the current user`() {
        val shared = tmp.newFile("identity-shared.json").also { it.delete() }
        val inline = java.util.concurrent.Executor { it.run() }
        val before = edts.apm.android.sdk.core.UserIdProvider(edts.apm.android.sdk.internal.NoBackupPrefs(shared, inline), persistence = inline)
        before.setUser("user-W"); val epochW = before.currentEpoch      // era 1
        before.setUser("user-X")                                         // era 2 — the one persisted

        // A new process: it knows era 2 (the persisted identity) but never saw era 1.
        val after = edts.apm.android.sdk.core.UserIdProvider(edts.apm.android.sdk.internal.NoBackupPrefs(shared, inline), persistence = inline)
        after.setUser("user-Y")                                          // era 3
        val queue = queued(asUser(epochW, "from-w"))
        val uploader = CapturingUploader()

        engineFor(queue, after, uploader).syncOnce()

        val envelope = uploader.envelopes.single()
        assertEquals("unknown era: anonymous", UserIdSource.GENERATED, envelope.userIdSource)
        assertNotEquals("user-Y", envelope.userId)   // the person who is signed in now did not write this
        assertNotEquals("user-W", envelope.userId)   // and we cannot prove it was W either
    }

    @Test fun `events with no recorded epoch keep the old behaviour - whoever is current`() {
        val ids = identityProvider(); ids.setUser("user-Y")
        val queue = queued(event("legacy", "S"))
        val uploader = CapturingUploader()

        engineFor(queue, ids, uploader).syncOnce()

        assertEquals("user-Y", uploader.envelopes.single().userId)
    }

    @Test fun `end to end - captured as X, uploaded after Y signs in, with a real disk queue`() {
        val ids = identityProvider()
        val disk = DiskEventQueue(tmp.newFolder("queue-identity"), SdkHealth(), JsonEventCodec())
        val capture = SessionStampingEventQueue(disk, currentIdentityEpoch = { ids.currentEpoch }) { "S" }

        ids.setUser("user-X")
        capture.enqueue(event("as-x")); Thread.sleep(5)
        ids.setUser("user-Y")                      // X signs out, Y signs in, before the upload ran
        capture.enqueue(event("as-y"))

        val uploader = CapturingUploader()
        val engine = engineFor(disk, ids, uploader)
        while (engine.syncOnce().shouldContinueImmediately) { /* drain */ }

        assertEquals(listOf("user-X", "user-Y"), uploader.envelopes.map { it.userId })
        assertEquals(listOf(listOf("as-x"), listOf("as-y")), uploader.envelopes.map(::labels))
    }

    @Test fun `on the wire the identity epoch never appears`() {
        val ids = identityProvider(); ids.setUser("user-X")
        val queue = queued(asUser(ids.currentEpoch, "a"))
        val uploader = CapturingUploader()
        engineFor(queue, ids, uploader).syncOnce()

        val wire = Json { encodeDefaults = true }.encodeToJsonElement(Envelope.serializer(), uploader.envelopes.single()).jsonObject

        assertFalse(wire.toString().contains("epoch"))
        assertEquals("user-X", wire.getValue("user_id").toString().trim('"'))
    }
}
