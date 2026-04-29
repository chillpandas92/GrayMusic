package com.example.localmusicapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 持久化当前播放队列、位置与模式。
 *
 * 目标：应用退出 / 进程被杀 / 从最近任务划掉后，重新进入仍能稳定恢复到上次歌曲与进度。
 */
object PlaybackStateStore {

    private const val PREFS = "playback_state"
    private const val KEY_SNAPSHOT = "snapshot_v1"

    data class Snapshot(
        val currentPath: String,
        val currentIndex: Int,
        val mode: PlaybackSettings.Mode,
        val positionMs: Long,
        val queuePaths: List<String>,
        val sourcePaths: List<String>,
        val sourceName: String,
        val savedAt: Long
    )

    fun save(context: Context, snapshot: Snapshot, sync: Boolean) {
        val root = JSONObject().apply {
            put("currentPath", snapshot.currentPath)
            put("currentIndex", snapshot.currentIndex)
            put("mode", snapshot.mode.name)
            put("positionMs", snapshot.positionMs)
            put("savedAt", snapshot.savedAt)
            put("sourceName", snapshot.sourceName)
            put("queuePaths", JSONArray().apply { snapshot.queuePaths.forEach(::put) })
            put("sourcePaths", JSONArray().apply { snapshot.sourcePaths.forEach(::put) })
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNAPSHOT, root.toString())
        if (sync) editor.commit() else editor.apply()
    }

    fun load(context: Context): Snapshot? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val queueArr = obj.optJSONArray("queuePaths") ?: JSONArray()
            val sourceArr = obj.optJSONArray("sourcePaths") ?: JSONArray()
            Snapshot(
                currentPath = obj.optString("currentPath"),
                currentIndex = obj.optInt("currentIndex", -1),
                mode = runCatching {
                    PlaybackSettings.Mode.valueOf(
                        obj.optString("mode", PlaybackSettings.Mode.SEQUENTIAL.name)
                    )
                }.getOrDefault(PlaybackSettings.Mode.SEQUENTIAL),
                positionMs = obj.optLong("positionMs", 0L).coerceAtLeast(0L),
                queuePaths = List(queueArr.length()) { idx -> queueArr.optString(idx) }.filter { it.isNotBlank() },
                sourcePaths = List(sourceArr.length()) { idx -> sourceArr.optString(idx) }.filter { it.isNotBlank() },
                sourceName = obj.optString("sourceName", ""),
                savedAt = obj.optLong("savedAt", 0L)
            )
        }.getOrNull()?.takeIf {
            it.currentPath.isNotBlank() && it.queuePaths.isNotEmpty()
        }
    }

    fun clear(context: Context, sync: Boolean) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SNAPSHOT)
        if (sync) editor.commit() else editor.apply()
    }
}
