package edts.apm.android.sdk.config

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk cache of the last successfully fetched [RemoteConfig] (MOB-20 "dengan cache").
 *
 * The cache exists so a launch with no network still honours the last known kill-switch state
 * rather than silently reverting to `enabled = true`. That is the whole point: a kill switch
 * that forgets itself the moment the device goes offline is not a kill switch.
 *
 * Every operation is fail-safe (CONSTITUTION rule #1): a read that cannot be parsed returns
 * `null` so the caller falls back to [RemoteConfig.DEFAULT], and a write that fails is dropped.
 * Writes are atomic (temp + rename) for the same reason [edts.apm.android.sdk.queue.DiskEventQueue]
 * is — a half-written cache file must never be readable as a valid config.
 */
internal class RemoteConfigStore(
    private val file: File,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {

    fun read(): RemoteConfig? = try {
        if (file.exists()) json.decodeFromString<RemoteConfig>(file.readText(Charsets.UTF_8)).sanitized() else null
    } catch (e: Exception) {
        null
    }

    fun write(config: RemoteConfig): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            tmp.writeText(json.encodeToString(config), Charsets.UTF_8)
            tmp.renameTo(file)   // atomic on POSIX (Linux/Android)
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }
}
