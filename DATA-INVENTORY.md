# Data inventory — APMKit Android SDK

Every field the SDK collects, why it exists, and what it can reveal (SEC-21). Written for a privacy
review and for the adopting team that has to update its own privacy policy (SEC-22).

**Derived from the code, not from the specification.** Each field below is written by a specific
line in this repository; a field the code does not produce is not listed, and vice versa. If you
are auditing, `grep` for the field name — every one of them appears in `sdk/src/main/kotlin`.

Applies to SDK **0.1.0**.

---

## 1. Identifiers

| Field | What it is | Source | Personal data? |
|---|---|---|---|
| `install_id` | Random UUID, generated on first run, stored in a file under the app's private `noBackupFilesDir` (not in `shared_prefs`) | `UUID.randomUUID()` | No. Not tied to a person or device. Kept in the SDK's no-backup state file, so a reinstall or a backup restore onto another device produces a **new** one; an app update keeps it. |
| `user_id` | **Whatever string the host app passes to `APM.setUser()`** — commonly a phone number, email or internal user id. **Kept on the device**: the current value is stored once in the SDK's no-backup state, encrypted with the Android Keystore when it is available (a marked plaintext fallback otherwise), so it survives a restart. It is not written per event | host app | **Yes, if the host app passes personal data.** See below. |
| `user_id_source` | `host` if the app called `setUser` (and not `clearUser`), `generated` otherwise | SDK | No |
| `identity epoch` | An opaque random number that changes when the signed-in user changes. Stored with each queued event so a backlog is uploaded under the user it was captured under. **Not sent, and not the user id** | SDK | No |
| `session_id` | Random UUID per session (foreground until 30 s in background). Stored with each queued event (inside the encrypted queue file) and the **latest one in the SDK's no-backup state file**, so a crash reported at the next launch is filed under the session it happened in | `UUID.randomUUID()` | No |

**`user_id` is the one field that can carry personal data, and the SDK does not decide its
contents — the adopting app does.** It is transmitted raw over TLS and hashed into `user_ref`
server-side (BE-21); the raw value is not persisted by the backend. It is deliberately excluded from
breadcrumbs, logs and every other event field. **On the device it is stored once** (the current value,
in app-private no-backup storage, encrypted when the Keystore is available — the same trade-off as the
queue; if a key exists but one encryption fails, nothing is stored) so that `setUser` survives a restart; the previous value is kept only in memory for the life of the
process, to upload a backlog under the right user.

The SDK **never** reads IMEI, serial number, `ANDROID_ID`, advertising ID, phone number, contacts,
location, or any permission-gated identifier. There is no code path that could — verifiable by
grep, and required by MOB-29/30.

## 2. Device and app context

Snapshotted once per session.

| Field | Example | Personal data? |
|---|---|---|
| `app.id`, `app.version`, `app.build` | `com.example.app`, `3.4.1`, `1204` | No |
| `device.os`, `device.os_version` | `Android`, `14` | No |
| `device.model` | `samsung SM-G991B` | No — model, not an identifier |
| `device.locale` | `id_ID` | No |
| `device.timezone` | `Asia/Jakarta` | Coarse location signal (country-level at most) |
| `sdk.name`, `sdk.version` | `apm-android-sdk`, `0.1.0` | No |
| `sdk.health` | counters: events written / sent / dropped, and drop reasons | No |

### Integrity signals

`is_emulator`, `is_rooted`, `is_dev_mode`, `debugger_attached` — all booleans, all heuristics from
build properties and file checks. No permission is requested for any of them.

## 3. Per-event context

Attached to every event.

| Field | Example | Notes |
|---|---|---|
| `connectivity` | `wifi`, `cellular`, `ethernet`, `offline` | Transport type only — never SSID, carrier or IP |
| `screen` | `CheckoutActivity` | The activity's class name, from automatic screen tracking |
| `app_state` | `foreground`, `background` | |
| `low_power` | `true` / `false` | |

## 4. Event types

### `network` / `network_failure`

| Field | Notes |
|---|---|
| `host` | Hostname only |
| `path` | **Normalised** — numeric and UUID-shaped segments become `{id}` before storage |
| `method`, `protocol`, `status_code` | |
| `tls_version`, `reused_connection` | Negotiated TLS version (e.g. `TLSv1.3`) and whether a pooled connection was reused. Environment facts, no personal data |
| `duration_ms`, `dns_ms`, `tcp_ms`, `tls_ms`, `ttfb_ms` | Timings |
| `req_bytes`, `res_bytes` | Sizes only |
| `failure_category`, `tls_phase_reached` | Failure classification |
| `error_domain`, `error_code`, `underlying_domain`, `underlying_code` | The failing exception's **class name** (e.g. `java.net.UnknownHostException`) and the OS `errno` when one was reported (`0` = none). Class names of platform exceptions only identify the kind of failure; the exception **message** is never sent |

**Request and response bodies are never captured, and there is no option to enable it** (SEC-04).
**No headers are captured** — `Authorization` and `Cookie` cannot leak because no header is recorded
at all. Query strings are not stored.

