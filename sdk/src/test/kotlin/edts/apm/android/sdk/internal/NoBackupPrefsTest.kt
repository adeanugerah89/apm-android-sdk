package edts.apm.android.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

/** A40 — the file-backed SharedPreferences that keeps the SDK's state out of Auto Backup. */
class NoBackupPrefsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val file by lazy { File(tmp.root, "state/apm-state.json") }

    /** Runs "background" writes inline so tests can look at the file straight away. */
    private val inline = Executor { it.run() }

    private fun prefs() = NoBackupPrefs(file, inline)

    @Test fun `the types the SDK uses round-trip across a new instance`() {
        prefs().edit().putString("s", "hello").putInt("i", 123456).putLong("l", 9_999_999_999L)
            .putBoolean("b", true).putFloat("f", 1.5f).putStringSet("set", mutableSetOf("a", "b")).commit()

        val reloaded = prefs()

        assertEquals("hello", reloaded.getString("s", null))
        assertEquals(123456, reloaded.getInt("i", -1))
        assertEquals(9_999_999_999L, reloaded.getLong("l", -1))
        assertTrue(reloaded.getBoolean("b", false))
        assertEquals(1.5f, reloaded.getFloat("f", 0f), 0f)
        assertEquals(setOf("a", "b"), reloaded.getStringSet("set", null))
    }

    @Test fun `defaults come back for absent keys`() {
        val p = prefs()
        assertNull(p.getString("nope", null))
        assertEquals(-1, p.getInt("nope", -1))
        assertEquals(7L, p.getLong("nope", 7L))
        assertFalse(p.contains("nope"))
    }

    @Test fun `apply is visible to the very next read, before anything reaches disk`() {
        val neverRuns = Executor { /* the background write has not happened yet */ }
        val p = NoBackupPrefs(file, neverRuns)

        p.edit().putString("apm_install_id", "abc").apply()

        assertEquals("abc", p.getString("apm_install_id", null))
        assertFalse("nothing was written yet", file.exists())
    }

    @Test fun `apply never writes on the caller's thread`() {
        val p = NoBackupPrefs(file, Executor { /* captures the write, does not run it */ })

        p.edit().putInt("x", 1).apply()

        assertFalse("the executor was handed the write instead of doing it inline", file.exists())
    }

    @Test fun `commit writes synchronously and reports success`() {
        assertTrue(prefs().edit().putString("k", "v").commit())
        assertTrue(file.exists())
    }

    @Test fun `remove and clear take effect, in memory and on disk`() {
        val p = prefs()
        p.edit().putString("a", "1").putString("b", "2").commit()
        p.edit().remove("a").commit()
        assertFalse(prefs().contains("a"))
        assertEquals("2", prefs().getString("b", null))

        p.edit().clear().commit()
        assertTrue(prefs().all.isEmpty())
    }

    @Test fun `a value read with the wrong type returns the default instead of throwing into the host`() {
        val p = prefs()
        p.edit().putString("k", "text").putInt("n", 5).commit()

        assertEquals(42, p.getInt("k", 42))                    // real SharedPreferences would throw ClassCastException
        assertNull(p.getString("n", null))
        assertEquals(false, p.getBoolean("n", false))
    }

    @Test fun `a damaged file is an empty store, and the next write replaces it`() {
        file.parentFile!!.mkdirs()
        file.writeText("{ this is not json")

        val p = prefs()
        assertTrue(p.all.isEmpty())

        p.edit().putString("k", "v").commit()
        assertEquals("v", prefs().getString("k", null))
    }

    @Test fun `a file holding something other than an object is ignored`() {
        file.parentFile!!.mkdirs()
        file.writeText("[1,2,3]")

        assertTrue(prefs().all.isEmpty())
    }

    @Test fun `a half-written temp file is never read as state`() {
        prefs().edit().putString("k", "good").commit()
        File(file.parentFile, file.name + ".tmp").writeText("{ half")

        assertEquals("good", prefs().getString("k", null))
    }

    @Test fun `concurrent writers do not lose each other's keys`() {
        val p = prefs()
        val threads = (1..8).map { t -> Thread { repeat(50) { i -> p.edit().putInt("t$t-$i", i).commit() } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }

        val reloaded = prefs()
        assertEquals(8 * 50, reloaded.all.size)
    }

    @Test fun `the real background writer eventually persists an apply, and then a new instance sees it`() {
        val p = NoBackupPrefs(file)            // default executor

        p.edit().putString("k", "v").apply()

        val deadline = System.currentTimeMillis() + 3_000
        while (!file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("v", NoBackupPrefs(file, inline).getString("k", null))
    }

    // ─── review finding 6 · commit() and apply() must not interleave on the shared temp file ──────

    @Test fun `commit on one thread and apply on the writer thread never corrupt the file`() {
        val p = NoBackupPrefs(file)             // the real background writer
        val keys = 200
        val committer = Thread { repeat(keys) { i -> p.edit().putInt("commit-$i", i).commit() } }
        val applier = Thread { repeat(keys) { i -> p.edit().putInt("apply-$i", i).apply() } }

        committer.start(); applier.start(); committer.join(); applier.join()
        assertTrue(NoBackupPrefs.awaitPendingWrites(5_000))

        val reloaded = NoBackupPrefs(file, inline)
        assertEquals("a corrupted file loads as EMPTY, silently resetting install_id and the stored user", 2 * keys, reloaded.all.size)
        assertFalse("no stale temp file is left behind", File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test fun `two instances over one file serialise their writes too`() {
        val a = NoBackupPrefs(file); val b = NoBackupPrefs(file)
        val ta = Thread { repeat(100) { i -> a.edit().putInt("a-$i", i).commit() } }
        val tb = Thread { repeat(100) { i -> b.edit().putInt("b-$i", i).commit() } }

        ta.start(); tb.start(); ta.join(); tb.join()

        // Each instance writes its own whole map, so the file holds one instance's keys — what matters is that it is a
        // valid, fully parsed file and not a torn mixture.
        val loaded = NoBackupPrefs(file, inline).all
        assertTrue("file must parse and be non-empty: $loaded", loaded.isNotEmpty())
        assertTrue(loaded.keys.all { it.startsWith("a-") } || loaded.keys.all { it.startsWith("b-") })
    }

    @Test fun `a late writer cannot overwrite a newer state with an older snapshot`() {
        val p = NoBackupPrefs(file)
        repeat(300) { i -> p.edit().putInt("counter", i).apply() }
        assertTrue(NoBackupPrefs.awaitPendingWrites(5_000))

        assertEquals(299, NoBackupPrefs(file, inline).getInt("counter", -1))
    }

    @Test fun `a reader never sees a torn file while commit and apply write concurrently (review finding 6)`() {
        // Big values widen the window in which two unserialised writers would interleave on the shared temp
        // file, and a validator thread reads continuously: a torn file renamed into place would show up as JSON
        // that does not parse, even if a later write then repaired it.
        val blob = "x".repeat(96 * 1024)
        val p = NoBackupPrefs(file)
        p.edit().putString("seed", blob).commit()
        val invalid = java.util.concurrent.atomic.AtomicInteger()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)

        val validator = Thread {
            while (running.get()) {
                try {
                    val text = file.readText()
                    reads.incrementAndGet()
                    kotlinx.serialization.json.Json.parseToJsonElement(text)
                } catch (e: java.io.FileNotFoundException) {
                    // between renames there is always a file; tolerate the very first moment
                } catch (e: Exception) {
                    invalid.incrementAndGet()
                }
            }
        }
        val writers = (1..4).map { t ->
            Thread { repeat(120) { i ->
                // The length changes on every write, so two interleaved snapshots differ in size and structure and a
                // torn mixture of them cannot accidentally still be valid JSON.
                val edit = p.edit().putString("k-$t", blob.take(1_000 + ((i * 37 + t * 11) % 90) * 1_000) + "|$t|$i")
                if (t % 2 == 0) edit.commit() else edit.apply()
            } }
        }

        validator.start(); writers.forEach { it.start() }; writers.forEach { it.join() }
        assertTrue(NoBackupPrefs.awaitPendingWrites(10_000))
        running.set(false); validator.join()

        assertTrue("the validator must actually have read the file many times: ${reads.get()}", reads.get() > 50)
        assertEquals("a torn, unparsable state file was observed", 0, invalid.get())
    }
}
