package com.example.localmusicapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户自建歌单的存储
 *
 * 跟 FavoritesStore 结构相似，但是多了 playlist 的概念：每个 playlist 有
 * 自己的名字、创建时间、一组 (path, addedAt) 条目。
 *
 * 持久化到 SharedPreferences "playlists" 的一个 JSONObject 里：
 *   {
 *     "<id>": { "name": "...", "createdAt": 0, "songs": [ {"p":..., "a":...}, ... ] },
 *     ...
 *   }
 */
object PlaylistStore {

    private const val PREFS = "playlists"
    private const val KEY_DATA = "playlists_v1"

    data class Entry(val path: String, val addedAt: Long)

    data class Playlist(
        val id: String,
        val name: String,
        val createdAt: Long,
        val songs: List<Entry>
    )

    // 内存里用一个可变 map，key 是 playlistId
    private data class PlaylistState(
        var name: String,
        val createdAt: Long,
        val songs: LinkedHashMap<String, Long> = LinkedHashMap()
    )

    private val playlists = ConcurrentHashMap<String, PlaylistState>()
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            playlists.clear()
            runCatching {
                val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_DATA, "{}") ?: "{}"
                val root = JSONObject(raw)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val obj = root.optJSONObject(id) ?: continue
                    val name = obj.optString("name", "").trim()
                    val createdAt = obj.optLong("createdAt", 0L)
                    if (id.isBlank() || name.isBlank()) continue
                    val state = PlaylistState(name, createdAt)
                    val arr = obj.optJSONArray("songs") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val sObj = arr.optJSONObject(i) ?: continue
                        val p = sObj.optString("p", "")
                        val at = sObj.optLong("a", 0L)
                        if (p.isNotBlank() && at > 0L) state.songs[p] = at
                    }
                    playlists[id] = state
                }
            }
            loaded = true
        }
    }

    private fun persist(context: Context) {
        val root = JSONObject()
        for ((id, state) in playlists) {
            val obj = JSONObject()
            obj.put("name", state.name)
            obj.put("createdAt", state.createdAt)
            val arr = JSONArray()
            for ((p, at) in state.songs) {
                arr.put(JSONObject().put("p", p).put("a", at))
            }
            obj.put("songs", arr)
            root.put(id, obj)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DATA, root.toString())
            .commit()
    }

    fun all(context: Context): List<Playlist> {
        load(context)
        return playlists.entries
            .map { (id, state) ->
                Playlist(
                    id = id,
                    name = state.name,
                    createdAt = state.createdAt,
                    songs = state.songs.map { Entry(it.key, it.value) }
                        .sortedByDescending { it.addedAt }
                )
            }
            .sortedByDescending { it.createdAt }
    }

    fun get(context: Context, id: String): Playlist? {
        load(context)
        val state = playlists[id] ?: return null
        return Playlist(
            id = id,
            name = state.name,
            createdAt = state.createdAt,
            songs = state.songs.map { Entry(it.key, it.value) }
                .sortedByDescending { it.addedAt }
        )
    }

    fun create(context: Context, name: String): Playlist {
        load(context)
        val trimmed = name.trim()
        val id = UUID.randomUUID().toString()
        val state = PlaylistState(trimmed, System.currentTimeMillis())
        playlists[id] = state
        persist(context)
        return Playlist(id, trimmed, state.createdAt, emptyList())
    }

    fun rename(context: Context, id: String, newName: String): Boolean {
        load(context)
        val state = playlists[id] ?: return false
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return false
        state.name = trimmed
        persist(context)
        return true
    }

    fun delete(context: Context, id: String): Boolean {
        load(context)
        val removed = playlists.remove(id) != null
        if (removed) persist(context)
        return removed
    }

    fun addSong(context: Context, id: String, path: String): Boolean {
        load(context)
        val state = playlists[id] ?: return false
        if (path.isBlank()) return false
        state.songs[path] = System.currentTimeMillis()
        persist(context)
        return true
    }

    fun removeSong(context: Context, id: String, path: String): Boolean {
        load(context)
        val state = playlists[id] ?: return false
        val removed = state.songs.remove(path) != null
        if (removed) persist(context)
        return removed
    }

    fun containsSong(context: Context, id: String, path: String): Boolean {
        load(context)
        val state = playlists[id] ?: return false
        return state.songs.containsKey(path)
    }

    /** 最近加入该歌单的一条路径（按 addedAt 倒序取第一条） */
    fun latestPath(context: Context, id: String): String? {
        load(context)
        val state = playlists[id] ?: return null
        return state.songs.entries.maxByOrNull { it.value }?.key
    }
}
