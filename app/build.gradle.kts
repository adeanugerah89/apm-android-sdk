plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/*
 * The certificate the DEBUG build trusts so it can reach the local HTTPS ingest server in
 * TESTING-ON-DEVICE.md. It is generated, never committed: a committed copy was a 30-day certificate that had
 * expired within days, and kept a trust anchor in the repository for a key nobody could account for.
 *
 *  - `tools/device-testing/server.crt` present (the one the setup step in TESTING-ON-DEVICE.md creates) → that
 *    certificate is trusted, so the device test works.
 *  - absent → a throwaway certificate is made and its key discarded, so the debug build still assembles and
 *    simply trusts nothing extra that anyone holds a key for.
 */
abstract class LocalTestCaTask : DefaultTask() {
    @get:Internal abstract val deviceCert: RegularFileProperty
    @get:Internal abstract val certConfig: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun generate() {
        val raw = output.get().asFile.resolve("raw").apply { deleteRecursively(); mkdirs() }
        val target = raw.resolve("apm_local_test_ca.pem")
        val existing = deviceCert.get().asFile
        if (existing.isFile) {
            existing.copyTo(target, overwrite = true)
            return
        }
        val scratch = temporaryDir
        val process = ProcessBuilder(
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-days", "30", "-nodes",
            "-keyout", scratch.resolve("discarded.key").path, "-out", target.path,
            "-config", certConfig.get().asFile.path
        ).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "openssl failed while making the debug test certificate:\n$log" }
        scratch.resolve("discarded.key").delete()
    }
}

/*
 * Verification app (A17, MOB-25). Its only job is to exercise the SDK on a real device so the
 * device-only checklist in FEATURES.md can actually be run.
 *
 * Deliberately plain Views, no Compose. Two reasons, both learned the hard way:
 *  - The template's Compose stack could not build at all: `lifecycle-runtime-compose-android:2.11.0`
 *    demands compileSdk 37 while AGP 8.13.2 caps at 36.
 *  - A12 showed dependency footprint is a budget concern. An app used to measure the SDK should
 *    add as little of its own weight and background behaviour as possible, or it measures itself.
 *
 * The namespace is NOT the SDK's. It used to be `edts.apm.android.sdk` for both, which meant the
 * app's generated R/BuildConfig collided with the library's.
 */
android {
    namespace = "edts.apm.android.verifier"
    compileSdk = 36

    defaultConfig {
        applicationId = "edts.apm.android.verifier"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // A9b Langkah 0 only: a JNI shim that faults for real, so xCrash's dumper can be tested
    // against a genuine SEGV_MAPERR rather than an injected SI_USER signal.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        // The app reads its own version/appId back out to hand to ApmConfig, exactly as an
        // adopting app would.
        buildConfig = true
    }

    buildTypes {
        release {
            // Signed with the debug key so the R8-minified build can actually be installed and
            // exercised. A release build that cannot be run is a release build nobody checks.
            signingConfig = signingConfigs.getByName("debug")
            // A19: R8 on, because `01` §8's symbol upload is about `mapping.txt` and that file only
            // exists when R8 runs. It also makes the verification app exercise the SDK the way a
            // real adopter's release build does — which is the only way the AAR's consumer rules
            // are ever actually tested.
            isMinifyEnabled = true
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
}

val generateLocalTestCa = tasks.register<LocalTestCaTask>("generateLocalTestCa") {
    deviceCert.set(rootProject.layout.projectDirectory.file("tools/device-testing/server.crt"))
    certConfig.set(rootProject.layout.projectDirectory.file("tools/device-testing/cert.cnf"))
    output.set(layout.buildDirectory.dir("generated/localTestCa"))
    outputs.upToDateWhen { false }   // the inputs live outside the project model; making one is cheap
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateLocalTestCa, LocalTestCaTask::output)
    }
}

dependencies {
    implementation(project(":sdk"))
    implementation(libs.androidx.core.ktx)
    // Needed to demonstrate the second integration step: attaching APM.networkEventListener()
    // to the host app's own client. If this app did not make real HTTP calls, that step would be
    // documented but never actually exercised.
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}

// `01` §8 — symbol upload as a release gate (A19). The coordinates are read here, where AGP's
// types resolve, and handed to the script.
extra["apmSymbolsAppId"] = android.defaultConfig.applicationId
extra["apmSymbolsVersion"] = android.defaultConfig.versionName
extra["apmSymbolsBuild"] = android.defaultConfig.versionCode.toString()
apply(from = rootProject.file("gradle/scripts/symbol-upload.gradle.kts"))
