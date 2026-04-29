package com.example.localmusicapp

import android.content.Context

/**
 * 排序设置
 */
object SortSettings {

    enum class Method(val label: String) {
        TITLE("歌曲名"),
        IMPORT_DATE("导入日期"),
        ARTIST_ALBUM("艺术家（专辑）"),
        PLAY_COUNT("按播放次数")
    }

    enum class Order(val label: String) {
        ASC("升序 (A-Z)"),
        DESC("降序 (Z-A)")
    }

    private const val PREFS = "sort_settings"
    private const val KEY_METHOD = "method"
    private const val KEY_ORDER = "order"
    private const val KEY_FAV_METHOD = "favorites_method"
    private const val KEY_FAV_ORDER = "favorites_order"
    private const val KEY_ALBUM_ORDER = "album_order"
    private const val KEY_ARTIST_ORDER = "artist_order"
    private const val KEY_FOLDER_ORDER = "folder_order"

    fun getMethod(context: Context): Method {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_METHOD, Method.TITLE.name) ?: Method.TITLE.name
        return runCatching { Method.valueOf(name) }.getOrDefault(Method.TITLE)
    }

    fun getOrder(context: Context): Order {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ORDER, Order.ASC.name) ?: Order.ASC.name
        return runCatching { Order.valueOf(name) }.getOrDefault(Order.ASC)
    }

    fun setMethod(context: Context, method: Method) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_METHOD, method.name).apply()
    }

    fun setOrder(context: Context, order: Order) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORDER, order.name).apply()
    }

    fun getFavoritesMethod(context: Context): Method {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FAV_METHOD, Method.TITLE.name) ?: Method.TITLE.name
        val method = runCatching { Method.valueOf(name) }.getOrDefault(Method.TITLE)
        return if (method == Method.IMPORT_DATE) Method.TITLE else method
    }

    fun getFavoritesOrder(context: Context): Order {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FAV_ORDER, Order.ASC.name) ?: Order.ASC.name
        return runCatching { Order.valueOf(name) }.getOrDefault(Order.ASC)
    }

    fun setFavoritesMethod(context: Context, method: Method) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_FAV_METHOD, method.name).apply()
    }

    fun setFavoritesOrder(context: Context, order: Order) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_FAV_ORDER, order.name).apply()
    }


    fun getAlbumOrder(context: Context): Order {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ALBUM_ORDER, Order.ASC.name) ?: Order.ASC.name
        return runCatching { Order.valueOf(name) }.getOrDefault(Order.ASC)
    }

    fun setAlbumOrder(context: Context, order: Order) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALBUM_ORDER, order.name).apply()
    }

    fun getArtistOrder(context: Context): Order {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ARTIST_ORDER, Order.ASC.name) ?: Order.ASC.name
        return runCatching { Order.valueOf(name) }.getOrDefault(Order.ASC)
    }

    fun setArtistOrder(context: Context, order: Order) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ARTIST_ORDER, order.name).apply()
    }

    fun getFolderOrder(context: Context): Order {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER_ORDER, Order.ASC.name) ?: Order.ASC.name
        return runCatching { Order.valueOf(name) }.getOrDefault(Order.ASC)
    }

    fun setFolderOrder(context: Context, order: Order) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_FOLDER_ORDER, order.name).apply()
    }


    // ============ 听歌排行的独立排序顺序（按播放时长） ============
    private const val KEY_LB_ORDER = "leaderboard_order"
    private const val KEY_LB_METHOD = "leaderboard_method"

    /** 听歌排行的排序维度 */
    enum class LeaderboardMethod(val label: String) {
        SONG_TIME("播放次数"),
        LISTEN_DURATION("播放时长"),
        ARTIST_COUNT("歌手排行"),
        RECENT_PLAY("最近播放")
    }

    fun getLeaderboardOrder(context: Context): Order {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LB_ORDER, Order.DESC.name) ?: Order.DESC.name
        return runCatching { Order.valueOf(name) }.getOrDefault(Order.DESC)
    }

    fun setLeaderboardOrder(context: Context, order: Order) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LB_ORDER, order.name).apply()
    }

    fun getLeaderboardMethod(context: Context): LeaderboardMethod {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LB_METHOD, LeaderboardMethod.SONG_TIME.name)
            ?: LeaderboardMethod.SONG_TIME.name
        return runCatching { LeaderboardMethod.valueOf(name) }
            .getOrDefault(LeaderboardMethod.SONG_TIME)
    }

    fun setLeaderboardMethod(context: Context, method: LeaderboardMethod) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LB_METHOD, method.name).apply()
    }

    // ============ 听歌排行的日期过滤 ============
    private const val KEY_LB_DATE = "leaderboard_date_filter"

    enum class DateFilter(val label: String) {
        TODAY("本日"),
        WEEK("本周"),
        MONTH("本月"),
        ALL("所有时间")
    }

    fun getLeaderboardDateFilter(context: Context): DateFilter {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LB_DATE, DateFilter.ALL.name) ?: DateFilter.ALL.name
        return runCatching { DateFilter.valueOf(name) }.getOrDefault(DateFilter.ALL)
    }

    fun setLeaderboardDateFilter(context: Context, filter: DateFilter) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LB_DATE, filter.name).apply()
    }

    /** 返回 DateFilter 对应的"起始时刻（ms since epoch）"，ALL 返回 0 */
    fun dateFilterStart(filter: DateFilter): Long {
        if (filter == DateFilter.ALL) return 0L
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        when (filter) {
            DateFilter.TODAY -> {}
            DateFilter.WEEK -> {
                // 本周从周一 00:00 算起
                val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
                val daysSinceMonday = (dow - java.util.Calendar.MONDAY + 7) % 7
                cal.add(java.util.Calendar.DAY_OF_YEAR, -daysSinceMonday)
            }
            DateFilter.MONTH -> {
                cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            else -> {}
        }
        return cal.timeInMillis
    }
}
