# Integrating the APMKit Android SDK

Target: a working integration in under 30 minutes (MOB-25).

There are **three manual steps**. Everything else — screen tracking, lifecycle breadcrumbs,
connectivity, crash capture, ANR detection, cold start, upload scheduling — happens on its own once
`APM.start()` runs.

> **Read [Before you commit](#before-you-commit) first.** Two things about this SDK affect your app
> beyond adding a dependency, and it is better to know them now than after review.

---

## 1. Add the dependency

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("edts.apm.android:apm-android-sdk:0.1.0")
}
```

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/adeanugerah89/apm-android-sdk")
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.token").get()
            }
        }
        google(); mavenCentral()
    }
}
```

`gpr.user` / `gpr.token` go in **your** `~/.gradle/gradle.properties`, never in the repository. The
token is a classic Personal Access Token with the `read:packages` scope.

> GitHub's Maven registry requires authentication even for public packages — so every developer and
> every CI job needs that token. If that is a problem for your team, tell the APM team; the SDK is
> not tied to GitHub Packages and can be moved to an internal Nexus/Artifactory.

Requires **minSdk 24**. No ProGuard/R8 rules to add — the AAR ships its own, and they are required:
without them R8 renames `SyncWorker` (uploads stop, silently) and strips the serializers (nothing is
recorded at all).

## 2. Start the SDK — `Application.onCreate()`

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        APM.start(
            context = this,
            config = ApmConfig(
                appId = BuildConfig.APPLICATION_ID,
                appVersion = BuildConfig.VERSION_NAME,
                appBuild = BuildConfig.VERSION_CODE.toString(),
                ingestUrl = "https://<ingest host>/v1/ingest",   // must be https
                appKey = "<your app key>"
            )
        )
    }
}
```

Register it in the manifest (`android:name=".MyApplication"`) if you do not already have an
`Application` subclass.

`start()` returns immediately and never throws. When called from the main thread it assembles the
SDK on a background thread, because assembly touches disk and `Application.onCreate()` is the main
thread. For a few milliseconds afterwards `APM.isStarted` is still `false` and every API is a no-op
— that is normal and needs no handling. Use `APM.awaitStarted(timeoutMs)` only if you genuinely
need to block.

## 3. Attach the network listener to **your** OkHttpClient

```kotlin
val client = OkHttpClient.Builder()
    .apply { APM.networkEventListener()?.let { eventListener(it) } }
    .build()
```

This step is manual by necessity: the SDK does not own your HTTP client and cannot attach itself to
it. Without it there is no network telemetry at all — everything else still works.

The listener excludes your ingest host from capture, so the SDK cannot record its own uploads.

**If you use Retrofit**, attach it to the `OkHttpClient` you hand to `Retrofit.Builder().client(…)`.

**Only an `OkHttpClient` that carries the listener is captured.** That covers everything built on it (Retrofit,
Coil, Glide, Ktor with the OkHttp engine) — but not a second `OkHttpClient` you forgot to attach it to, nor
`HttpURLConnection`, Cronet, Volley's default stack or Ktor on another engine.

**For those, report the request yourself** once it finishes:

```kotlin
// A response arrived (any status; 400+ is also recorded as a failure, as with OkHttp):
APM.reportNetworkRequest(url = url, method = "GET", durationMs = elapsedMs, statusCode = 200)
// No response (timeout, DNS, TLS, cancelled, ...):
APM.reportNetworkRequest(url = url, method = "GET", durationMs = elapsedMs, error = throwable)
```

It produces the same `network` / `network_failure` events as the listener. Only the host and the normalised path
are kept — the query string, fragment and credentials in `url` are discarded, and no header or body can be passed.
DNS/TCP/TLS/first-byte timings are not recorded (your stack does not expose them to the SDK). **Do not report a
request that already went through an instrumented `OkHttpClient`** — it would be counted twice. Reporting is
non-blocking and never throws; it does nothing before `APM.start()` succeeds.

## 4. Upload symbols from your CI — required for readable crashes

> **Status (2026-11-27): uploads work; readable frames do not yet.** The backend accepts and stores your
> `mapping.txt` (apm-backend feat-029, upload half). Deobfuscating crash frames with it is the second half
> and is not built yet, so Android frames still show R8 names for now. Upload anyway: every release you
> upload now is readable as soon as retrace lands, and one you did not upload never will be.

A release build is obfuscated by R8, so a crash arrives as `a.b.c(SourceFile:1)` unless the
`mapping.txt` from *your* build is uploaded. The SDK is shrunk as part of your app, so this is your
mapping file, not the APM team's.

```kotlin
// app/build.gradle.kts
extra["apmSymbolsAppId"] = "<your APM app id>"          // the same appId you pass to ApmConfig
extra["apmSymbolsVersion"] = android.defaultConfig.versionName
extra["apmSymbolsBuild"] = android.defaultConfig.versionCode.toString()
apply(from = "<path to>/symbol-upload.gradle.kts")
```

```bash
./gradlew :app:uploadSymbols \
  -PapmSymbolUploadUrl=<base URL of the symbol upload service> \
  -PapmSymbolUploadToken="$APM_SYMBOL_UPLOAD_TOKEN"
