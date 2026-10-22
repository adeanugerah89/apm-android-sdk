import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

/**
 * Reads a publish setting from, in order: a `-P` property, `local.properties`, then an environment
 * variable.
 *
 * `local.properties` is included because it is where Android developers expect machine-local
 * settings to live and it is gitignored by convention — verified for this repo. It is **not** a
 * substitute for a secret store: it is still a plaintext file inside the project directory, one
 * `git add -f` away from a public repository, and CI cannot use it. For a release token prefer the
 * macOS Keychain (see `~/.gradle/init.d/apm-credentials.gradle.kts`) or CI secrets.
 */
fun publishSetting(property: String, env: String): String? {
    val fromProperty = (findProperty(property) as String?)?.takeIf { it.isNotBlank() }
    if (fromProperty != null) return fromProperty

    val local = rootProject.file("local.properties")
    val fromLocal = if (local.exists()) {
        val props = Properties()
        local.inputStream().use { stream -> props.load(stream) }
        props.getProperty(property)?.takeIf { it.isNotBlank() }
    } else {
        null
    }
    if (fromLocal != null) return fromLocal

    return System.getenv(env)?.takeIf { it.isNotBlank() }
}

// A13 (MOB-23/24, SEC-19). Coordinates come from gradle.properties so the POM, the AAR's
// BuildConfig.LIBRARY_VERSION and the X-APM-Sdk header can never disagree about what version this is.
val apmGroupId: String by project
val apmArtifactId: String by project
val apmSdkVersion: String by project

group = apmGroupId
version = apmSdkVersion

android {
    namespace = "edts.apm.android.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        buildConfigField("String", "LIBRARY_VERSION", "\"$apmSdkVersion\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // MOB-23: the artifact is an AAR for an internal Maven repo. Sources are published alongside
    // it so an adopting team can step into the SDK while debugging their own integration — the
    // single biggest cost in MOB-25's 30-minute target is not being able to see what went wrong.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("androidTest") { java.srcDirs("src/androidTest/kotlin") }
    }
}

// A43 (MOB-24): the supported API is exactly what `consumer-rules.pro` keeps (RELEASING.md). Strict explicit-API
// mode makes the compiler hold that line — a declaration is public only when someone wrote `public` on it, so
// widening the API takes a visible diff instead of a forgotten modifier. Applies to main sources only.
kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)

    // Instrumented tests (A17). These exist for exactly the things Robolectric cannot do:
    // the real AndroidKeyStore (SEC-08) and a real WorkManager/Looper. Run with
    // `./gradlew :sdk:connectedDebugAndroidTest` against a device or emulator.
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// ─── A12 · Performance budget (`02` §5) ─────────────────────────────────────
//
// "Dilanggar = tidak boleh rilis. Diukur otomatis di CI, bukan diperiksa manual."
//
// This enforces the one budget line a JVM/Gradle runner can measure honestly: the size of what
// the SDK itself ships. The other lines in `02` §5 — cold start p95, CPU, resident memory, and
// "zero blocking I/O on the main thread" — cannot be measured without running on a device under
// load, and a check that silently measures something easier would be worse than no check at all.
// They are tracked as explicit gaps on A12 in FEATURES.md rather than approximated here.

/** `02` §5: "Penambahan ukuran app ≤ 1,5 MB per platform (terkompresi)". */
val sdkAarBudgetBytes = 1_500L * 1024

tasks.register("checkPerformanceBudget") {
    group = "verification"
    description = "Fails the build if the release AAR exceeds the `02` §5 size budget."
    dependsOn("assembleRelease")

    val aarFile = layout.buildDirectory.file("outputs/aar/${project.name}-release.aar")
    val budget = sdkAarBudgetBytes

    doLast {
        val aar = aarFile.get().asFile
        if (!aar.exists()) {
            throw GradleException("PERF_BUDGET: release AAR not found at ${aar.path}")
        }
        val actual = aar.length()
        val pct = actual * 100 / budget
        println("PERF_BUDGET aar=${actual}B budget=${budget}B used=${pct}%")

        if (actual > budget) {
            throw GradleException(
                "PERF_BUDGET FAIL: release AAR is ${actual}B, over the ${budget}B budget (`02` §5). " +
                    "Shrink the SDK or raise the budget with a dated decision in CONSTITUTION.md — " +
                    "do not silently bump this number."
            )
        }
    }
}

