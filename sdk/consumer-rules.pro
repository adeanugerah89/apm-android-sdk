# Rules shipped inside the AAR and applied to every adopting app's R8 build.
#
# These are not optional hardening: without them the SDK compiles, installs, and then silently
# stops working in release builds only. Every rule below exists because something in this SDK is
# reached reflectively, and R8 cannot see reflection.

# ─── Public API ─────────────────────────────────────────────────────────────
# The host app calls these by name; keeping them also keeps them readable in a crash report.
-keep public class edts.apm.android.sdk.APM { public *; }
-keep public class edts.apm.android.sdk.ApmConfig { public *; }
-keep public class edts.apm.android.sdk.internal.CertificatePinningConfig { public *; }
-keep public enum edts.apm.android.sdk.breadcrumb.BreadcrumbCategory { *; }
-keep public enum edts.apm.android.sdk.breadcrumb.BreadcrumbLevel { *; }

# ─── WorkManager ────────────────────────────────────────────────────────────
# SyncWorker is constructed reflectively by WorkManager's default factory, by class name. If R8
# renames or removes it, upload stops entirely and nothing reports an error — the queue simply
# fills. The two-argument constructor is the one the factory looks for (see SyncWorker).
-keep class edts.apm.android.sdk.sync.SyncWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }

# ─── kotlinx.serialization ──────────────────────────────────────────────────
# Serializers are generated as nested `$$serializer` classes and resolved through the companion
# object. R8 sees no call site for either, so both must be kept or every event fails to serialize
# and the SDK writes nothing to disk.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class edts.apm.android.sdk.** {
    *** Companion;
}
-keepclasseswithmembers class edts.apm.android.sdk.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class edts.apm.android.sdk.**$$serializer { *; }

# The wire contract is a contract: `@SerialName` values are what the backend matches on, and the
# enum constants below are serialized by name.
-keepclassmembers enum edts.apm.android.sdk.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
