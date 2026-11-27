package edts.apm.android.sdk.core

import android.content.SharedPreferences
import android.util.Base64
import edts.apm.android.sdk.crypto.QueueCipher
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A `user_id` with where it came from — `01` §2.1/§2.2. */
internal data class UserIdentity(val id: String, val source: UserIdSource)

/**
 * Manages `user_id` / `user_id_source` — MOB-28, `01` §2.1/§2.2 — **and which identity each event was
 * captured under** (A39).
 *
 * `user_id` always has a value: the host's [setUser] value, or a stable generated fallback.
 *
 * **Persistence (A41).** The host's value is stored once, in the SDK's no-backup state, so it survives a
 * process restart as it does on iOS. It is encrypted with the queue's Keystore cipher when there is one. If
 * there is a cipher and it *fails*, nothing is written — the queue refuses to write an event it cannot
 * encrypt, and the more sensitive value gets no weaker rule. Plaintext (marked `p1:`) is used only when there
 * is no cipher at all, the same trade-off as the queue (CONSTITUTION 2026-09-07, A14). The value is never
 * written anywhere else: not per event, not in breadcrumbs or logs (SEC-06).
 *
 * The encryption and the write happen **on a background thread**, never on the caller's: `setUser` is
 * normally called from the login flow on the main thread, and a Keystore operation is a blocking binder call.
 *
 * **Identity epoch (A39).** An opaque number that changes whenever the identity *changes* — a different
 * [setUser] value, or [clearUser]. Events record the epoch, not the id, so nothing personal is written per
 * event. At upload [identityFor] turns an epoch back into an identity: the one that was current then, if this
 * process knows it, otherwise the generated id. **An event is never attributed to someone who was not signed
 * in when it happened** — when unsure, it goes out anonymous.
 *
 * **What is not guaranteed.** The stored identity trails the in-memory one by the time the writer needs. If the
 * process dies inside that window, the next launch reads the *previous* identity and files the crash or
 * termination it recovers under it. That is the price of keeping a blocking Keystore call off the caller's
 * thread; the window is the writer's latency, not a timeout.
 *
 * Epochs are **random, not a counter**, deliberately. A counter can repeat: if the process dies after a
 * switch but before the state write lands, the next run would hand the same number to a different user and
 * the earlier backlog would be filed under them. Setting the same value again is not a change, so after a
 * restart the host's next `setUser` is compared against the stored value.
 *
 * SEC-06: this class only hands the raw value to whoever builds the envelope's `user_id` slot.
 */
