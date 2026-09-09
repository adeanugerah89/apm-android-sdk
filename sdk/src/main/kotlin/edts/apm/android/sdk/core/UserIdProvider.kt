package edts.apm.android.sdk.core

import android.content.SharedPreferences
import java.util.UUID

/**
 * Manages `user_id` / `user_id_source` — MOB-28, `01` §2.1/§2.2.
 *
 * `user_id` always has a value: whatever the host last passed to [setUser], or a stable
 * generated fallback (persisted like [InstallIdProvider], so it survives process restarts but
 * not uninstall). [setUser] itself is in-memory only — hosts that want it to survive a restart
 * are expected to call it again on launch (e.g. after restoring auth state), which is also what
 * lets `user_id_source` correctly read `generated` for any events emitted before that happens.
 *
 * SEC-06: this class only ever hands the raw value to whoever builds the envelope's `user_id`
 * slot — it must never be threaded into breadcrumbs, logs, or event attrs.
 */
class UserIdProvider(private val prefs: SharedPreferences) {
    private val KEY = "apm_generated_user_id"

    @Volatile private var hostUserId: String? = null

    private val generatedId: String by lazy {
        prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also { newId ->
            prefs.edit().putString(KEY, newId).apply()
        }
    }

    /** MOB-28: app host sets an identifier — free-form string, never validated or rejected. */
    fun setUser(id: String) {
        hostUserId = id
    }

    val userId: String get() = hostUserId ?: generatedId

    val userIdSource: UserIdSource get() = if (hostUserId != null) UserIdSource.HOST else UserIdSource.GENERATED
}
