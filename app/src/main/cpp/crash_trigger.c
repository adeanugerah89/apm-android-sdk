// Genuine native fault trigger for the verification app (A9b, Langkah 0).
//
// Everything captured so far used `kill -11`, which delivers SIGSEGV with code SI_USER — a signal
// injected from outside the process. xCrash's dumper failed on every one of those. This produces
// the other kind: a real memory fault raised by the CPU inside the crashing thread, code
// SEGV_MAPERR. If the dumper succeeds here, the failures were an artefact of how the crash was
// provoked and not a defect in xCrash.
//
// `volatile` and the indirection through a function keep the compiler from turning a null
// dereference into a trap instruction or deleting it as undefined behaviour, which would raise
// SIGILL or nothing at all instead of the SIGSEGV we are trying to observe.
#include <jni.h>

static int read_through(volatile int *p) {
    return *p;
}

JNIEXPORT jint JNICALL
Java_edts_apm_android_verifier_NativeCrashTrigger_crashNow(JNIEnv *env, jclass clazz) {
    volatile int *nowhere = (volatile int *) 0;
    return read_through(nowhere);
}
