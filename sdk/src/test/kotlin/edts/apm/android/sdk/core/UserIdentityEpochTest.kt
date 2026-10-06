package edts.apm.android.sdk.core

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import edts.apm.android.sdk.crypto.AesGcmQueueCipher
import edts.apm.android.sdk.crypto.QueueCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor
import javax.crypto.KeyGenerator

/**
 * A39 + A41 — who an event belongs to, and the host's identity surviving a restart.
 *
 * The rule under test: an event is attributed to the user who was signed in when it was **captured**, or to
 * nobody (the generated id). It is never attributed to a user who was not.
 */
@RunWith(RobolectricTestRunner::class)
class UserIdentityEpochTest {

    private lateinit var prefs: SharedPreferences

    /** Runs the encrypt-and-persist step inline, so a "restart" (a second provider) sees the first one's write. */
    private val inline = Executor { it.run() }

    @Before fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("identity-epoch-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun provider(cipher: QueueCipher? = null, persistence: Executor = inline) = UserIdProvider(prefs, cipher, persistence)

    private fun newCipher() = AesGcmQueueCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    // ─── A41 · the host's identity survives a restart ───────────────────────────

    @Test fun `setUser survives a process restart, with source HOST - no second call needed`() {
        provider().setUser("+628123456789")

        val afterRestart = provider()

        assertEquals("+628123456789", afterRestart.userId)
        assertEquals("without this the backend sees 'generated' after every restart and wrongly warns the app never calls setUser",
            UserIdSource.HOST, afterRestart.userIdSource)
    }

    @Test fun `a restart does not look like an identity change when the host sets the same user again`() {
        val first = provider(); first.setUser("user-7"); val epochBefore = first.currentEpoch

        val afterRestart = provider()
        afterRestart.setUser("user-7")   // what an app does in onCreate after restoring its session

        assertEquals("same user again is not a change", epochBefore, afterRestart.currentEpoch)
    }

    @Test fun `a restart followed by a different user is a change, and the earlier user's backlog stays theirs`() {
        val first = provider(); first.setUser("user-X"); val epochX = first.currentEpoch
        val afterRestart = provider()

        afterRestart.setUser("user-Y")

        assertNotEquals(epochX, afterRestart.currentEpoch)
        assertEquals("events captured before the restart, as X, still go out as X", "user-X", afterRestart.identityFor(epochX).id)
        assertEquals(UserIdSource.HOST, afterRestart.identityFor(epochX).source)
        assertEquals("user-Y", afterRestart.identityFor(afterRestart.currentEpoch).id)
    }

    // ─── epochs ─────────────────────────────────────────────────────────────────

    @Test fun `the epoch changes only when the identity actually changes`() {
        val p = provider()
        val start = p.currentEpoch

        p.setUser("a"); val a = p.currentEpoch; assertNotEquals(start, a)
        p.setUser("a"); assertEquals("same value", a, p.currentEpoch)
        p.setUser("b"); val b = p.currentEpoch; assertNotEquals(a, b)
        p.clearUser(); val cleared = p.currentEpoch; assertNotEquals(b, cleared)
        p.clearUser(); assertEquals("already anonymous", cleared, p.currentEpoch)
    }

    @Test fun `X then Y in one process - each era's events go out under their own user`() {
        val p = provider()
        p.setUser("user-X"); val epochX = p.currentEpoch
        p.setUser("user-Y"); val epochY = p.currentEpoch

        assertEquals("user-X", p.identityFor(epochX).id)
        assertEquals("user-Y", p.identityFor(epochY).id)
        assertEquals(UserIdSource.HOST, p.identityFor(epochX).source)
    }

    @Test fun `a stretch where nobody was signed in goes out anonymous, not as the previous user`() {
        val p = provider()
        p.setUser("user-X")
        p.clearUser(); val loggedOut = p.currentEpoch
        val generated = p.userId   // current is anonymous now

        val identity = p.identityFor(loggedOut)

        assertEquals(UserIdSource.GENERATED, identity.source)
        assertEquals(generated, identity.id)
        assertNotEquals("user-X", identity.id)
    }

    @Test fun `anonymous then signed in - the earlier events keep the generated id they were captured under`() {
        val p = provider()
        val anonymousEpoch = p.currentEpoch
        val generated = p.userId
        p.setUser("user-X")

        assertEquals(generated, p.identityFor(anonymousEpoch).id)
        assertEquals(UserIdSource.GENERATED, p.identityFor(anonymousEpoch).source)
    }

    @Test fun `an older era this process never saw goes out as generated - never as the current user`() {
        val first = provider(); first.setUser("user-W"); val epochW = first.currentEpoch; first.setUser("user-X")
        val afterRestart = provider()    // knows only the persisted era (X), not W's
        afterRestart.setUser("user-Y")

        val unknown = afterRestart.identityFor(epochW)

        assertEquals(UserIdSource.GENERATED, unknown.source)
        assertNotEquals("user-Y", unknown.id)
        assertNotEquals("user-W", unknown.id)
    }

    @Test fun `an epoch nobody has heard of is anonymous - not the current user (review finding 1)`() {
        val p = provider(); p.setUser("user-X")

        for (unknown in listOf(p.currentEpoch + 5, 1, 0, Int.MAX_VALUE)) {
            if (unknown == p.currentEpoch) continue
            val identity = p.identityFor(unknown)
            assertEquals("epoch $unknown", UserIdSource.GENERATED, identity.source)
            assertNotEquals("user-X", identity.id)
        }
        assertEquals("user-X", p.identityFor(p.currentEpoch).id)
    }

    @Test fun `a switch whose state write never reached disk cannot be filed under the previous user (review finding 1)`() {
        // Process 1: X is persisted. Then the host switches to Y, but the process dies before the write runs.
        val first = provider(); first.setUser("user-X")
        val lostWrite = Executor { /* the process was killed: this task never runs */ }
        val p1 = provider(persistence = lostWrite)
        p1.setUser("user-Y"); val epochY = p1.currentEpoch      // Y's events are stamped with this on disk

        // Process 2 loads the state that DID reach disk: still X.
        val p2 = provider()
        assertEquals("user-X", p2.userId)

        // The backlog stamped with Y's epoch must not be uploaded as X.
        val identity = p2.identityFor(epochY)
        assertEquals(UserIdSource.GENERATED, identity.source)
        assertNotEquals("user-X", identity.id)
    }

    @Test fun `epochs never repeat across a lost write, so a later user cannot inherit an earlier backlog (review finding 1)`() {
        val first = provider(); first.setUser("user-X")
        val p1 = provider(persistence = Executor { /* killed before the write */ })
        p1.setUser("user-Y"); val epochY = p1.currentEpoch

        val p2 = provider()                // loads X, never saw Y's epoch
        p2.setUser("user-Z")               // a DIFFERENT user signs in on the next run

        assertNotEquals("a counter would hand Z the same number Y had", epochY, p2.currentEpoch)
        assertNotEquals("user-Z", p2.identityFor(epochY).id)
    }

    @Test fun `the history is bounded, and a pruned era falls back to anonymous rather than to someone`() {
        val p = provider()
        p.setUser("user-first"); val firstEpoch = p.currentEpoch
        repeat(100) { p.setUser("user-$it") }

        assertEquals(UserIdSource.GENERATED, p.identityFor(firstEpoch).source)       // pruned long ago
        assertEquals("user-99", p.identityFor(p.currentEpoch).id)
    }

    // ─── how the host id is stored ──────────────────────────────────────────────

    @Test fun `with a cipher the stored value is encrypted - the raw id is not readable at rest`() {
        provider(newCipher()).setUser("+628123456789")

        val stored = prefs.getString("apm_host_user_id", null)!!

        assertTrue(stored, stored.startsWith("e1:"))
        assertFalse("raw id must not appear: $stored", stored.contains("628123456789"))
    }

    @Test fun `an encrypted value round-trips with the same cipher`() {
        val cipher = newCipher()
        provider(cipher).setUser("+628123456789")

        assertEquals("+628123456789", provider(cipher).userId)
    }

    @Test fun `without a cipher it falls back to a marked plaintext value, as the queue does`() {
        provider(null).setUser("user-X")

        assertEquals("p1:user-X", prefs.getString("apm_host_user_id", null))
        assertEquals("user-X", provider(null).userId)
    }

    @Test fun `a cipher that FAILS never causes a plaintext write - fail closed (review finding 3)`() {
        val failing = object : QueueCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray? = null     // e.g. a transient Keystore error
            override fun decrypt(ciphertext: ByteArray): ByteArray? = null
        }
        val p = provider(failing)

        p.setUser("+628123456789")

        assertNull("nothing readable may be stored when a cipher exists but fails", prefs.getString("apm_host_user_id", null))
        assertEquals("the identity still works for this process", "+628123456789", p.userId)
        assertEquals(UserIdSource.HOST, p.userIdSource)
        assertEquals("after a restart the user is anonymous - safe, not leaked", UserIdSource.GENERATED, provider(failing).userIdSource)
    }

