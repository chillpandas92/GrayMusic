package com.example.localmusicapp

/**
 * 搜索页打开前，把当前页面可搜索的数据暂存到这里。
 */
object SearchSessionHolder {

    object Scope {
        const val LIBRARY = "library"
        const val LEADERBOARD = "leaderboard"
        const val FAVORITES = "favorites"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val FOLDERS = "folders"
        const val FOLDER = "folder"
        const val PLAYLIST = "playlist"
    }

    enum class Presentation {
        SONG,
        ALBUM
    }

    data class Item(
        val index: Int,
        val path: String,
        val title: String,
        val subtitle: String,
        val coverPath: String = path,
        val trailing: String = ""
    )

    data class Request(
        val scope: String,
        val sourceName: String,
        val items: List<Item>,
        val presentation: Presentation = Presentation.SONG,
        val targetLabel: String = "歌曲"
    )

    @Volatile
    var request: Request? = null

    /** 记录 PLAYLIST scope 搜索时来自哪个用户歌单，以便返回时能定位回去 */
    @Volatile
    var lastPlaylistId: String? = null

    /** 记录 FOLDER scope 搜索时来自哪个文件夹，以便返回时能定位回去 */
    @Volatile
    var lastFolderKey: String? = null

    /** 最近一次搜索页实际显示出来的歌曲结果，用来点击歌曲后直接生成“搜索结果”播放队列。 */
    @Volatile
    var lastSearchQuery: String = ""

    @Volatile
    var lastSearchResultPaths: List<String> = emptyList()
}
