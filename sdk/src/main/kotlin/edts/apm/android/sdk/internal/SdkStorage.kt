package edts.apm.android.sdk.internal

import android.content.Context
import java.io.File

/**
 * Where the SDK keeps its files (SEC-07: the queue must be excluded from backup).
 *
 * Everything lives under [Context.getNoBackupFilesDir]. The platform never includes that directory
 * in Auto Backup, **whatever backup rules the host app declares** (Android's documentation: "always
 * excluded even if you try to include them", developer.android.com/identity/data/autobackup) —
 * which is the point. The earlier design kept the queue in `filesDir` and shipped a rules file for
 * the host to merge; that depended on every adopter doing so, `INTEGRATION.md` never told them to,
 * and the file excluded `apm_queue/` while the directory was actually `apm-queue/`, so it matched
 * nothing. A host with the default `allowBackup="true"` and no rules (like the verification app) had
 * the whole queue uploaded to the user's cloud backup.
 *
 * The SDK deliberately does not set `allowBackup`, `fullBackupContent` or `dataExtractionRules` in
 * its own manifest: the manifest merger would push that onto the host's entire app and override a
 * policy that is theirs to set (see CONSTITUTION 2026-09-07, A11, for the same reasoning on cleartext).
 */
internal object SdkStorage {

    const val QUEUE_DIR_NAME = "apm-queue"
    const val CONFIG_CACHE_NAME = "apm-remote-config.json"
    const val STATE_FILE_NAME = "apm-state.json"

    /** The SharedPreferences file earlier builds used. It sits in `shared_prefs/`, which Auto Backup includes. */
    const val LEGACY_PREFS_NAME = "edts_apm_prefs"
    private const val KEY_STATE_MIGRATED = "apm_state_migrated"

    /** An update after less than this is treated as part of the install, not as a real update. */
    private const val UPDATE_MIN_GAP_MS = 60_000L

    /**
     * The directory holding everything the SDK writes as a file. Falls back to `filesDir` only if the
     * platform cannot supply a no-backup directory — the SDK keeps working, just without the
     * exclusion, rather than failing to start (CONSTITUTION rule #1).
     */
    fun root(context: Context): File =
        (try { context.noBackupFilesDir } catch (e: Throwable) { null }) ?: context.filesDir

    fun queueDir(context: Context): File = File(root(context), QUEUE_DIR_NAME)

    fun configCache(context: Context): File = File(root(context), CONFIG_CACHE_NAME)

    /**
     * Moves what a build before this one left in `filesDir` into [root]. Without it, an app that
     * updates would strand its whole pending queue — real, unsent telemetry — in a directory nothing
     * reads any more. Idempotent, never overwrites a file already in place, and never throws.
     *
     * **Only on a genuine app update**, the same rule [openState] applies to the key-value state. Those old
     * files sat in `filesDir`, which Auto Backup included, so on a reinstall or a restore onto another device
     * they are a *backup's* copy of some earlier queue — events the old device may already have sent, filed
     * under no session. Moving them in would upload them under this install's new `install_id`. When the
     * install times positively say this is a fresh install, they are deleted instead (the cloud-eligible copy
     * must not linger). When the platform will not say, they are moved: dropping real telemetry because a
     * lookup failed is the worse mistake.
     *
     * Moving is a rename where the filesystem allows (it preserves the modification time the queue
     * orders itself by); otherwise copy-then-delete with the time restored. A file that cannot be moved
     * is left where it is rather than lost.
     *
     * Returns how many files were moved.
     */
    fun migrateLegacy(
        context: Context,
        installTimes: () -> Pair<Long, Long>? = { packageInstallTimes(context) }
    ): Int = try {
        val legacyConfig = File(context.filesDir, CONFIG_CACHE_NAME)
        val legacyQueue = File(context.filesDir, QUEUE_DIR_NAME)
        if (root(context) == context.filesDir) {
            0   // no separate no-backup directory: nothing to migrate from or to
        } else if (!legacyConfig.exists() && !legacyQueue.exists()) {
            0   // the usual case after the first launch: no platform lookup needed
        } else if (isFreshInstall(installTimes())) {
            legacyConfig.delete()
            legacyQueue.deleteRecursively()
            0
        } else {
            moveFile(legacyConfig, configCache(context)) + moveDirectory(legacyQueue, queueDir(context))
        }
    } catch (e: Throwable) {
        0
    }

