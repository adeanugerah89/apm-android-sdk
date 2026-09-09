import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * `01` §8 — `POST /v1/symbols`, uploaded from CI, never from a device.
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
 * Credentials are a CI token, separate from the app key (`01` §8), and are never committed:
 * `-PapmSymbolsUrl`/`-PapmCiToken` or `APM_SYMBOLS_URL`/`APM_CI_TOKEN`.
 */
fun resolveSetting(project: Project, property: String, env: String): String? =
    (project.findProperty(property) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

/**
 * Posts one file as `multipart/form-data`. Written against `HttpURLConnection` rather than OkHttp
 * so the build does not depend on the SDK's own runtime dependencies — a build script that shares
 * a HTTP stack with the thing it is releasing is a version conflict waiting to happen.
 */
fun uploadMultipart(
    endpoint: String,
    token: String,
    fields: Map<String, String>,
    fileField: String,
    file: File
): Pair<Int, String> {
    val boundary = "----apm" + System.currentTimeMillis()
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 30_000
        readTimeout = 120_000
        setRequestProperty("Authorization", "Bearer $token")
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    }

    DataOutputStream(connection.outputStream.buffered()).use { out ->
        fields.forEach { (name, value) ->
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            out.write(value.toByteArray(Charsets.UTF_8))
            out.writeBytes("\r\n")
        }
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"\r\n")
        out.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
        file.inputStream().use { it.copyTo(out) }
        out.writeBytes("\r\n--$boundary--\r\n")
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
    description = "Uploads the release mapping.txt to POST /v1/symbols. Fails the build if it cannot (01 §8)."
    dependsOn("assembleRelease")

    val mappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    // Supplied by the applying module: an applied script cannot resolve AGP's types, and reaching
    // for them through a cast fails at script-compile time rather than at execution.
    val appId = project.findProperty("apmSymbolsAppId") as String?
    val versionName = project.findProperty("apmSymbolsVersion") as String?
    val versionCode = project.findProperty("apmSymbolsBuild") as String?

    doLast {
        val endpoint = resolveSetting(project, "apmSymbolsUrl", "APM_SYMBOLS_URL")
            ?: throw GradleException(
                "SYMBOLS: no endpoint. Set -PapmSymbolsUrl=… or APM_SYMBOLS_URL. `01` §8 makes symbol " +
                    "upload a release gate, so this task refuses to be a no-op."
            )
        val token = resolveSetting(project, "apmCiToken", "APM_CI_TOKEN")
            ?: throw GradleException("SYMBOLS: no CI token. Set -PapmCiToken=… or APM_CI_TOKEN.")

        val mapping = mappingFile.get().asFile
        if (!mapping.exists() || mapping.length() == 0L) {
            throw GradleException(
                "SYMBOLS: no mapping.txt at ${mapping.path}. The release build must have R8 enabled " +
                    "(isMinifyEnabled = true), otherwise there is nothing to symbolicate with."
            )
        }

        println("SYMBOLS uploading ${mapping.length()} bytes for $appId $versionName ($versionCode)")

        val (code, body) = try {
            uploadMultipart(
                endpoint = endpoint,
                token = token,
                fields = mapOf(
                    "app_id" to (appId ?: "unknown"),
                    "version" to (versionName ?: "unknown"),
                    "build" to (versionCode ?: "unknown"),
                    "platform" to "android"
                ),
                fileField = "file",
                file = mapping
            )
        } catch (e: Exception) {
            throw GradleException("SYMBOLS FAILED: could not reach $endpoint — ${e.message}", e)
        }

        if (code !in 200..299) {
            throw GradleException(
                "SYMBOLS FAILED: $endpoint returned $code. ${body.take(400)}\n" +
                    "`01` §8: a release must not proceed without symbols — without them crash reports " +
                    "are unreadable, and that is only discovered weeks later."
            )
        }
        println("SYMBOLS OK $code")
    }
}