// ─── A13 · Publication (MOB-23/24, SEC-19) ──────────────────────────────────
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = apmGroupId
            artifactId = apmArtifactId
            version = apmSdkVersion

            afterEvaluate { from(components["release"]) }

            pom {
                name.set("APMKit Android SDK")
                description.set("APM monitoring SDK for Android — network, crash, breadcrumb and stability capture.")
                licenses {
                    license {
                        name.set("Proprietary — internal use only")
                        distribution.set("repo")
                    }
                }
            }
        }
    }

    repositories {
        // The real destination (SEC-19). Configured at release time, never committed — see
        // gradle.properties. Registered only when supplied, so a developer without credentials can
        // still run every other publish task.
        val internalUrl = publishSetting("apmMavenUrl", "APM_MAVEN_URL")
        if (!internalUrl.isNullOrBlank()) {
            maven {
                name = "internal"
                url = uri(internalUrl)
                credentials {
                    username = publishSetting("apmMavenUsername", "APM_MAVEN_USER")
                    password = publishSetting("apmMavenPassword", "APM_MAVEN_PASSWORD")
                }
            }
        }

        // A real repository on disk, so the publication can be produced and inspected without any
        // credentials. This is what makes the release path testable at all: `publishToMavenLocal`
        // hides the layout inside ~/.m2, and a broken POM or a missing artifact would otherwise
        // only surface during an actual release.
        maven {
            name = "localVerification"
            url = uri(layout.buildDirectory.dir("local-repo"))
        }
    }
}

/**
 * SEC-19 requires published checksums. Gradle emits md5/sha1 (and sha256/sha512) beside each
 * artifact for the repository's own integrity checking; this writes a single consolidated
 * SHA-256 manifest that a release note can quote and an adopter can verify by hand.
 */
tasks.register("generateChecksums") {
    group = "publishing"
    description = "Writes SHA-256 checksums for the published artifacts (SEC-19)."
    dependsOn("publishReleasePublicationToLocalVerificationRepository")

    val repoDir = layout.buildDirectory.dir("local-repo")
    val outFile = layout.buildDirectory.file("local-repo/CHECKSUMS-SHA256.txt")

    doLast {
        val root = repoDir.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        val lines = root.walkTopDown()
            .filter { it.isFile && (it.extension == "aar" || it.extension == "pom" || it.extension == "jar") }
            .sortedBy { it.path }
            .map { file ->
                digest.reset()
                val hash = digest.digest(file.readBytes())
                    .joinToString("") { byte -> "%02x".format(byte) }
                "$hash  ${file.relativeTo(root).path}"
            }
            .toList()

        if (lines.isEmpty()) throw GradleException("SEC-19: no artifacts found to checksum in ${root.path}")
        outFile.get().asFile.writeText(lines.joinToString("\n") + "\n")
        println("CHECKSUMS wrote ${lines.size} entries to ${outFile.get().asFile.path}")
        lines.forEach { println("  $it") }
    }
}

/**
 * Validates the publication before it can ever reach the internal repository (MOB-23, SEC-19).
 *
 * A release is the one operation with no undo: an artifact that adopting teams have already
 * resolved cannot be quietly replaced. Everything asserted here is a mistake that would otherwise
 * be discovered by an adopter — a POM with a version range, an AAR missing its classes, a
 * checksum manifest that does not match the bytes beside it.
 */