### `error` — from `APM.logError()`

`name`, `message`, `handled`, `source_file`, `source_function`, `source_line`, a breadcrumb
snapshot (`breadcrumbs`, plus `breadcrumbs_dropped`, always `0` on Android), and any `custom.*` fields
the app supplies. A JVM `crash` event carries the same breadcrumb snapshot.

`message` and `custom.*` are **written by the app's developers**, which makes them the most likely
route for personal data to arrive by accident. Everything is scrubbed (§5), but the adopting team
should still avoid putting personal data in an error message.

Source location comes from a stack walk. On Android this yields a bare file name
(`LoginActivity.kt`) — never a build-machine path.

### `crash`

`crash_type` (`exception`, `signal`, `anr`), `name`, `reason`, `is_fatal`, `threads`,
`binary_images`, `app_state`, `time_since_launch_ms`, and for native crashes `abi`.

`threads` contains stack frames: class/method or native symbol names, file names, line numbers,
memory addresses and build IDs. **A stack trace can incidentally contain personal data** if a
variable name or an exception message does — exception messages are scrubbed like every other
string.

### `breadcrumb`

`timestamp`, `category`, `level`, `message`. Kept in memory only (last 100), attached to `error` and
`crash` events. **Never written to disk on its own.**

Automatic breadcrumbs: screen changes, foreground/background, connectivity changes. Manual ones come
from `APM.breadcrumb()` and are developer-written text — scrubbed, but the same caution as `message`
applies.

### `lifecycle` / `termination`

`lifecycle`: `state` (always `cold_start` today) and `duration_ms`. Foreground/background transitions
are recorded as breadcrumbs, not as events. `termination`: `termination_reason`. No personal data.

(The wire schema also lists a `performance` type, `01` §4.6; this SDK emits none.)

## 5. Redaction, before anything is stored

Scrubbing runs as the **last step before every disk write**, including for data passed through the
manual API. Nothing reaches disk unscrubbed.

Replaced with `[REDACTED]` anywhere they appear in any string field:

- Email addresses
- Indonesian phone numbers (`+62…`, `62…`, `08…`)
- JWT-shaped tokens
- Digit runs of 10 or more (card numbers, account numbers, national IDs)

URL paths are additionally normalised: UUID-shaped and long-numeric segments become `{id}`, so
`/users/628123456789/orders` is stored as `/users/{id}/orders`.

This is pattern-based and therefore not exhaustive. It is a safety net, not a licence to put
personal data into messages.

## 6. Storage, transmission and retention

**Debug logging.** With `debugLogging = true` **and** a debuggable build, each captured event is
written to logcat. The logger sits inside the scrubber, so what it prints is already redacted, and a
release build ignores the flag entirely — logcat is not a private channel and production events
never reach it.

**On the device.** Events are written to the app's private internal storage
(`noBackupFilesDir/apm-queue`), which Android's Auto Backup always excludes, regardless of the host app's backup rules. Encrypted at rest with AES-GCM using a key held
in the Android Keystore that never leaves it. If the Keystore is unavailable the SDK falls back to
plaintext rather than losing telemetry — already-scrubbed data in app-private storage.

Each queued event also records, next to it, **the session, app version/build and OS version it was captured
under**. None of that is sent per event — it exists so that an event uploaded later (a backlog, or a crash
reported after an app update) goes out under the values that were true when it happened. The app
version/build and OS version of the running process are also kept in the SDK's no-backup state file so the
next launch can attribute a crash to them. Non-personal, like every other field of this kind. The host's
`setUser` value is **not** recorded per event: each event carries only a non-personal *identity epoch*, and at
upload the SDK turns it back into the user who was signed in when it was captured — or, if that user is no
longer known (an older era after a restart), the generated anonymous id, **never the user signed in now**.

Bounded at **5 000 events or 20 MB**, whichever comes first; oldest are evicted. No time-based
expiry, so an offline device retains events until capacity forces eviction.

**In transit.** TLS 1.2 minimum. Cleartext is structurally impossible — the SDK's HTTP client has no
cleartext connection spec, and a non-`https` ingest URL prevents the SDK from starting at all.
Certificate pinning is available and off by default.

**Deletion from the device.** An event is deleted only after the backend returns `2xx`, or `400`
(the backend has stated the payload can never be accepted). Every other response leaves the data in
place.

**Retention on the backend is not this SDK's concern** and is not described here — ask the APM
backend team.

## 7. What is never collected

- Request or response bodies — no opt-in exists
- HTTP headers of any kind
- Query string values
- IMEI, serial number, `ANDROID_ID`, advertising ID
- Location, contacts, calendar, photos, microphone, camera
- Keystrokes, screen contents, screenshots
- Any data requiring a runtime permission — the SDK requests none

## 8. Permissions

Declared by the SDK: `INTERNET`, `ACCESS_NETWORK_STATE`.

