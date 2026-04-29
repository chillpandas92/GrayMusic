package com.example.localmusicapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * 音频封面加载器
 *
 * 解决 RecyclerView 快速滑动时封面闪空的问题：
 *   - per-path 共享 Deferred，多个 ImageView 请求同一 path 不互相取消
 *   - LRU 缓存 48MB + 解码目标降到 160px（56dp ImageView 完全够用，bitmap 体积减小 2.5 倍）
 *     意味着同等内存下能缓存约 250 张封面，对大多数音乐库足够全量常驻
 *   - prefetch() 允许上层主动在进入列表页时把所有封面预热进 cache
 *   - Semaphore(2) 限制解码并发，避免一次吃太多 I/O
 */
object CoverLoader {

    // 48MB 缓存；单张 160px bitmap 约 100KB，可容纳 ~450 张
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // 播放页高清封面专用的 LRU（一次只会看到 1-2 张，缓存 3 张就够了）
    // 单张 720px ARGB_8888 bitmap 约 2MB，限制 3 张占 ~6MB
    private val highResCache = object : LruCache<String, Bitmap>(3) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }
    private val highResInFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    // 专辑网格中等分辨率封面 LRU
    // 480px RGB_565 ≈ 460KB/张，限制 24MB 可缓存约 50 张
    // 2 列网格里第一屏通常 ~8 张，切 tab 回来也能秒显示
    private val albumCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val albumInFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    // 已确认无封面的路径（不重复尝试）
    private val noCover = ConcurrentHashMap.newKeySet<String>()

    // 正在解码的任务，按 path 共享
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    private val ioSem = Semaphore(permits = 2)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = MainScope()

    fun load(view: ImageView, path: String, placeholder: Int) {
        view.tag = path

        cache.get(path)?.let {
            view.setImageBitmap(it)
            CoverFrameStyler.applyFromBitmap(view, it)
            return
        }

        if (noCover.contains(path)) {
            view.setImageResource(placeholder)
            CoverFrameStyler.applyDefault(view)
            return
        }

        view.setImageResource(placeholder)
        CoverFrameStyler.applyDefault(view)

        val deferred = inFlight.getOrPut(path) {
            ioScope.async {
                try {
                    ioSem.withPermit { extractCover(path) }
                } finally {
                    inFlight.remove(path)
                }
            }
        }

        mainScope.launch {
            val bmp = runCatching { deferred.await() }.getOrNull()
            if (bmp != null) cache.put(path, bmp)
            else noCover.add(path)
            if (view.tag == path) {
                if (bmp != null) {
                    view.setImageBitmap(bmp)
                    CoverFrameStyler.applyFromBitmap(view, bmp)
                } else {
                    view.setImageResource(placeholder)
                    CoverFrameStyler.applyDefault(view)
                }
            }
        }
    }

    /**
     * 专辑网格封面：480px RGB_565，单列宽度上有 2x 超采样，肉眼清晰不糊。
     *
     * 策略：
     *   1) 有 album cache 命中 → 直接显示
     *   2) 先用列表小图 cache 兜底（避免空白）
     *   3) 异步从音频文件解码 480px，写回 albumCache（不走 160px 磁盘缓存，避免污染）
     *
     * 和 loadHighRes 的区别：
     *   - 目标更小（480 vs 720），总 cache 更大（50+ 张 vs 3 张），适合网格场景
     *   - RGB_565 而不是 ARGB_8888，内存减半
     */
    fun loadAlbumCover(view: ImageView, path: String, placeholder: Int) {
        view.tag = path

        albumCache.get(path)?.let {
            view.setImageBitmap(it)
            CoverFrameStyler.applyFromBitmap(view, it)
            return
        }

        if (noCover.contains(path)) {
            view.setImageResource(placeholder)
            CoverFrameStyler.applyDefault(view)
            return
        }

        // 退化策略：先放已有小图，避免空白闪烁
        val small = cache.get(path)
        if (small != null) {
            view.setImageBitmap(small)
            CoverFrameStyler.applyFromBitmap(view, small)
        } else {
            view.setImageResource(placeholder)
            CoverFrameStyler.applyDefault(view)
        }

        val deferred = albumInFlight.getOrPut(path) {
            ioScope.async {
                try {
                    ioSem.withPermit { CoverDiskCache.extractFromAudioMidRes(path) }
                } finally {
                    albumInFlight.remove(path)
                }
            }
        }

        mainScope.launch {
            val bmp = runCatching { deferred.await() }.getOrNull()
            if (bmp != null) {
                albumCache.put(path, bmp)
                if (view.tag == path) {
                    view.setImageBitmap(bmp)
                    CoverFrameStyler.applyFromBitmap(view, bmp)
                }
            } else {
                if (small == null) noCover.add(path)
                if (view.tag == path && small == null) {
                    view.setImageResource(placeholder)
                    CoverFrameStyler.applyDefault(view)
                }
            }
        }
    }

    /**
     * 播放页专用：加载高清大图
     *
     * 策略：
     *   1) 立刻显示占位（并同步尝试小图缓存，若命中就先显示小图，避免空白）
     *   2) 后台用 MediaMetadataRetriever 把 embeddedPicture 按 720px 目标降采样，ARGB_8888 解码
     *   3) 完成后若 view.tag 仍是同一 path，切大图
     *
     * @param onBitmap 可选回调：高清 bitmap 就绪（或为 null）时调用，调用端可以拿它做
     *                 调色板提取等操作。已经命中缓存时同步回调，异步解码时异步回调。
     *                 不保证 view.tag 仍相同——调用端需自行判断是否仍是当前歌曲。
     */
    fun loadHighRes(
        view: ImageView,
        path: String,
        placeholder: Int,
        onBitmap: ((Bitmap?) -> Unit)? = null
    ) {
        view.tag = path

        // 如果已经缓存了高清图 → 直接上
        highResCache.get(path)?.let {
            view.setImageBitmap(it)
            CoverFrameStyler.applyFromBitmap(view, it)
            onBitmap?.invoke(it)
            return
        }

        if (noCover.contains(path)) {
            view.setImageResource(placeholder)
            CoverFrameStyler.applyDefault(view)
            onBitmap?.invoke(null)
            return
        }

        // 退化策略：先显示已有的小图/占位，避免空白闪烁
        val small = cache.get(path)
        if (small != null) {
            view.setImageBitmap(small)
            CoverFrameStyler.applyFromBitmap(view, small)
        } else {
            view.setImageResource(placeholder)
            CoverFrameStyler.applyDefault(view)
        }

        val deferred = highResInFlight.getOrPut(path) {
            ioScope.async {
                try {
                    ioSem.withPermit { CoverDiskCache.extractFromAudioHighRes(path) }
                } finally {
                    highResInFlight.remove(path)
                }
            }
        }

        mainScope.launch {
            val bmp = runCatching { deferred.await() }.getOrNull()
            if (bmp != null) {
                highResCache.put(path, bmp)
                if (view.tag == path) {
                    view.setImageBitmap(bmp)
                    CoverFrameStyler.applyFromBitmap(view, bmp)
                }
            } else {
                noCover.add(path)
                if (view.tag == path && small == null) {
                    view.setImageResource(placeholder)
                    CoverFrameStyler.applyDefault(view)
                }
            }
            onBitmap?.invoke(bmp)
        }
    }

    /**
     * 预加载一条路径到缓存。进入列表页后后台调用，可显著减少快速滑动时的空封面闪烁。
     * 可以 suspend 等待，或者 fire-and-forget
     */
    suspend fun prefetch(path: String) {
        if (cache.get(path) != null || noCover.contains(path)) return
        val deferred = inFlight.getOrPut(path) {
            ioScope.async {
                try {
                    ioSem.withPermit { extractCover(path) }
                } finally {
                    inFlight.remove(path)
                }
            }
        }
        val bmp = runCatching { deferred.await() }.getOrNull()
        if (bmp != null) cache.put(path, bmp)
        else noCover.add(path)
    }

    /**
     * 给需要 Bitmap 的场景（比如媒体通知）。
     * 命中缓存直接返回；否则解码一次，结果也写入缓存。
     */
    suspend fun loadBitmap(path: String): Bitmap? {
        cache.get(path)?.let { return it }
        if (noCover.contains(path)) return null

        val deferred = inFlight.getOrPut(path) {
            ioScope.async {
                try {
                    ioSem.withPermit { extractCover(path) }
                } finally {
                    inFlight.remove(path)
                }
            }
        }
        val bmp = runCatching { deferred.await() }.getOrNull()
        if (bmp != null) cache.put(path, bmp)
        else noCover.add(path)
        return bmp
    }

    /**
     * 媒体通知 / 锁屏专用的高清 bitmap
     *
     * 系统通知栏的大图可以显示到 ~720px，使用 160px 的缓存图会在锁屏或某些设备上
     * 被拉伸放大显示非常模糊。这里走与播放页相同的 720px ARGB_8888 路径，命中
     * 同一个高清 LRU（大部分情况下用户打开过播放页后这里就是缓存命中）。
     *
     * 回退：如果高清解码失败（e.g. 文件无内嵌封面），再退回到小图。
     */
    suspend fun loadBitmapHighRes(path: String): Bitmap? {
        highResCache.get(path)?.let { return it }
        if (noCover.contains(path)) return null

        val deferred = highResInFlight.getOrPut(path) {
            ioScope.async {
                try {
                    ioSem.withPermit { CoverDiskCache.extractFromAudioHighRes(path) }
                } finally {
                    highResInFlight.remove(path)
                }
            }
        }
        val bmp = runCatching { deferred.await() }.getOrNull()
        if (bmp != null) {
            highResCache.put(path, bmp)
            return bmp
        }
        // 高清失败就回落到小图，至少通知不是黑块
        return loadBitmap(path)
    }

    private fun extractCover(path: String): Bitmap? {
        // 优先读磁盘缓存——快一个数量级
        CoverDiskCache.read(path)?.let { return it }
        if (CoverDiskCache.isKnownMissing(path)) return null

        // 没有磁盘缓存，从音频文件解码一张并写回磁盘
        val bmp = CoverDiskCache.extractFromAudio(path)
        if (bmp != null) {
            // 异步写磁盘，不阻塞 UI 拿 bitmap 的路径
            ioScope.launch {
                try {
                    CoverDiskCache.prefetchAndCache(path)
                } catch (_: Exception) {}
            }
        }
        return bmp
    }
}
