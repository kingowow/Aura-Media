package org.akkirrai.hibiki.core.subtitle

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * LLM translation cache — RAM-first with periodic disk persistence.
 *
 * Design:
 *  - Key: sha256(engine + "|" + model + "|" + targetCode + "|" + sourceLine)
 *  - Value: the translated text (single line).
 *  - RAM map is [ConcurrentHashMap] — thread-safe, O(1) lookups.
 *  - Disk file is written every [FLUSH_EVERY] new entries, so a long session
 *    does not grow the file on every put. The file also lets a second play
 *    of the same episode skip ALL network calls entirely.
 *  - LRU eviction at [MAX_ENTRIES] keeps memory bounded (~a few MB worst-case).
 *
 * Only LLM engines use this cache. Fast MT engines (Google Free, Edge, DeepL,
 * Azure) skip it — they are ~200ms per line, so the cache overhead is not
 * worth it.
 *
 * Thread safety: [get] / [put] are safe to call from any thread.
 * [flush] is idempotent and can be called from a background coroutine.
 */
class SubtitleTranslationCache(
    private val context: Context,
    private val coroutineContext: CoroutineContext,
) {

    private companion object {
        const val FILE_NAME = "subtitle-translation-cache.json"
        const val FLUSH_EVERY = 200
        const val MAX_ENTRIES = 20_000
        private val LOCK = Any()
    }

    private val memory = ConcurrentHashMap<String, String>()
    private val file = File(context.filesDir, FILE_NAME)

    // How many puts since the last disk flush (used to throttle writes).
    @Volatile private var pendingPuts = 0

    // Track insertion order for LRU eviction. Bounded by MAX_ENTRIES so the
    // overhead of a small ArrayDeque is acceptable.
    private val lru = ArrayDeque<String>()

    init {
        loadFromDisk()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Deterministic key for one (engine, model, target, source line) tuple.
     * The source line is normalized (trim + collapse internal newlines) so
     * that equivalent lines hash the same.
     */
    fun key(engine: String, model: String, targetCode: String, sourceLine: String): String {
        val normalized = sourceLine.trim().replace("\r\n", "\n").replace("\r", "\n")
        val raw = "$engine|$model|$targetCode|$normalized"
        return sha256(raw)
    }

    /** Returns the cached translation, or null on a miss. */
    fun get(key: String): String? = memory[key]

    /** Stores a translation. Triggers a disk flush every [FLUSH_EVERY] new entries. */
    fun put(key: String, value: String) {
        synchronized(LOCK) {
            if (!memory.containsKey(key)) {
                lru.addLast(key)
                // Evict oldest if we exceed the cap.
                while (lru.size > MAX_ENTRIES) {
                    val oldest = lru.removeFirst()
                    memory.remove(oldest)
                }
            }
        }
        memory[key] = value
        pendingPuts++
        if (pendingPuts >= FLUSH_EVERY) {
            pendingPuts = 0
            flush()
        }
    }

    /**
     * Writes the entire RAM map to disk. Called automatically after
     * [FLUSH_EVERY] puts; also call explicitly when the player screen closes
     * so a normal session always persists its progress.
     */
    fun flush() {
        val snapshot = memory.toMap()
        if (snapshot.isEmpty()) return
        try {
            val digest = MessageDigest.getInstance("SHA-1")
            val json = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<Map<String, String>>(),
                snapshot,
            )
            file.writeText(json)
        } catch (_: Exception) {
            // Disk full / IO — the in-RAM map is still authoritative; the next
            // flush retries. Never crash the translation pipeline over a cache.
        }
    }

    /** Removes all cached entries (RAM + disk). Used by "Clear translation cache". */
    fun clear() {
        synchronized(LOCK) {
            memory.clear()
            lru.clear()
            pendingPuts = 0
        }
        runCatching { file.delete() }
    }

    /** Number of entries currently in the RAM cache. Useful for UI/stats. */
    val size: Int get() = memory.size

    // ── Private ───────────────────────────────────────────────────────────────

    private fun loadFromDisk() {
        if (!file.exists() || file.length() == 0L) return
        runCatching {
            val text = file.readText()
            val map: Map<String, String> =
                kotlinx.serialization.json.Json.decodeFromString(
                    kotlinx.serialization.serializer(),
                    text,
                )
            // LRU order is not persisted; just seed the RAM map.
            memory.putAll(map)
        } // Corrupt/partial file — start empty, the map rebuilds as lines translate.
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
