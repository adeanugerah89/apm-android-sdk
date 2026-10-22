package edts.apm.android.sdk.core

import android.content.SharedPreferences
import java.util.UUID

/**
 * Provides a stable install_id — generated once, persisted in SharedPreferences.
 * Survives app restarts and app updates. It is kept in the SDK's no-backup state file, so — unlike an
 * ordinary SharedPreferences value — a reinstall or a backup restore onto another device does NOT bring
 * it back: `01` §2 says it resets with the install (A40).
 * NOT a user identifier — the SDK treats it as an installation token only. (§2)
 */
internal class InstallIdProvider(private val prefs: SharedPreferences) {
    private val KEY = "apm_install_id"

    /**
     * Returns the existing install_id or generates + persists a new one.
     * Lazy so disk access happens only when first needed, not at init time.
     */
    val installId: String by lazy {
        prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also { newId ->
            prefs.edit().putString(KEY, newId).apply()
        }
    }
}
