package edts.apm.android.sdk.internal

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A [SharedPreferences] stored in one JSON file, so it can live under `noBackupFilesDir` (A40).
 *
 * A real SharedPreferences file is always placed in `shared_prefs/`, which Auto Backup includes by
 * default — and it cannot be pointed anywhere else. The SDK's few values (`install_id`, a generated
 * fallback user id, the latest session id, two crash-dedup markers) must not be restored onto another
 * device or after a reinstall, so they are kept here instead. Implementing the platform interface keeps
 * every consumer, and its tests, exactly as they were.
 *
 * Behaves like the real thing where the SDK relies on it: reads see an `apply()` immediately, `apply()`
 * writes in the background (never I/O on the caller's thread), `commit()` writes synchronously, and an
 * unreadable file is an empty store rather than a failure. The file is written atomically (temp +
 * rename), the same way the queue and the config cache are.
 *
 * Two deliberate differences: a value read with the wrong type returns the default instead of throwing
 * `ClassCastException` (the SDK must never throw into its host), and change listeners are accepted but
 * never called — nothing in the SDK registers one.
 */
internal class NoBackupPrefs(
    private val file: File,
    private val writer: Executor = DEFAULT_WRITER
) : SharedPreferences {

    private val lock = Any()
    private val values: MutableMap<String, Any> = load()

    private fun load(): MutableMap<String, Any> {
        val result = HashMap<String, Any>()
        try {
            if (!file.exists()) return result
            val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject ?: return result
            for ((key, element) in root) fromJson(element)?.let { result[key] = it }
        } catch (e: Throwable) {
            result.clear()   // a damaged file is an empty store; the next write replaces it
        }
        return result
    }

    private fun fromJson(e: kotlinx.serialization.json.JsonElement): Any? = when (e) {
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> e.longOrNull          // Int and Long share one JSON number
            e.doubleOrNull != null -> e.doubleOrNull
            else -> null
        }
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.toSet()
        else -> null
    }

    private fun toJson(): String = synchronized(lock) {
        JsonObject(values.mapValues { (_, v) ->
            when (v) {
                is String -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is Int -> JsonPrimitive(v)
                is Long -> JsonPrimitive(v)
                is Float -> JsonPrimitive(v)
                is Double -> JsonPrimitive(v)
                is Set<*> -> JsonArray(v.filterIsInstance<String>().sorted().map { JsonPrimitive(it) })
                else -> JsonPrimitive(v.toString())
            }
        }).toString()
    }

    /** Writes the current contents atomically. Returns false instead of throwing. */
    private fun write(): Boolean = synchronized(WRITE_LOCK) {
        // One writer at a time. apply() writes on the background thread while commit() writes on the caller's;
        // both use the same temp path, so unserialised they could interleave and rename a half-written file over
        // the state — which load() reads as empty, silently resetting install_id, the stored user and the epoch.
        // The snapshot is taken INSIDE the lock, so a late writer can never overwrite a newer state with an older one.
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            val json = toJson()
            file.parentFile?.mkdirs()
            tmp.writeText(json, Charsets.UTF_8)
            tmp.renameTo(file)
        } catch (e: Throwable) {
            tmp.delete()
            false
        }
    }

    // ─── reads ──────────────────────────────────────────────────────────────

    override fun getAll(): MutableMap<String, *> = synchronized(lock) { HashMap(values) }

    override fun getString(key: String, defValue: String?): String? =
        synchronized(lock) { values[key] as? String } ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(lock) { (values[key] as? Set<String>)?.toMutableSet() } ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        synchronized(lock) { (values[key] as? Number)?.toLong() }?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        synchronized(lock) { (values[key] as? Number)?.let { if (it is Double || it is Float) null else it.toLong() } } ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        synchronized(lock) { (values[key] as? Number)?.toFloat() } ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        synchronized(lock) { values[key] as? Boolean } ?: defValue

    override fun contains(key: String): Boolean = synchronized(lock) { values.containsKey(key) }

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    // ─── writes ─────────────────────────────────────────────────────────────

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()   // null value = remove
        private var clearFirst = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clearFirst = true }

        /** Memory first, so the very next read sees it; disk is the part that may be deferred. */
        private fun applyToMemory() = synchronized(lock) {
            if (clearFirst) values.clear()
            for ((key, v) in pending) if (v == null) values.remove(key) else values[key] = v
        }

        override fun commit(): Boolean { applyToMemory(); return write() }

        override fun apply() {
            applyToMemory()
            try { writer.execute { write() } } catch (e: Throwable) { /* best effort, like the rest of the SDK's persistence */ }
        }
    }

    companion object {
        /** Shared by every instance: two instances over one file (or one file, two threads) must not interleave. */
        private val WRITE_LOCK = Any()

        /**
         * Blocks until every write already handed to the shared background writer has finished (or [timeoutMs]
         * passes). The writer is one FIFO thread, so a no-op queued behind them is a barrier. Exists so tests
         * can stop a late write from one test landing in the next; production never needs it.
         */
        internal fun awaitPendingWrites(timeoutMs: Long = 2_000): Boolean {
            val done = java.util.concurrent.CountDownLatch(1)
            return try {
                DEFAULT_WRITER.execute { done.countDown() }
                done.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: Throwable) {
                false
            }
        }

        /**
         * At most one thread, so writes stay ordered; it exits after 5 s idle, so an idle SDK holds no
         * thread open (a plain single-thread executor would keep its core thread alive for ever).
         */
        private val DEFAULT_WRITER: Executor = ThreadPoolExecutor(
            1, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()
        ) { r -> Thread(r, "apm-state-writer").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
            .apply { allowCoreThreadTimeOut(true) }
    }
}