```

**`apmSymbolsVersion` and `apmSymbolsBuild` must be the same values you pass to `ApmConfig` as
`appVersion` and `appBuild`.** A mapping file has no UUID, so the release is the only thing that ties
a crash to its mapping; a mapping uploaded under another version or build matches no crash. The task
refuses to run when any of the three is missing rather than upload under a placeholder.

The token is an **upload token** issued per app by the APM team (apm-backend
`keyctl upload-token issue --app=<id>`), separate from your app key; never use the app key here.
Re-running the upload for the same build is safe (`already_present`); uploading a *different*
mapping for a build that already has one fails with `409`, because crashes already read with the
first mapping must keep reading the same.

The task **fails the build** if the upload fails — deliberately (`01` §8). A release that ships
without symbols produces crash reports nobody can read, and that is only discovered weeks later.

---

## Optional API

```kotlin
APM.logError(exception)                                    // handled error + breadcrumb trail
APM.logError(exception, mapOf("order_id" to "12345"))      // up to a few small extra fields
APM.setUser("+628123456789")                               // any string you use to identify a user
APM.clearUser()                                            // call on logout
APM.breadcrumb("tapped checkout", BreadcrumbCategory.USER_ACTION)
```

`BreadcrumbCategory`: `NAVIGATION`, `USER_ACTION`, `NETWORK`, `LIFECYCLE`, `STATE`, `LOG`.
`BreadcrumbLevel`: `DEBUG`, `INFO`, `WARNING`, `ERROR` (defaults to `INFO`).

**`setUser` ignores an empty or whitespace-only string** (it is not an identity; the previous user stays). Any
other value is taken as given — including placeholders like `"0"` or `"guest"`, which would put every
signed-out visitor under one user: before login do not call `setUser`, and on logout call `clearUser()`.

**`setUser` survives a restart** — call it when the user signs in, as you would on iOS; you do **not** need to
call it again on every launch (calling it again with the same value is harmless and changes nothing). **Call
`clearUser()` when they sign out.** The SDK cannot see your sign-out, and without it anonymous activity keeps
going out under the last user.

Events are uploaded under the user who was signed in **when they were captured**, not whoever is signed in
when the upload happens. A user switch (`setUser` with a different value, or `clearUser`) therefore does not
re-label what was already captured. If the SDK can no longer tell whose an older event was — for example after
a restart that skipped several users — it sends it as anonymous (`user_id_source: generated`) rather than guess.

Every one of these is safe to call from any thread, before `start()`, and after a failed `start()` —
they do nothing rather than throw. Capture never blocks the calling thread.

### `ApmConfig`

| Parameter | Default | Notes |
|---|---|---|
| `appId`, `appVersion`, `appBuild` | — | required |
| `ingestUrl` | — | **must be https** — the SDK refuses to start otherwise |
| `appKey` | — | an identifier, not a secret (it ships inside your APK) |
| `configUrl` | derived from `ingestUrl` | set only if config lives on another host |
| `uploadIntervalMs` | 30 000 | remote config can lower it |
| `debugLogging` | `false` | Logs every captured event to logcat under tag `APM`. **Ignored entirely in a release build**, so leaving it on cannot leak events in production. |
| `pinning` | `null` | certificate pinning, off by default. See below. |

### Certificate pinning (optional, off by default)

```kotlin
pinning = CertificatePinningConfig.of(
    hostname = "ingest.example.com",
    primary = "<base64 SPKI sha256>",
    backup = "<a different pin>"
)
```

A backup pin is mandatory by signature — a config with one pin cannot be expressed. If a server
certificate rotates and pinning breaks, the APM team can switch it off remotely without an app
release. Returns `null` (pinning disabled) if a pin is malformed; it never throws.

---

## Before you commit

### The SDK adds four permissions to your app

Through its WorkManager dependency, not by asking for them:

`WAKE_LOCK` · `RECEIVE_BOOT_COMPLETED` · `FOREGROUND_SERVICE` · `REORDER_TASKS`

The SDK itself declares only `INTERNET` and `ACCESS_NETWORK_STATE`. The four above are WorkManager's
and **will appear on your Play Store listing**. If that is a problem for your app, raise it with the
APM team before integrating rather than after.

### Backups

**You need to do nothing.** The event queue, the cached remote config and the SDK's small key-value state
(the random `install_id`, a generated fallback user id, the current session id, two crash-dedup markers) are
all stored in `noBackupFilesDir`, which Android's Auto Backup always excludes — whatever backup rules your
app declares. Two consequences worth knowing:

- A reinstall, or a restore onto another device, starts a **new** `install_id`, as `01` §2 specifies. One
  `install_id` never ends up on two live devices.
- An app **update** keeps its `install_id` and its pending events: the SDK moves what an older build kept in
  `filesDir` / `shared_prefs` into the no-backup directory the first time it starts.

The SDK does not set `allowBackup`, `fullBackupContent` or `dataExtractionRules`: from a library the manifest
merger would force them onto your whole app, overriding a policy that is yours. A host-supplied `setUser`
value is never written to disk at all.

### What is collected

Network timings and failure categories (never request or response bodies), handled and unhandled
errors, crashes, ANRs, cold start, screen names, breadcrumbs, and coarse device/integrity signals.
Personal data is redacted before anything reaches disk. `user_id` is whatever string you pass to
`setUser` — it is sent over TLS and hashed server-side, and the backend never stores it raw. On the device the
current value is kept in app-private storage that is excluded from backup (encrypted when the Keystore allows).

**Your Play Console Data Safety form and your privacy policy both need updating** — a release whose
Data Safety form does not match its behaviour can be rejected. [DATA-INVENTORY.md](DATA-INVENTORY.md)
lists every field the SDK collects and maps it to the form's answers, including the one decision that
is yours: what you pass to `APM.setUser()`.

### Known limitations today

- **Native (C/C++) crash capture requires API 30+.** Below that, only JVM crashes are captured.
- **Native crashes are not symbolicated yet** — they resolve to a library and an offset, not a
  function name. The symbol upload contract for `.so` files does not exist yet.

---

## Checking it works

1. Run your app, trigger `APM.logError(RuntimeException("apm check"))`.
2. Confirm files appear in `<app noBackupFilesDir>/apm-queue/`:
   ```bash
   adb shell run-as <your.package.name> ls -l no_backup/apm-queue/
   ```
   (Debug builds only — `run-as` does not work on a release APK.)
3. With `debugLogging = true` in a debug build, `adb logcat -s APM` shows one line per captured
   event — the fastest way to confirm capture is working.
4. Each file should begin with byte `0x01` — the at-rest encryption header. If a file starts with
   `{`, the device Keystore was unavailable and the SDK fell back to plaintext; still functional,
   but tell the APM team.
5. Events leave the queue once uploaded. If the queue only grows, the upload is failing — check
   `ingestUrl` and that the device has network.

`app/` in the SDK repo is a working verification app with a button per behaviour; use it as a
reference if something does not line up.

## Getting help

Open an issue on the SDK repo with your `appVersion`, `appBuild`, Android version, and what you
expected versus what you saw. If the SDK failed to start, `adb logcat -s APM` carries one warning
line explaining why.
