package edts.apm.android.sdk.config

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** MOB-20 — the cache that lets a deliberate kill switch survive an offline launch. */
class RemoteConfigStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store(name: String = "config.json") = RemoteConfigStore(File(tmp.root, name))

    @Test fun `read returns null when no cache file exists`() {
        assertNull(store().read())
    }

    @Test fun `written config round-trips, kill switch included`() {
        val s = store()
        assertTrue(s.write(RemoteConfig(enabled = false, maxBatch = 25, disabledFeatures = listOf("anr"))))

        val read = s.read()!!
        assertFalse(read.enabled)
        assertEquals(25, read.maxBatch)
        assertTrue(read.isDisabled(RemoteConfig.Feature.ANR))
    }

    @Test fun `a corrupt cache file reads as null instead of throwing`() {
        val file = File(tmp.root, "config.json")
        file.writeText("{ this is not json")

        assertNull(RemoteConfigStore(file).read())
    }

    @Test fun `read sanitizes a cache file that holds out-of-range values`() {
        val file = File(tmp.root, "config.json")
        file.writeText("""{ "enabled": true, "max_batch": 0, "upload_interval_s": 0 }""")

        val read = RemoteConfigStore(file).read()!!
        assertEquals(RemoteConfig.DEFAULT.maxBatch, read.maxBatch)
        assertEquals(RemoteConfig.DEFAULT.uploadIntervalS, read.uploadIntervalS)
    }

    @Test fun `write creates the parent directory when it does not exist`() {
        val s = RemoteConfigStore(File(tmp.root, "nested/dir/config.json"))
        assertTrue(s.write(RemoteConfig(enabled = false)))
        assertFalse(s.read()!!.enabled)
    }

    @Test fun `write leaves no temp file behind`() {
        val s = store()
        s.write(RemoteConfig())

        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
