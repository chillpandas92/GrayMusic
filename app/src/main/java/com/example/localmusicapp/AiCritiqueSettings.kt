package com.example.localmusicapp

import android.content.Context

/**
 * AI 歌曲总结（锐评）相关的配置与缓存。
 *
 * - DeepSeek API key 写入 SharedPreferences（本地持久化）
 * - 每首歌生成过的锐评用 path 做 key 缓存在内存中，切歌/重放时直接命中，不重复调用接口
 * - 全局开关控制详情页是否显示 AI 锐评区块
 */
object AiCritiqueSettings {

    private const val PREFS = "ai_critique_settings"
    private const val KEY_API_KEY = "deepseek_api_key"
    private const val KEY_ENABLED = "enabled"

    fun getApiKey(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "").orEmpty()
    }

    fun setApiKey(context: Context, key: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, key.trim())
            .apply()
    }

    fun isEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    // 按歌曲路径缓存已生成的锐评（进程内）。清空由进程重启触发即可。
    private val critiqueCache = HashMap<String, String>()

    fun cachedCritique(path: String): String? = critiqueCache[path]

    fun putCritique(path: String, text: String) {
        critiqueCache[path] = text
    }

    fun clearCritiques() {
        critiqueCache.clear()
    }
}
