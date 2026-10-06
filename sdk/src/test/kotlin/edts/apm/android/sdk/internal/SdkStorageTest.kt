package edts.apm.android.sdk.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A29 / SEC-07 — the queue must live where Auto Backup cannot reach it, and a build that used to keep
 * it in `filesDir` must not strand the events it left there.
 */
@RunWith(RobolectricTestRunner::class)
class SdkStorageTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clean()
    }

    @After fun tearDown() = clean()

    private fun clean() {
        File(SdkStorage.root(context), SdkStorage.STATE_FILE_NAME).delete()
        context.getSharedPreferences(SdkStorage.LEGACY_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, SdkStorage.QUEUE_DIR_NAME).deleteRecursively()
        File(context.filesDir, SdkStorage.CONFIG_CACHE_NAME).delete()
        SdkStorage.queueDir(context).deleteRecursively()
        SdkStorage.configCache(context).delete()
    }

    private fun legacyQueue() = File(context.filesDir, SdkStorage.QUEUE_DIR_NAME)

    private fun legacyEvent(name: String, body: String, modified: Long) =
        File(legacyQueue().apply { mkdirs() }, name).apply { writeText(body); setLastModified(modified) }

    // ─── where things live ──────────────────────────────────────────────────────

    @Test fun `the queue and the config cache are under noBackupFilesDir, not filesDir`() {
        val noBackup = context.noBackupFilesDir.canonicalPath

        assertTrue(SdkStorage.queueDir(context).canonicalPath.startsWith(noBackup))
        assertTrue(SdkStorage.configCache(context).canonicalPath.startsWith(noBackup))
        assertNotEquals("the exact bug A29 fixes", context.filesDir.canonicalPath, SdkStorage.queueDir(context).parentFile!!.canonicalPath)
    }

    @Test fun `the names match what the old rules file claimed to exclude - and that file was wrong`() {
        // The old backup rules excluded "apm_queue/" while the directory was "apm-queue".
        assertEquals("apm-queue", SdkStorage.QUEUE_DIR_NAME)
    }

    // ─── migrating what an older build left behind ──────────────────────────────

    @Test fun `a fresh install has nothing to migrate and creates nothing in filesDir`() {
        assertEquals(0, SdkStorage.migrateLegacy(context) { genuineUpdate })
        assertFalse(legacyQueue().exists())
    }

    @Test fun `pending events move over with their bytes and their order`() {
        legacyEvent("a.evt", "first", modified = 1_000_000L)
        legacyEvent("b.evt", "second", modified = 2_000_000L)
        legacyEvent("c.evt", "third", modified = 3_000_000L)

        assertEquals(3, SdkStorage.migrateLegacy(context) { genuineUpdate })

        val moved = SdkStorage.queueDir(context).listFiles()!!.sortedBy { it.lastModified() }
        assertEquals(listOf("first", "second", "third"), moved.map { it.readText() })
        // FIFO is decided by modification time, so a migration that reset it would reorder the queue.
        assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L), moved.map { it.lastModified() })
        assertFalse("the old directory is gone", legacyQueue().exists())
    }

    @Test fun `running it again changes nothing`() {
        legacyEvent("a.evt", "first", modified = 1_000_000L)
        SdkStorage.migrateLegacy(context) { genuineUpdate }

        assertEquals(0, SdkStorage.migrateLegacy(context) { genuineUpdate })
        assertEquals(1, SdkStorage.queueDir(context).listFiles()!!.size)
    }

    @Test fun `files already in the new location are never overwritten`() {
        SdkStorage.queueDir(context).apply { mkdirs() }.resolve("a.evt").writeText("NEW")
        legacyEvent("a.evt", "OLD", modified = 1L)
        legacyEvent("b.evt", "only-old", modified = 2L)

        SdkStorage.migrateLegacy(context) { genuineUpdate }

        assertEquals("NEW", File(SdkStorage.queueDir(context), "a.evt").readText())
        assertEquals("only-old", File(SdkStorage.queueDir(context), "b.evt").readText())
    }

    @Test fun `the cached remote config moves too, so a kill switch survives the update`() {
        File(context.filesDir, SdkStorage.CONFIG_CACHE_NAME).writeText("""{"enabled":false}""")

        assertEquals(1, SdkStorage.migrateLegacy(context) { genuineUpdate })

        assertEquals("""{"enabled":false}""", SdkStorage.configCache(context).readText())
        assertFalse(File(context.filesDir, SdkStorage.CONFIG_CACHE_NAME).exists())
    }

    @Test fun `an existing config cache in the new location wins over a stale one`() {
        SdkStorage.configCache(context).apply { parentFile!!.mkdirs() }.writeText("""{"enabled":true}""")
        File(context.filesDir, SdkStorage.CONFIG_CACHE_NAME).writeText("""{"enabled":false}""")

        SdkStorage.migrateLegacy(context) { genuineUpdate }

        assertEquals("""{"enabled":true}""", SdkStorage.configCache(context).readText())
    }

    // ─── a restore is not an update ─────────────────────────────────────────────

    @Test fun `on a fresh install the old queue and config cache are a backup's copy, so they are deleted, not uploaded`() {
        legacyEvent("a.evt", "from-another-device", modified = 1_000_000L)
        File(context.filesDir, SdkStorage.CONFIG_CACHE_NAME).writeText("""{"enabled":false}""")

        assertEquals(0, SdkStorage.migrateLegacy(context) { freshInstall })

        assertFalse("nothing may reach the queue the uploader reads", SdkStorage.queueDir(context).exists())
        assertFalse(SdkStorage.configCache(context).exists())
        assertFalse("and the cloud-eligible copy must not linger", legacyQueue().exists())
        assertFalse(File(context.filesDir, SdkStorage.CONFIG_CACHE_NAME).exists())
    }

    @Test fun `when the platform will not say, pending events are moved rather than dropped`() {
        legacyEvent("a.evt", "real-pending", modified = 1_000_000L)

        assertEquals(1, SdkStorage.migrateLegacy(context) { null })

        assertEquals("real-pending", File(SdkStorage.queueDir(context), "a.evt").readText())
    }

    @Test fun `with nothing to migrate the platform is not asked at all`() {
        var asked = false
        SdkStorage.migrateLegacy(context) { asked = true; null }

        assertFalse("a package-manager binder call on every cold start would be pure waste", asked)
    }

    // ─── A40 · the SDK's key-value state ────────────────────────────────────────

    private val firstInstall = 1_700_000_000_000L
    private val freshInstall = firstInstall to firstInstall                      // lastUpdate == firstInstall
    private val genuineUpdate = firstInstall to firstInstall + 3 * 86_400_000L   // updated three days later

    private fun seedLegacyPrefs() = context.getSharedPreferences(SdkStorage.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString("apm_install_id", "install-from-old-build").putString("apm_session_id", "old-session")
        .putInt("apm_last_reported_exit_pid", 4242).putLong("apm_last_native_crash_ts", 1_788_000_000_000L).commit()

    private fun legacyPrefs() = context.getSharedPreferences(SdkStorage.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    @Test fun `the state file is under noBackupFilesDir, not in shared_prefs`() {
        SdkStorage.openState(context) { genuineUpdate }.edit().putString("k", "v").commit()

        val stateFile = File(SdkStorage.root(context), SdkStorage.STATE_FILE_NAME)
        assertTrue(stateFile.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertTrue(stateFile.exists())
        assertTrue("nothing new in the backup-eligible file", legacyPrefs().all.isEmpty())
    }

    @Test fun `on a genuine app update the old values are carried over with their types`() {
        seedLegacyPrefs()

        val state = SdkStorage.openState(context) { genuineUpdate }

        assertEquals("install_id continuity across an update", "install-from-old-build", state.getString("apm_install_id", null))
        assertEquals("old-session", state.getString("apm_session_id", null))
        assertEquals(4242, state.getInt("apm_last_reported_exit_pid", -1))
        assertEquals(1_788_000_000_000L, state.getLong("apm_last_native_crash_ts", -1))
        assertTrue("the backup-eligible copy no longer holds an install_id", legacyPrefs().all.isEmpty())
    }

    @Test fun `on a fresh install the old values are NOT carried over - they came from a backup restore`() {
        seedLegacyPrefs()   // what Auto Backup restored onto the new device

        val state = SdkStorage.openState(context) { freshInstall }

        assertNull("a restored install_id must not come back (01 section 2: reset on reinstall)", state.getString("apm_install_id", null))
        assertNull(state.getString("apm_session_id", null))
        assertEquals(-1, state.getInt("apm_last_reported_exit_pid", -1))
        assertTrue("and the cloud-eligible copy is emptied", legacyPrefs().all.isEmpty())
    }

    @Test fun `when the platform will not say, nothing is carried over - the safe side of the clone risk`() {
        seedLegacyPrefs()

        val state = SdkStorage.openState(context) { null }

        assertNull(state.getString("apm_install_id", null))
        assertTrue(legacyPrefs().all.isEmpty())
    }

    @Test fun `an update right after install is still treated as the install`() {
        seedLegacyPrefs()

        val state = SdkStorage.openState(context) { firstInstall to firstInstall + 5_000L }

        assertNull(state.getString("apm_install_id", null))
    }

    @Test fun `it migrates once - a later launch never pulls from shared_prefs again`() {
        seedLegacyPrefs()
        SdkStorage.openState(context) { genuineUpdate }.edit().remove("apm_install_id").commit()
        legacyPrefs().edit().putString("apm_install_id", "resurrected").commit()   // e.g. a stray restore

        val again = SdkStorage.openState(context) { genuineUpdate }

        assertNull("the marker stops a second import", again.getString("apm_install_id", null))
    }

    @Test fun `with nothing in the old file there is nothing to do, and a fresh install_id is generated later as usual`() {
        val state = SdkStorage.openState(context) { genuineUpdate }

        assertTrue(state.all.keys.none { it.startsWith("apm_install") })
    }

    // ─── review finding 5 · an interrupted migration must be safe to repeat ─────────────────────

    private fun stateFile() = File(SdkStorage.root(context), SdkStorage.STATE_FILE_NAME)

    @Test fun `a repeated migration never overwrites newer state with stale legacy values`() {
        seedLegacyPrefs()
        val state = SdkStorage.openState(context) { genuineUpdate }
        // The user signed in during the first launch: newer values than anything in the legacy file.
        state.edit().putInt("apm_identity_epoch", 777).putString("apm_host_user_id", "p1:user-signed-in-since").commit()

        // Simulate the process dying before the old file was emptied and the marker written: both are absent again.
        state.edit().remove("apm_state_migrated").commit()
        legacyPrefs().edit().putString("apm_session_id", "STALE").putInt("apm_identity_epoch", 11)
            .putString("apm_install_id", "install-from-old-build").commit()

        val again = SdkStorage.openState(context) { genuineUpdate }

        assertEquals("the newer epoch survives", 777, again.getInt("apm_identity_epoch", -1))
        assertEquals("p1:user-signed-in-since", again.getString("apm_host_user_id", null))
        assertNotEquals("STALE", again.getString("apm_session_id", null))
        assertEquals("install-from-old-build", again.getString("apm_install_id", null))   // already there, still the same
        assertTrue("and the cloud-eligible copy is emptied again", legacyPrefs().all.isEmpty())
    }

    @Test fun `an interrupted migration still imports the keys it had not got to`() {
        // A previous, interrupted attempt had imported only the install id.
        SdkStorage.openState(context) { null }.edit().remove("apm_state_migrated").putString("apm_install_id", "install-from-old-build").commit()
        legacyPrefs().edit().putString("apm_install_id", "install-from-old-build").putString("apm_generated_user_id", "generated-old").commit()

        val state = SdkStorage.openState(context) { genuineUpdate }

        assertEquals("generated-old", state.getString("apm_generated_user_id", null))
    }

    @Test fun `the migrated marker is on disk as soon as the call returns, not left to a background write`() {
        SdkStorage.openState(context) { genuineUpdate }

        assertTrue(stateFile().readText(), stateFile().readText().contains("apm_state_migrated"))
    }

    @Test fun `the legacy file is emptied durably, not by an asynchronous apply that a kill could lose`() {
        seedLegacyPrefs()
        SdkStorage.openState(context) { genuineUpdate }

        assertTrue(context.getSharedPreferences(SdkStorage.LEGACY_PREFS_NAME, Context.MODE_PRIVATE).all.isEmpty())
    }
}
