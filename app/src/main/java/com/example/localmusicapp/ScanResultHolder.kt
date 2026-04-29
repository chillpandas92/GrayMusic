package com.example.localmusicapp

import android.content.Context

/**
 * 扫描结果的进程内单例存储。
 *
 * 冷启动从最近任务或长时间后台返回时，进程内单例可能已经丢失；
 * 这里额外提供一次磁盘缓存兜底，避免播放器页/曲库页出现空白状态。
 */
object ScanResultHolder {
    @Volatile
    var result: MusicScanner.ScanResult? = null

    fun ensure(context: Context): MusicScanner.ScanResult? {
        result?.let { return it }
        val cached = ScanCache.load(context) ?: return null
        result = cached
        return cached
    }

    fun files(context: Context): List<MusicScanner.MusicFile> {
        return ensure(context)?.files ?: emptyList()
    }
}
