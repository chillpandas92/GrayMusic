package com.example.localmusicapp

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.imageview.ShapeableImageView

/**
 * 歌手详情抽屉：可以从任意 Activity（PlayerActivity / SongListActivity / …）弹出，
 * 不再强制跳转到 SongListActivity 的歌手 Tab。
 *
 * 内部会：
 *  1. 根据歌手名在 ScanResultHolder 里查找所有匹配歌曲
 *  2. 构建 ArtistEntry（含专辑分组）
 *  3. 弹出和 SongListActivity.showArtistSheet() 一模一样的 BottomSheetDialog
 *
 * 专辑卡片点击 → 通过 Intent 跳转到 SongListActivity 的专辑详情页（复用已有逻辑）。
 * 歌曲行点击 → PlaybackManager 直接播放。
 */
object ArtistDetailSheet {

    /**
     * 按歌手名构建 ArtistEntry 并弹出详情页。
     * @return 是否成功找到歌手（false = 歌曲库里没有匹配）
     */
    fun show(activity: AppCompatActivity, artistName: String): Boolean {
        val entry = buildEntry(activity, artistName) ?: return false
        showSheet(activity, entry)
        return true
    }

    fun coverPathFor(activity: AppCompatActivity, artistName: String): String {
        return buildEntry(activity, artistName)?.coverPath.orEmpty()
    }

    /**
     * 从任意页面直接弹出专辑详情抽屉，不跳转到曲库页。
     * albumArtist 为空时只按专辑名匹配；不为空时优先匹配同名专辑中的同一专辑艺术家。
     */
    fun showAlbum(activity: AppCompatActivity, albumTitle: String, albumArtist: String = ""): Boolean {
        val entry = buildAlbumEntry(activity, albumTitle, albumArtist) ?: return false
        showAlbumSheet(activity, entry)
        return true
    }

    // ------------------------------------------------------------------

    private fun buildAlbumEntry(activity: AppCompatActivity, albumTitle: String, albumArtist: String): AlbumEntry? {
        val titleKey = SortKeyHelper.keyOf(albumTitle).ifBlank { albumTitle.trim().lowercase() }
        if (titleKey.isBlank()) return null
        val artistKey = SortKeyHelper.keyOf(albumArtist).ifBlank { albumArtist.trim().lowercase() }
        val albums = buildAlbumEntries(ScanResultHolder.files(activity))
        if (albums.isEmpty()) return null
        return albums.firstOrNull { album ->
            val sameTitle = SortKeyHelper.keyOf(album.title).ifBlank { album.title.trim().lowercase() } == titleKey
            val sameArtist = artistKey.isBlank() || SortKeyHelper.keyOf(album.artist).ifBlank { album.artist.trim().lowercase() } == artistKey
            sameTitle && sameArtist
        } ?: albums.firstOrNull { album ->
            SortKeyHelper.keyOf(album.title).ifBlank { album.title.trim().lowercase() } == titleKey
        }
    }

    private fun buildEntry(activity: AppCompatActivity, artistName: String): ArtistEntry? {
        val allFiles = ScanResultHolder.files(activity)
        if (allFiles.isEmpty()) return null
        val targetKey = SortKeyHelper.keyOf(artistName).ifBlank { artistName.trim().lowercase() }

        val matched = allFiles.filter { file ->
            val source = file.artist.ifBlank { file.albumArtist }.ifBlank { "未知艺术家" }
            ArtistUtils.splitArtists(source).any { name ->
                val k = SortKeyHelper.keyOf(name).ifBlank { name.trim().lowercase() }
                k == targetKey
            }
        }.distinctBy { it.path }

        if (matched.isEmpty()) return null

        val orderedSongs = sortedArtistSongs(matched)
        val albums = buildAlbumEntries(matched)

        return ArtistEntry(
            name = artistName,
            songs = orderedSongs,
            albums = albums,
            coverPath = albums.firstOrNull()?.coverPath ?: orderedSongs.firstOrNull()?.path.orEmpty(),
            totalDurationMs = orderedSongs.sumOf { it.duration }
        )
    }

