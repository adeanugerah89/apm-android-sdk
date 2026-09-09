# APMKit Android SDK

Application performance monitoring for Android: network capture, crash and ANR reporting,
breadcrumbs, and cold-start metrics. Byte-identical wire output to the iOS SDK — the same backend
ingests both.

```kotlin
implementation("edts.apm.android:apm-android-sdk:0.1.0")
```

```kotlin
APM.start(context = this, config = ApmConfig(
    appId = BuildConfig.APPLICATION_ID,
    appVersion = BuildConfig.VERSION_NAME,
    appBuild = BuildConfig.VERSION_CODE.toString(),
    ingestUrl = "https://<ingest host>/v1/ingest",
    appKey = "<your app key>",
))
```

**→ [INTEGRATION.md](INTEGRATION.md)** — the full guide. Three manual steps, ~30 minutes, plus the
two things you should know before committing to it.

## What it captures

| | |
|---|---|
| Network | Request timings and failure phase (DNS/TCP/TLS/TTFB) via OkHttp `EventListener`. Never bodies. |
| Crashes | JVM exceptions, and native crashes from Android's own tombstone (API 30+) |
| ANR | Main-thread watchdog, reported as `crash_type: anr` |
| Performance | Cold start to first frame |
| Breadcrumbs | Screen changes, lifecycle, connectivity — automatic; plus your own |
| Errors | `APM.logError()`, with the call site filled in automatically |

Capture never blocks the calling thread. Everything is scrubbed of personal data and encrypted
before it touches disk, and nothing is deleted locally until the backend has acknowledged it.

## Repository layout

| Path | |
|---|---|
| `sdk/` | the library — this is what ships |
| `app/` | verification app: one button per behaviour, used to test on real devices |
| `docs/` | the product spec (`00` overview · `01` wire contract · `02` mobile SDK) |
| `INTEGRATION.md` | how an app adopts the SDK |
| `DATA-INVENTORY.md` | every field collected, and how to fill Play Data Safety |
| `RELEASING.md` | how a version is published, and the release gates |
| `CONSTITUTION.md` | binding rules and every dated decision, with reasons |
| `FEATURES.md` | scope, status and the evidence behind each ✅ |

## Building and verifying

```bash
./verify.sh all        # build (sdk + verification app), unit tests, lint, size budget, publication
```

That command is the definition of green — CI runs the same script, so there is only ever one.

```bash
./gradlew :sdk:connectedDebugAndroidTest   # needs a device or emulator
```

Not part of `verify.sh` because it needs hardware. Run it anyway before a release: several defects
in this SDK were invisible to the JVM tests and only appeared on a real device — a Keystore call
that failed on every device while 300 unit tests stayed green, and an ANR detector that missed the
blocks it was built to catch.

## Status

Phase 1 and Phase 2 are complete. Distribution to the internal Maven repository and symbol upload
are implemented and tested but not yet switched on — both are waiting on a repository URL and
credentials, not on code. See `FEATURES.md`.
