package com.example.localmusicapp

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 扫描结果的持久化缓存
 *
 * 把 MusicFile 列表序列化成 JSON 存到 filesDir/scan_cache.json。
 * 初次扫描后写入；之后每次启动都能直接加载，跳过扫描过程。
 */
object ScanCache {

    private const val TAG = "ScanCache"
    private const val FILENAME = "scan_cache.json"
    private const val VERSION = 4

    private fun file(context: Context): File = File(context.filesDir, FILENAME)

    fun exists(context: Context): Boolean = file(context).exists()

    fun save(context: Context, result: MusicScanner.ScanResult) {
        try {
            val array = JSONArray()
            for (f in result.files) {
                val obj = JSONObject()
                obj.put("id", f.id)
                obj.put("path", f.path)
                obj.put("title", f.title)
                obj.put("artist", f.artist)
                obj.put("album", f.album)
                obj.put("duration", f.duration)
                obj.put("format", f.format)
                obj.put("size", f.size)
                obj.put("dateAddedSec", f.dateAddedSec)
                obj.put("albumArtist", f.albumArtist)
                obj.put("discNumber", f.discNumber)
                obj.put("trackNumber", f.trackNumber)
                obj.put("year", f.year)
                obj.put("folderName", f.folderName)
                obj.put("folderPath", f.folderPath)
                obj.put("externalLrcUri", f.externalLrcUri)
                array.put(obj)
            }
            val fmtCounts = JSONObject()
            for ((k, v) in result.formatCounts) fmtCounts.put(k, v)

            val root = JSONObject()
            root.put("files", array)
            root.put("formatCounts", fmtCounts)
            root.put("version", VERSION)
            file(context).writeText(root.toString())
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }

    fun load(context: Context): MusicScanner.ScanResult? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            val json = JSONObject(f.readText())
            val version = json.optInt("version", 1)
            // v4 起补充 SAF 同目录同名 LRC 记录；旧缓存直接触发重扫，避免歌词来源不准。
            if (version < VERSION) return null

            val array = json.getJSONArray("files")
            val files = mutableListOf<MusicScanner.MusicFile>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val path = obj.getString("path")
                val isContentUri = path.startsWith("content://", ignoreCase = true)
                // 物理文件不在了，不再回列；SAF content:// 文件依赖持久 URI 权限，不能用 File.exists 判断。
                if (!isContentUri && !File(path).exists()) continue
                files.add(
                    MusicScanner.MusicFile(
                        id = obj.optLong("id", 0L),
                        path = path,
                        title = MusicScanner.fixEncoding(obj.optString("title")),
                        artist = MusicScanner.fixEncoding(obj.optString("artist")),
                        album = MusicScanner.fixEncoding(obj.optString("album")),
                        duration = obj.optLong("duration", 0L),
                        format = obj.optString("format"),
                        size = obj.optLong("size", 0L),
                        dateAddedSec = obj.optLong(
                            "dateAddedSec",
                            if (isContentUri) 0L else File(path).lastModified() / 1000L
                        ),
                        albumArtist = MusicScanner.fixEncoding(obj.optString("albumArtist", "")),
                        discNumber = obj.optInt("discNumber", 0),
                        trackNumber = obj.optInt("trackNumber", 0),
                        year = obj.optInt("year", 0),
                        folderName = MusicScanner.fixEncoding(obj.optString("folderName", "")),
                        folderPath = obj.optString("folderPath", ""),
                        externalLrcUri = obj.optString("externalLrcUri", "")
                    )
                )
            }
            // 重新按现有 files 统计 formatCounts（更可靠）
            val fmt = files.groupingBy { it.format }.eachCount()
            MusicScanner.ScanResult(files = files, formatCounts = fmt)
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
            null
        }
    }

    fun clear(context: Context) {
        try { file(context).delete() } catch (_: Exception) {}
    }
}
