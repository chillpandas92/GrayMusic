package com.example.localmusicapp

import android.content.Context

/**
 * 播放相关设置。
 *
 * 目前持久化：
 *   1. 播放计次阈值
 *   2. 用户上次主动选择的播放模式（顺序 / 单曲循环 / 随机）
 */
object PlaybackSettings {

    enum class Mode(val label: String) {
        SEQUENTIAL("顺序播放"),
        REPEAT_ONE("歌曲循环"),
        RANDOM("随机播放")
    }

    enum class PlayerCoverShape(val label: String) {
        SQUARE("方形"),
        CIRCLE("圆形")
    }

    private const val PREFS = "playback_settings"
    private const val KEY_THRESHOLD = "play_count_threshold"
    private const val KEY_PREFERRED_MODE = "preferred_mode"
    private const val KEY_REPLAY_GAIN_ENABLED = "replay_gain_enabled"
    private const val KEY_MINI_COVER_ROTATION_ENABLED = "mini_cover_rotation_enabled"
    private const val KEY_QUALITY_BADGE_ENABLED = "quality_badge_enabled"
    private const val KEY_CAR_LYRICS_ENABLED = "car_lyrics_enabled"
    private const val KEY_PLAYER_COVER_SHAPE = "player_cover_shape"

    /** 播放阈值百分比：歌曲播放位置 >= 该比例才算一次有效播放。默认 70 */
    fun getThresholdPercent(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_THRESHOLD, 70)
    }

    fun setThresholdPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_THRESHOLD, percent).apply()
    }

    fun getPreferredMode(context: Context): Mode {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFERRED_MODE, Mode.SEQUENTIAL.name)
            ?: Mode.SEQUENTIAL.name
        return runCatching { Mode.valueOf(name) }.getOrDefault(Mode.SEQUENTIAL)
    }

    fun setPreferredMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFERRED_MODE, mode.name).apply()
    }

    fun isReplayGainEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REPLAY_GAIN_ENABLED, false)
    }

    fun setReplayGainEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_REPLAY_GAIN_ENABLED, enabled).apply()
    }

    fun isMiniCoverRotationEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MINI_COVER_ROTATION_ENABLED, false)
    }

    fun setMiniCoverRotationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MINI_COVER_ROTATION_ENABLED, enabled).apply()
    }

    fun getPlayerCoverShape(context: Context): PlayerCoverShape {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PLAYER_COVER_SHAPE, PlayerCoverShape.SQUARE.name)
            ?: PlayerCoverShape.SQUARE.name
        return runCatching { PlayerCoverShape.valueOf(name) }.getOrDefault(PlayerCoverShape.SQUARE)
    }

    fun setPlayerCoverShape(context: Context, shape: PlayerCoverShape) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PLAYER_COVER_SHAPE, shape.name).apply()
    }

    fun isQualityBadgeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_QUALITY_BADGE_ENABLED, false)
    }

    fun setQualityBadgeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_QUALITY_BADGE_ENABLED, enabled).apply()
    }

    fun isCarLyricsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CAR_LYRICS_ENABLED, false)
    }

    fun setCarLyricsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CAR_LYRICS_ENABLED, enabled).apply()
    }
}