    private fun moveDirectory(from: File, to: File): Int {
        if (!from.isDirectory) return 0

        // Whole-directory rename when the destination is free: one atomic step for the common case.
        if (!to.exists()) {
            val count = from.listFiles()?.size ?: 0
            to.parentFile?.mkdirs()
            if (from.renameTo(to)) return count
        }

        to.mkdirs()
        var moved = 0
        from.listFiles()?.forEach { file -> moved += moveFile(file, File(to, file.name)) }
        from.delete()   // succeeds only if empty — a file that would not move keeps its directory
        return moved
    }

    private fun moveFile(from: File, to: File): Int {
        if (!from.isFile || to.exists()) return 0
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return 1
        return try {
            from.copyTo(to, overwrite = false)
            to.setLastModified(from.lastModified())
            from.delete()
            1
        } catch (e: Throwable) {
            to.delete()   // never leave a half-copied file where the queue would read it
            0
        }
    }

    /**
     * Opens the SDK's small key-value state (A40): `install_id`, the generated fallback user id, the
     * latest session id and the crash-dedup markers. It lives under [root], so Auto Backup never sees it —
     * a reinstall or a restore onto another device starts a new `install_id`, as `01` §2 promises, and one
     * `install_id` cannot end up on two live devices.
     *
     * Values an older build kept in `shared_prefs/` are carried over **only on a genuine app update**
     * (`lastUpdateTime` clearly later than `firstInstallTime`). On a fresh install the app was just
     * installed, so whatever sits in `shared_prefs/` came from a backup restore — carrying it over would
     * recreate the very clone this exists to prevent. Either way the old file is emptied, so the cloud
     * copy stops holding an `install_id`. Runs once (a marker is written), never throws.
     */
    fun openState(
        context: Context,
        installTimes: () -> Pair<Long, Long>? = { packageInstallTimes(context) }
    ): android.content.SharedPreferences {
        val state = NoBackupPrefs(File(root(context), STATE_FILE_NAME))
        migrateLegacyPrefs(context, state, installTimes)
        return state
    }

    private fun migrateLegacyPrefs(
        context: Context,
        state: android.content.SharedPreferences,
        installTimes: () -> Pair<Long, Long>?
    ) {
        if (state.getBoolean(KEY_STATE_MIGRATED, false)) return
        try {
            val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            val old = legacy.all
            if (old.isNotEmpty()) {
                if (isGenuineUpdate(installTimes())) {
                    val editor = state.edit()
                    var copied = false
                    for ((key, value) in old) {
                        // Never overwrite: if a previous attempt was interrupted, the state may already hold
                        // newer values (a user who signed in, a fresh epoch) than the stale ones left here.
                        if (state.contains(key)) continue
                        when (value) {
                            is String -> editor.putString(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Boolean -> editor.putBoolean(key, value)
                            is Float -> editor.putFloat(key, value)
                            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
                            else -> continue
                        }
                        copied = true
                    }
                    if (copied) editor.commit()   // durable BEFORE the old copy is emptied
                }
                legacy.edit().clear().commit()    // synchronous: the cloud-eligible copy must really be empty
            }
        } catch (e: Throwable) {
            // Carrying values over is a courtesy; failing to must not stop the SDK from starting.
        }
        // Written LAST and durably. If the process dies earlier the marker is absent, the next launch repeats the
        // steps above, and because nothing is overwritten a repeat is harmless.
        state.edit().putBoolean(KEY_STATE_MIGRATED, true).commit()
    }

    private fun isGenuineUpdate(times: Pair<Long, Long>?): Boolean =
        times != null && times.second - times.first > UPDATE_MIN_GAP_MS

    /** Affirmatively a fresh install — unlike `!isGenuineUpdate`, an unknown answer is not one. */
    private fun isFreshInstall(times: Pair<Long, Long>?): Boolean =
        times != null && times.second - times.first <= UPDATE_MIN_GAP_MS

    /** (firstInstallTime, lastUpdateTime), or null if the platform will not say. */
    private fun packageInstallTimes(context: Context): Pair<Long, Long>? = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime to info.lastUpdateTime
    } catch (e: Throwable) {
        null
    }
}
