package com.example.localmusicapp

import android.content.Context
import org.json.JSONObject

/** Stores the user-selected LRC file URI for each song path. */
object LyricOverrideStore {
    private const val PREFS = "lyric_override_store"
    private const val KEY_MAP = "song_to_lrc_uri"

    fun get(context: Context, songPath: String): String {
        if (songPath.isBlank()) return ""
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MAP, "{}") ?: "{}"
        return runCatching { JSONObject(raw).optString(songPath, "") }.getOrDefault("")
    }

    fun set(context: Context, songPath: String, lrcUri: String) {
        if (songPath.isBlank() || lrcUri.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val obj = runCatching { JSONObject(prefs.getString(KEY_MAP, "{}") ?: "{}") }.getOrDefault(JSONObject())
        obj.put(songPath, lrcUri)
        prefs.edit().putString(KEY_MAP, obj.toString()).apply()
    }

    fun clear(context: Context, songPath: String) {
        if (songPath.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val obj = runCatching { JSONObject(prefs.getString(KEY_MAP, "{}") ?: "{}") }.getOrDefault(JSONObject())
        obj.remove(songPath)
        prefs.edit().putString(KEY_MAP, obj.toString()).apply()
    }
}