    @Test fun `a failed encrypt also removes a stale stored value rather than leaving the previous user behind`() {
        val working = newCipher()
        provider(working).setUser("user-X")
        assertTrue(prefs.getString("apm_host_user_id", null)!!.startsWith("e1:"))

        val failing = object : QueueCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray? = null
            override fun decrypt(ciphertext: ByteArray): ByteArray? = working.decrypt(ciphertext)
        }
        provider(failing).setUser("user-Y")

        assertNull(prefs.getString("apm_host_user_id", null))
    }

    @Test fun `if the key is gone and the value cannot be decrypted the user is anonymous, not an error`() {
        provider(newCipher()).setUser("user-X")

        val withNewKey = provider(newCipher())   // a regenerated Keystore key

        assertEquals(UserIdSource.GENERATED, withNewKey.userIdSource)
        assertNotEquals("user-X", withNewKey.userId)
    }

    @Test fun `a stored value in an unknown shape is ignored`() {
        prefs.edit().putString("apm_host_user_id", "some-old-raw-value").commit()

        assertEquals(UserIdSource.GENERATED, provider().userIdSource)
    }

    @Test fun `clearUser removes the stored value`() {
        val p = provider(); p.setUser("user-X"); p.clearUser()

        assertNull(prefs.getString("apm_host_user_id", null))
        assertEquals(UserIdSource.GENERATED, provider().userIdSource)
    }

    @Test fun `the generated id is the same before and after a sign-in, so anonymous history stays one identity`() {
        val p = provider(); val before = p.userId
        p.setUser("user-X"); p.clearUser()

        assertEquals(before, p.userId)
    }

    // ─── review finding 4 · nothing heavy on the caller's thread ────────────────

    @Test fun `setUser does not encrypt or write on the caller's thread (review finding 4)`() {
        var encryptCalls = 0
        val counting = object : QueueCipher {
            private val real = newCipher()
            override fun encrypt(plaintext: ByteArray): ByteArray? { encryptCalls++; return real.encrypt(plaintext) }
            override fun decrypt(ciphertext: ByteArray): ByteArray? = real.decrypt(ciphertext)
        }
        val queued = mutableListOf<Runnable>()
        val p = provider(counting, persistence = Executor { queued += it })   // captures, does not run

        p.setUser("+628123456789")

        assertEquals("no Keystore work on the caller's thread", 0, encryptCalls)
        assertNull("nothing written yet", prefs.getString("apm_host_user_id", null))
        assertEquals("the in-memory identity is already correct", "+628123456789", p.userId)
        assertEquals(1, queued.size)

        queued.single().run()   // the background thread does it

        assertEquals(1, encryptCalls)
        assertTrue(prefs.getString("apm_host_user_id", null)!!.startsWith("e1:"))
    }

    @Test fun `the real background writer persists, and awaitPersisted is a barrier for it`() {
        val p = UserIdProvider(prefs, newCipher())     // default executor

        p.setUser("user-X")
        assertTrue(p.awaitPersisted())

        assertTrue(prefs.getString("apm_host_user_id", null)!!.startsWith("e1:"))
    }

    @Test fun `successive changes persist in order, so the last one wins`() {
        val p = UserIdProvider(prefs, null)
        repeat(20) { p.setUser("user-$it") }
        assertTrue(p.awaitPersisted())

        assertEquals("user-19", UserIdProvider(prefs, null).userId)
    }

    @Test fun `the write is handed off while the identity lock is held, so concurrent changes are queued in order`() {
        // If the handoff happened after the lock was released, two threads could enqueue their writes in the
        // opposite order to their changes and the disk would end with the earlier identity.
        val probed = java.util.concurrent.atomic.AtomicBoolean(false)
        var otherStillWaiting: Boolean? = null
        lateinit var p: UserIdProvider
        val probe = Executor { task ->
            if (probed.compareAndSet(false, true)) {
                val other = Thread { p.setUser("user-B") }
                other.start()
                other.join(300)
                otherStillWaiting = other.isAlive   // blocked on the lock the first change still holds
            }
            task.run()
        }
        p = provider(persistence = probe)

        p.setUser("user-A")

        assertEquals("a second change must wait for the first to finish handing off", true, otherStillWaiting)
    }
}
