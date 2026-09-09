package edts.apm.android.sdk.integrity

import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBuild

/** MOB-30. */
@RunWith(RobolectricTestRunner::class)
class RootDetectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun resetBuildTags() {
        // Build.TAGS is a static field one test method's ShadowBuild.setTags() mutates for the
        // whole class run — JUnit does not guarantee method order, so every test needs its own
        // known-clean baseline rather than assuming whatever an earlier method left behind.
        ShadowBuild.setTags("release-keys")
    }

    @Test fun `clean device with no signals is not flagged as rooted`() {
        val result = RootDetector.isRooted(
            context,
            pathExists = { false },
            systemPartitionWritable = { false }
        )
        assertFalse(result)
    }

    @Test fun `su binary presence is detected`() {
        val result = RootDetector.isRooted(
            context,
            pathExists = { it == "/system/xbin/su" },
            systemPartitionWritable = { false }
        )
        assertTrue(result)
    }

    @Test fun `test-keys build tag is detected`() {
        ShadowBuild.setTags("release-keys")
        assertFalse(RootDetector.isRooted(context, pathExists = { false }, systemPartitionWritable = { false }))

        ShadowBuild.setTags("test-keys")
        assertTrue(RootDetector.isRooted(context, pathExists = { false }, systemPartitionWritable = { false }))
    }

    @Test fun `writable system partition is detected`() {
        val result = RootDetector.isRooted(context, pathExists = { false }, systemPartitionWritable = { true })
        assertTrue(result)
    }

    @Test fun `a known root manager package is detected`() {
        val packageInfo = PackageInfo().apply { packageName = "com.topjohnwu.magisk" }
        shadowOf(context.packageManager).installPackage(packageInfo)

        val result = RootDetector.isRooted(context, pathExists = { false }, systemPartitionWritable = { false })
        assertTrue(result)
    }

    @Test fun `an unrelated installed package does not trigger a false positive`() {
        val packageInfo = PackageInfo().apply { packageName = "com.example.notepad" }
        shadowOf(context.packageManager).installPackage(packageInfo)

        val result = RootDetector.isRooted(context, pathExists = { false }, systemPartitionWritable = { false })
        assertFalse(result)
    }
}
