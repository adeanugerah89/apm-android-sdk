import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * `01` §8 — uploads the release `mapping.txt` from CI, never from a device.
 *
 * Applied to an application module. It uploads the R8 `mapping.txt` produced by that module's
 * release build, which is what makes an obfuscated stack trace readable. Without it a crash report
 * arrives as `a.b.c(SourceFile:1)` and the data is, in `01` §8's words, practically lost — and only
 * discovered weeks later when someone tries to read a crash.
 *
 * **This is a release gate.** The task fails the build on any non-2xx response or transport error.
 * That is the entire requirement: "pipeline CI harus **gagal** jika upload symbol gagal." A warning
 * would be worse than nothing, because it would let a release ship believing it had symbols.
 *
 * **The request is the backend's symbol upload, the same one iOS uses** (apm-backend feat-029,
 * CONSTITUTION 2026-11-27): `POST <base>/v1/apps/<app_id>/symbols?platform=android&version=…&build=…`,
 * the raw `mapping.txt` as the body, and an upload token in `X-APM-Upload-Token`. The token is issued
 * per app with apm-backend's `keyctl upload-token issue` and is separate from the app key (SEC-13).
 * The release is the mapping's identity — a mapping.txt has no UUID — so `version`/`build` must be the
 * same `versionName`/`versionCode` the app passes to `ApmConfig` as `appVersion`/`appBuild`.
 *
 * Settings are never committed, and use the same names as APMKit's `scripts/upload-dsyms.sh`:
 * `-PapmSymbolUploadUrl`/`-PapmSymbolUploadToken` or `APM_SYMBOL_UPLOAD_URL`/`APM_SYMBOL_UPLOAD_TOKEN`.
 * The URL is the base of the symbol upload service (e.g. `https://symbols.example.com`), not the full
 * endpoint.
 */
fun resolveSetting(project: Project, property: String, env: String): String? =
    (project.findProperty(property) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

/** The full upload URL for one release. Separate so the shape is easy to read and to check. */
fun symbolUploadUrl(base: String, appId: String, version: String, build: String): String =
    "${base.trimEnd('/')}/v1/apps/${urlEncode(appId)}/symbols" +
        "?platform=android&version=${urlEncode(version)}&build=${urlEncode(build)}"

/**
 * Posts one file as the raw request body. Written against `HttpURLConnection` rather than OkHttp so
 * the build does not depend on the SDK's own runtime dependencies — a build script that shares a HTTP
 * stack with the thing it is releasing is a version conflict waiting to happen. Fixed-length
 * streaming, so the request carries `Content-Length` instead of chunked encoding (some gateways
 * refuse chunked uploads, the same defect A26 fixed in the SDK's own uploader).
 */
fun uploadRaw(endpoint: String, token: String, file: File): Pair<Int, String> {
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 30_000
        readTimeout = 120_000
        setFixedLengthStreamingMode(file.length())
        setRequestProperty("X-APM-Upload-Token", token)
        setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        // The server checks the token before it reads the body. With 100-continue it answers a
        // refused token straight away instead of letting a multi-megabyte body be sent first.
        setRequestProperty("Expect", "100-continue")
    }
    try {
        connection.outputStream.buffered().use { out -> file.inputStream().use { it.copyTo(out) } }
    } catch (e: java.io.IOException) {
        // With 100-continue, a server that answers without asking for the body (a refused token)
        // makes getOutputStream throw ("Server rejected operation"), but the answer itself is
        // readable as the response code. Report that answer; only when there is no response at all
        // is this a transport error. Both pieces are needed: without 100-continue the body write
        // fails instead and the response is lost (checked against cmd/symbolupload, 2026-11-27).
        val code = try { connection.responseCode } catch (_: java.io.IOException) { -1 }
        if (code < 0) throw e
    }

    val code = connection.responseCode
    val body = try {
        (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
    } catch (e: Exception) {
        ""
    }
    connection.disconnect()
    return code to body
}

tasks.register("uploadSymbols") {
    group = "publishing"
    description = "Uploads the release mapping.txt to the APM symbol upload service. Fails the build if it cannot (01 §8)."
    dependsOn("assembleRelease")

    val mappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    // Supplied by the applying module: an applied script cannot resolve AGP's types, and reaching
    // for them through a cast fails at script-compile time rather than at execution.
    val appId = project.findProperty("apmSymbolsAppId") as String?
    val versionName = project.findProperty("apmSymbolsVersion") as String?
    val versionCode = project.findProperty("apmSymbolsBuild") as String?

    doLast {
        val base = resolveSetting(project, "apmSymbolUploadUrl", "APM_SYMBOL_UPLOAD_URL")
            ?: throw GradleException(
                "SYMBOLS: no upload URL. Set -PapmSymbolUploadUrl=… or APM_SYMBOL_UPLOAD_URL (the base URL of " +
                    "the symbol upload service). `01` §8 makes symbol upload a release gate, so this task " +
                    "refuses to be a no-op."
            )
        val token = resolveSetting(project, "apmSymbolUploadToken", "APM_SYMBOL_UPLOAD_TOKEN")
            ?: throw GradleException(
                "SYMBOLS: no upload token. Set -PapmSymbolUploadToken=… or APM_SYMBOL_UPLOAD_TOKEN " +
                    "(issued with apm-backend's `keyctl upload-token issue`)."
            )
        // A mapping is stored under its release. Uploading it under a guessed or placeholder release
        // would store it where no crash will ever look, which is the silent failure this gate exists
        // to prevent — so a missing value fails the build instead of defaulting.
        val missing = listOf(
            "apmSymbolsAppId" to appId, "apmSymbolsVersion" to versionName, "apmSymbolsBuild" to versionCode
        ).filter { it.second.isNullOrBlank() }.map { it.first }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "SYMBOLS: ${missing.joinToString()} not set on the module. They must be the backend app id and " +
                    "the same versionName/versionCode the app passes to ApmConfig, or the mapping matches no crash."
            )
        }

        val mapping = mappingFile.get().asFile
        if (!mapping.exists() || mapping.length() == 0L) {
            throw GradleException(
                "SYMBOLS: no mapping.txt at ${mapping.path}. The release build must have R8 enabled " +
                    "(isMinifyEnabled = true), otherwise there is nothing to symbolicate with."
            )
        }

        // `missing` above has already failed the build if any of the three is null or blank.
        val endpoint = symbolUploadUrl(base, appId.orEmpty(), versionName.orEmpty(), versionCode.orEmpty())
        println("SYMBOLS uploading ${mapping.length()} bytes for $appId $versionName ($versionCode)")

        val (code, body) = try {
            uploadRaw(endpoint, token, mapping)
        } catch (e: Exception) {
            throw GradleException("SYMBOLS FAILED: could not reach $base — ${e.message}", e)
        }

        if (code !in 200..299) {
            throw GradleException(
                "SYMBOLS FAILED: $base returned $code. ${body.take(400)}\n" +
                    "`01` §8: a release must not proceed without symbols — without them crash reports " +
                    "are unreadable, and that is only discovered weeks later."
            )
        }
        println("SYMBOLS OK $code ${body.take(400)}")
    }
}
