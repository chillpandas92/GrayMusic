package com.example.localmusicapp

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object FavoritesStore {

    private const val PREFS = "favorites"
    private const val KEY_DATA = "favorites_v1"

    data class Entry(
        val path: String,
        val addedAt: Long
    )

    private val favorites = ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            favorites.clear()
            runCatching {
                val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_DATA, "{}") ?: "{}"
                val obj = JSONObject(raw)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val path = keys.next()
                    val at = obj.optLong(path, 0L)
                    if (path.isNotBlank() && at > 0L) favorites[path] = at
                }
            }
            loaded = true
        }
    }

    private fun persist(context: Context) {
        val obj = JSONObject()
        for ((path, at) in favorites) obj.put(path, at)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DATA, obj.toString())
            .commit()
    }

    fun isFavorite(context: Context, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        load(context)
        return favorites.containsKey(path)
    }

    fun setFavorite(context: Context, path: String, favorite: Boolean): Boolean {
        load(context)
        if (favorite) {
            favorites[path] = System.currentTimeMillis()
        } else {
            favorites.remove(path)
        }
        persist(context)
        return favorite
    }

    fun toggle(context: Context, path: String): Boolean {
        val next = !isFavorite(context, path)
        setFavorite(context, path, next)
        return next
    }

    fun snapshot(context: Context): Map<String, Long> {
        load(context)
        return HashMap(favorites)
    }

    fun entries(context: Context): List<Entry> {
        load(context)
        return favorites.entries
            .map { Entry(it.key, it.value) }
            .sortedByDescending { it.addedAt }
    }

    fun latestPath(context: Context): String? {
        return entries(context).firstOrNull()?.path
    }
}
