# Releasing the APMKit Android SDK

Covers MOB-23 (distribution), MOB-24 (semver + compatibility) and SEC-19 (internal repository,
published checksums, locked dependency versions).

## Coordinates

```
edts.apm.android:apm-android-sdk:<version>
```

Group, artifact and version live in one place — [`gradle.properties`](gradle.properties). The POM,
`BuildConfig.LIBRARY_VERSION` and the `X-APM-Sdk` header all read from there, so a released
artifact cannot report a version different from the one it was published under.

## Versioning (MOB-24)

Semver. The rule that matters is what counts as breaking:

| Change | Bump |
|---|---|
| Removing or changing a public API signature (`APM`, `ApmConfig`, `CertificatePinningConfig`, …) | **MAJOR** |
| Changing the wire contract in a way an already-deployed backend would reject | **MAJOR** |
| Raising `minSdk` | **MAJOR** |
| Any change to the meaning of an existing field in `01` | **MAJOR** |
| New optional API, new event type, new remote-config flag | MINOR |
| Bug fix, dependency patch bump, internal refactor | PATCH |

### What "public API" means here (decided 2026-10-02, after a code review)

The supported API is **exactly what `consumer-rules.pro` keeps**: `APM`, `ApmConfig`,
`CertificatePinningConfig`, `BreadcrumbCategory` and `BreadcrumbLevel`. Only those survive R8 in an
adopting app with their names intact, so only those can be called, referenced or reflected on by name.

Everything else — `Event`, `EventQueue`, `SyncEngine`, `UserIdProvider`, `RemoteConfig`,
`FailureCategoryMapper`, and every other class in the module — is **implementation**. It is declared
`public` only because Kotlin visibility cannot span the packages of one module without it. It is **not**
covered by the table above, may change in any release including a PATCH, and an adopter's R8 build renames
it anyway. A change to those types is not a MAJOR bump.

Two consequences are worth being explicit about. First, when a type has to be added to the supported list
it must also be added to `consumer-rules.pro` in the same change, or the promise is empty. Second, this is a
decision about *what we promise*, not about *what is visible*: making the implementation types `internal`
would be better still, but it cascades through `EventQueue`, `Event` and `SyncEngine`, which appear in each
other's public signatures, and is tracked as its own piece of work.

**The wire contract is part of the public API.** An SDK talks to a backend that ships separately,
so a field the SDK renames is as breaking as a method it deletes — and worse, because it fails at
ingestion rather than at compile time. `schema_version` in the envelope (`01` §2) is the
coordination point: it moves only on a breaking wire change, and a backend rejects versions it does
not know.

### Compatibility

| SDK version | `schema_version` | minSdk | compileSdk | Notes |
|---|---|---|---|---|
| 0.1.0 | 1 | 24 | 36 | Initial internal release. Native crash symbolication unavailable — see A9b. |

Pre-1.0 means the public API may still change in MINOR releases. Say so to adopting teams
explicitly; do not let a `0.x` number imply stability it does not have.

## Publishing

Credentials are never committed (CONSTITUTION: no secrets in build files or properties).
Supply them at release time:

```bash
./gradlew :sdk:publishReleasePublicationToInternalRepository \
  -PapmMavenUrl=https://maven.internal.example.com/releases \
  -PapmMavenUsername="$APM_MAVEN_USER" \
  -PapmMavenPassword="$APM_MAVEN_PASSWORD"
```

`APM_MAVEN_URL`, `APM_MAVEN_USER` and `APM_MAVEN_PASSWORD` work as environment variables too. The
`internal` repository is registered only when a URL is supplied, so everything below still runs on
a machine with no credentials at all.

### GitHub Packages

The repository in use is `https://maven.pkg.github.com/adeanugerah89/apm-android-sdk`. Nothing in
the build changes — it is an ordinary Maven repository, supplied the same way as any other:

```properties
# ~/.gradle/gradle.properties — on your machine, never in this repo
apmMavenUrl=https://maven.pkg.github.com/adeanugerah89/apm-android-sdk
apmMavenUsername=<your GitHub username>
apmMavenPassword=<PAT with write:packages>
```

The token is a **classic** Personal Access Token with the `write:packages` scope
(github.com → Settings → Developer settings → Personal access tokens → Tokens (classic)).
Fine-grained tokens do not currently work with the Maven registry.

> **Consumers need a token too — even though the repository is public.** GitHub's Maven registry
> requires authentication for *reads*, unlike Maven Central. Every adopting team therefore needs a
> PAT with `read:packages` in their own `~/.gradle/gradle.properties`, and their CI needs one as a
> secret. That is real friction to weigh against an internal Nexus/Artifactory, which usually allows
> anonymous reads inside the network. It is a fine first step, not necessarily the final home.

