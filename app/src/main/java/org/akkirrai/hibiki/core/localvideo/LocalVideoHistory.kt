package org.akkirrai.hibiki.core.localvideo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A single local (device) video the user picked from the library.
 *
 * [uri] is a `content://` URI handed out by the Storage Access Framework, so the app
 * never needs broad storage permission to play it again later.
 */
data class LocalVideoItem(
    val id: String,
    val uri: String,
    val displayName: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAtMs: Long,
)

/**
 * Recently played local videos, persisted so the library can offer "continue" without
 * asking for access to the whole device storage.
 */
class LocalVideoHistory(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<LocalVideoItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index) ?: continue
                    val uri = entry.optString(KEY_URI).takeIf { it.isNotBlank() } ?: continue
                    add(
                        LocalVideoItem(
                            id = entry.optString(KEY_ID).takeIf { it.isNotBlank() }
                                ?: UUID.randomUUID().toString(),
                            uri = uri,
                            displayName = entry.optString(KEY_NAME).ifBlank { uri },
                            positionMs = entry.optLong(KEY_POSITION),
                            durationMs = entry.optLong(KEY_DURATION),
                            lastPlayedAtMs = entry.optLong(KEY_LAST_PLAYED),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun byId(id: String): LocalVideoItem? = load().firstOrNull { it.id == id }

    /**
     * Registers [uri] as most recently played. Re-picking a known video keeps its resume
     * position instead of resetting it.
     */
    fun add(uri: String, displayName: String): LocalVideoItem {
        val now = System.currentTimeMillis()
        val current = load()
        val item = current.firstOrNull { it.uri == uri }?.copy(lastPlayedAtMs = now)
            ?: LocalVideoItem(
                id = UUID.randomUUID().toString(),
                uri = uri,
                displayName = displayName,
                positionMs = 0L,
                durationMs = 0L,
                lastPlayedAtMs = now,
            )
        save(listOf(item) + current.filterNot { it.uri == uri })
        return item
    }

    fun updateProgress(id: String, positionMs: Long, durationMs: Long) {
        val current = load()
        val updated = current.map { item ->
            if (item.id == id) {
                item.copy(positionMs = positionMs, durationMs = durationMs, lastPlayedAtMs = System.currentTimeMillis())
            } else {
                item
            }
        }
        save(updated)
    }

    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    private fun save(items: List<LocalVideoItem>) {
        val trimmed = items.sortedByDescending { it.lastPlayedAtMs }.take(MAX_ITEMS)
        val array = JSONArray()
        trimmed.forEach { item ->
            array.put(
                JSONObject().apply {
                    put(KEY_ID, item.id)
                    put(KEY_URI, item.uri)
                    put(KEY_NAME, item.displayName)
                    put(KEY_POSITION, item.positionMs)
                    put(KEY_DURATION, item.durationMs)
                    put(KEY_LAST_PLAYED, item.lastPlayedAtMs)
                }
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "hibiki_local_video_history"
        private const val KEY_ITEMS = "items"
        private const val KEY_ID = "id"
        private const val KEY_URI = "uri"
        private const val KEY_NAME = "name"
        private const val KEY_POSITION = "positionMs"
        private const val KEY_DURATION = "durationMs"
        private const val KEY_LAST_PLAYED = "lastPlayedAtMs"
        private const val MAX_ITEMS = 30
    }
}
