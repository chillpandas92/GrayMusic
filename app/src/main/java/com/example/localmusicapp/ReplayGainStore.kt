package com.example.localmusicapp

import android.content.Context
import org.json.JSONObject
import kotlin.math.pow

/**
 * 本机 ReplayGain 缓存。
 *
 * 扫描时从文件标签读取 replaygain_track_gain / replaygain_album_gain；播放时优先使用 track gain。
 * 这里按 path 持久化，避免每次播放都重新解析音频标签。
 */
object ReplayGainStore {

    data class Entry(
        val path: String,
        val trackGainDb: Float?,
        val albumGainDb: Float?,
        val scannedAt: Long
    ) {
        val effectiveGainDb: Float?
            get() = trackGainDb ?: albumGainDb
    }

    data class Stats(
        val albumCount: Int,
        val songCount: Int,
        val withReplayGain: Int,
        val withoutReplayGain: Int
    )

    private const val PREFS = "replay_gain_store"
    private const val KEY_ENTRIES = "entries_json"

    fun getEntry(context: Context, path: String): Entry? = loadAll(context)[path]

    fun getGainDb(context: Context, path: String): Float? = getEntry(context, path)?.effectiveGainDb

    fun filesWithoutReplayGain(context: Context, files: List<MusicScanner.MusicFile>): List<MusicScanner.MusicFile> {
        val stored = loadAll(context)
        return files.filter { stored[it.path]?.effectiveGainDb == null }
    }

    fun saveEntries(context: Context, entries: Collection<Entry>) {
        if (entries.isEmpty()) return
        val current = loadAll(context).toMutableMap()
        for (entry in entries) current[entry.path] = entry
        saveAll(context, current)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ENTRIES)
            .apply()
    }

    fun stats(context: Context, files: List<MusicScanner.MusicFile>): Stats {
        val stored = loadAll(context)
        val albums = files.map { albumKey(it) }.toSet().size
        val with = files.count { stored[it.path]?.effectiveGainDb != null }
        return Stats(
            albumCount = albums,
            songCount = files.size,
            withReplayGain = with,
            withoutReplayGain = (files.size - with).coerceAtLeast(0)
        )
    }

    fun dbToLinearVolume(gainDb: Float): Float {
        return 10.0.pow(gainDb / 20.0).toFloat()
    }

    private fun loadAll(context: Context): Map<String, Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
            ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val out = LinkedHashMap<String, Entry>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val path = keys.next()
                val obj = root.optJSONObject(path) ?: continue
                out[path] = Entry(
                    path = path,
                    trackGainDb = if (obj.has("trackGainDb") && !obj.isNull("trackGainDb")) obj.optDouble("trackGainDb").toFloat() else null,
                    albumGainDb = if (obj.has("albumGainDb") && !obj.isNull("albumGainDb")) obj.optDouble("albumGainDb").toFloat() else null,
                    scannedAt = obj.optLong("scannedAt", 0L)
                )
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun saveAll(context: Context, map: Map<String, Entry>) {
        val root = JSONObject()
        for ((path, entry) in map) {
            val obj = JSONObject()
            if (entry.trackGainDb != null) obj.put("trackGainDb", entry.trackGainDb.toDouble())
            if (entry.albumGainDb != null) obj.put("albumGainDb", entry.albumGainDb.toDouble())
            obj.put("scannedAt", entry.scannedAt)
            root.put(path, obj)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, root.toString())
            .apply()
    }

    private fun albumKey(file: MusicScanner.MusicFile): String {
        return file.album.trim().lowercase() + "\u0001" + file.artistGroup().trim().lowercase()
    }
}
