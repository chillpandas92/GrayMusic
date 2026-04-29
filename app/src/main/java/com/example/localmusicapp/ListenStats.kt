package com.example.localmusicapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放统计
 *
 * 三个维度的数据：
 *   - accumulated[path] : 累计毫秒（不含当前 live session）
 *   - playCounts[path]  : 过阈值总次数
 *   - countEvents       : (path, atMs) 形式的达阈值事件流，用于"本日/本周/本月"过滤
 *
 * 持久化到 SharedPreferences "listen_stats"：
 *   data_v1         → accumulated
 *   counts_v1       → playCounts
 *   count_events_v1 → countEvents 的 JSON 数组
 *
 * 时间累计也需要按日期过滤，但那是每秒累加的连续数据，体积太大；
 * 对于日期过滤的"播放时长"查询，我们只能基于当前 accumulated 做近似。
 * 实现上：sessionFirstStartedAt 记在 PlaybackService 里，session 结束时把
 * (path, startedAt, elapsed) 也写成一条事件。
 */
object ListenStats {

    private const val PREFS = "listen_stats"
    private const val KEY_TIME = "data_v1"
    private const val KEY_COUNT = "counts_v1"
    private const val KEY_COUNT_EVENTS = "count_events_v1"
    private const val KEY_LISTEN_EVENTS = "listen_events_v1"
    private const val KEY_LAST_PLAYED = "last_played_v1"

    /** path → 累计毫秒（不含当前 session） */
    private val accumulated = ConcurrentHashMap<String, Long>()

    /** path → 过阈值次数 */
    private val playCounts = ConcurrentHashMap<String, Int>()

    /** 过阈值事件 */
    data class CountEvent(val path: String, val atMs: Long)
    private val countEvents: MutableList<CountEvent> = java.util.Collections.synchronizedList(ArrayList())

    /** 完整 listen 段事件（path, startedAt, durationMs） */
    data class ListenEvent(val path: String, val startedAt: Long, val listenedMs: Long)
    private val listenEvents: MutableList<ListenEvent> = java.util.Collections.synchronizedList(ArrayList())

    /** path → 最近一次开始播放的时间（用于“最近播放”排序） */
    private val lastPlayedAt = ConcurrentHashMap<String, Long>()

