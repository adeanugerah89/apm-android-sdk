# Testing the SDK on a device

The unit suite runs on a JVM and cannot see most of what matters here. Four defects in this SDK
were invisible to hundreds of green unit tests and only appeared on a device: encryption that
failed on every real Keystore, an ANR detector that missed the blocks it was built for, empty R8
consumer rules, and remote config that was never fetched at all.

So: before a release, run the device checklist in `FEATURES.md`. This file is how.

## Setup

```bash
# 1. an emulator with a validated network — this matters, see below
~/Library/Android/sdk/emulator/emulator -avd <avd> -no-snapshot-save &
adb shell dumpsys connectivity | grep -o "IS_VALIDATED"      # must print something

# 2. a local HTTPS ingest server (SEC-12 forbids cleartext, so TLS is not optional)
cd tools/device-testing
openssl req -x509 -newkey rsa:2048 -sha256 -days 30 -nodes \
  -keyout server.key -out server.crt -config cert.cnf
python3 local_ingest_server.py received.json server.crt server.key config.json

# 3. point the verification app at it
#    app/src/main/java/.../VerifierApplication.kt → ingestUrl = "https://10.0.2.2:8443/v1/ingest"
./gradlew :app:installDebug
```

The debug build trusts `tools/device-testing/server.crt` — it is copied in at build time by the
`generateLocalTestCa` task in `app/build.gradle.kts` (debug only, so it can never reach a release APK).
**Run step 2 before `installDebug`**: without a `server.crt` the build makes a throwaway certificate whose key
is discarded, and the app will not trust your server. The certificate is no longer committed — the old copy
was valid for two days and had expired. `server.key`, `server.crt` and `received.json` are git-ignored.

**The validated network matters.** WorkManager's `NetworkType.CONNECTED` constraint is not satisfied
by an emulator that has an interface but no working internet; it reports `Constraints not met` and
`SyncWorker` never runs. Two earlier attempts to verify the upload path failed for exactly this
reason and were wrongly read as an SDK problem.

## What the server shows you

`received.json` records each upload: headers, whether the body was gzipped, and the decoded
envelope — `schema_version`, event types, `sdk.health`, `integrity`, `user_id_source`. It is the
only way to see the actual wire output, which is the thing the backend will consume.

It also serves `GET /v1/config` from `config.json`, so remote config and both kill switches can be
exercised by editing that file while the app runs.

## Reading results

- **Events uploaded and deleted:** queue count drops to 0 and requests appear in `received.json`.
- **Fail-closed:** queue count *stays* — data is never dropped because an upload failed.
- **Capture stopped (kill switch):** no new queue files, and with `debugLogging` on, no `APM:`
  log line either — the gate rejects before the logger is reached.

```bash
adb shell run-as <pkg> ls no_backup/apm-queue/     # debug builds only; run-as fails on release
adb logcat -s APM                              # one line per captured event when debugLogging = true
```

## Things that need something this setup cannot give

- A **rooted** device (checklist 2) and an **OOM kill** (checklist 6).
- API 24–29 for the native-crash gap, which needs an older image.
- A release build cannot be inspected with `run-as`; use the app's own status screen.