internal class UserIdProvider(
    private val prefs: SharedPreferences,
    private val cipher: QueueCipher? = null,
    /** Runs the encrypt-and-persist step. A single ordered background thread unless a test substitutes one. */
    private val persistence: Executor = PERSISTENCE
) {
    private val lock = Any()

    /** Who was signed in during an epoch, as far as this process knows. `null` host id = anonymous. */
    private class Era(val hostId: String?)

    /** Insertion-ordered so the oldest era is the one pruned. Guarded by [lock]. */
    private val eras = LinkedHashMap<Int, Era>()

    @Volatile private var hostUserId: String? = loadHostId()
    @Volatile private var epoch: Int = prefs.getInt(KEY_EPOCH, 0)

    init {
        eras[epoch] = Era(hostUserId)   // the persisted identity is the baseline the next setUser is compared with
    }

    private val generatedId: String by lazy {
        prefs.getString(KEY_GENERATED, null) ?: UUID.randomUUID().toString().also { newId ->
            prefs.edit().putString(KEY_GENERATED, newId).apply()
        }
    }

    /** The identity epoch events are stamped with right now. */
    val currentEpoch: Int get() = epoch

    /**
     * MOB-28: app host sets an identifier — free-form string, not validated for content (SEC-06).
     *
     * The one exception (A54, `02` MOB-28/SEC-06): an empty or whitespace-only string is **ignored**,
     * because it is not an identity — taking it would file everything under a blank `user_id`. Returns
     * `false` when it was ignored; nothing changes then (no new epoch, nothing persisted). Anything else
     * is taken exactly as given, **not trimmed**, and placeholders such as `"0"` or `"guest"` stay the
     * host's responsibility.
     */
    fun setUser(id: String): Boolean {
        if (id.isBlank()) return false
        change(id)
        return true
    }

    /** The host signed the user out: events from now on are anonymous again, not attributed to the last user. */
    fun clearUser() = change(null)

    private fun change(newHostId: String?) {
        synchronized(lock) {
            if (newHostId == hostUserId) return   // not a change: same user again, or already anonymous
            hostUserId = newHostId
            val newEpoch = freshEpoch()
            epoch = newEpoch
            eras[newEpoch] = Era(newHostId)
            while (eras.size > MAX_ERAS) eras.remove(eras.keys.first())
            // Handed off INSIDE the lock, so two concurrent changes are queued in the order they happened: the
            // single writer then ends with the same identity the process holds in memory. `execute` only enqueues
            // (it does not block), and the encryption and the write themselves still run on the writer thread.
            try {
                persistence.execute { persist(newEpoch, newHostId) }
            } catch (e: Throwable) {
                // Persistence is best effort; the in-memory identity is already correct for this process.
            }
        }
    }

    val userId: String get() = hostUserId ?: generatedId

    val userIdSource: UserIdSource get() = if (hostUserId != null) UserIdSource.HOST else UserIdSource.GENERATED

    /** The identity right now. */
    fun current(): UserIdentity = hostUserId?.let { UserIdentity(it, UserIdSource.HOST) } ?: UserIdentity(generatedId, UserIdSource.GENERATED)

    /**
     * The identity an event captured in [epoch] should be uploaded under: the identity of that era if this
     * process knows it, and the **generated** id for any epoch it does not — an older era after a restart, or
     * an epoch written by a run whose state never reached disk. Never the current user by default.
     */
    fun identityFor(epoch: Int): UserIdentity {
        val era = synchronized(lock) { eras[epoch] }
        return era?.hostId?.let { UserIdentity(it, UserIdSource.HOST) } ?: UserIdentity(generatedId, UserIdSource.GENERATED)
    }

    /** Blocks until every persistence step handed off so far has run (or [timeoutMs] passes). For tests. */
    internal fun awaitPersisted(timeoutMs: Long = 2_000): Boolean {
        val done = CountDownLatch(1)
        return try {
            persistence.execute { done.countDown() }
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            false
        }
    }

    // ─── storage ────────────────────────────────────────────────────────────

    /** Caller holds [lock]. A value no known era uses, so it can never be mistaken for one. */
    private fun freshEpoch(): Int {
        var candidate: Int
        do {
            candidate = ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE)
        } while (candidate == epoch || eras.containsKey(candidate))
        return candidate
    }

    private fun persist(newEpoch: Int, newHostId: String?) {
        try {
            val editor = prefs.edit().putInt(KEY_EPOCH, newEpoch)
            val encoded = newHostId?.let { encode(it) }
            // A host id that could not be encoded is simply not stored (stale value removed): after a restart
            // the user is anonymous, which is safe; writing it readable would not be.
            if (encoded == null) editor.remove(KEY_HOST) else editor.putString(KEY_HOST, encoded)
            editor.apply()
        } catch (e: Throwable) {
            // Best effort.
        }
    }

    /** `null` means "do not store this": a cipher exists but failed. */
    private fun encode(id: String): String? {
        val activeCipher = cipher ?: return PLAIN + id   // no cipher at all: the documented plaintext fallback
        val encrypted = activeCipher.encrypt(id.toByteArray(Charsets.UTF_8)) ?: return null
        return ENCRYPTED + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /** `null` for anything not recognised, or an encrypted value that cannot be decrypted (the key is gone). */
    private fun loadHostId(): String? {
        val stored = prefs.getString(KEY_HOST, null) ?: return null
        return try {
            when {
                stored.startsWith(PLAIN) -> stored.removePrefix(PLAIN)
                stored.startsWith(ENCRYPTED) ->
                    cipher?.decrypt(Base64.decode(stored.removePrefix(ENCRYPTED), Base64.NO_WRAP))?.toString(Charsets.UTF_8)
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    private companion object {
        const val KEY_GENERATED = "apm_generated_user_id"
        const val KEY_HOST = "apm_host_user_id"
        const val KEY_EPOCH = "apm_identity_epoch"
        const val PLAIN = "p1:"
        const val ENCRYPTED = "e1:"
        const val MAX_ERAS = 32

        /** One ordered thread, gone after 5 s idle — same shape as the state writer. */
        val PERSISTENCE: Executor = ThreadPoolExecutor(
            1, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()
        ) { r -> Thread(r, "apm-identity-writer").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
            .apply { allowCoreThreadTimeOut(true) }
    }
}