tasks.register("verifyPublication") {
    group = "verification"
    description = "Fails if the published artifacts are malformed (MOB-23, SEC-19)."
    dependsOn("generateChecksums")

    val repoDir = layout.buildDirectory.dir("local-repo")
    val expectedGroupPath = apmGroupId.replace('.', '/')
    val expectedArtifact = apmArtifactId
    val expectedVersion = apmSdkVersion

    doLast {
        val root = repoDir.get().asFile
        val dir = File(root, "$expectedGroupPath/$expectedArtifact/$expectedVersion")
        val problems = mutableListOf<String>()

        // Located by extension rather than by exact name: Maven rewrites SNAPSHOT filenames with a
        // build timestamp (`…-0.1.0-20260909.074855-1.aar`), so matching `artifact-version.aar`
        // works for releases and silently fails every SNAPSHOT — which is exactly the form a team
        // rehearses a first publish with.
        val present = dir.listFiles().orEmpty()
        fun artifact(suffix: String): File? =
            present.firstOrNull { it.name.endsWith(suffix) && !it.name.endsWith(".md5") }

        val aar = artifact(".aar")
        val pom = artifact(".pom")
        val sources = artifact("-sources.jar")

        if (aar == null) problems += "missing AAR in ${dir.path}"
        if (pom == null) problems += "missing POM in ${dir.path}"
        if (sources == null) problems += "missing sources jar in ${dir.path}"

        if (aar != null) {
            val entries = zipEntryNames(aar)
            if ("classes.jar" !in entries) problems += "AAR has no classes.jar"
            if ("AndroidManifest.xml" !in entries) problems += "AAR has no AndroidManifest.xml"
            // The reference network security config (A11/SEC-10) is part of the public surface.
            if (entries.none { it.endsWith("apm_network_security_config.xml") }) {
                problems += "AAR is missing res/xml/apm_network_security_config.xml"
            }
        }

        if (sources != null && zipEntryNames(sources).none { it.endsWith(".kt") }) {
            problems += "sources jar contains no Kotlin sources"
        }

        if (pom != null) {
            val text = pom.readText()
            if (!text.contains("<groupId>$apmGroupId</groupId>")) problems += "POM groupId is wrong"
            if (!text.contains("<artifactId>$expectedArtifact</artifactId>")) problems += "POM artifactId is wrong"
            if (!text.contains("<version>$expectedVersion</version>")) problems += "POM version is wrong"
            // SEC-19: "dependensi ... versinya dikunci". A range would let an adopter resolve a
            // version this SDK was never built or tested against.
            Regex("<version>([^<]+)</version>").findAll(text)
                .map { it.groupValues[1] }
                .filter { v -> v.any { it in "[](),+" } }
                .forEach { problems += "POM contains an unlocked version range: $it" }
        }

        // The manifest must describe the bytes actually sitting next to it.
        val manifest = File(root, "CHECKSUMS-SHA256.txt")
        if (!manifest.exists()) {
            problems += "missing CHECKSUMS-SHA256.txt (SEC-19)"
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
            manifest.readLines().filter { it.isNotBlank() }.forEach { line ->
                val expected = line.substringBefore("  ")
                val file = File(root, line.substringAfter("  "))
                if (!file.exists()) {
                    problems += "checksum manifest references a missing file: ${file.name}"
                } else {
                    digest.reset()
                    val actual = digest.digest(file.readBytes()).joinToString("") { b -> "%02x".format(b) }
                    if (actual != expected) problems += "checksum mismatch for ${file.name}"
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException("PUBLICATION INVALID:\n" + problems.joinToString("\n") { "  - $it" })
        }
        println("PUBLICATION OK $apmGroupId:$expectedArtifact:$expectedVersion (aar=${aar?.length()}B, sources=${sources?.length()}B)")
    }
}

fun zipEntryNames(file: File): List<String> =
    ZipFile(file).use { zip -> zip.entries().toList().map { entry -> entry.name } }
