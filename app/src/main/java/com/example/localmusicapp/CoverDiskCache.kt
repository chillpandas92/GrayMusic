package com.example.localmusicapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 封面磁盘缓存
 *
 * 每个音频文件的封面提取一次，存成 cacheDir/covers/<md5(path)>.jpg
 * 下次需要时直接 BitmapFactory.decodeFile 即可，比 MediaMetadataRetriever 快一个数量级。
 *
 * 初次扫描完成后会预提取所有歌曲的封面。
 * CoverLoader.load 会首先查磁盘再解码，命中即立返。
 */
object CoverDiskCache {

    private const val TAG = "CoverDiskCache"
    private const val TARGET = 160        // 列表缩略图降采样目标边长
    private const val TARGET_MID = 480    // 专辑网格中等分辨率目标
    private const val TARGET_HIGH = 1080   // 播放页高清封面的目标边长（屏幕宽 ~ 1080px 足够清晰）
    private const val JPEG_QUALITY = 80
    private const val JPEG_QUALITY_HIGH = 90

    private var baseDir: File? = null
    private var appContext: Context? = null
    private val missingPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        val dir = File(context.cacheDir, "covers")
        if (!dir.exists()) dir.mkdirs()
        baseDir = dir
    }

    private fun hash(path: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(path.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun fileFor(path: String): File? {
        val dir = baseDir ?: return null
        return File(dir, hash(path) + ".jpg")
    }

    private fun setRetrieverDataSource(retriever: MediaMetadataRetriever, path: String) {
        if (path.startsWith("content://", ignoreCase = true)) {
            val context = appContext ?: throw IllegalStateException("CoverDiskCache has not been initialized")
            retriever.setDataSource(context, Uri.parse(path))
        } else {
            retriever.setDataSource(path)
        }
    }

    /** 磁盘上有封面吗？ */
    fun has(path: String): Boolean {
        val f = fileFor(path) ?: return false
        return f.exists() && f.length() > 0
    }

    /** 已经探测过但没找到封面？ */
    fun isKnownMissing(path: String): Boolean = missingPaths.contains(path)

    /** 从磁盘读取封面 bitmap；没有就返回 null */
    fun read(path: String): Bitmap? {
        val f = fileFor(path) ?: return null
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        } catch (e: Exception) {
            Log.w(TAG, "read failed for $path", e)
            null
        }
    }

    /** 如果磁盘还没有封面，从音频文件提取并写入磁盘 */
    fun prefetchAndCache(path: String) {
        if (baseDir == null) return
        if (has(path)) return
        if (isKnownMissing(path)) return

        val bmp = extractFromAudio(path)
        if (bmp == null) {
            missingPaths.add(path)
            return
        }
        val f = fileFor(path) ?: return
        try {
            FileOutputStream(f).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "write failed for $path", e)
        }
    }

    /** 从 audio 文件提取一张降采样的封面 bitmap（保留给 CoverLoader 用作 fallback） */
    fun extractFromAudio(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            setRetrieverDataSource(retriever, path)
            val bytes = retriever.embeddedPicture ?: return null

            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
            var sample = 1
            while (probe.outWidth / sample > TARGET * 2 || probe.outHeight / sample > TARGET * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * 专辑网格用：从 audio 文件提取一张 *中等分辨率* 封面 bitmap
     * - 目标边长 480px（对应单元格宽度 ~160dp × 3x dpi ≈ 480px，2x 超采样看起来清晰）
     * - RGB_565 保留内存预算（ARGB_8888 太占）
     * - 不走 160 磁盘缓存（会糊），直接从音频文件降采样解码
     */
    fun extractFromAudioMidRes(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            setRetrieverDataSource(retriever, path)
            val bytes = retriever.embeddedPicture ?: return null

            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
            var sample = 1
            while (probe.outWidth / (sample * 2) >= TARGET_MID &&
                   probe.outHeight / (sample * 2) >= TARGET_MID) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            Log.w(TAG, "mid-res extract failed for $path", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * 播放页用：从 audio 文件提取一张 *高清* 封面 bitmap
     * - 目标边长 720px（对应屏幕宽 ~1080px 的物理像素，肉眼看不出马赛克）
     * - 使用 ARGB_8888 保色彩质量
     * - 不走磁盘缓存（播放页一次只需要一张，MainActivity / 列表仍使用小图缓存走别路径）
     */
    fun extractFromAudioHighRes(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            setRetrieverDataSource(retriever, path)
            val bytes = retriever.embeddedPicture ?: return null

            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
            // 计算降采样：让解码后的尺寸略大于 TARGET_HIGH，避免图像过小放大失真
            var sample = 1
            while (probe.outWidth / (sample * 2) >= TARGET_HIGH &&
                   probe.outHeight / (sample * 2) >= TARGET_HIGH) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            Log.w(TAG, "high-res extract failed for $path", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
