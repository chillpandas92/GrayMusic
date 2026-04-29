package com.example.localmusicapp

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份 / 恢复 SharedPreferences
 *
 * ZIP 结构：
 *   backup.json — 一个 JSON 对象，key = prefs 文件名，value = { key: value_string }
 *                 所有 value 统一保存为 String，恢复时按已知类型回填
 *   manifest.json — 版本信息
 *
 * 当前覆盖的 prefs：listen_stats / sort_settings / playback_settings / favorites
 */
object BackupManager {

    private const val TAG = "BackupManager"
    const val VERSION = 1

    /** 目前支持备份的 prefs 文件名 */
    private val PREFS_NAMES = listOf(
        "listen_stats",
        "sort_settings",
        "playback_settings",
        "favorites"
    )

    /** 用户友好的显示名 */
    val PREFS_LABELS = mapOf(
        "listen_stats" to "听歌排行（播放时长统计）",
        "sort_settings" to "排序设置",
        "playback_settings" to "播放模式",
        "favorites" to "收藏夹"
    )

    /** 导出到指定 URI（SAF 选定的位置） */
    fun export(context: Context, uri: Uri): Boolean {
        // 关键：先把 ListenStats 的内存状态刷回 prefs，否则读 prefs.all 会漏掉内存里新产生的条目
        try { ListenStats.save(context) } catch (_: Exception) {}

        return try {
            val os = context.contentResolver.openOutputStream(uri)
                ?: return false
            ZipOutputStream(BufferedOutputStream(os)).use { zip ->
                // backup.json
                val root = JSONObject()
                for (name in PREFS_NAMES) {
                    val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    val section = JSONObject()
                    for ((k, v) in prefs.all) {
                        when (v) {
                            is String -> section.put(k, "S:$v")
                            is Int -> section.put(k, "I:$v")
                            is Long -> section.put(k, "L:$v")
                            is Float -> section.put(k, "F:$v")
                            is Boolean -> section.put(k, "B:$v")
                            else -> section.put(k, "S:${v?.toString() ?: ""}")
                        }
                    }
                    root.put(name, section)
                }
                zip.putNextEntry(ZipEntry("backup.json"))
                zip.write(root.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // manifest.json
                val manifest = JSONObject().apply {
                    put("version", VERSION)
                    put("exportedAt", System.currentTimeMillis())
                    put("app", "GrayMusic")
                }
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            false
        }
    }

    /**
     * 解析指定 URI 的备份文件，返回"每个 prefs section + 内部键值数量"
     * 用于导入前的预览/选择
     */
    data class Preview(
        val exportedAt: Long,
        val sections: Map<String, Int>   // prefs 名 → 有多少个 key
    )

    fun preview(context: Context, uri: Uri): Preview? {
        return try {
            val ins = context.contentResolver.openInputStream(uri) ?: return null
            var exportedAt = 0L
            val sectionCounts = mutableMapOf<String, Int>()
            ZipInputStream(BufferedInputStream(ins)).use { zip ->
                var e: ZipEntry? = zip.nextEntry
                while (e != null) {
                    val bytes = zip.readBytes()
                    when (e.name) {
                        "backup.json" -> {
                            val obj = JSONObject(String(bytes, Charsets.UTF_8))
                            val keys = obj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val section = obj.getJSONObject(k)
                                sectionCounts[k] = section.length()
                            }
                        }
                        "manifest.json" -> {
                            val m = JSONObject(String(bytes, Charsets.UTF_8))
                            exportedAt = m.optLong("exportedAt", 0L)
                        }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
            Preview(exportedAt, sectionCounts)
        } catch (e: Exception) {
            Log.e(TAG, "preview failed", e)
            null
        }
    }

    /**
     * 从 URI 导入选定的 prefs sections。
     * @param selectedNames 用户勾选的 prefs 名（必须是 PREFS_NAMES 子集）
     */
    fun import(
        context: Context,
        uri: Uri,
        selectedNames: Set<String>
    ): Boolean {
        return try {
            val ins = context.contentResolver.openInputStream(uri) ?: return false
            var backupJson: String? = null
            ZipInputStream(BufferedInputStream(ins)).use { zip ->
                var e: ZipEntry? = zip.nextEntry
                while (e != null) {
                    if (e.name == "backup.json") {
                        backupJson = String(zip.readBytes(), Charsets.UTF_8)
                    } else {
                        // 丢掉其它 entry
                        zip.readBytes()
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
            val json = backupJson ?: return false
            val root = JSONObject(json)
            for (name in selectedNames) {
                if (name !in PREFS_NAMES) continue
                if (!root.has(name)) continue
                val section = root.getJSONObject(name)
                val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                editor.clear()
                val keys = section.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val raw = section.getString(k)
                    putTagged(editor, k, raw)
                }
                // commit() 同步写盘，确保重启时数据已经落地
                editor.commit()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "import failed", e)
            false
        }
    }

    private fun putTagged(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        tagged: String
    ) {
        if (tagged.length < 2 || tagged[1] != ':') {
            editor.putString(key, tagged); return
        }
        val tag = tagged[0]
        val value = tagged.substring(2)
        when (tag) {
            'S' -> editor.putString(key, value)
            'I' -> value.toIntOrNull()?.let { editor.putInt(key, it) }
            'L' -> value.toLongOrNull()?.let { editor.putLong(key, it) }
            'F' -> value.toFloatOrNull()?.let { editor.putFloat(key, it) }
            'B' -> editor.putBoolean(key, value.toBoolean())
            else -> editor.putString(key, tagged)
        }
    }

    /** 生成默认导出文件名：GrayMusic_YYYYMMDD.zip */
    fun defaultFileName(): String {
        val cal = java.util.Calendar.getInstance()
        val y = cal.get(java.util.Calendar.YEAR)
        val m = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        return "GrayMusic_%04d%02d%02d.zip".format(y, m, d)
    }
}