Consumer side:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/adeanugerah89/apm-android-sdk")
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.token").get()   // read:packages
            }
        }
        google(); mavenCentral()
    }
}
```

### First time: rehearse with a SNAPSHOT

A release cannot be un-released — an artifact adopters have already resolved cannot be quietly
replaced. So the first publish to a new repository should be a SNAPSHOT, which repositories treat as
overwritable:

```bash
./gradlew :sdk:publishReleasePublicationToInternalRepository \
  -PapmSdkVersion=0.1.0-SNAPSHOT \
  -PapmMavenUrl=<URL> -PapmMavenUsername=<user> -PapmMavenPassword=<token>
```

Confirm it appears in the repository, then publish `0.1.0` for real. `verifyPublication` handles
both forms — Maven rewrites SNAPSHOT filenames with a build timestamp, and the check looks up
artifacts by extension rather than by exact name for that reason.

> The `…ToInternalRepository` task only exists once `apmMavenUrl` is supplied — the repository is
> registered conditionally so the build still works on a machine with no credentials. If Gradle says
> the task does not exist, the URL did not reach it.

### Before publishing — every time

```bash
./verify.sh all              # build (sdk + verification app), unit tests, lint, size budget
./gradlew :sdk:verifyPublication
```

`verifyPublication` builds the publication into `sdk/build/local-repo` and refuses it if the AAR is
missing classes or the network-security-config resource, the sources jar is empty, the POM has a
version **range** rather than a locked version (SEC-19), or the SHA-256 manifest does not match the
bytes beside it. A release is the one operation with no undo — an artifact adopters have already
resolved cannot be quietly replaced.

Also run the device suite; several defects in this SDK were invisible to the JVM tests:

```bash
./gradlew :sdk:connectedDebugAndroidTest    # needs a device or emulator
```

### Checksums (SEC-19)

`generateChecksums` writes `sdk/build/local-repo/CHECKSUMS-SHA256.txt`. Publish its contents with
the release notes so an adopter can verify a download by hand. Gradle additionally writes
`.md5`/`.sha1`/`.sha256`/`.sha512` beside every artifact for the repository's own integrity checks.

### Locked dependencies (SEC-19)

The POM must contain exact versions only. `verifyPublication` fails on any range. The dependency
set is deliberately small and is fixed by CONSTITUTION — adding to it requires a dated decision
there, not just a line in `libs.versions.toml`.

## Symbols (`01` §8) — a release gate

An obfuscated crash report is unreadable, and `01` §8 is blunt about the consequence: without
symbols the data is practically lost, and nobody finds out for weeks. So the upload is a gate, not
a step — `:app:uploadSymbols` **fails the build** on any non-2xx response, on a transport error, and
on a missing endpoint or token. It refuses to be a no-op, because a task that quietly does nothing
is how a release ships without symbols.

```bash
./gradlew :app:uploadSymbols \
  -PapmSymbolsUrl=https://ingest.internal.example.com/v1/symbols \
  -PapmCiToken="$APM_CI_TOKEN"
```

`APM_SYMBOLS_URL` and `APM_CI_TOKEN` work as environment variables, and CI reads them from repo
secrets. The token is a **CI token, separate from the app key** (`01` §8) — the app key is an
identifier embedded in the binary (SEC-13) and must never be used to authenticate a release.

What is uploaded is `mapping.txt` from the R8 release build, as `multipart/form-data` with
`app_id`, `version`, `build` and `platform=android`. **R8 must be enabled** (`isMinifyEnabled =
true`) or there is no mapping file and the task fails.

### The adopting app uploads its own symbols

This matters and is easy to get wrong: the SDK ships as an AAR and is shrunk as part of the **host
app's** build, so the `mapping.txt` that can read a crash in the field is the host app's, not this
repo's. `gradle/scripts/symbol-upload.gradle.kts` is applied to the verification app here as the
reference implementation; an adopting team applies the same script to their own app module. Say
this explicitly in the integration document — a team that skips it gets crash reports it cannot read.

### Native symbols are not covered

`01` §8 lists `mapping.txt` for Android and nothing else. Since A9b, native crash reports carry
build IDs and *could* be symbolicated — but that needs the unstripped `.so` files uploaded and
matched by build ID, which the endpoint contract does not describe. Flagged for the backend team;
until it exists, native frames resolve to a library and an offset but not a function name.

## Known release blockers

Do not publish while either of these stands:

1. **The upload path has never run on a device.** Manual checklist item 15 is still open —
   WorkManager reported `Constraints not met` on both test emulators, so `SyncWorker` has never
   executed outside unit tests. Publishing an SDK whose upload path has never run means adopters
   find the problem.
2. **`02` §5 says a violated performance budget means no release.** All eight budget lines pass
   today; re-check with `./verify.sh budget` and the device suite before every release.
