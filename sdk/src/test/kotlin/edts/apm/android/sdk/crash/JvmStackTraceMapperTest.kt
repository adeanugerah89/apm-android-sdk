package edts.apm.android.sdk.crash

import org.junit.Test
import org.junit.Assert.*

/** MOB-17 — Java frames per CONSTITUTION 2026-09-07 D1 (no addresses, symbol info filled now). */
class JvmStackTraceMapperTest {

    private fun frame(className: String, methodName: String, file: String, line: Int) =
        StackTraceElement(className, methodName, file, line)

    @Test fun `crashed thread is always first, marked crashed`() {
        val crashed = Thread("crashed-thread")
        val other = Thread("other-thread")
        val stacks = mapOf(
            other to arrayOf(frame("com.example.Other", "run", "Other.kt", 10)),
            crashed to arrayOf(frame("com.example.Crashed", "boom", "Crashed.kt", 5))
        )

        val result = JvmStackTraceMapper.map(stacks, crashed, appPackagePrefix = "com.example")

        assertEquals(0, result[0].index)
        assertTrue(result[0].crashed)
        assertEquals("crashed-thread", result[0].name)
        assertFalse(result[1].crashed)
    }

    @Test fun `is_app is true for frames under the app package prefix`() {
        val thread = Thread.currentThread()
        val stacks = mapOf(
            thread to arrayOf(
                frame("com.merchant.app.LoginActivity", "onCreate", "LoginActivity.kt", 42),
                frame("java.lang.reflect.Method", "invoke", "Method.java", 1)
            )
        )

        val result = JvmStackTraceMapper.map(stacks, thread, appPackagePrefix = "com.merchant.app")

        assertTrue(result[0].frames[0].isApp)
        assertFalse(result[0].frames[1].isApp)
    }

    @Test fun `symbol_name, file and line are filled directly, addresses stay null`() {
        val thread = Thread.currentThread()
        val stacks = mapOf(
            thread to arrayOf(frame("com.example.Foo", "bar", "Foo.kt", 99))
        )

        val result = JvmStackTraceMapper.map(stacks, thread, appPackagePrefix = "com.example")
        val f = result[0].frames[0]

        assertEquals("bar", f.symbolName)
        assertEquals("Foo.kt", f.file)
        assertEquals(99, f.line)
        assertNull(f.objectAddr)
        assertNull(f.instructionAddr)
        assertEquals("com.example.Foo", f.objectName)
    }

    @Test fun `frame index matches original order, 0 is innermost`() {
        val thread = Thread.currentThread()
        val stacks = mapOf(
            thread to arrayOf(
                frame("com.example.A", "inner", "A.kt", 1),
                frame("com.example.B", "outer", "B.kt", 2)
            )
        )

        val result = JvmStackTraceMapper.map(stacks, thread, appPackagePrefix = "com.example")

        assertEquals(0, result[0].frames[0].index)
        assertEquals("inner", result[0].frames[0].symbolName)
        assertEquals(1, result[0].frames[1].index)
        assertEquals("outer", result[0].frames[1].symbolName)
    }

    @Test fun `other threads are ordered by name for determinism`() {
        val crashed = Thread("main")
        val threadB = Thread("zzz-worker")
        val threadA = Thread("aaa-worker")
        val stacks = mapOf(
            crashed to emptyArray<StackTraceElement>(),
            threadB to emptyArray(),
            threadA to emptyArray()
        )

        val result = JvmStackTraceMapper.map(stacks, crashed, appPackagePrefix = "com.example")

        assertEquals(listOf("main", "aaa-worker", "zzz-worker"), result.map { it.name })
    }
}
