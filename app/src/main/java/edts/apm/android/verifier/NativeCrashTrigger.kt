package edts.apm.android.verifier

/**
 * Provokes a genuine native memory fault (A9b, Langkah 0) — as opposed to `kill -11`, which
 * delivers an injected signal (`SI_USER`) that xCrash's dumper has failed on every time.
 */
object NativeCrashTrigger {
    private var available = false

    init {
        available = try {
            System.loadLibrary("apm_crash_trigger")
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun isAvailable(): Boolean = available

    /** Does not return: the process dies with SIGSEGV / SEGV_MAPERR. */
    @JvmStatic
    external fun crashNow(): Int
}
