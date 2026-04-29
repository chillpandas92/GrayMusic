package com.example.localmusicapp

import android.content.Context

object LyricsSettings {
    enum class Alignment { LEFT, CENTER }

    private const val PREFS = "lyrics_settings"
    private const val KEY_ALIGNMENT = "alignment"

    fun getAlignment(context: Context): Alignment {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ALIGNMENT, Alignment.LEFT.name)
        return runCatching { Alignment.valueOf(raw ?: Alignment.LEFT.name) }
            .getOrDefault(Alignment.LEFT)
    }

    fun setAlignment(context: Context, alignment: Alignment) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALIGNMENT, alignment.name)
            .apply()
    }
}