    private fun showSheet(activity: AppCompatActivity, artist: ArtistEntry) {
        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_artist, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)

        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog).findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT)
                val statusTop = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
                val coord = sheet.parent as? View
                val parentHeight = coord?.height ?: activity.resources.displayMetrics.heightPixels
                val targetHeight = (parentHeight - statusTop).coerceAtLeast(0)
                sheet.layoutParams = (sheet.layoutParams as ViewGroup.LayoutParams).apply {
                    height = targetHeight
                }
                BottomSheetBehavior.from(sheet).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                    isFitToContents = true
                }
            }
        }

        val cover = view.findViewById<ShapeableImageView>(R.id.ivArtistSheetCover)
        val name = view.findViewById<TextView>(R.id.tvArtistSheetName)
        val albumContainer = view.findViewById<LinearLayout>(R.id.artistAlbumsContainer)
        val songsMeta = view.findViewById<TextView>(R.id.tvArtistSheetSongsMeta)
        val tracksContainer = view.findViewById<LinearLayout>(R.id.artistSheetTracksContainer)

        name.text = artist.name
        if (artist.coverPath.isNotBlank()) {
            CoverLoader.loadHighRes(cover, artist.coverPath, R.drawable.music_note_24)
        } else {
            cover.setImageResource(R.drawable.music_note_24)
        }

        // 专辑卡片
        albumContainer.removeAllViews()
        artist.albums.forEach { album ->
            albumContainer.addView(buildAlbumCard(activity, dialog, album))
        }

        // 歌曲列表
        songsMeta.text = "${artist.songCount}首歌曲 / ${formatDuration(artist.totalDurationMs)}"
        tracksContainer.removeAllViews()
        val orderedSongs = sortedArtistSongs(artist.songs)
        orderedSongs.forEachIndexed { index, song ->
            tracksContainer.addView(
                buildSongRow(activity, artist.name, orderedSongs, song, index)
            )
        }

        dialog.show()
    }

    // ------------------------------------------------------------------
    // 专辑卡片
    // ------------------------------------------------------------------

    private fun buildAlbumCard(
        activity: AppCompatActivity,
        parentDialog: BottomSheetDialog,
        album: AlbumEntry
    ): View {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(14)
            }
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(context, R.drawable.song_item_touch_bg)
        }

        val cover = ShapeableImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFF2F2F2.toInt())
            shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(dp(4).toFloat())
                .build()
            setImageResource(R.drawable.music_note_24)
            contentDescription = "album cover"
        }
        CoverLoader.loadAlbumCover(cover, album.coverPath, R.drawable.music_note_24)
        container.addView(cover)

        container.addView(TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(7)
            }
            text = album.title
            textSize = 12f
            setTextColor(0xFF222222.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        container.setOnClickListener {
            parentDialog.dismiss()
            container.post { showAlbumSheet(activity, album) }
        }
        AppFont.applyTo(container)
        return container
    }

    // ------------------------------------------------------------------
    // 歌曲行
    // ------------------------------------------------------------------

    private fun buildSongRow(
        activity: AppCompatActivity,
        artistName: String,
        orderedSongs: List<MusicScanner.MusicFile>,
        song: MusicScanner.MusicFile,
        absoluteIndex: Int
    ): View {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(context, R.drawable.song_item_touch_bg)
        }

        // 歌曲封面
        val coverView = ShapeableImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                marginEnd = dp(10)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFF2F2F2.toInt())
            shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(dp(4).toFloat())
                .build()
            setImageResource(R.drawable.music_note_24)
            contentDescription = "song cover"
        }
        CoverLoader.load(coverView, song.path, R.drawable.music_note_24)
        row.addView(coverView)

        val currentPath = PlaybackUiResolver.displayPath(activity)
        val isCurrent = song.path == currentPath
        val titleColor = if (isCurrent) 0xFF1565C0.toInt() else 0xFF111111.toInt()
        val subColor = if (isCurrent) 0xFF1565C0.toInt() else 0xFF777777.toInt()
        val timeColor = if (isCurrent) 0xFF1565C0.toInt() else 0xFF666666.toInt()

        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = song.title
                textSize = 15f
                setTextColor(titleColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = song.album.ifBlank { "未知专辑" }
                textSize = 12f
                setTextColor(subColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        })

        row.addView(TextView(activity).apply {
            text = SongAdapter.formatDuration(song.duration)
            textSize = 12f
            setTextColor(timeColor)
        })

        row.setOnClickListener {
            val mode = PlaybackSettings.getPreferredMode(activity)
            val (queue, startIdx) = buildQueueForMode(orderedSongs, absoluteIndex, mode)
            PlaybackManager.playQueue(
                context = activity,
                files = queue,
                startIndex = startIdx,
                mode = mode,
                sourceList = orderedSongs,
                sourceName = "歌手：$artistName"
            )
        }
        row.setOnLongClickListener {
            SongActionSheet.show(activity, song)
            true
        }
        AppFont.applyTo(row)
        return row
    }

    private fun showAlbumSheet(
        activity: AppCompatActivity,
        album: AlbumEntry
    ) {
        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_album, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)

        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog).findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT)
                val statusTop = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
                val coord = sheet.parent as? View
                val parentHeight = coord?.height ?: activity.resources.displayMetrics.heightPixels
                val targetHeight = (parentHeight - statusTop).coerceAtLeast(0)
                sheet.layoutParams = (sheet.layoutParams as ViewGroup.LayoutParams).apply {
                    height = targetHeight
                }
                BottomSheetBehavior.from(sheet).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                    isFitToContents = true
                }
            }
        }

        val cover = view.findViewById<ShapeableImageView>(R.id.ivAlbumSheetCover)
        val title = view.findViewById<TextView>(R.id.tvAlbumSheetTitle)
        val artist = view.findViewById<TextView>(R.id.tvAlbumSheetArtist)
        val meta = view.findViewById<TextView>(R.id.tvAlbumSheetMeta)
        val tracksContainer = view.findViewById<LinearLayout>(R.id.albumSheetTracksContainer)
        val listenedView = view.findViewById<TextView>(R.id.tvAlbumSheetListenTime)

        title.text = album.title
        artist.text = buildAlbumArtistsLabel(album)
        meta.text = "${album.songCount}首歌曲 / ${formatCompactDuration(album.totalDurationMs)}"
        CoverLoader.loadHighRes(cover, album.coverPath, R.drawable.music_note_24)

        ListenStats.load(activity)
        val listenMap = ListenStats.snapshot()
        val listenedMs = album.songs.sumOf { listenMap[it.path] ?: 0L }
        listenedView.text = if (listenedMs > 0L) formatListenDuration(listenedMs) else "0 秒"

        tracksContainer.removeAllViews()
        val orderedSongs = sortedAlbumSongs(album.songs)
        val discs = orderedSongs.groupBy { it.discNumber.coerceAtLeast(1) }
        val showDiscHeader = discs.keys.size > 1 || discs.keys.any { it > 1 }
        discs.toSortedMap().forEach { (discNo, songs) ->
            if (showDiscHeader) {
                tracksContainer.addView(buildDiscHeader(activity, discNo, songs.sumOf { it.duration }))
            }
            songs.forEachIndexed { indexInDisc, song ->
                tracksContainer.addView(
                    buildAlbumSongRow(
                        activity = activity,
                        orderedSongs = orderedSongs,
                        song = song,
                        absoluteIndex = orderedSongs.indexOfFirst { it.path == song.path },
                        indexInDisc = indexInDisc
                    )
                )
            }
        }

        dialog.show()
    }

    private fun buildDiscHeader(
        activity: AppCompatActivity,
        discNumber: Int,
        durationMs: Long
    ): View {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(8))
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = "Disc${discNumber}（${formatCompactDuration(durationMs)}）"
                textSize = 14f
                typeface = AppFont.typeface(context)
                setTextColor(0xFF000000.toInt())
            })
        }
    }

    private fun buildAlbumSongRow(
        activity: AppCompatActivity,
        orderedSongs: List<MusicScanner.MusicFile>,
        song: MusicScanner.MusicFile,
        absoluteIndex: Int,
        indexInDisc: Int
    ): View {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ta.getDrawable(0)
            ta.recycle()
        }

        row.addView(TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            text = when {
                song.trackNumber > 0 -> song.trackNumber.toString()
                else -> (indexInDisc + 1).toString()
            }
            textSize = 13f
            setTextColor(0xFF000000.toInt())
        })

        val currentPath = PlaybackUiResolver.displayPath(activity)
        val isCurrent = song.path == currentPath
        val titleColor = if (isCurrent) 0xFF1565C0.toInt() else 0xFF111111.toInt()
        val subColor = if (isCurrent) 0xFF1565C0.toInt() else 0xFF777777.toInt()
        val timeColor = if (isCurrent) 0xFF1565C0.toInt() else 0xFF666666.toInt()

        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = song.title
                textSize = 15f
                setTextColor(titleColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = ArtistUtils.displayArtists(song.artist)
                textSize = 12f
                setTextColor(subColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        })

        row.addView(TextView(activity).apply {
            text = SongAdapter.formatDuration(song.duration)
            textSize = 12f
            setTextColor(timeColor)
        })

        row.setOnClickListener {
            val mode = PlaybackSettings.getPreferredMode(activity)
            val (queue, startIdx) = buildQueueForMode(orderedSongs, absoluteIndex, mode)
            PlaybackManager.playQueue(
                context = activity,
                files = queue,
                startIndex = startIdx,
                mode = mode,
                sourceList = orderedSongs,
                sourceName = "专辑：${orderedSongs.firstOrNull()?.album?.trim().orEmpty().ifBlank { "未知专辑" }}"
            )
        }
        row.setOnLongClickListener {
            SongActionSheet.show(activity, song)
            true
        }
        AppFont.applyTo(row)
        return row
    }

    private fun sortedAlbumSongs(songs: List<MusicScanner.MusicFile>): List<MusicScanner.MusicFile> {
        return songs.sortedWith(
            compareBy<MusicScanner.MusicFile> { it.discNumber.coerceAtLeast(1) }
                .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { SortKeyHelper.keyOf(it.title) }
                .thenBy { it.path }
        )
    }

    private fun buildAlbumArtistsLabel(album: AlbumEntry): String {
        val set = linkedSetOf<String>()
        album.songs.forEach { file ->
            val source = file.albumArtist.trim().ifBlank { ArtistUtils.primaryArtist(file.artist) }
            if (source.isNotBlank()) set.add(source)
        }
        return when {
            set.isEmpty() -> album.artist.ifBlank { "未知艺术家" }
            set.size == 1 -> set.first()
            else -> "群星 / ${set.size}位艺术家"
        }
    }

    private fun formatCompactDuration(ms: Long): String {
        val totalMinutes = (ms / 1000L / 60L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) "${hours}时${minutes}分" else "${minutes}分"
    }

    private fun formatListenDuration(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> "${hours}时${minutes}分"
            minutes > 0L -> "${minutes}分${seconds}秒"
            else -> "${seconds}秒"
        }
    }

    // ------------------------------------------------------------------
    // 工具方法（与 SongListActivity 内部逻辑一致）
    // ------------------------------------------------------------------

    private fun sortedArtistSongs(songs: List<MusicScanner.MusicFile>): List<MusicScanner.MusicFile> {
        return songs.sortedWith(
            compareBy<MusicScanner.MusicFile> { SortKeyHelper.keyOf(it.album.ifBlank { "未知专辑" }) }
                .thenBy { it.discNumber.coerceAtLeast(1) }
                .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { SortKeyHelper.keyOf(it.title) }
                .thenBy { it.path }
        )
    }

    private fun buildAlbumEntries(files: List<MusicScanner.MusicFile>): List<AlbumEntry> {
        if (files.isEmpty()) return emptyList()
        val grouped = linkedMapOf<String, MutableList<MusicScanner.MusicFile>>()
        for (file in files) {
            val title = file.album.trim().ifBlank { "未知专辑" }
            val artistSeed = file.albumArtist.trim()
            val key = title + "\u0001" + artistSeed
            grouped.getOrPut(key) { ArrayList() }.add(file)
        }
        return grouped.values.map { songs ->
            val orderedSongs = songs.sortedWith(
                compareBy<MusicScanner.MusicFile> { it.discNumber.coerceAtLeast(1) }
                    .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                    .thenBy { SortKeyHelper.keyOf(it.title) }
                    .thenBy { it.path }
            )
            val first = orderedSongs.first()
            val title = first.album.trim().ifBlank { "未知专辑" }
            val artist = resolveAlbumArtist(orderedSongs)
            val year = orderedSongs.asSequence()
                .map { it.year }
                .filter { it in 1000..9999 }
                .minOrNull() ?: 0
            AlbumEntry(
                key = title + "\u0001" + artist,
                title = title,
                artist = artist,
                year = year,
                songs = orderedSongs,
                coverPath = first.path,
                totalDurationMs = orderedSongs.sumOf { it.duration }
            )
        }.sortedWith(compareBy { SortKeyHelper.keyOf(it.title) })
    }

    private fun resolveAlbumArtist(songs: List<MusicScanner.MusicFile>): String {
        if (songs.isEmpty()) return "未知艺术家"
        val candidates = songs.asSequence()
            .map { it.albumArtist.trim().ifBlank { ArtistUtils.primaryArtist(it.artist) } }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
        return candidates.maxByOrNull { it.value }?.key ?: "未知艺术家"
    }

    private fun buildQueueForMode(
        list: List<MusicScanner.MusicFile>,
        clickedIndex: Int,
        mode: PlaybackSettings.Mode
    ): Pair<List<MusicScanner.MusicFile>, Int> {
        if (list.isEmpty() || clickedIndex !in list.indices) return list to 0
        return when (mode) {
            PlaybackSettings.Mode.SEQUENTIAL,
            PlaybackSettings.Mode.REPEAT_ONE -> list to clickedIndex
            PlaybackSettings.Mode.RANDOM -> {
                val clicked = list[clickedIndex]
                val others = list.toMutableList().also { it.removeAt(clickedIndex) }.shuffled()
                (listOf(clicked) + others) to 0
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = (ms / 1000L / 60L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) "${hours}时${minutes}分" else "${minutes}分"
    }
}
