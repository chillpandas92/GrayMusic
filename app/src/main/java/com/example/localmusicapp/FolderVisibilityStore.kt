package com.example.localmusicapp

import android.content.Context

/**
 * 保存用户隐藏的文件夹。隐藏不会删除媒体库缓存，只会让该文件夹歌曲从曲库主列表、专辑页、歌手页和搜索中排除。
 */
object FolderVisibilityStore {
    private const val PREFS = "folder_visibility_store"
    private const val KEY_HIDDEN_FOLDERS = "hidden_folders"

    fun hiddenKeys(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN_FOLDERS, emptySet())
            ?.toSet()
            .orEmpty()
    }

    fun isHidden(context: Context, key: String): Boolean {
        if (key.isBlank()) return false
        return hiddenKeys(context).contains(key)
    }

    fun setHidden(context: Context, key: String, hidden: Boolean) {
        if (key.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getStringSet(KEY_HIDDEN_FOLDERS, emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()
        if (hidden) next.add(key) else next.remove(key)
        prefs.edit().putStringSet(KEY_HIDDEN_FOLDERS, next).apply()
    }
}
