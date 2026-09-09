package edts.apm.android.sdk.crash

import org.junit.Assert.*
import org.junit.Test

/**
 * A9b opsi B — parsed against a **real Android system tombstone**, captured from
 * `ApplicationExitInfo.getTraceInputStream()` after a genuine null-dereference on an API 33
 * device. The fixture is the raw protobuf, byte for byte.
 *
 * The field numbers this parser uses were derived from these bytes rather than from a copy of
 * `Tombstone.proto`, so these tests are the specification: if AOSP renumbers a field, they fail
 * here instead of silently producing wrong crash reports in production.
 */
class SystemTombstoneParserTest {

    private val packageName = "edts.apm.android.verifier"

    private fun fixture(): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream("tombstones/system-tombstone-api33-segv.pb")) {
            "missing tombstone fixture"
        }.readBytes()

    private val tombstone by lazy { SystemTombstoneParser.parse(fixture(), packageName)!! }

    @Test fun `reads the signal, its code and the kernel's own explanation`() {
        assertEquals("SIGSEGV", tombstone.signalName)
        assertEquals("SEGV_MAPERR", tombstone.signalCode)
        assertEquals("0x0", tombstone.faultAddress)
        assertEquals("null pointer dereference", tombstone.cause)
    }

    @Test fun `reads process identity and architecture`() {
        assertEquals(packageName, tombstone.processName)
        assertEquals("arm64", tombstone.architecture)
        assertNotNull(tombstone.timestamp)
    }

    @Test fun `the crashing thread comes first and is the only one marked crashed`() {
        val threads = tombstone.toCrashThreads()

        assertTrue("expected many threads, got ${threads.size}", threads.size > 5)
        assertEquals(0, threads.first().index)
        assertTrue(threads.first().crashed)
        assertEquals(1, threads.count { it.crashed })
    }

    @Test fun `the crashing thread has a deep backtrace — far past what xCrash produced`() {
        val crashed = tombstone.toCrashThreads().first()

        assertTrue("got ${crashed.frames.size} frames", crashed.frames.size > 30)
    }

    @Test fun `the crashing app frames are identified, named and given a base address`() {
        val appFrames = tombstone.toCrashThreads().first().frames.filter { it.isApp }

        assertTrue("no app frames found — FE-06 highlighting would be empty", appFrames.isNotEmpty())
        val trigger = appFrames.first { it.objectName == "libapm_crash_trigger.so" }
        assertTrue(trigger.instructionAddr!!.startsWith("0x"))
        assertNotNull("object_addr is what makes an address resolvable", trigger.objectAddr)
    }

    @Test fun `an exported symbol is present without any symbolication`() {
        val named = tombstone.toCrashThreads().first().frames
            .firstOrNull { it.symbolName?.contains("NativeCrashTrigger_crashNow") == true }

        assertNotNull("the JNI entry point should be named in the tombstone", named)
        assertTrue(named!!.isApp)
    }

    @Test fun `system frames are never claimed as app frames`() {
        val frames = tombstone.toCrashThreads().first().frames
        val system = frames.filter { it.objectName == "libart.so" || it.objectName == "libc.so" }

        assertTrue(system.isNotEmpty())
        assertTrue(system.none { it.isApp })
    }

    // ─── the whole point of opsi B ──────────────────────────────────────────

    @Test fun `binary_images is populated — this is what xCrash could never deliver`() {
        val images = tombstone.toBinaryImages()

        // One entry per object actually on a stack, not one per mapping: this tombstone has 2102
        // mappings and the crash touched a handful of them. Listing all of them would be noise the
        // backend has to filter before it can symbolicate anything.
        assertTrue("expected the objects on the stacks, got ${images.size}", images.size in 5..60)
        assertTrue(images.any { it.name == "libapm_crash_trigger.so" })
        assertTrue(images.any { it.name == "libart.so" })
        assertEquals("build ids must be unique per image", images.size, images.map { it.uuid }.toSet().size)
    }

    @Test fun `every binary image carries the uuid that 01 section 4_3_2 marks mandatory`() {
        val images = tombstone.toBinaryImages()

        assertTrue(images.all { it.uuid.isNotBlank() })
        assertTrue(images.all { it.baseAddr.startsWith("0x") })
        assertTrue(images.all { it.size > 0 })
        assertTrue(images.all { it.arch == "arm64" })
    }

    @Test fun `the app's own library appears in binary_images, flagged as app`() {
        val own = tombstone.toBinaryImages().firstOrNull { it.name == "libapm_crash_trigger.so" }

        assertNotNull("the crashing library must be symbolicatable", own)
        assertTrue(own!!.isApp)
        assertEquals(40, own.uuid.length)   // a 20-byte ELF build id in hex
    }

    @Test fun `a frame's build id agrees with the binary image it points into`() {
        val threads = tombstone.toCrashThreads()
        val frame = threads.first().frames.first { it.objectName == "libapm_crash_trigger.so" }
        val image = tombstone.toBinaryImages().first { it.name == "libapm_crash_trigger.so" }

        assertEquals(
            "object_addr must be the base address of the image it belongs to",
            image.baseAddr,
            frame.objectAddr
        )
    }

    @Test fun `garbage and truncated input return null instead of throwing`() {
        assertNull(SystemTombstoneParser.parse(ByteArray(0), packageName))
        assertNull(SystemTombstoneParser.parse(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x01), packageName))
        // A tombstone cut in half is not a tombstone.
        val half = fixture().copyOfRange(0, fixture().size / 2)
        val partial = SystemTombstoneParser.parse(half, packageName)
        assertTrue("must be null or degraded, never wrong", partial == null || partial.threads.isNotEmpty())
    }
}