    @Volatile private var currentPath: String? = null
    @Volatile private var sessionStartedAt: Long = 0L
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            accumulated.clear()
            playCounts.clear()
            synchronized(countEvents) { countEvents.clear() }
            synchronized(listenEvents) { listenEvents.clear() }
            lastPlayedAt.clear()
            currentPath = null
            sessionStartedAt = 0L

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            runCatching {
                val obj = JSONObject(prefs.getString(KEY_TIME, "{}") ?: "{}")
                val it = obj.keys()
                while (it.hasNext()) { val k = it.next(); accumulated[k] = obj.getLong(k) }
            }
            runCatching {
                val obj = JSONObject(prefs.getString(KEY_COUNT, "{}") ?: "{}")
                val it = obj.keys()
                while (it.hasNext()) { val k = it.next(); playCounts[k] = obj.getInt(k) }
            }
            runCatching {
                val arr = JSONArray(prefs.getString(KEY_COUNT_EVENTS, "[]") ?: "[]")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    countEvents.add(CountEvent(o.getString("p"), o.getLong("t")))
                }
            }
            runCatching {
                val arr = JSONArray(prefs.getString(KEY_LISTEN_EVENTS, "[]") ?: "[]")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    listenEvents.add(ListenEvent(o.getString("p"), o.getLong("s"), o.getLong("l")))
                }
            }
            runCatching {
                val obj = JSONObject(prefs.getString(KEY_LAST_PLAYED, "{}") ?: "{}")
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    lastPlayedAt[k] = obj.getLong(k)
                }
            }
            loaded = true
        }
    }

    fun startSession(context: Context, path: String) {
        load(context)
        synchronized(this) {
            commitSessionLocked()
            currentPath = path
            sessionStartedAt = System.currentTimeMillis()
        }
    }

    fun commitSession() {
        synchronized(this) {
            commitSessionLocked()
        }
    }

    fun pauseSession() { commitSession() }

    fun timeOf(path: String): Long {
        val stored = accumulated[path] ?: 0L
        return if (path == currentPath) stored + (System.currentTimeMillis() - sessionStartedAt)
        else stored
    }

    fun incrementPlayCount(path: String) {
        playCounts.merge(path, 1) { a, b -> a + b }
    }

    /** 当达阈值时，由 PlaybackService 调用追加事件 */
    fun recordCountEvent(path: String, atMs: Long) {
        countEvents.add(CountEvent(path, atMs))
    }

    fun countOf(path: String): Int = playCounts[path] ?: 0

    fun lastCountEventOf(path: String): CountEvent? {
        if (path.isBlank()) return null
        return synchronized(countEvents) {
            countEvents.asReversed().firstOrNull { it.path == path }
        }
    }

    fun markRecentPlay(context: Context, path: String, atMs: Long = System.currentTimeMillis()) {
        load(context)
        if (path.isBlank()) return
        lastPlayedAt[path] = atMs
        persistSnapshot(context, sync = false)
    }

    fun recentSnapshot(): Map<String, Long> = HashMap(lastPlayedAt)

    fun recentSince(sinceMs: Long): Map<String, Long> {
        if (sinceMs <= 0L) return recentSnapshot()
        return lastPlayedAt.entries
            .asSequence()
            .filter { it.value >= sinceMs }
            .associate { it.key to it.value }
    }

    /** 包含 live session 的全量时长快照 */
    fun snapshot(): Map<String, Long> {
        val result = HashMap(accumulated)
        val cur = currentPath
        if (cur != null) {
            val live = System.currentTimeMillis() - sessionStartedAt
            result[cur] = (result[cur] ?: 0L) + live
        }
        return result
    }

    fun countSnapshot(): Map<String, Int> = HashMap(playCounts)

    /** 日期过滤后的"时长"聚合：sinceMs=0 表示所有时间 */
    fun timeSince(sinceMs: Long): Map<String, Long> {
        if (sinceMs <= 0L) return snapshot()
        val result = HashMap<String, Long>()
        synchronized(listenEvents) {
            for (e in listenEvents) {
                if (e.startedAt >= sinceMs) {
                    result[e.path] = (result[e.path] ?: 0L) + e.listenedMs
                }
            }
        }
        // 包含 live session
        val cur = currentPath
        if (cur != null && sessionStartedAt >= sinceMs) {
            val live = System.currentTimeMillis() - sessionStartedAt
            result[cur] = (result[cur] ?: 0L) + live
        }
        return result
    }

    /** 日期过滤后的"次数"聚合：sinceMs=0 表示所有时间 */


    fun listenEventsSnapshot(): List<ListenEvent> {
        val snapshot = synchronized(listenEvents) { listenEvents.toMutableList() }
        val cur = currentPath
        val startedAt = sessionStartedAt
        val now = System.currentTimeMillis()
        if (!cur.isNullOrBlank() && startedAt > 0L && now > startedAt) {
            snapshot.add(ListenEvent(cur, startedAt, now - startedAt))
        }
        return snapshot
    }

    fun finishedListenEventsSnapshot(): List<ListenEvent> {
        return synchronized(listenEvents) { listenEvents.toList() }
    }

    fun countSince(sinceMs: Long): Map<String, Int> {
        if (sinceMs <= 0L) return countSnapshot()
        val result = HashMap<String, Int>()
        synchronized(countEvents) {
            for (e in countEvents) {
                if (e.atMs >= sinceMs) {
                    result[e.path] = (result[e.path] ?: 0) + 1
                }
            }
        }
        return result
    }

    fun save(context: Context) {
        persist(context, commitCurrentSession = true, sync = true)
    }

    fun persistSnapshot(context: Context, sync: Boolean = false) {
        persist(context, commitCurrentSession = false, sync = sync)
    }

    private fun persist(
        context: Context,
        commitCurrentSession: Boolean,
        sync: Boolean
    ) {
        load(context)

        val now = System.currentTimeMillis()
        if (commitCurrentSession) {
            synchronized(this) {
                commitSessionLocked(now)
            }
        }

        val accumulatedCopy = HashMap(accumulated)
        val countCopy = HashMap(playCounts)
        val countEventsCopy = synchronized(countEvents) { countEvents.toList() }
        val listenEventsCopy = synchronized(listenEvents) { listenEvents.toMutableList() }
        val recentCopy = HashMap(lastPlayedAt)

        val cur = currentPath
        val startedAt = sessionStartedAt
        if (!commitCurrentSession && !cur.isNullOrBlank() && startedAt > 0L && now > startedAt) {
            val live = now - startedAt
            accumulatedCopy[cur] = (accumulatedCopy[cur] ?: 0L) + live
            listenEventsCopy.add(ListenEvent(cur, startedAt, live))
        }

        val timeObj = JSONObject()
        for ((k, v) in accumulatedCopy) timeObj.put(k, v)
        val countObj = JSONObject()
        for ((k, v) in countCopy) countObj.put(k, v)
        val cntArr = JSONArray()
        for (e in countEventsCopy) {
            cntArr.put(JSONObject().put("p", e.path).put("t", e.atMs))
        }
        val lsnArr = JSONArray()
        for (e in listenEventsCopy) {
            lsnArr.put(JSONObject().put("p", e.path).put("s", e.startedAt).put("l", e.listenedMs))
        }
        val recentObj = JSONObject()
        for ((k, v) in recentCopy) recentObj.put(k, v)

        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TIME, timeObj.toString())
            .putString(KEY_COUNT, countObj.toString())
            .putString(KEY_COUNT_EVENTS, cntArr.toString())
            .putString(KEY_LISTEN_EVENTS, lsnArr.toString())
            .putString(KEY_LAST_PLAYED, recentObj.toString())
        if (sync) editor.commit() else editor.apply()
    }

    private fun commitSessionLocked(now: Long = System.currentTimeMillis()) {
        val path = currentPath ?: return
        val startedAt = sessionStartedAt
        val elapsed = (now - startedAt).coerceAtLeast(0L)
        if (elapsed > 0 && startedAt > 0L) {
            accumulated.merge(path, elapsed) { a, b -> a + b }
            listenEvents.add(ListenEvent(path, startedAt, elapsed))
        }
        currentPath = null
        sessionStartedAt = 0L
    }

    fun reset() {
        synchronized(this) {
            accumulated.clear()
            playCounts.clear()
            synchronized(countEvents) { countEvents.clear() }
            synchronized(listenEvents) { listenEvents.clear() }
            lastPlayedAt.clear()
            currentPath = null
            sessionStartedAt = 0L
            loaded = false
        }
    }
}