Added transitively by its WorkManager dependency, and **visible on the adopting app's store
listing**: `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `REORDER_TASKS`. The SDK
uses none of them directly.

No runtime permission is ever requested.

## 9. Turning it off

`enabled: false` in remote config stops all capture and upload for an app, without an app release.
Individual features can be disabled the same way. Data already on the device stays there and is sent
if the SDK is re-enabled.

---

## 10. What every adopting app must do (SEC-22)

Embedding this SDK changes what your app collects, so it changes what you must declare. **Play
Console will reject or take down a release whose Data Safety form does not match its actual
behaviour**, and the form is the app's responsibility — not the SDK team's.

This section is the reference for filling it in. It is guidance from the SDK's behaviour, not legal
advice; your privacy counsel makes the final call.

### Play Console → Data Safety

| Data type | Declare? | Because |
|---|---|---|
| **Crash logs** | **Yes** — collected, transmitted off device | `crash` events, incl. stack traces |
| **Diagnostics** | **Yes** | Network timings, ANR, cold start, `sdk.health` |
| **Other app performance data** | **Yes** | Breadcrumbs, lifecycle, screen names |
| **Device or other IDs** | Usually **no** | `install_id` is a random UUID scoped to your app; it resets on reinstall because the SDK keeps it outside Auto Backup. It is not an advertising ID, `ANDROID_ID`, IMEI or serial — the SDK reads none of those. Confirm against your own other SDKs. |
| **Personal info → User IDs** | **Yes, if you call `APM.setUser()`** | Whatever string you pass is transmitted, and the current value is kept on the device (encrypted when the Keystore allows) so it survives a restart |
| **Personal info → Phone number / Email** | **Yes, if that is what you pass to `setUser()`** | The SDK does not inspect or restrict the value |
| Location, contacts, photos, audio, files, calendar, health, financial | **No** | The SDK collects none of these and requests no runtime permission |

For every type you declare:

- **Collected:** yes — it leaves the device.
- **Shared:** no — data goes to your organisation's own APM backend, not to a third party. Confirm
  this matches how your organisation classifies an internal service.
- **Encrypted in transit:** yes — TLS 1.2 minimum, cleartext is structurally impossible.
- **Processing:** "App functionality" and "Analytics" both apply; crash logs and diagnostics are
  normally declared as **required**, since the SDK is not user-toggleable in-app by default.
- **Data deletion:** the SDK stores nothing off-device itself; deletion is a question for the APM
  backend team.

### The decision that is yours, not ours

**`APM.setUser()` is the only place personal data can enter.** The SDK neither inspects nor limits
the value. Three ways to answer it:

1. **Pass an internal, non-personal user id.** Then you declare "User IDs" and nothing more. This is
   the recommended option.
2. **Pass a phone number or email**, because you need to look users up by it. Then you must also
   declare that data type, and your privacy policy has to say the app transmits it.
3. **Do not call `setUser()` at all.** The SDK generates a random per-install id; no personal data
   is involved. You lose the ability to correlate a report to a specific user.

### Also update your privacy policy

Data Safety is a form; the policy is the statement users read. It should say the app collects crash
and performance diagnostics, that they are sent to your organisation's own monitoring service, and —
if option 2 above — which identifier is transmitted.

### iOS parity

Teams shipping both platforms fill the App Store Connect privacy labels from the iOS inventory, not from
this one. The wire output is meant to be identical, but **it is not yet**: as of 2026-10-02 (APMKit 0.0.7)
iOS also sends, on `network` events, an allowlisted copy of the request and response headers
(`req_headers` / `res_headers` — `Content-Type`, `Content-Length`, `Accept`, `User-Agent` only) and keeps the
query-parameter *names* (values redacted) in `path`. **Android sends none of that:** no header and no query
string ever reaches an event, so `Authorization` and `Cookie` cannot leak by construction (SEC-02, SEC-03),
and a test pins it. Neither platform's attributes are read by the backend today. iOS also keeps the host's
`user_id` in `UserDefaults` (which Auto Backup/iCloud includes), where Android keeps it in no-backup,
Keystore-encrypted storage. These differences are the iOS team's to confirm or remove; see `FEATURES.md` A38.

---

## Open questions for the privacy review

1. **`user_id` contents are the adopting app's choice.** If a team passes a phone number, personal
   data transits the ingest endpoint before being hashed. The claim that survives scrutiny is "not
   stored", not "never sent" — worth stating in that form.
2. **The stored `user_id` is kept as readable text only when there is no Keystore key at all** (marked `p1:` in the state file), as the queue does; a key that exists but fails on one attempt stores nothing instead — so a phone number passed to `setUser` can sit in app-private, no-backup storage unencrypted on such a device. Not in any backup; still worth a reviewer's eye.
3. **`device.timezone`** is a coarse location signal. Retained because it is needed to interpret
   timestamps, but a reviewer should see it listed.
3. **Stack traces and developer-written messages** can carry personal data incidentally. Scrubbing
   is pattern-based, so it cannot be claimed as complete.
