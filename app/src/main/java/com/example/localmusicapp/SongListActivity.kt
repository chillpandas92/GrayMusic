package com.example.localmusicapp

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * 主页
 */
class SongListActivity : AppCompatActivity(), PlaybackManager.Listener {

    private enum class TabKind { ALL, ALBUM, ARTIST, GENRE, FOLDER }
    private enum class NavKind { SONGS, PLAYLIST, PROFILE }


private data class ReportSongSlot(
    val cover: ShapeableImageView,
    val title: TextView
)

private enum class ReportRankMode { SONGS, ARTISTS }

    private lateinit var tabAll: View
    private lateinit var tabAlbum: View
    private lateinit var tabArtist: View
    private lateinit var tabGenre: View
    private lateinit var tabFolder: View

    private lateinit var navSongs: View
    private lateinit var navPlaylist: View
    private lateinit var navProfile: View

    private lateinit var songsContainer: View
    private lateinit var navPlaceholder: View
    private lateinit var profileContainer: View
    private lateinit var profileSettingsOverlay: View
    private lateinit var profileSettingsPanel: View
    private lateinit var profileAboutOverlay: View
    private lateinit var profileAboutPanel: View
    private lateinit var tvSettingsThreshold: TextView
    private var profileSettingsPanelBasePaddingBottom: Int = 0
    private var profileAboutPanelBasePaddingBottom: Int = 0
    private var profileSettingsVisible: Boolean = false
    private var profileAboutVisible: Boolean = false

    private lateinit var rv: RecyclerView
    private lateinit var rvFavorites: RecyclerView
    private lateinit var rvAlbums: RecyclerView
    private lateinit var rvArtists: RecyclerView
    private lateinit var rvFolders: RecyclerView
    private lateinit var tabPlaceholder: View
    private lateinit var albumEmptyView: View
    private lateinit var artistEmptyView: View
    private lateinit var folderEmptyView: View
    private lateinit var tvPlaceholder: TextView
    private lateinit var tvHeaderTitle: TextView
    private lateinit var btnPlayAllHeader: ImageButton
    private lateinit var btnAddFolder: ImageButton
    private lateinit var fabLocate: FloatingActionButton
    private lateinit var fabLocateLb: FloatingActionButton
    private lateinit var fabLocateFav: FloatingActionButton
    private lateinit var fabLocateFolder: FloatingActionButton
    private lateinit var swipeRefresh: SwipeRefreshLayout


private lateinit var btnReportDay: TextView
private lateinit var btnReportWeek: TextView
private lateinit var btnReportMonth: TextView
private lateinit var tvReportRangeLabel: TextView
private lateinit var tvReportSummaryValue: TextView
private lateinit var tvReportBestArtistValue: TextView
private lateinit var tvReportRefreshCountdown: TextView
private lateinit var reportChartView: ListenReportChartView
private lateinit var reportSongSlots: List<ReportSongSlot>
// 排行切换：默认显示"听歌排行"，点"歌手排行"后改渲染 top 3 歌手
private lateinit var btnReportRankSongs: TextView
private lateinit var btnReportRankArtists: TextView
private var currentReportRankMode: ReportRankMode = ReportRankMode.SONGS
private var currentReportRange: ListenReportCalculator.Range = ListenReportCalculator.Range.DAY
private val reportCountdownHandler = Handler(Looper.getMainLooper())
private val reportCountdownTicker = object : Runnable {
    override fun run() {
        updateReportRefreshCountdown()
        reportCountdownHandler.postDelayed(this, 30_000L)
    }
}

    // Mini Player
    private lateinit var miniPlayer: View
    private lateinit var tvMiniTitle: TextView
    private lateinit var tvMiniArtist: TextView
    private lateinit var ivMiniCover: ShapeableImageView
    private lateinit var btnMiniPlayPause: ImageView

    // 多选模式（仅作用于"歌曲"主列表）
    private lateinit var miniPlayerWrapper: View
    private lateinit var multiSelectActionBar: View
    private lateinit var btnMultiSelect: ImageButton
    private var songsAdapterRef: SongAdapter? = null
    private var multiSelectMode: Boolean = false
    private val multiSelectedPaths: LinkedHashSet<String> = LinkedHashSet()

    // Mini Player Lottie 状态追踪
    private var lastMiniLottiePlayingState: Boolean? = null
    private var miniCoverRotationAnimator: ObjectAnimator? = null

    private var adapter: SongAdapter? = null
    private var favoritesAdapter: SongAdapter? = null
    private var albumAdapter: AlbumAdapter? = null
    private var artistAdapter: ArtistAdapter? = null
    private var folderAdapter: FolderAdapter? = null
    private var sortedFiles: List<MusicScanner.MusicFile> = emptyList()
    private var sortedFavorites: List<MusicScanner.MusicFile> = emptyList()
    private var albumEntries: List<AlbumEntry> = emptyList()
    private var artistEntries: List<ArtistEntry> = emptyList()
    private var folderEntries: List<FolderEntry> = emptyList()
    private var albumSheetDialog: BottomSheetDialog? = null
    private var artistSheetDialog: BottomSheetDialog? = null

    private var leaderboardAdapter: LeaderboardAdapter? = null
    private var leaderboardEntries: List<LeaderboardAdapter.Row> = emptyList()
    private var leaderboardVisible: Boolean = false
    private var favoritesVisible: Boolean = false
    // 用户自建歌单：当前进入的哪一个详情页（null = 没进），缓存一份当前歌单的歌曲列表
    private var currentPlaylistId: String? = null
    private var currentPlaylistSongs: List<MusicScanner.MusicFile> = emptyList()
    private var playlistDetailAdapter: SongAdapter? = null
    private var playlistDetailVisible: Boolean = false
    private var currentFolderKey: String? = null
    private var currentFolderSongs: List<MusicScanner.MusicFile> = emptyList()
    private var folderDetailAdapter: SongAdapter? = null
    private var folderDetailVisible: Boolean = false
    private var folderImportInProgress: Boolean = false
    private var folderImportDialog: BottomSheetDialog? = null
    private var folderImportStatus: TextView? = null
    private var folderImportList: LinearLayout? = null
    private var folderImportScroll: ScrollView? = null

    // 我的页 -「扫描媒体库」专用：复用 activity_scan.xml 的中央卡片样式（半透明遮罩 +
    // 居中白色圆角卡 + 标题转圈 + 滚动文件名 + 底部确定按钮），跟首次扫描页完全同款。
    // 单文件夹导入仍走上面的 BottomSheetDialog；这一组只服务于"扫描媒体库"。
    private var libraryRescanDialog: android.app.Dialog? = null
    private var libraryRescanList: LinearLayout? = null
    private var libraryRescanScroll: ScrollView? = null
    private var libraryRescanSpinner: ProgressBar? = null
    private var libraryRescanConfirm: TextView? = null
    private var libraryRescanFinished: Boolean = false

    /**
     * 多选作用域：记住用户是在哪个列表里点了 todolist 图标进来的。
     * 这样"全选"、"分享"等操作都会作用在那一个列表上，不会和当前可见的其他 adapter 串线。
     */
    private enum class MultiSelectScope { LIBRARY, FAVORITES, LEADERBOARD, PLAYLIST, FOLDER }
    private var multiSelectScope: MultiSelectScope? = null
    private var miniPlayerBoundPath: String? = null
    private var currentTabKind: TabKind = TabKind.ALL
    private var currentNavKind: NavKind = NavKind.SONGS

    private var lastAlbumTabClickAt: Long = 0L
    private var lastArtistTabClickAt: Long = 0L
    private var lastFolderTabClickAt: Long = 0L
    private var isAlphabetSidebarDragging: Boolean = false

    // SAF launchers for backup import / export
    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val ok = BackupManager.export(this, uri)
            Toast.makeText(
                this,
                if (ok) "备份已导出" else "备份导出失败",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) showImportSheet(uri)
    }

    private val searchLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleSearchResult(result.resultCode, result.data)
    }

    private val folderPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) importFolderFromTree(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_song_list)
        AppFont.applyTo(findViewById(android.R.id.content))

        // 确保磁盘缓存目录初始化（二次进入 app 时直接来这里，不走 ScanActivity）
        CoverDiskCache.init(this)

        val header = findViewById<View>(R.id.header)
        val bottomNav = findViewById<View>(R.id.bottomNav)
        songsContainer = findViewById(R.id.songsContainer)
        navPlaceholder = findViewById(R.id.navPlaceholder)
        profileContainer = findViewById(R.id.profileContainer)
        profileSettingsOverlay = findViewById(R.id.profileSettingsOverlay)
        profileSettingsPanel = findViewById(R.id.profileSettingsPanel)
        profileAboutOverlay = findViewById(R.id.profileAboutOverlay)
        profileAboutPanel = findViewById(R.id.profileAboutPanel)
        tvSettingsThreshold = findViewById(R.id.tvSettingsThreshold)
        profileSettingsPanelBasePaddingBottom = profileSettingsPanel.paddingBottom
        profileAboutPanelBasePaddingBottom = profileAboutPanel.paddingBottom
        rv = findViewById(R.id.rvSongs)
        rvFavorites = findViewById(R.id.rvFavorites)
        rvAlbums = findViewById(R.id.rvAlbums)
        rvArtists = findViewById(R.id.rvArtists)
        rvFolders = findViewById(R.id.rvFolders)
        tabPlaceholder = findViewById(R.id.tabPlaceholder)
        albumEmptyView = findViewById(R.id.albumEmptyView)
        artistEmptyView = findViewById(R.id.artistEmptyView)
        folderEmptyView = findViewById(R.id.folderEmptyView)
        tvPlaceholder = findViewById(R.id.tvPlaceholder)
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        btnPlayAllHeader = findViewById(R.id.btnPlayAll)
        btnAddFolder = findViewById(R.id.btnAddFolder)
        fabLocate = findViewById(R.id.fabLocate)
        fabLocateLb = findViewById(R.id.fabLocateLb)
        fabLocateFav = findViewById(R.id.fabLocateFav)
        fabLocateFolder = findViewById(R.id.fabLocateFolder)
        swipeRefresh = findViewById(R.id.swipeRefresh)


btnReportDay = findViewById(R.id.btnReportDay)
btnReportWeek = findViewById(R.id.btnReportWeek)
btnReportMonth = findViewById(R.id.btnReportMonth)
tvReportRangeLabel = findViewById(R.id.tvReportRangeLabel)
tvReportSummaryValue = findViewById(R.id.tvReportSummaryValue)
tvReportBestArtistValue = findViewById(R.id.tvReportBestArtistValue)
tvReportRefreshCountdown = findViewById(R.id.tvReportRefreshCountdown)
reportChartView = findViewById(R.id.reportChartView)
reportSongSlots = listOf(
    ReportSongSlot(
        cover = findViewById(R.id.ivReportRank2Cover),
        title = findViewById(R.id.tvReportRank2Title)
    ),
    ReportSongSlot(
        cover = findViewById(R.id.ivReportRank1Cover),
        title = findViewById(R.id.tvReportRank1Title)
    ),
    ReportSongSlot(
        cover = findViewById(R.id.ivReportRank3Cover),
        title = findViewById(R.id.tvReportRank3Title)
    )
)
btnReportRankSongs = findViewById(R.id.btnReportRankSongs)
btnReportRankArtists = findViewById(R.id.btnReportRankArtists)
btnReportRankSongs.isSelected = currentReportRankMode == ReportRankMode.SONGS
btnReportRankArtists.isSelected = currentReportRankMode == ReportRankMode.ARTISTS
reportChartView.setAccentColor(0xFF1565C0.toInt())

        // 下拉刷新：只允许"全部"页触发；字母侧栏拖动期间也彻底禁用刷新拦截
        swipeRefresh.setColorSchemeColors(0xFF1565C0.toInt())
        swipeRefresh.setOnRefreshListener { refreshLibrary() }
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            when {
                isAlphabetSidebarDragging -> true
                currentNavKind != NavKind.SONGS -> true
                currentTabKind != TabKind.ALL -> true
                else -> rv.canScrollVertically(-1)
            }
        }

        miniPlayer = findViewById(R.id.miniPlayer)
        tvMiniTitle = findViewById(R.id.tvMiniTitle)
        tvMiniArtist = findViewById(R.id.tvMiniArtist)
        ivMiniCover = findViewById(R.id.ivMiniCover)
        btnMiniPlayPause = findViewById(R.id.btnMiniPlayPause)
        miniPlayerWrapper = findViewById(R.id.miniPlayerWrapper)
        multiSelectActionBar = findViewById(R.id.multiSelectActionBar)
        btnMultiSelect = findViewById(R.id.btnMultiSelect)

        val emptyView = findViewById<View>(R.id.emptyView)

        // edge-to-edge
        val headerTop = header.paddingTop
        val bottomNavBottom = bottomNav.paddingBottom
        val profileTop = profileContainer.paddingTop
        val playlistHeader = findViewById<View>(R.id.playlistHeader)
        val playlistHeaderTop = playlistHeader.paddingTop
        val leaderboardHeader = findViewById<View>(R.id.leaderboardHeader)
        val leaderboardHeaderTop = leaderboardHeader.paddingTop
        val favoritesHeader = findViewById<View>(R.id.favoritesHeader)
        val favoritesHeaderTop = favoritesHeader.paddingTop
        val playlistDetailHeader = findViewById<View>(R.id.playlistDetailHeader)
        val playlistDetailHeaderTop = playlistDetailHeader.paddingTop
        val folderDetailHeader = findViewById<View>(R.id.folderDetailHeader)
        val folderDetailHeaderTop = folderDetailHeader.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.updatePadding(top = headerTop + bars.top)
            bottomNav.updatePadding(bottom = bottomNavBottom + bars.bottom)
            profileContainer.updatePadding(top = profileTop + bars.top)
            playlistHeader.updatePadding(top = playlistHeaderTop + bars.top)
            leaderboardHeader.updatePadding(top = leaderboardHeaderTop + bars.top)
            favoritesHeader.updatePadding(top = favoritesHeaderTop + bars.top)
            playlistDetailHeader.updatePadding(top = playlistDetailHeaderTop + bars.top)
            folderDetailHeader.updatePadding(top = folderDetailHeaderTop + bars.top)
            profileSettingsPanel.updatePadding(bottom = profileSettingsPanelBasePaddingBottom + bars.bottom)
            profileAboutPanel.updatePadding(bottom = profileAboutPanelBasePaddingBottom + bars.bottom)
            insets
        }

        // 顶部右侧按钮
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            openSearchForCurrentPage()
        }
        findViewById<ImageButton>(R.id.btnMultiSelect).setOnClickListener {
            if (multiSelectMode) exitMultiSelectMode() else enterMultiSelectMode(MultiSelectScope.LIBRARY)
        }
        findViewById<View>(R.id.btnMultiSelectMore).setOnClickListener {
            showMultiSelectMoreSheet()
        }
        findViewById<View>(R.id.btnMultiSelectAll).setOnClickListener {
            selectAllCurrent()
        }
        findViewById<View>(R.id.btnMultiSelectShare).setOnClickListener {
            shareMultiSelectedSongs()
        }
        findViewById<ImageButton>(R.id.btnSort).setOnClickListener {
            showSortSheet()
        }
        findViewById<ImageButton>(R.id.btnAddFolder).setOnClickListener {
            openFolderPicker()
        }

        // 左上方随机播放：在当前页面直接创建随机播放列表
        findViewById<ImageButton>(R.id.btnPlayAll).setOnClickListener {
            if (sortedFiles.isEmpty()) {
                Toast.makeText(this, "暂无可播放的歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val queue = sortedFiles.shuffled()
            PlaybackManager.playQueue(
                context = this,
                files = queue,
                startIndex = 0,
                mode = PlaybackSettings.Mode.RANDOM,
                sourceList = sortedFiles,
                sourceName = "曲库"
            )
        }

        tvSettingsThreshold.text = "${PlaybackSettings.getThresholdPercent(this)}%"
        findViewById<View>(R.id.rowSettingsThreshold).setOnClickListener {
            showThresholdSheet(tvSettingsThreshold)
        }

        // AI 歌曲总结：点击弹对话框配置 DeepSeek API Key
        val tvAiStatus = findViewById<TextView>(R.id.tvSettingsAiCritiqueStatus)
        refreshAiCritiqueSettingsStatus(tvAiStatus)
        findViewById<View>(R.id.rowSettingsAiCritique).setOnClickListener {
            showAiCritiqueSettingsDialog(tvAiStatus)
        }

        refreshReplayGainSettingsUi()
        findViewById<View>(R.id.rowSettingsReplayGainScan).setOnClickListener {
            val library = ScanResultHolder.result?.files ?: sortedFiles
            ReplayGainScanSheet.show(this, library) {
                refreshReplayGainSettingsUi()
                PlaybackManager.refreshReplayGain()
            }
        }
        findViewById<View>(R.id.rowSettingsReplayGainToggle).setOnClickListener {
            val enabled = !PlaybackSettings.isReplayGainEnabled(this)
            PlaybackSettings.setReplayGainEnabled(this, enabled)
            refreshReplayGainSettingsUi()
            PlaybackManager.refreshReplayGain()
        }
        refreshQualityBadgeSettingsUi()
        findViewById<View>(R.id.rowSettingsQualityBadgeToggle).setOnClickListener {
            val enabled = !PlaybackSettings.isQualityBadgeEnabled(this)
            PlaybackSettings.setQualityBadgeEnabled(this, enabled)
            refreshQualityBadgeSettingsUi()
            refreshSongQualityBadges()
        }
        refreshCarLyricsSettingsUi()
        findViewById<View>(R.id.rowSettingsCarLyricsToggle).setOnClickListener {
            val enabled = !PlaybackSettings.isCarLyricsEnabled(this)
            PlaybackSettings.setCarLyricsEnabled(this, enabled)
            refreshCarLyricsSettingsUi()
            PlaybackManager.refreshCarLyrics()
            Toast.makeText(this, if (enabled) "车载歌词已开启" else "车载歌词已关闭", Toast.LENGTH_SHORT).show()
        }
        refreshMiniCoverRotationSettingsUi()
        findViewById<View>(R.id.rowSettingsMiniCoverRotation).setOnClickListener {
            val enabled = !PlaybackSettings.isMiniCoverRotationEnabled(this)
            PlaybackSettings.setMiniCoverRotationEnabled(this, enabled)
            refreshMiniCoverRotationSettingsUi()
            updateMiniCoverRotation()
        }

        // 「扫描媒体库」入口：复用文件夹导入扫描的弹窗，扫描系统 Music 目录 + 所有
        // 之前 take 过持久权限的 SAF 文件夹，把结果合并写回曲库缓存。
        findViewById<View>(R.id.btnScanMediaLibrary).setOnClickListener {
            rescanMediaLibrary()
        }

        // ImageButton 默认 clickable=true 会吞掉父 FrameLayout 的点击，所以把同一个
        // 监听器也直接绑到按钮上，保证点击设置 icon 能弹出上拉面板。
        val openProfileAbout = View.OnClickListener { showProfileAboutPanel() }
        findViewById<View>(R.id.profileAboutHost).setOnClickListener(openProfileAbout)
        findViewById<View>(R.id.btnProfileAbout).setOnClickListener(openProfileAbout)
        findViewById<View>(R.id.btnCloseAboutPanel).setOnClickListener {
            hideProfileAboutPanel()
        }
        findViewById<View>(R.id.btnCopyQqGroup).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("QQ群", "1092091891"))
            Toast.makeText(this, "已复制QQ群号", Toast.LENGTH_SHORT).show()
        }
        profileAboutOverlay.setOnClickListener {
            hideProfileAboutPanel()
        }
        profileAboutPanel.setOnClickListener { }

        val openProfileSettings = View.OnClickListener { showProfileSettingsPanel() }
        findViewById<View>(R.id.profileSettingsHost).setOnClickListener(openProfileSettings)
        findViewById<View>(R.id.btnProfileSettings).setOnClickListener(openProfileSettings)
        findViewById<View>(R.id.btnCloseSettingsPanel).setOnClickListener {
            hideProfileSettingsPanel()
        }
        profileSettingsOverlay.setOnClickListener {
            hideProfileSettingsPanel()
        }
        profileSettingsPanel.setOnClickListener { }
        installAboutPanelDragToDismiss()
        installSettingsPanelDragToDismiss()


btnReportDay.setOnClickListener {
    switchListenReportRange(ListenReportCalculator.Range.DAY)
}
btnReportWeek.setOnClickListener {
    switchListenReportRange(ListenReportCalculator.Range.WEEK)
}
btnReportMonth.setOnClickListener {
    switchListenReportRange(ListenReportCalculator.Range.MONTH)
}
btnReportRankSongs.setOnClickListener {
    switchListenReportRankMode(ReportRankMode.SONGS)
}
btnReportRankArtists.setOnClickListener {
    switchListenReportRankMode(ReportRankMode.ARTISTS)
}

        // 歌单页"歌曲列表"的加号：弹出新建歌单输入框
        findViewById<View>(R.id.btnAddPlaylist).setOnClickListener {
            showNewPlaylistDialog()
        }

        // Mini Player 按钮：播放/暂停（空态时只 Toast 提醒）
        btnMiniPlayPause.setOnClickListener {
            if (currentDisplayFile() == null) {
                Toast.makeText(this, "请先选择一首歌", Toast.LENGTH_SHORT).show()
            } else {
                PlaybackManager.toggle(this)
            }
        }
        // 点 Mini Player 主体：启动全屏播放页（底部滑入）
        miniPlayer.setOnClickListener {
            if (currentDisplayFile() == null) return@setOnClickListener
            startActivity(android.content.Intent(this, PlayerActivity::class.java))
            overridePendingTransition(R.anim.slide_up, R.anim.stay)
        }

        // ========= 数据 =========
        val result = ScanResultHolder.ensure(this)
        val rawFiles = result?.files ?: emptyList()
        val files = visibleLibraryFiles(rawFiles)

        val albumCount = files.asSequence()
            .map {
                val album = it.album.trim()
                when {
                    album.isBlank() -> ""
                    it.albumArtist.isNotBlank() -> "${it.albumArtist}:$album"
                    else -> album
                }
            }
            .filter { it.isNotBlank() }
            .toSet()
            .size
        val artistCount = buildArtistEntries(files).size
        val folderCount = buildFolderEntries(rawFiles).size

        findViewById<TextView>(R.id.tabAllCount).text = files.size.toString()
        findViewById<TextView>(R.id.tabAlbumCount).text = albumCount.toString()
        findViewById<TextView>(R.id.tabArtistCount).text = artistCount.toString()
        findViewById<TextView>(R.id.tabFolderCount).text = folderCount.toString()

        findViewById<TextView>(R.id.tvSongCount).text = "${files.size} 首"
        findViewById<TextView>(R.id.tvLibraryAlbumCount).text = "${albumCount} 个"
        findViewById<TextView>(R.id.tvLibraryArtistCount).text = "${artistCount} 位"
        findViewById<TextView>(R.id.tvTotalDuration).text = formatTotalDuration(files)
        findViewById<TextView>(R.id.tvStorageUsed).text = formatStorageSize(files)
        refreshReplayGainSettingsUi()

        if (rawFiles.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            songsContainer.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            // 按当前排序偏好排好（首次进入也会应用持久化选择）
            sortedFiles = applySortOrder(files)
            refreshReplayGainSettingsUi()
            val songAdapter = SongAdapter(
                initialItems = sortedFiles,
                onItemClick = { position, file ->
                    if (multiSelectMode) {
                        toggleMultiSelected(file.path)
                    } else {
                        val mode = PlaybackSettings.getPreferredMode(this)
                        val (queue, startIdx) = buildQueueForMode(sortedFiles, position, mode)
                        PlaybackManager.playQueue(this, queue, startIdx, mode, sortedFiles, "曲库")
                    }
                },
                onItemLongClick = { _, file ->
                    if (!multiSelectMode) {
                        SongActionSheet.show(this, file)
                    }
                }
            )
            songsAdapterRef = songAdapter
            adapter = songAdapter
            syncSongAdaptersTrailingMode()
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = songAdapter
            rv.setHasFixedSize(true)
            rv.setItemViewCacheSize(20)
            (rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            rv.itemAnimator?.moveDuration = 220
            rv.itemAnimator?.changeDuration = 120

            lifecycleScope.launch(Dispatchers.IO) {
                // 只预热首屏附近封面，避免大曲库首次进入时持续解码所有音频封面导致耗电。
                for (f in sortedFiles.take(80)) CoverLoader.prefetch(f.path)
            }

            // 构建字母侧栏
            setupAlphabetSidebar(sortedFiles)
            PlaybackManager.restoreSavedStateIfNeeded(this, rawFiles)
        }
        populateFavorites()

        fabLocate.setOnClickListener {
            currentDisplayPath()?.let { path ->
                scrollLibraryToPath(path, flash = false)
            }
        }
        fabLocateLb.setOnClickListener {
            currentDisplayPath()?.let { path ->
                scrollLeaderboardToPath(path, flash = false)
            }
        }
        fabLocateFav.setOnClickListener {
            currentDisplayPath()?.let { path ->
                scrollFavoritesToPath(path, flash = false)
            }
        }
        fabLocateFolder.setOnClickListener {
            currentDisplayPath()?.let { path ->
                scrollFolderDetailToPath(path, flash = false)
            }
        }

        // 歌单页的两个卡片
        findViewById<View>(R.id.cardFavorites).setOnClickListener {
            showFavorites()
        }
        findViewById<View>(R.id.cardChart).setOnClickListener {
            showLeaderboard()
        }
        // 排行子页的顶部按钮
        findViewById<ImageButton>(R.id.btnLeaderboardBack).setOnClickListener {
            hideLeaderboard()
        }
        findViewById<ImageButton>(R.id.btnLbSearch).setOnClickListener {
            openSearchForCurrentPage()
        }
        findViewById<ImageButton>(R.id.btnLbMulti).setOnClickListener {
            if (multiSelectMode) {
                exitMultiSelectMode()
                return@setOnClickListener
            }
            // 歌手排行下，行是聚合的歌手，没有单首歌可选；这种视图下不进多选
            if (SortSettings.getLeaderboardMethod(this) == SortSettings.LeaderboardMethod.ARTIST_COUNT) {
                Toast.makeText(this, "歌手排行不支持多选", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            enterMultiSelectMode(MultiSelectScope.LEADERBOARD)
        }
        findViewById<ImageButton>(R.id.btnLbSort).setOnClickListener {
            showLeaderboardSortSheet()
        }
        findViewById<ImageButton>(R.id.btnLbShuffle).setOnClickListener {
            // 歌手排行下，行是聚合的歌手而不是歌曲，没有"这一行的歌"可以随机播放，
            // 跟多选保持一致逻辑：toast 提示并不动播放器。
            if (SortSettings.getLeaderboardMethod(this) == SortSettings.LeaderboardMethod.ARTIST_COUNT) {
                Toast.makeText(this, "歌手排行不支持随机播放", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val files = leaderboardEntries.map { it.file }
            if (files.isEmpty()) {
                Toast.makeText(this, "听歌排行暂无可播放的歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val queue = files.shuffled()
            PlaybackManager.playQueue(
                context = this,
                files = queue,
                startIndex = 0,
                mode = PlaybackSettings.Mode.RANDOM,
                sourceList = files,
                sourceName = "听歌排行"
            )
        }
        findViewById<ImageButton>(R.id.btnFavoritesBack).setOnClickListener {
            hideFavorites()
        }
        findViewById<ImageButton>(R.id.btnFavSearch).setOnClickListener {
            openSearchForCurrentPage()
        }
        findViewById<ImageButton>(R.id.btnFavSort).setOnClickListener {
            showFavoritesSortSheet()
        }
        findViewById<ImageButton>(R.id.btnFavTodo).setOnClickListener {
            // 收藏夹的多选：作用域 = FAVORITES，全选会选中当前 sortedFavorites
            if (multiSelectMode) exitMultiSelectMode() else enterMultiSelectMode(MultiSelectScope.FAVORITES)
        }
        findViewById<ImageButton>(R.id.btnFavShuffle).setOnClickListener {
            if (sortedFavorites.isEmpty()) {
                Toast.makeText(this, "收藏夹暂无可播放的歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val queue = sortedFavorites.shuffled()
            PlaybackManager.playQueue(
                context = this,
                files = queue,
                startIndex = 0,
                mode = PlaybackSettings.Mode.RANDOM,
                sourceList = sortedFavorites,
                sourceName = "收藏夹"
            )
        }

        // 用户歌单详情页的顶部按钮
        findViewById<ImageButton>(R.id.btnPlaylistDetailBack).setOnClickListener {
            hidePlaylistDetail()
        }
        findViewById<ImageButton>(R.id.btnPlaylistDetailShuffle).setOnClickListener {
            if (currentPlaylistSongs.isEmpty()) {
                Toast.makeText(this, "歌单暂无可播放的歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = currentPlaylistId?.let { PlaylistStore.get(this, it)?.name } ?: "歌单"
            val queue = currentPlaylistSongs.shuffled()
            PlaybackManager.playQueue(
                context = this,
                files = queue,
                startIndex = 0,
                mode = PlaybackSettings.Mode.RANDOM,
                sourceList = currentPlaylistSongs,
                sourceName = name
            )
        }
        // 用户歌单详情页：搜索 / 排序 / 多选（todo 占位，行为对齐收藏夹）
        findViewById<ImageButton>(R.id.btnPlaylistDetailSearch).setOnClickListener {
            openSearchForCurrentPage()
        }
        findViewById<ImageButton>(R.id.btnPlaylistDetailSort).setOnClickListener {
            showFavoritesSortSheet()
        }
        findViewById<ImageButton>(R.id.btnPlaylistDetailTodo).setOnClickListener {
            if (multiSelectMode) exitMultiSelectMode() else enterMultiSelectMode(MultiSelectScope.PLAYLIST)
        }

        // 文件夹详情页：返回 / 搜索 / 排序 / 多选
        findViewById<ImageButton>(R.id.btnFolderDetailBack).setOnClickListener {
            hideFolderDetail()
        }
        findViewById<ImageButton>(R.id.btnFolderDetailSearch).setOnClickListener {
            openSearchForCurrentPage()
        }
        findViewById<ImageButton>(R.id.btnFolderDetailSort).setOnClickListener {
            showFolderDetailSortSheet()
        }
        findViewById<ImageButton>(R.id.btnFolderDetailTodo).setOnClickListener {
            if (multiSelectMode) exitMultiSelectMode() else enterMultiSelectMode(MultiSelectScope.FOLDER)
        }

        // 设置页：数据备份
        findViewById<View>(R.id.btnBackupExport).setOnClickListener {
            exportLauncher.launch(BackupManager.defaultFileName())
        }
        findViewById<View>(R.id.btnBackupImport).setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "*/*"))
        }

        tabAll = findViewById(R.id.tabAll)
        tabAlbum = findViewById(R.id.tabAlbum)
        tabArtist = findViewById(R.id.tabArtist)
        tabGenre = findViewById(R.id.tabGenre)
        tabFolder = findViewById(R.id.tabFolder)

        // 全部 tab 支持双击回顶：第二次点击在 300ms 内就滚动到顶
        var lastAllClickAt = 0L
        tabAll.setOnClickListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (currentTabKind == TabKind.ALL && now - lastAllClickAt < 300L) {
                (rv.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0) ?: rv.scrollToPosition(0)
                lastAllClickAt = 0L
            } else {
                lastAllClickAt = now
                selectTab(TabKind.ALL, animate = true)
            }
        }

        // 专辑 tab 支持双击回顶：只有双击才允许强制回到顶部。
        tabAlbum.setOnClickListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (currentTabKind == TabKind.ALBUM && now - lastAlbumTabClickAt < 300L) {
                (rvAlbums.layoutManager as? GridLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0) ?: rvAlbums.scrollToPosition(0)
                lastAlbumTabClickAt = 0L
            } else {
                lastAlbumTabClickAt = now
                selectTab(TabKind.ALBUM, animate = true)
            }
        }
        tabArtist.setOnClickListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (currentTabKind == TabKind.ARTIST && now - lastArtistTabClickAt < 300L) {
                (rvArtists.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0) ?: rvArtists.scrollToPosition(0)
                lastArtistTabClickAt = 0L
            } else {
                lastArtistTabClickAt = now
                selectTab(TabKind.ARTIST, animate = true)
            }
        }
        tabGenre.setOnClickListener {
            selectTab(TabKind.GENRE, animate = true)
        }
        tabFolder.setOnClickListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (currentTabKind == TabKind.FOLDER && now - lastFolderTabClickAt < 300L) {
                (rvFolders.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0) ?: rvFolders.scrollToPosition(0)
                lastFolderTabClickAt = 0L
            } else {
                lastFolderTabClickAt = now
                selectTab(TabKind.FOLDER, animate = true)
            }
        }
        selectTab(TabKind.ALL, animate = false)
        updateSwipeRefreshAvailability()

        navSongs = findViewById(R.id.navSongs)
        navPlaylist = findViewById(R.id.navPlaylist)
        navProfile = findViewById(R.id.navProfile)
        navSongs.setOnClickListener { selectNav(NavKind.SONGS) }
        navPlaylist.setOnClickListener { selectNav(NavKind.PLAYLIST) }
        navProfile.setOnClickListener { selectNav(NavKind.PROFILE) }
        selectNav(NavKind.SONGS)

        // 首次布局完成后定位 blob 指示器到默认选项
        val navIndicator = findViewById<View>(R.id.navIndicator)
        navIndicator.post { positionIndicatorInstant(navIndicator, navSongs) }

        // 文件格式占比
        populateFormatBreakdown(files)
    }

    // ============================================================
    // 格式占比横条 + 图例
    // ============================================================

    private fun populateFormatBreakdown(files: List<MusicScanner.MusicFile>) {
        val bar = findViewById<LinearLayout>(R.id.formatBar)
        val legend = findViewById<LinearLayout>(R.id.formatLegend)
        bar.removeAllViews()
        legend.removeAllViews()
        if (files.isEmpty()) return

        // 按扩展名分类统计
        val counts = files.groupingBy { it.path.substringAfterLast('.', "").lowercase() }
            .eachCount()
            .filterKeys { it.isNotEmpty() }

        // 让常见格式有固定顺序；其余统一归到"其他"
        val known = listOf("mp3", "flac", "m4a", "ogg", "wav")
        val displayOrder = known.filter { counts.containsKey(it) && counts[it]!! > 0 }
        val otherCount = counts.filterKeys { it !in known }.values.sum()

        val entries = mutableListOf<Triple<String, Int, Int>>()
        for (ext in displayOrder) entries.add(Triple(ext.uppercase(), counts[ext]!!, colorFor(ext)))
        if (otherCount > 0) entries.add(Triple("其他", otherCount, colorFor("")))
        if (entries.isEmpty()) return

        val total = entries.sumOf { it.second }.toFloat()

        // 横条的彩色分段
        for ((_, count, color) in entries) {
            val seg = View(this)
            seg.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, count.toFloat()
            )
            seg.setBackgroundColor(color)
            bar.addView(seg)
        }

        // 图例：每个格式一条 "● 名称 数量"，间距 12dp
        val dp = resources.displayMetrics.density
        for ((i, entry) in entries.withIndex()) {
            val (name, count, color) = entry
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                if (i > 0) lp.marginStart = (12 * dp).toInt()
                layoutParams = lp
            }
            val dot = View(this).apply {
                val size = (8 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            }
            val text = TextView(this).apply {
                text = " $name $count"
                textSize = 11f
                setTextColor(Color.parseColor("#FF666666"))
            }
            row.addView(dot)
            row.addView(text)
            legend.addView(row)
        }
    }

    private fun colorFor(ext: String): Int = when (ext.lowercase()) {
        "mp3" -> Color.parseColor("#FF1976D2")
        "flac" -> Color.parseColor("#FF388E3C")
        "m4a" -> Color.parseColor("#FFF57C00")
        "ogg" -> Color.parseColor("#FF7B1FA2")
        "wav" -> Color.parseColor("#FFD32F2F")
        else -> Color.parseColor("#FF757575")
    }

    // ============================================================
    // Blob 指示器 - spring 动画
    // ============================================================

    /** 把指示器瞬间定位到目标 nav item（第一次布局后用） */
    private fun positionIndicatorInstant(indicator: View, target: View) {
        val targetX = target.x + (target.width - indicator.width) / 2f
        indicator.translationX = targetX
        indicator.scaleX = 1f
        indicator.scaleY = 1f
    }

    /**
     * 平滑地把指示器移动到目标 nav item：
     *   - translationX 用 spring 走，带一点过冲
     *   - 移动中 scaleX 先拉到 1.3，再回落 —— 产生 blob 拉伸-回弹的感觉
     */
    private fun animateIndicatorTo(target: View) {
        val indicator = findViewById<View>(R.id.navIndicator)
        val targetX = target.x + (target.width - indicator.width) / 2f

        // 平移 spring
        SpringAnimation(indicator, DynamicAnimation.TRANSLATION_X)
            .setSpring(SpringForce(targetX).apply {
                stiffness = SpringForce.STIFFNESS_LOW
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            })
            .start()

        // blob 拉伸：横向先变宽再回正
        val stretchUp = SpringAnimation(indicator, DynamicAnimation.SCALE_X)
            .setSpring(SpringForce(1.3f).apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            })
        val stretchBack = SpringAnimation(indicator, DynamicAnimation.SCALE_X)
            .setSpring(SpringForce(1f).apply {
                stiffness = SpringForce.STIFFNESS_LOW
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            })
        stretchUp.addEndListener { _, _, _, _ -> stretchBack.start() }
        stretchUp.start()

        // 纵向微缩让"被拉长"更明显
        SpringAnimation(indicator, DynamicAnimation.SCALE_Y)
            .setSpring(SpringForce(1f).apply {
                stiffness = SpringForce.STIFFNESS_LOW
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            })
            .setStartValue(0.85f)
            .start()
    }
    

    override fun onStart() {
        super.onStart()
        PlaybackManager.addListener(this)
        populateFavorites()
        if (currentNavKind == NavKind.SONGS && currentTabKind == TabKind.ALBUM) {
            refreshAlbumsPage()
            setupAlbumAlphabetSidebar(albumEntries)
        }
        if (currentNavKind == NavKind.SONGS && currentTabKind == TabKind.ARTIST) {
            refreshArtistsPage()
            setupArtistAlphabetSidebar(artistEntries)
        }
        if (currentNavKind == NavKind.SONGS && currentTabKind == TabKind.FOLDER) {
            refreshFoldersPage()
        }
        if (folderDetailVisible) populateFolderDetail()
        if (leaderboardVisible) populateLeaderboard()
        if (currentNavKind == NavKind.PLAYLIST && !leaderboardVisible && !favoritesVisible) {
            // 重新进来的时候，如果停在歌单 grid 或 detail，两种都要刷一下封面/计数
            if (playlistDetailVisible) populatePlaylistDetail() else populateUserPlaylists()
        }
        switchListenReportRange(currentReportRange, force = true)
        reportCountdownHandler.removeCallbacks(reportCountdownTicker)
        reportCountdownHandler.post(reportCountdownTicker)
        updateLocateButtons(currentDisplayPath())
        // 如果是从 PlayerActivity / SongActionSheet 跳过来的，打开对应歌手 / 专辑页
        intent?.let { handleOpenExtras(it) }
    }

    override fun onStop() {

        super.onStop()
        reportCountdownHandler.removeCallbacks(reportCountdownTicker)
        PlaybackManager.removeListener(this)
        runCatching { ListenStats.persistSnapshot(this, sync = false) }
        miniCoverRotationAnimator?.pause()
        // 扫描媒体库弹窗如果还挂着，活动停止时收掉，避免 WindowLeaked。
        // 协程本身用 lifecycleScope 启动，这里不需要手动 cancel——activity 销毁时
        // lifecycleScope 会自动 cancel，扫描会停在下一次切回 Main 之前。
        libraryRescanDialog?.let { d ->
            runCatching { d.dismiss() }
        }
        libraryRescanDialog = null
    }

    override fun onPlaybackChanged(currentPath: String?, isPlaying: Boolean) {
        val displayPath = currentPath ?: currentDisplayPath()
        val displayFile = currentDisplayFile()
        adapter?.currentPath = displayPath
        favoritesAdapter?.currentPath = displayPath
        leaderboardAdapter?.currentPath = displayPath
        playlistDetailAdapter?.currentPath = displayPath
        folderDetailAdapter?.currentPath = displayPath
        albumAdapter?.currentKey = currentAlbumKey(displayPath)
        syncSongAdaptersTrailingMode()
        if (currentNavKind == NavKind.PROFILE) refreshListenReportCard()
        updateLocateButtons(displayPath)

        // Mini Player 始终显示；同一首歌只更新播放按钮，避免重复解码封面造成切歌卡顿。
        if (displayPath != miniPlayerBoundPath) {
            miniPlayerBoundPath = displayPath
            if (displayFile == null) {
                tvMiniTitle.text = "请播放歌曲"
                tvMiniArtist.text = ""
                ivMiniCover.setImageResource(R.drawable.music_note_24)
            } else {
                tvMiniTitle.text = displayFile.title
                tvMiniArtist.text = ArtistUtils.displayArtists(displayFile.artist)
                CoverLoader.load(ivMiniCover, displayFile.path, R.drawable.music_note_24)
            }
        }

        if (displayFile == null) {
            setMiniLottiePlayPauseState(isPlaying = false, animate = false)
            btnMiniPlayPause.alpha = 0.35f
        } else {
            setMiniLottiePlayPauseState(isPlaying = isPlaying, animate = true)
            btnMiniPlayPause.alpha = 1.0f
        }
        updateMiniCoverRotation()
    }

    /**
     * 驱动 Mini Player 的播放/暂停图标（静态矢量 · 白色）：
     * - 正在播放 → 显示"暂停"两条竖线（点击即暂停）
     * - 未播放 → 显示"播放"三角（点击即播放）
     */
    private fun setMiniLottiePlayPauseState(isPlaying: Boolean, animate: Boolean) {
        val prev = lastMiniLottiePlayingState
        if (prev == isPlaying) return
        lastMiniLottiePlayingState = isPlaying

        btnMiniPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause_custom
            else R.drawable.ic_play_custom
        )
    }

    private fun refreshMiniCoverRotationSettingsUi() {
        val toggle = runCatching { findViewById<ImageView>(R.id.ivSettingsMiniCoverRotationToggle) }.getOrNull()
        setSettingsToggleState(toggle, PlaybackSettings.isMiniCoverRotationEnabled(this))
    }

    private fun updateMiniCoverRotation() {
        if (!::ivMiniCover.isInitialized) return
        val enabled = PlaybackSettings.isMiniCoverRotationEnabled(this)
        val shouldRotate = enabled && PlaybackManager.isPlaying() && currentDisplayFile() != null
        if (shouldRotate) {
            val animator = miniCoverRotationAnimator ?: ObjectAnimator.ofFloat(ivMiniCover, View.ROTATION, ivMiniCover.rotation, ivMiniCover.rotation + 360f).apply {
                duration = 16_000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                miniCoverRotationAnimator = this
            }
            if (!animator.isStarted) {
                animator.start()
            } else if (animator.isPaused) {
                animator.resume()
            }
        } else {
            miniCoverRotationAnimator?.let { animator ->
                if (!enabled || currentDisplayFile() == null) {
                    animator.cancel()
                    miniCoverRotationAnimator = null
                } else if (animator.isStarted && !animator.isPaused) {
                    animator.pause()
                }
            }
        }
        if (!enabled || currentDisplayFile() == null) {
            ivMiniCover.animate().rotation(0f).setDuration(180L).start()
        }
    }

    private fun formatTotalDuration(files: List<MusicScanner.MusicFile>): String {
        val totalMillis = files.sumOf { it.duration }
        val totalMinutes = totalMillis / 1000 / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "$hours 时 $minutes 分" else "$minutes 分"
    }

    private fun formatPlaylistHeaderMeta(files: List<MusicScanner.MusicFile>): String {
        return "${files.size}首歌/${formatPlaylistHeaderDuration(files.sumOf { it.duration })}"
    }

    private fun formatPlaylistHeaderDuration(totalMs: Long): String {
        val totalMinutes = (totalMs / 1000L / 60L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) "${hours}时${minutes}分" else "${minutes}分"
    }

    private fun formatStorageSize(files: List<MusicScanner.MusicFile>): String {
        val totalBytes = files.sumOf { it.size }
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            totalBytes >= gb -> "%.2f GB".format(totalBytes / gb)
            totalBytes >= mb -> "%.1f MB".format(totalBytes / mb)
            totalBytes >= kb -> "%.1f KB".format(totalBytes / kb)
            else -> "$totalBytes B"
        }
    }

    private fun selectTab(kind: TabKind, animate: Boolean = true) {
        if (multiSelectMode) exitMultiSelectMode()
        val previousKind = currentTabKind
        val outgoing = currentVisibleTabContentView()

        currentTabKind = kind
        tabAll.isSelected = kind == TabKind.ALL
        tabAlbum.isSelected = kind == TabKind.ALBUM
        tabArtist.isSelected = kind == TabKind.ARTIST
        tabGenre.isSelected = kind == TabKind.GENRE
        tabFolder.isSelected = kind == TabKind.FOLDER

        tvHeaderTitle.text = when (kind) {
            TabKind.ALL -> "听音乐"
            TabKind.ALBUM -> "专辑"
            TabKind.ARTIST -> "歌手"
            TabKind.GENRE -> "流派"
            TabKind.FOLDER -> "文件夹"
        }
        // 专辑 / 歌手 / 流派 / 文件夹页不展示歌曲多选图标；文件夹页右侧显示添加文件夹。
        btnMultiSelect.visibility = if (kind == TabKind.ALL) View.VISIBLE else View.GONE
        btnAddFolder.visibility = if (kind == TabKind.FOLDER) View.VISIBLE else View.GONE

        val hasData = ScanResultHolder.result?.files?.isNotEmpty() == true
        val mainSidebar = findViewById<LinearLayout>(R.id.alphabetSidebar)
        val mainBubble = findViewById<TextView>(R.id.alphabetBubble)
        val albumSidebar = findViewById<LinearLayout>(R.id.alphabetSidebarAlbum)
        val albumBubble = findViewById<TextView>(R.id.alphabetBubbleAlbum)
        val artistSidebar = findViewById<LinearLayout>(R.id.alphabetSidebarArtist)
        val artistBubble = findViewById<TextView>(R.id.alphabetBubbleArtist)

        rv.visibility = View.GONE
        rvAlbums.visibility = View.GONE
        rvArtists.visibility = View.GONE
        rvFolders.visibility = View.GONE
        tabPlaceholder.visibility = View.GONE
        albumEmptyView.visibility = View.GONE
        artistEmptyView.visibility = View.GONE
        folderEmptyView.visibility = View.GONE
        mainSidebar.visibility = View.GONE
        mainBubble.visibility = View.GONE
        albumSidebar.visibility = View.GONE
        albumBubble.visibility = View.GONE
        artistSidebar.visibility = View.GONE
        artistBubble.visibility = View.GONE

        when (kind) {
            TabKind.ALL -> {
                rv.visibility = if (hasData) View.VISIBLE else View.GONE
                if (hasData) {
                    setupAlphabetSidebar(sortedFiles)
                }
            }
            TabKind.ALBUM -> {
                refreshAlbumsPage()
                setupAlbumAlphabetSidebar(albumEntries)
            }
            TabKind.ARTIST -> {
                refreshArtistsPage()
                setupArtistAlphabetSidebar(artistEntries)
            }
            TabKind.GENRE -> {
                tabPlaceholder.visibility = View.VISIBLE
                tvPlaceholder.text = "页面开发中"
            }
            TabKind.FOLDER -> {
                refreshFoldersPage()
            }
        }

        val incoming = currentVisibleTabContentView()
        if (animate && outgoing != null && incoming != null && outgoing !== incoming) {
            animateTabContentSwitch(
                outgoing = outgoing,
                incoming = incoming,
                slideToLeft = tabOrder(kind) > tabOrder(previousKind)
            )
        }
        updateLocateButtons(currentDisplayPath())
        updateSwipeRefreshAvailability()
    }

    private fun currentVisibleTabContentView(): View? {
        return when {
            rvAlbums.visibility == View.VISIBLE -> rvAlbums
            rvArtists.visibility == View.VISIBLE -> rvArtists
            rvFolders.visibility == View.VISIBLE -> rvFolders
            rv.visibility == View.VISIBLE -> rv
            albumEmptyView.visibility == View.VISIBLE -> albumEmptyView
            artistEmptyView.visibility == View.VISIBLE -> artistEmptyView
            folderEmptyView.visibility == View.VISIBLE -> folderEmptyView
            tabPlaceholder.visibility == View.VISIBLE -> tabPlaceholder
            else -> null
        }
    }

    private fun updateSwipeRefreshAvailability() {
        val enableRefresh =
            currentNavKind == NavKind.SONGS &&
                currentTabKind == TabKind.ALL &&
                !isAlphabetSidebarDragging
        if (!enableRefresh && swipeRefresh.isRefreshing) {
            swipeRefresh.isRefreshing = false
        }
        swipeRefresh.isEnabled = enableRefresh
    }

    private fun beginAlphabetSidebarDrag(sidebar: View) {
        isAlphabetSidebarDragging = true
        updateSwipeRefreshAvailability()
        sidebar.parent?.requestDisallowInterceptTouchEvent(true)
        swipeRefresh.parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun endAlphabetSidebarDrag(sidebar: View) {
        isAlphabetSidebarDragging = false
        updateSwipeRefreshAvailability()
        sidebar.parent?.requestDisallowInterceptTouchEvent(false)
        swipeRefresh.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun scrollAlbumsWithSidebarTouch(index: Int, touchY: Float, sidebarHeight: Int) {
        if (index !in albumEntries.indices) return
        val grid = rvAlbums.layoutManager as? GridLayoutManager
        val spanCount = grid?.spanCount?.coerceAtLeast(1) ?: 1
        val anchorIndex = index - (index % spanCount)
        val rvContentHeight = (rvAlbums.height - rvAlbums.paddingTop - rvAlbums.paddingBottom).coerceAtLeast(1)
        val fraction = if (sidebarHeight > 0) {
            (touchY / sidebarHeight.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val rowHeight = rvAlbums.getChildAt(0)?.height?.takeIf { it > 0 } ?: dp(180)
        val desiredTop = (rvAlbums.paddingTop + (rvContentHeight * fraction) - rowHeight * 0.5f).toInt()
        val maxTop = rvAlbums.paddingTop + (rvContentHeight - rowHeight).coerceAtLeast(0)
        val offset = desiredTop.coerceIn(rvAlbums.paddingTop, maxTop)
        rvAlbums.stopScroll()
        grid?.scrollToPositionWithOffset(anchorIndex, offset) ?: rvAlbums.scrollToPosition(anchorIndex)
    }

    private fun tabOrder(kind: TabKind): Int = when (kind) {
        TabKind.ALL -> 0
        TabKind.ALBUM -> 1
        TabKind.ARTIST -> 2
        TabKind.GENRE -> 3
        TabKind.FOLDER -> 4
    }

    private fun animateTabContentSwitch(
        outgoing: View,
        incoming: View,
        slideToLeft: Boolean
    ) {
        val distance = (resources.displayMetrics.density * 28f)
        val enterFrom = if (slideToLeft) distance else -distance
        val exitTo = -enterFrom

        outgoing.animate().cancel()
        incoming.animate().cancel()

        outgoing.visibility = View.VISIBLE
        incoming.visibility = View.VISIBLE
        incoming.alpha = 0f
        incoming.translationX = enterFrom

        incoming.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(220)
            .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            .start()

        outgoing.animate()
            .alpha(0f)
            .translationX(exitTo)
            .setDuration(220)
            .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            .withEndAction {
                outgoing.alpha = 1f
                outgoing.translationX = 0f
                outgoing.visibility = View.GONE
            }
            .start()
    }

    private fun selectNav(kind: NavKind) {
        if (kind != NavKind.PROFILE && profileSettingsVisible) {
            hideProfileSettingsPanel()
        }
        if (kind != NavKind.PROFILE && profileAboutVisible) {
            hideProfileAboutPanel()
        }
        // 切 tab 视为退出多选——避免"选中集留在另一个页面上"的诡异体验
        if (multiSelectMode) exitMultiSelectMode()
        if (kind != NavKind.SONGS && folderDetailVisible) {
            hideFolderDetail(showList = false)
        }
        currentNavKind = kind
        val previouslySelected = when {
            navSongs.isSelected -> NavKind.SONGS
            navPlaylist.isSelected -> NavKind.PLAYLIST
            navProfile.isSelected -> NavKind.PROFILE
            else -> null
        }

        navSongs.isSelected = kind == NavKind.SONGS
        navPlaylist.isSelected = kind == NavKind.PLAYLIST
        navProfile.isSelected = kind == NavKind.PROFILE

        songsContainer.visibility = if (kind == NavKind.SONGS && !folderDetailVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.folderDetailView).visibility =
            if (kind == NavKind.SONGS && folderDetailVisible) View.VISIBLE else View.GONE
        navPlaceholder.visibility = if (kind == NavKind.PLAYLIST) View.VISIBLE else View.GONE
        profileContainer.visibility = if (kind == NavKind.PROFILE) View.VISIBLE else View.GONE

        if (kind == NavKind.PROFILE) refreshListenReportCard()

        // 进入歌单 tab 时刷新一下用户自建歌单的封面/计数（如果停在 grid 上）
        if (kind == NavKind.PLAYLIST && !leaderboardVisible && !favoritesVisible && !playlistDetailVisible) {
            populateUserPlaylists()
        }

        // 切到别的 tab 时，如果歌单子页还开着，重置回 grid
        if (kind != NavKind.PLAYLIST && (leaderboardVisible || favoritesVisible || playlistDetailVisible)) {
            showPlaylistGrid()
        }
        if (kind == NavKind.SONGS && currentTabKind == TabKind.ALBUM) {
            refreshAlbumsPage()
        }
        if (kind == NavKind.SONGS && currentTabKind == TabKind.ARTIST) {
            refreshArtistsPage()
            setupArtistAlphabetSidebar(artistEntries)
        }
        if (kind == NavKind.SONGS && currentTabKind == TabKind.FOLDER) {
            refreshFoldersPage()
        }

        val target = when (kind) {
            NavKind.SONGS -> navSongs
            NavKind.PLAYLIST -> navPlaylist
            NavKind.PROFILE -> navProfile
        }
        // 第一次还没测量不要动，改在 post 里
        if (findViewById<View>(R.id.navIndicator).width > 0 && target.width > 0) {
            animateIndicatorTo(target)
        }

        // 被选中那个 tab 的 icon 做一次抖动/弹跳（只在真正切换时触发）
        if (previouslySelected != null && previouslySelected != kind) {
            bounceNavIcon(target)
        }
        updateLocateButtons(currentDisplayPath())
        updateSwipeRefreshAvailability()
    }

    /**
     * 底部 tab icon 切换时的抖动动画：
     *   - 先快速缩小到 0.8，再过冲到 1.2，最后弹回 1.0
     *   - 同时做一次小幅 rotation 左右抖动，模拟 Lottie 里的"橡皮"质感
     */
    private fun bounceNavIcon(navItem: View) {
        val icon = (navItem as? ViewGroup)?.getChildAt(0) ?: return
        icon.animate().cancel()

        // scale 弹跳：先缩后弹
        icon.scaleX = 0.8f
        icon.scaleY = 0.8f
        icon.rotation = -12f
        icon.animate()
            .scaleX(1.2f).scaleY(1.2f).rotation(8f)
            .setDuration(110)
            .withEndAction {
                icon.animate()
                    .scaleX(1f).scaleY(1f).rotation(0f)
                    .setDuration(260)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f))
                    .start()
            }
            .start()
    }

    // ============================================================
    // 排序
    // ============================================================

    /** 根据当前 SortSettings 返回排序后的列表（不修改入参） */
    /**
     * 根据播放模式构建队列
     *   - SEQUENTIAL / REPEAT_ONE：队列 = 列表本身，起始 index = 用户点击那首
     *                             （REPEAT_ONE 不动队列——它只影响播完一首后的行为）
     *   - RANDOM：队列第一首 = 用户点击那首，其余随机排在后面
     *            起始 index = 0。播放时按这个固定顺序走（不是每次 next 重新随机）
     *            这样播放列表抽屉能稳定显示"接下来会放的歌"
     */
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

    private fun applySortOrder(files: List<MusicScanner.MusicFile>): List<MusicScanner.MusicFile> {
        return applySortOrder(files, SortSettings.getMethod(this), SortSettings.getOrder(this))
    }

    private fun applyFavoritesSortOrder(files: List<MusicScanner.MusicFile>): List<MusicScanner.MusicFile> {
        return applySortOrder(
            files,
            SortSettings.getFavoritesMethod(this),
            SortSettings.getFavoritesOrder(this)
        )
    }

    private fun applySortOrder(
        files: List<MusicScanner.MusicFile>,
        method: SortSettings.Method,
        order: SortSettings.Order
    ): List<MusicScanner.MusicFile> {
        if (files.isEmpty()) return emptyList()
        return when (method) {
            SortSettings.Method.TITLE -> {
                val sorted = files.sortedWith(titleComparator())
                if (order == SortSettings.Order.ASC) sorted else sorted.reversed()
            }
            SortSettings.Method.IMPORT_DATE -> {
                val cmp = compareBy<MusicScanner.MusicFile> { it.dateAddedSec }
                    .thenBy { SortKeyHelper.keyOf(it.title) }
                    .thenBy { it.path }
                val sorted = files.sortedWith(cmp)
                if (order == SortSettings.Order.ASC) sorted else sorted.reversed()
            }
            SortSettings.Method.ARTIST_ALBUM -> files.sortedWith(artistAlbumComparator(order))
            SortSettings.Method.PLAY_COUNT -> {
                ListenStats.load(this)
                val countMap = ListenStats.countSnapshot()
                val sorted = files.sortedWith(
                    compareByDescending<MusicScanner.MusicFile> { countMap[it.path] ?: 0 }
                        .thenBy { SortKeyHelper.keyOf(it.title) }
                        .thenBy { SortKeyHelper.keyOf(it.artistGroup()) }
                        .thenBy { it.path }
                )
                if (order == SortSettings.Order.DESC) sorted else sorted.reversed()
            }
        }
    }

    private fun titleComparator(): Comparator<MusicScanner.MusicFile> {
        return compareBy<MusicScanner.MusicFile> { SortKeyHelper.keyOf(it.title) }
            .thenBy { SortKeyHelper.keyOf(it.artistGroup()) }
            .thenBy { SortKeyHelper.keyOf(it.album) }
            .thenBy { it.discNumber }
            .thenBy { it.trackNumber }
            .thenBy { it.path }
    }

    private fun artistAlbumComparator(order: SortSettings.Order): Comparator<MusicScanner.MusicFile> {
        return Comparator { a, b ->
            val artistCmp = SortKeyHelper.keyOf(a.artistGroup())
                .compareTo(SortKeyHelper.keyOf(b.artistGroup()))
            if (artistCmp != 0) {
                return@Comparator if (order == SortSettings.Order.ASC) artistCmp else -artistCmp
            }

            val albumCmp = SortKeyHelper.keyOf(a.album)
                .compareTo(SortKeyHelper.keyOf(b.album))
            if (albumCmp != 0) {
                return@Comparator if (order == SortSettings.Order.ASC) albumCmp else -albumCmp
            }

            val discCmp = a.discNumber.compareTo(b.discNumber)
            if (discCmp != 0) return@Comparator discCmp

            val trackCmp = a.trackNumber.compareTo(b.trackNumber)
            if (trackCmp != 0) return@Comparator trackCmp

            val titleCmp = SortKeyHelper.keyOf(a.title)
                .compareTo(SortKeyHelper.keyOf(b.title))
            if (titleCmp != 0) return@Comparator titleCmp

            a.path.compareTo(b.path)
        }
    }

    private fun showSortSheet() {
        if (folderDetailVisible) {
            showFolderDetailSortSheet()
            return
        }
        if (currentNavKind == NavKind.SONGS && currentTabKind == TabKind.ALBUM) {
            showAlbumSortSheet()
            return
        }
        if (currentNavKind == NavKind.SONGS && currentTabKind == TabKind.ARTIST) {
            showArtistSortSheet()
            return
        }
        if (currentNavKind == NavKind.SONGS && currentTabKind == TabKind.GENRE) {
            Toast.makeText(this, "页面开发中", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentNavKind == NavKind.SONGS && currentTabKind == TabKind.FOLDER) {
            showFolderSortSheet()
            return
        }

        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_sort, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)

        dialog.setOnShowListener {
            dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val checkMethodTitle = view.findViewById<View>(R.id.checkMethodTitle)
        val checkMethodImportDate = view.findViewById<View>(R.id.checkMethodImportDate)
        val checkMethodArtistAlbum = view.findViewById<View>(R.id.checkMethodArtistAlbum)
        val checkMethodPlayCount = view.findViewById<View>(R.id.checkMethodPlayCount)
        val checkAsc = view.findViewById<View>(R.id.checkOrderAsc)
        val checkDesc = view.findViewById<View>(R.id.checkOrderDesc)

        fun refresh() {
            val method = SortSettings.getMethod(this)
            checkMethodTitle.visibility = if (method == SortSettings.Method.TITLE) View.VISIBLE else View.INVISIBLE
            checkMethodImportDate.visibility = if (method == SortSettings.Method.IMPORT_DATE) View.VISIBLE else View.INVISIBLE
            checkMethodArtistAlbum.visibility = if (method == SortSettings.Method.ARTIST_ALBUM) View.VISIBLE else View.INVISIBLE
            checkMethodPlayCount.visibility = if (method == SortSettings.Method.PLAY_COUNT) View.VISIBLE else View.INVISIBLE
            val order = SortSettings.getOrder(this)
            checkAsc.visibility = if (order == SortSettings.Order.ASC) View.VISIBLE else View.INVISIBLE
            checkDesc.visibility = if (order == SortSettings.Order.DESC) View.VISIBLE else View.INVISIBLE
        }
        refresh()

        fun pickMethod(method: SortSettings.Method) {
            if (SortSettings.getMethod(this) != method) {
                SortSettings.setMethod(this, method)
                applyAndReloadSort()
                refresh()
            }
        }

        view.findViewById<View>(R.id.rowMethodTitle).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.Method.TITLE) }
        }
        view.findViewById<View>(R.id.rowMethodImportDate).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.Method.IMPORT_DATE) }
        }
        view.findViewById<View>(R.id.rowMethodArtistAlbum).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.Method.ARTIST_ALBUM) }
        }
        view.findViewById<View>(R.id.rowMethodPlayCount).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.Method.PLAY_COUNT) }
        }
        view.findViewById<View>(R.id.rowOrderAsc).setOnClickListener {
            runThrottledSortAction {
                if (SortSettings.getOrder(this) != SortSettings.Order.ASC) {
                    SortSettings.setOrder(this, SortSettings.Order.ASC)
                    applyAndReloadSort()
                    refresh()
                }
            }
        }
        view.findViewById<View>(R.id.rowOrderDesc).setOnClickListener {
            runThrottledSortAction {
                if (SortSettings.getOrder(this) != SortSettings.Order.DESC) {
                    SortSettings.setOrder(this, SortSettings.Order.DESC)
                    applyAndReloadSort()
                    refresh()
                }
            }
        }

        dialog.show()
    }

    /** 排序设置变更后重新计算 sortedFiles 并刷新适配器 */
    private fun applyAndReloadSort() {
        val rawFiles = ScanResultHolder.result?.files ?: return
        sortedFiles = applySortOrder(visibleLibraryFiles(rawFiles))
        adapter?.updateItems(sortedFiles)
        syncSongAdaptersTrailingMode()
        adapter?.currentPath = currentDisplayPath()
        setupAlphabetSidebar(sortedFiles)
        updateLocateButtons(currentDisplayPath())
        refreshReplayGainSettingsUi()
    }

    // ============================================================
    // 字母侧栏（0 / A-Z / # · 按当前排序快速跳到首个匹配）
    // ============================================================

    private var lastSortActionAt: Long = 0L

    private fun runThrottledSortAction(action: () -> Unit) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSortActionAt < 1000L) return
        lastSortActionAt = now
        action()
    }

    private fun setupAlphabetSidebar(files: List<MusicScanner.MusicFile>) {
        val sidebar = findViewById<LinearLayout>(R.id.alphabetSidebar)
        val bubble = findViewById<TextView>(R.id.alphabetBubble)
        val method = SortSettings.getMethod(this)
        if (method == SortSettings.Method.IMPORT_DATE || method == SortSettings.Method.PLAY_COUNT) {
            sidebar.visibility = View.GONE
            sidebar.removeAllViews()
            sidebar.setOnTouchListener(null)
            bubble.visibility = View.GONE
            return
        }
        setupAlphabetSidebarFor(sidebar, bubble, rv, files, method)
    }

    private fun setupFavoritesSidebar(files: List<MusicScanner.MusicFile>) {
        val sidebar = findViewById<LinearLayout>(R.id.alphabetSidebarFav)
        val bubble = findViewById<TextView>(R.id.alphabetBubblePlaylist)
        val method = SortSettings.getFavoritesMethod(this)
        setupAlphabetSidebarFor(sidebar, bubble, rvFavorites, files, method)
    }

    /**
     * 专辑页字母侧栏：数据源是 albumEntries，滚动目标是 rvAlbums。
     * 侧栏显示顺序和当前专辑排序保持一致；触摸 DOWN / MOVE 都直接驱动列表位置。
     */
    private fun setupAlbumAlphabetSidebar(entries: List<AlbumEntry>) {
        val sidebar = findViewById<LinearLayout>(R.id.alphabetSidebarAlbum)
        val bubble = findViewById<TextView>(R.id.alphabetBubbleAlbum)
        sidebar.removeAllViews()
        bubble.visibility = View.GONE
        if (entries.isEmpty()) {
            sidebar.visibility = View.GONE
            sidebar.setOnTouchListener(null)
            return
        }

        sidebar.visibility = View.VISIBLE
        val letters = albumSidebarLetters()
        val firstIndex = mutableMapOf<String, Int>()
        entries.forEachIndexed { index, entry ->
            val bucket = bucketOf(entry.title)
            if (bucket !in firstIndex) firstIndex[bucket] = index
        }

        for (letter in letters) {
            val tv = TextView(this).apply {
                text = letter
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                setTextColor(
                    if (letter in firstIndex) 0xFF1565C0.toInt() else 0xFFCCCCCC.toInt()
                )
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            sidebar.addView(tv, lp)
        }

        // 对齐"全部"页的字母侧栏逻辑：
        //   - DOWN / MOVE 合并：都走 "letter != activeLetter 才滚动"，不再用 force=true 覆盖
        //   - scrollToLetter 用 firstVisible..lastVisible 做可视范围判断，避免已经在屏的目标二次跳到顶
        //   - DOWN 不再强制滚动：手指只要还在之前那个字母的区域，就不会"啪"地跳一下
        var activeLetter: String? = null

        sidebar.setOnTouchListener { v, event ->
            val usable = (v.height - v.paddingTop - v.paddingBottom).coerceAtLeast(1)
            val yInContent = (event.y - v.paddingTop).coerceIn(0f, usable.toFloat() - 1f)
            val idxInLetters = (yInContent / usable * letters.size)
                .toInt().coerceIn(0, letters.size - 1)
            val letter = letters[idxInLetters]

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    beginAlphabetSidebarDrag(v)
                    if (letter != activeLetter) {
                        activeLetter = letter
                        val albumIdx = firstIndex[letter]
                        bubble.text = letter
                        bubble.visibility = View.VISIBLE
                        bubble.alpha = 1f
                        if (albumIdx != null) {
                            scrollAlbumsWithSidebarTouch(
                                index = albumIdx,
                                touchY = yInContent,
                                sidebarHeight = usable
                            )
                            v.performHapticFeedback(
                                HapticFeedbackConstants.CLOCK_TICK,
                                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                            )
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endAlphabetSidebarDrag(v)
                    activeLetter = null
                    bubble.animate().alpha(0f).setDuration(180).withEndAction {
                        bubble.visibility = View.GONE
                        bubble.alpha = 1f
                    }.start()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupAlphabetSidebarFor(
        sidebar: LinearLayout,
        bubble: TextView,
        targetRv: RecyclerView,
        files: List<MusicScanner.MusicFile>,
        method: SortSettings.Method
    ) {
        sidebar.removeAllViews()
        bubble.visibility = View.GONE
        if (files.isEmpty() || method == SortSettings.Method.IMPORT_DATE || method == SortSettings.Method.PLAY_COUNT) {
            sidebar.visibility = View.GONE
            sidebar.setOnTouchListener(null)
            return
        }

        sidebar.visibility = View.VISIBLE
        val letters = listOf("0") + ('A'..'Z').map { it.toString() } + listOf("#")
        val firstIndex = mutableMapOf<String, Int>()
        files.forEachIndexed { i, f ->
            val bucket = bucketOf(sidebarLabelFor(f, method))
            if (bucket !in firstIndex) firstIndex[bucket] = i
        }

        for (letter in letters) {
            val tv = TextView(this).apply {
                text = letter
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                setTextColor(
                    if (letter in firstIndex) 0xFF1565C0.toInt() else 0xFFCCCCCC.toInt()
                )
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            sidebar.addView(tv, lp)
        }

        var activeLetter: String? = null
        sidebar.setOnTouchListener { v, event ->
            val usable = (v.height - v.paddingTop - v.paddingBottom).coerceAtLeast(1)
            val yInContent = (event.y - v.paddingTop).coerceIn(0f, usable.toFloat() - 1f)
            val idxInLetters = (yInContent / usable * letters.size)
                .toInt().coerceIn(0, letters.size - 1)
            val letter = letters[idxInLetters]

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    beginAlphabetSidebarDrag(v)
                    if (letter != activeLetter) {
                        activeLetter = letter
                        val songIdx = firstIndex[letter]
                        bubble.text = letter
                        bubble.visibility = View.VISIBLE
                        bubble.alpha = 1f
                        if (songIdx != null) {
                            (targetRv.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(songIdx, 0)
                                ?: targetRv.scrollToPosition(songIdx)
                            v.performHapticFeedback(
                                HapticFeedbackConstants.CLOCK_TICK,
                                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                            )
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endAlphabetSidebarDrag(v)
                    activeLetter = null
                    bubble.animate().alpha(0f).setDuration(180).withEndAction {
                        bubble.visibility = View.GONE
                        bubble.alpha = 1f
                    }.start()
                    true
                }
                else -> false
            }
        }
    }

    private fun sidebarLabelFor(
        file: MusicScanner.MusicFile,
        method: SortSettings.Method
    ): String {
        return when (method) {
            SortSettings.Method.TITLE -> file.title
            SortSettings.Method.IMPORT_DATE -> file.title
            SortSettings.Method.ARTIST_ALBUM -> file.artistGroup()
            SortSettings.Method.PLAY_COUNT -> file.title
        }
    }

    /** 把首字符归到 0 / A-Z / # 中的某一桶 */
    private fun bucketOf(text: String): String {
        if (text.isEmpty()) return "#"
        val raw = text.trimStart()
        if (raw.startsWith("长城") || raw.startsWith("長城")) return "C"
        val normalized = SortKeyHelper.keyOf(text).trimStart()
        val first = normalized.firstOrNull() ?: return "#"
        return when {
            first.isDigit() -> "0"
            first in 'a'..'z' -> first.uppercaseChar().toString()
            first in 'A'..'Z' -> first.toString()
            else -> "#"
        }
    }

    // ============================================================
    // 搜索
    // ============================================================

    private fun openSearchForCurrentPage() {
        when {
            folderDetailVisible -> openSearchForFolderDetail()
            playlistDetailVisible -> openSearchForPlaylistDetail()
            favoritesVisible -> openSearchForFavorites()
            leaderboardVisible -> openSearchForLeaderboard()
            currentNavKind == NavKind.SONGS && currentTabKind == TabKind.ALBUM -> openSearchForAlbums()
            currentNavKind == NavKind.SONGS && currentTabKind == TabKind.ARTIST -> openSearchForArtists()
            currentNavKind == NavKind.SONGS && currentTabKind == TabKind.GENRE ->
                Toast.makeText(this, "页面开发中", Toast.LENGTH_SHORT).show()
            currentNavKind == NavKind.SONGS && currentTabKind == TabKind.FOLDER -> openSearchForFolders()
            else -> openSearchForLibrary()
        }
    }

    private fun openSearchForLibrary() {
        if (sortedFiles.isEmpty()) {
            Toast.makeText(this, "暂无可搜索的歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        SearchSessionHolder.request = SearchSessionHolder.Request(
            scope = SearchSessionHolder.Scope.LIBRARY,
            sourceName = "曲库",
            items = sortedFiles.mapIndexed { index, file ->
                SearchSessionHolder.Item(
                    index = index,
                    path = file.path,
                    title = file.title,
                    subtitle = SongAdapter.buildSubtitle(file.artist, file.album)
                )
            }
        )
        searchLauncher.launch(Intent(this, SearchActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.stay)
    }

    private fun openSearchForAlbums() {
        if (albumEntries.isEmpty()) {
            refreshAlbumsPage()
        }
        if (albumEntries.isEmpty()) {
            Toast.makeText(this, "暂无可搜索的专辑", Toast.LENGTH_SHORT).show()
            return
        }
        SearchSessionHolder.request = SearchSessionHolder.Request(
            scope = SearchSessionHolder.Scope.ALBUMS,
            sourceName = "专辑",
            presentation = SearchSessionHolder.Presentation.ALBUM,
            targetLabel = "专辑",
            items = albumEntries.mapIndexed { index, album ->
                SearchSessionHolder.Item(
                    index = index,
                    path = album.key,
                    title = album.title,
                    subtitle = album.artist,
                    coverPath = album.coverPath,
                    trailing = album.yearText
                )
            }
        )
        searchLauncher.launch(Intent(this, SearchActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.stay)
    }

    private fun openSearchForArtists() {
        if (artistEntries.isEmpty()) {
            refreshArtistsPage()
        }
        if (artistEntries.isEmpty()) {
            Toast.makeText(this, "暂无可搜索的歌手", Toast.LENGTH_SHORT).show()
            return
        }
        SearchSessionHolder.request = SearchSessionHolder.Request(
            scope = SearchSessionHolder.Scope.ARTISTS,
            sourceName = "歌手",
            targetLabel = "歌手",
            items = artistEntries.mapIndexed { index, artist ->
                SearchSessionHolder.Item(
                    index = index,
                    path = artist.key,
                    title = artist.name,
                    subtitle = artist.metaText,
                    coverPath = artist.coverPath,
                    trailing = ""
                )
            }
        )
        searchLauncher.launch(Intent(this, SearchActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.stay)
    }

    private fun openSearchForLeaderboard() {
        if (leaderboardEntries.isEmpty()) {
            Toast.makeText(this, "当前页面暂无可搜索内容", Toast.LENGTH_SHORT).show()
            return
        }
        SearchSessionHolder.request = SearchSessionHolder.Request(
            scope = SearchSessionHolder.Scope.LEADERBOARD,
            sourceName = "听歌排行",
            items = leaderboardEntries.mapIndexed { index, row ->
                val subtitle = when {
                    row.subtitle.isBlank() -> row.trailing
                    row.trailing.isBlank() -> row.subtitle
                    else -> "${row.subtitle}  ·  ${row.trailing}"
                }
                SearchSessionHolder.Item(
                    index = index,
                    path = row.file.path,
                    title = row.primary,
                    subtitle = subtitle
                )
            }
        )
        searchLauncher.launch(Intent(this, SearchActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.stay)
    }

    private fun openSearchForFavorites() {
        if (sortedFavorites.isEmpty()) {
            Toast.makeText(this, "收藏夹暂无可搜索的歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        SearchSessionHolder.request = SearchSessionHolder.Request(
            scope = SearchSessionHolder.Scope.FAVORITES,
            sourceName = "收藏夹",
            items = sortedFavorites.mapIndexed { index, file ->
                SearchSessionHolder.Item(
                    index = index,
                    path = file.path,
                    title = file.title,
                    subtitle = SongAdapter.buildSubtitle(file.artist, file.album)
                )
            }
        )
        searchLauncher.launch(Intent(this, SearchActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.stay)
    }

    private fun openSearchForPlaylistDetail() {
        if (currentPlaylistSongs.isEmpty()) {
            Toast.makeText(this, "歌单暂无可搜索的歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        val id = currentPlaylistId ?: return
        val name = PlaylistStore.get(this, id)?.name ?: "歌单"
        SearchSessionHolder.lastPlaylistId = id
        SearchSessionHolder.request = SearchSessionHolder.Request(
            scope = SearchSessionHolder.Scope.PLAYLIST,
            sourceName = name,
            items = currentPlaylistSongs.mapIndexed { index, file ->
                SearchSessionHolder.Item(
                    index = index,
                    path = file.path,
                    title = file.title,
                    subtitle = SongAdapter.buildSubtitle(file.artist, file.album)
                )
            }
        )
        searchLauncher.launch(Intent(this, SearchActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.stay)
    }

    private fun openSearchForFolders() {
        if (folderEntries.isEmpty()) {
            refreshFoldersPage()
        }
        if (folderEntries.isEmpty()) {
            Toast.makeText(this, "暂无可搜索的文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        SearchSessionHolder.request = SearchSessionHolder.Request(
            scope = SearchSessionHolder.Scope.FOLDERS,
            sourceName = "文件夹",
            targetLabel = "文件夹",
            items = folderEntries.mapIndexed { index, folder ->
                SearchSessionHolder.Item(
                    index = index,
                    path = folder.key,
                    title = folder.name,
                    subtitle = "${folder.songCount}首歌曲",
                    coverPath = folder.coverPath.ifBlank { folder.key },
                    trailing = folder.displayPath
                )
            }
        )
        searchLauncher.launch(Intent(this, SearchActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.stay)
    }

    private fun openSearchForFolderDetail() {
        if (currentFolderSongs.isEmpty()) {
            Toast.makeText(this, "文件夹暂无可搜索的歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        val key = currentFolderKey ?: return
        val name = buildFolderEntries(ScanResultHolder.result?.files ?: emptyList())
            .firstOrNull { it.key == key }
            ?.name
            ?: "文件夹"
        SearchSessionHolder.lastFolderKey = key
        SearchSessionHolder.request = SearchSessionHolder.Request(
            scope = SearchSessionHolder.Scope.FOLDER,
            sourceName = name,
            items = currentFolderSongs.mapIndexed { index, file ->
                SearchSessionHolder.Item(
                    index = index,
                    path = file.path,
                    title = file.title,
                    subtitle = SongAdapter.buildSubtitle(file.artist, file.album)
                )
            }
        )
        searchLauncher.launch(Intent(this, SearchActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.stay)
    }

    private fun handleSearchResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK || data == null) return
        val scope = data.getStringExtra(SearchActivity.EXTRA_SCOPE) ?: return
        val path = data.getStringExtra(SearchActivity.EXTRA_PATH) ?: return
        val index = data.getIntExtra(SearchActivity.EXTRA_INDEX, -1)
        val searchQuery = data.getStringExtra(SearchActivity.EXTRA_QUERY)
            ?: SearchSessionHolder.lastSearchQuery
        if (isSongSearchScope(scope)) {
            playSearchResultQueue(path, searchQuery)
            return
        }
        when (scope) {
            SearchSessionHolder.Scope.LIBRARY -> {
                selectNav(NavKind.SONGS)
                selectTab(TabKind.ALL, animate = false)
                rv.post {
                    if (index in sortedFiles.indices) scrollLibraryToPosition(index, flash = true)
                    else scrollLibraryToPath(path, flash = true)
                }
            }
            SearchSessionHolder.Scope.ALBUMS -> {
                val entries = if (albumEntries.isNotEmpty()) albumEntries else buildAlbumEntries(visibleLibraryFiles(ScanResultHolder.result?.files ?: emptyList()))
                val target = entries.firstOrNull { it.key == path } ?: entries.getOrNull(index)
                if (target != null) {
                    showAlbumSheet(target)
                }
            }
            SearchSessionHolder.Scope.ARTISTS -> {
                val entries = if (artistEntries.isNotEmpty()) artistEntries else buildArtistEntries(visibleLibraryFiles(ScanResultHolder.result?.files ?: emptyList()))
                val target = entries.firstOrNull { it.key == path } ?: entries.getOrNull(index)
                if (target != null) {
                    showArtistSheet(target)
                }
            }
            SearchSessionHolder.Scope.LEADERBOARD -> {
                selectNav(NavKind.PLAYLIST)
                if (!leaderboardVisible) showLeaderboard()
                findViewById<RecyclerView>(R.id.rvLeaderboard).post {
                    if (index in leaderboardEntries.indices) scrollLeaderboardToPosition(index, flash = true)
                    else scrollLeaderboardToPath(path, flash = true)
                }
            }
            SearchSessionHolder.Scope.FAVORITES -> {
                selectNav(NavKind.PLAYLIST)
                if (!favoritesVisible) showFavorites()
                rvFavorites.post {
                    if (index in sortedFavorites.indices) scrollFavoritesToPosition(index, flash = true)
                    else scrollFavoritesToPath(path, flash = true)
                }
            }
            SearchSessionHolder.Scope.PLAYLIST -> {
                val playlistId = SearchSessionHolder.lastPlaylistId
                if (playlistId.isNullOrBlank()) return
                selectNav(NavKind.PLAYLIST)
                if (!playlistDetailVisible || currentPlaylistId != playlistId) {
                    showPlaylistDetail(playlistId)
                }
                val rv = findViewById<RecyclerView>(R.id.rvPlaylistDetail)
                rv.post {
                    val idx = if (index in currentPlaylistSongs.indices) index
                        else currentPlaylistSongs.indexOfFirst { it.path == path }
                    if (idx >= 0) {
                        rv.stopScroll()
                        (rv.layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(idx, 0)
                            ?: rv.scrollToPosition(idx)
                        rv.post { playlistDetailAdapter?.flashPath(path) }
                    }
                }
            }
            SearchSessionHolder.Scope.FOLDERS -> {
                selectNav(NavKind.SONGS)
                selectTab(TabKind.FOLDER, animate = true)
                rvFolders.post {
                    refreshFoldersPage()
                    val target = folderEntries.firstOrNull { it.key == path }
                        ?: folderEntries.getOrNull(index)
                    if (target != null) {
                        scrollFoldersToKey(target.key, flash = true)
                        rvFolders.post { showFolderDetail(target.key) }
                    }
                }
            }
            SearchSessionHolder.Scope.FOLDER -> {
                val folderKey = SearchSessionHolder.lastFolderKey
                if (folderKey.isNullOrBlank()) return
                selectNav(NavKind.SONGS)
                if (!folderDetailVisible || currentFolderKey != folderKey) {
                    showFolderDetail(folderKey)
                }
                val rv = findViewById<RecyclerView>(R.id.rvFolderDetail)
                rv.post {
                    val idx = if (index in currentFolderSongs.indices) index
                        else currentFolderSongs.indexOfFirst { it.path == path }
                    if (idx >= 0) {
                        rv.stopScroll()
                        (rv.layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(idx, 0)
                            ?: rv.scrollToPosition(idx)
                        rv.post { folderDetailAdapter?.flashPath(path) }
                    }
                }
            }
        }
    }

    private fun isSongSearchScope(scope: String): Boolean {
        return scope in setOf(
            SearchSessionHolder.Scope.LIBRARY,
            SearchSessionHolder.Scope.LEADERBOARD,
            SearchSessionHolder.Scope.FAVORITES,
            SearchSessionHolder.Scope.FOLDER,
            SearchSessionHolder.Scope.PLAYLIST
        )
    }

    private fun playSearchResultQueue(clickedPath: String, rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isBlank()) return
        val sourcePaths = SearchSessionHolder.lastSearchResultPaths
        if (sourcePaths.isEmpty()) return
        val byPath = (ScanResultHolder.result?.files ?: emptyList()).associateBy { it.path }
        val searchFiles = sourcePaths.mapNotNull { byPath[it] }.distinctBy { it.path }
        if (searchFiles.isEmpty()) return
        val clickedIndex = searchFiles.indexOfFirst { it.path == clickedPath }
        if (clickedIndex < 0) return
        val mode = PlaybackSettings.getPreferredMode(this)
        val (queue, startIdx) = buildQueueForMode(searchFiles, clickedIndex, mode)
        PlaybackManager.playQueue(
            context = this,
            files = queue,
            startIndex = startIdx,
            mode = mode,
            sourceList = searchFiles,
            sourceName = "搜索结果：$query"
        )
    }

    private fun scrollLibraryToPath(path: String, flash: Boolean = false) {
        val idx = adapter?.positionOf(path) ?: sortedFiles.indexOfFirst { it.path == path }
        if (idx >= 0) {
            rv.stopScroll()
            (rv.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(idx, 0)
                ?: rv.scrollToPosition(idx)
            if (flash) rv.post { adapter?.flashPath(path) }
        }
    }

    private fun scrollLeaderboardToPath(path: String, flash: Boolean = false) {
        val rvLb = findViewById<RecyclerView>(R.id.rvLeaderboard)
        val idx = leaderboardAdapter?.positionOfPath(path)
            ?: leaderboardEntries.indexOfFirst { it.file.path == path }
        if (idx >= 0) {
            rvLb.stopScroll()
            (rvLb.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(idx, 0)
                ?: rvLb.scrollToPosition(idx)
            if (flash) rvLb.post { leaderboardAdapter?.flashPath(path) }
        }
    }

    private fun scrollFavoritesToPath(path: String, flash: Boolean = false) {
        val idx = favoritesAdapter?.positionOf(path)
            ?: sortedFavorites.indexOfFirst { it.path == path }
        if (idx >= 0) {
            rvFavorites.stopScroll()
            (rvFavorites.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(idx, 0)
                ?: rvFavorites.scrollToPosition(idx)
            if (flash) rvFavorites.post { favoritesAdapter?.flashPath(path) }
        }
    }

    private fun scrollFolderDetailToPath(path: String, flash: Boolean = false) {
        val idx = folderDetailAdapter?.positionOf(path)
            ?: currentFolderSongs.indexOfFirst { it.path == path }
        if (idx >= 0) {
            val rvDetail = findViewById<RecyclerView>(R.id.rvFolderDetail)
            rvDetail.stopScroll()
            (rvDetail.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(idx, 0)
                ?: rvDetail.scrollToPosition(idx)
            if (flash) rvDetail.post { folderDetailAdapter?.flashPath(path) }
        }
    }

    private fun scrollLibraryToPosition(index: Int, flash: Boolean = false) {
        if (index !in sortedFiles.indices) return
        rv.stopScroll()
        (rv.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(index, 0)
            ?: rv.scrollToPosition(index)
        if (flash) rv.post { adapter?.flashPath(sortedFiles[index].path) }
    }

    private fun scrollLeaderboardToPosition(index: Int, flash: Boolean = false) {
        if (index !in leaderboardEntries.indices) return
        val rvLb = findViewById<RecyclerView>(R.id.rvLeaderboard)
        rvLb.stopScroll()
        (rvLb.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(index, 0)
            ?: rvLb.scrollToPosition(index)
        if (flash) rvLb.post { leaderboardAdapter?.flashPath(leaderboardEntries[index].file.path) }
    }

    private fun scrollFavoritesToPosition(index: Int, flash: Boolean = false) {
        if (index !in sortedFavorites.indices) return
        rvFavorites.stopScroll()
        (rvFavorites.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(index, 0)
            ?: rvFavorites.scrollToPosition(index)
        if (flash) rvFavorites.post { favoritesAdapter?.flashPath(sortedFavorites[index].path) }
    }


    private fun scrollArtistsToKey(key: String, flash: Boolean = false) {
        val idx = artistAdapter?.positionOfKey(key)
            ?: artistEntries.indexOfFirst { it.key == key }
        if (idx >= 0) {
            rvArtists.stopScroll()
            (rvArtists.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(idx, 0)
                ?: rvArtists.scrollToPosition(idx)
            if (flash) rvArtists.post { artistAdapter?.flashKey(artistEntries[idx].key) }
        }
    }

    private fun scrollAlbumsToKey(key: String, flash: Boolean = false) {
        val idx = albumAdapter?.positionOfKey(key)
            ?: albumEntries.indexOfFirst { it.key == key }
        if (idx >= 0) {
            scrollAlbumsToPosition(idx, flash)
        }
    }

    private fun scrollAlbumsToPosition(
        index: Int,
        flash: Boolean = false,
        fromSidebar: Boolean = false
    ) {
        if (index !in albumEntries.indices) return
        val grid = rvAlbums.layoutManager as? GridLayoutManager
        val anchorIndex = if (grid != null) {
            val spanCount = grid.spanCount.coerceAtLeast(1)
            index - (index % spanCount)
        } else {
            index
        }

        rvAlbums.stopScroll()
        grid?.scrollToPositionWithOffset(anchorIndex, 0)
            ?: rvAlbums.scrollToPosition(anchorIndex)
        rvAlbums.post {
            if (flash) albumAdapter?.flashKey(albumEntries[index].key)
        }
    }

    private fun refreshArtistsPage() {
        val rawFiles = visibleLibraryFiles(ScanResultHolder.result?.files ?: emptyList())
        val entries = buildArtistEntries(rawFiles)
        artistEntries = entries

        rv.visibility = View.GONE
        rvAlbums.visibility = View.GONE
        tabPlaceholder.visibility = View.GONE
        albumEmptyView.visibility = View.GONE
        rvArtists.visibility = if (entries.isNotEmpty()) View.VISIBLE else View.GONE
        artistEmptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        if (artistAdapter == null) {
            artistAdapter = ArtistAdapter(entries) { _, artist ->
                showArtistSheet(artist)
            }.also {
                rvArtists.layoutManager = LinearLayoutManager(this)
                rvArtists.adapter = it
                rvArtists.setHasFixedSize(true)
                rvArtists.setItemViewCacheSize(20)
                (rvArtists.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
                rvArtists.itemAnimator?.moveDuration = 220
                rvArtists.itemAnimator?.changeDuration = 120
            }
        }
        artistAdapter?.updateItems(entries)
    }

    private fun buildArtistEntries(files: List<MusicScanner.MusicFile>): List<ArtistEntry> {
        if (files.isEmpty()) return emptyList()
        data class Bucket(val displayName: String, val songs: MutableList<MusicScanner.MusicFile>)
        val grouped = linkedMapOf<String, Bucket>()
        for (file in files) {
            val source = file.artist.ifBlank { file.albumArtist }.ifBlank { "未知艺术家" }
            val names = ArtistUtils.splitArtists(source)
                .map { rawName -> rawName.trim().ifBlank { "未知艺术家" } }
                .distinctBy { artistName -> SortKeyHelper.keyOf(artistName).ifBlank { artistName.lowercase() } }
            for (name in names) {
                val key = SortKeyHelper.keyOf(name).ifBlank { name.lowercase() }
                val bucket = grouped.getOrPut(key) { Bucket(name, ArrayList()) }
                bucket.songs.add(file)
            }
        }

        val sorted = grouped.values.map { bucket ->
            val uniqueSongs = bucket.songs.distinctBy { it.path }
            val orderedSongs = sortedArtistSongs(uniqueSongs)
            val albums = buildAlbumEntries(uniqueSongs)
            ArtistEntry(
                name = bucket.displayName,
                songs = orderedSongs,
                albums = albums,
                coverPath = albums.firstOrNull()?.coverPath ?: orderedSongs.firstOrNull()?.path.orEmpty(),
                totalDurationMs = orderedSongs.sumOf { it.duration }
            )
        }.sortedWith(artistEntryComparator())

        return if (SortSettings.getArtistOrder(this) == SortSettings.Order.ASC) sorted else sorted.reversed()
    }

    private fun artistEntryComparator(): Comparator<ArtistEntry> {
        return compareBy<ArtistEntry> { artistBucketRank(it.name) }
            .thenBy { SortKeyHelper.keyOf(it.name) }
            .thenBy { it.name }
    }

    private fun artistBucketRank(name: String): Int {
        val bucket = bucketOf(name)
        return when {
            bucket == "0" -> 0
            bucket.length == 1 && bucket[0] in 'A'..'Z' -> bucket[0] - 'A' + 1
            else -> 27
        }
    }

    private fun sortedArtistSongs(songs: List<MusicScanner.MusicFile>): List<MusicScanner.MusicFile> {
        return songs.sortedWith(
            compareBy<MusicScanner.MusicFile> { SortKeyHelper.keyOf(it.album.ifBlank { "未知专辑" }) }
                .thenBy { it.discNumber.coerceAtLeast(1) }
                .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { SortKeyHelper.keyOf(it.title) }
                .thenBy { it.path }
        )
    }

    private fun setupArtistAlphabetSidebar(entries: List<ArtistEntry>) {
        val sidebar = findViewById<LinearLayout>(R.id.alphabetSidebarArtist)
        val bubble = findViewById<TextView>(R.id.alphabetBubbleArtist)
        sidebar.removeAllViews()
        bubble.visibility = View.GONE
        if (entries.isEmpty()) {
            sidebar.visibility = View.GONE
            sidebar.setOnTouchListener(null)
            return
        }

        sidebar.visibility = View.VISIBLE
        val letters = artistSidebarLetters()
        val firstIndex = mutableMapOf<String, Int>()
        entries.forEachIndexed { index, entry ->
            val bucket = bucketOf(entry.name)
            if (bucket !in firstIndex) firstIndex[bucket] = index
        }

        for (letter in letters) {
            val tv = TextView(this).apply {
                text = letter
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                setTextColor(if (letter in firstIndex) 0xFF1565C0.toInt() else 0xFFCCCCCC.toInt())
            }
            sidebar.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        // 与"全部"/"专辑"页字母侧栏统一：
        //   DOWN 不再 force=true；DOWN 和 MOVE 走同一套 "letter != activeLetter 才滚动"；
        //   用 firstVisible..lastVisible 判断，目标已在屏就不滚
        var activeLetter: String? = null
        sidebar.setOnTouchListener { v, event ->
            val usable = (v.height - v.paddingTop - v.paddingBottom).coerceAtLeast(1)
            val yInContent = (event.y - v.paddingTop).coerceIn(0f, usable.toFloat() - 1f)
            val idxInLetters = (yInContent / usable * letters.size)
                .toInt().coerceIn(0, letters.size - 1)
            val letter = letters[idxInLetters]

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    beginAlphabetSidebarDrag(v)
                    if (letter != activeLetter) {
                        activeLetter = letter
                        val artistIdx = firstIndex[letter]
                        bubble.text = letter
                        bubble.visibility = View.VISIBLE
                        bubble.alpha = 1f
                        if (artistIdx != null) {
                            val lm = rvArtists.layoutManager as? LinearLayoutManager
                            val firstVisible = lm?.findFirstVisibleItemPosition()
                                ?: RecyclerView.NO_POSITION
                            val lastVisible = lm?.findLastVisibleItemPosition()
                                ?: RecyclerView.NO_POSITION
                            if (artistIdx !in firstVisible..lastVisible) {
                                rvArtists.stopScroll()
                                lm?.scrollToPositionWithOffset(artistIdx, 0)
                                    ?: rvArtists.scrollToPosition(artistIdx)
                                v.performHapticFeedback(
                                    HapticFeedbackConstants.CLOCK_TICK,
                                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                                )
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endAlphabetSidebarDrag(v)
                    activeLetter = null
                    bubble.animate().alpha(0f).setDuration(180).withEndAction {
                        bubble.visibility = View.GONE
                        bubble.alpha = 1f
                    }.start()
                    true
                }
                else -> false
            }
        }
    }

    private fun artistSidebarLetters(): List<String> {
        val letters = listOf("0") + ('A'..'Z').map { it.toString() } + listOf("#")
        return if (SortSettings.getArtistOrder(this) == SortSettings.Order.ASC) letters else letters.asReversed()
    }

    private fun showArtistSortSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_sort, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)
        dialog.setOnShowListener {
            dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )?.setBackgroundColor(Color.TRANSPARENT)
        }

        view.findViewById<View>(R.id.rowMethodImportDate).visibility = View.GONE
        view.findViewById<View>(R.id.rowMethodArtistAlbum).visibility = View.GONE
        view.findViewById<View>(R.id.rowMethodPlayCount).visibility = View.GONE
        (view.findViewById<View>(R.id.rowMethodTitle) as? ViewGroup)?.let { row ->
            (row.getChildAt(0) as? TextView)?.text = "歌手名"
        }

        val checkMethodTitle = view.findViewById<View>(R.id.checkMethodTitle)
        val checkAsc = view.findViewById<View>(R.id.checkOrderAsc)
        val checkDesc = view.findViewById<View>(R.id.checkOrderDesc)

        fun refresh() {
            checkMethodTitle.visibility = View.VISIBLE
            val order = SortSettings.getArtistOrder(this)
            checkAsc.visibility = if (order == SortSettings.Order.ASC) View.VISIBLE else View.INVISIBLE
            checkDesc.visibility = if (order == SortSettings.Order.DESC) View.VISIBLE else View.INVISIBLE
        }
        refresh()

        fun pickOrder(order: SortSettings.Order) {
            if (SortSettings.getArtistOrder(this) != order) {
                SortSettings.setArtistOrder(this, order)
                refreshArtistsPage()
                setupArtistAlphabetSidebar(artistEntries)
                refresh()
            }
        }

        view.findViewById<View>(R.id.rowOrderAsc).setOnClickListener {
            runThrottledSortAction { pickOrder(SortSettings.Order.ASC) }
        }
        view.findViewById<View>(R.id.rowOrderDesc).setOnClickListener {
            runThrottledSortAction { pickOrder(SortSettings.Order.DESC) }
        }

        dialog.show()
    }

    private fun showArtistSheet(artist: ArtistEntry) {
        val dialog = BottomSheetDialog(this)
        artistSheetDialog?.dismiss()
        artistSheetDialog = dialog
        val view = layoutInflater.inflate(R.layout.bottom_sheet_artist, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)
        dialog.setOnDismissListener {
            if (artistSheetDialog === dialog) artistSheetDialog = null
        }
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog).findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT)
                val statusTop = ViewCompat.getRootWindowInsets(window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
                val coord = sheet.parent as? View
                val parentHeight = coord?.height ?: resources.displayMetrics.heightPixels
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

        albumContainer.removeAllViews()
        artist.albums.forEach { album ->
            albumContainer.addView(buildArtistAlbumCard(album))
        }

        songsMeta.text = "${artist.songCount}首歌曲 / ${formatArtistTotalDuration(artist.totalDurationMs)}"
        tracksContainer.removeAllViews()
        val orderedSongs = sortedArtistSongs(artist.songs)
        orderedSongs.forEachIndexed { index, song ->
            tracksContainer.addView(buildArtistSongRow(artist.name, orderedSongs, song, index))
        }

        dialog.show()
    }

    private fun buildArtistAlbumCard(album: AlbumEntry): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.START
            layoutParams = LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(14)
            }
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(context, R.drawable.song_item_touch_bg)
        }

        val cover = ShapeableImageView(this).apply {
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

        container.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(7)
            }
            text = album.title
            textSize = 12f
            setTextColor(0xFF222222.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        container.setOnClickListener {
            artistSheetDialog?.dismiss()
            showAlbumSheet(album)
        }
        AppFont.applyTo(container)
        return container
    }

    private fun buildArtistSongRow(
        artistName: String,
        orderedSongs: List<MusicScanner.MusicFile>,
        song: MusicScanner.MusicFile,
        absoluteIndex: Int
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(context, R.drawable.song_item_touch_bg)
        }

        // 歌曲封面（替换原来的专辑号 / 曲目号）
        val coverView = ShapeableImageView(this).apply {
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

        val isCurrent = song.path == currentDisplayPath()
        val titleColor = if (isCurrent) 0xFF1565C0.toInt() else 0xFF111111.toInt()
        val subColor = if (isCurrent) 0xFF1565C0.toInt() else 0xFF777777.toInt()
        val timeColor = if (isCurrent) 0xFF1565C0.toInt() else 0xFF666666.toInt()

        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = song.title
                textSize = 15f
                setTextColor(titleColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = song.album.ifBlank { "未知专辑" }
                textSize = 12f
                setTextColor(subColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        })

        row.addView(TextView(this).apply {
            text = SongAdapter.formatDuration(song.duration)
            textSize = 12f
            setTextColor(timeColor)
        })

        row.setOnClickListener {
            val mode = PlaybackSettings.getPreferredMode(this)
            val (queue, startIdx) = buildQueueForMode(orderedSongs, absoluteIndex, mode)
            PlaybackManager.playQueue(
                context = this,
                files = queue,
                startIndex = startIdx,
                mode = mode,
                sourceList = orderedSongs,
                sourceName = "歌手：$artistName"
            )
        }
        row.setOnLongClickListener {
            SongActionSheet.show(this, song)
            true
        }
        AppFont.applyTo(row)
        return row
    }

    private fun formatArtistTotalDuration(ms: Long): String {
        val totalMinutes = (ms / 1000L / 60L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) "${hours}时${minutes}分" else "${minutes}分"
    }

    private fun refreshAlbumsPage() {
        val rawFiles = visibleLibraryFiles(ScanResultHolder.result?.files ?: emptyList())
        val entries = buildAlbumEntries(rawFiles)
        albumEntries = entries

        rv.visibility = View.GONE
        tabPlaceholder.visibility = View.GONE
        rvAlbums.visibility = if (entries.isNotEmpty()) View.VISIBLE else View.GONE
        albumEmptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        if (albumAdapter == null) {
            albumAdapter = AlbumAdapter(entries) { _, album ->
                showAlbumSheet(album)
            }.also {
                rvAlbums.layoutManager = GridLayoutManager(this, 2)
                rvAlbums.adapter = it
                rvAlbums.setHasFixedSize(true)
                rvAlbums.setItemViewCacheSize(12)
                (rvAlbums.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
                rvAlbums.itemAnimator?.moveDuration = 240
                rvAlbums.itemAnimator?.changeDuration = 120
            }
        }
        albumAdapter?.updateItems(entries)
        albumAdapter?.currentKey = currentAlbumKey(currentDisplayPath())
    }

    private fun buildAlbumEntries(files: List<MusicScanner.MusicFile>): List<AlbumEntry> {
        if (files.isEmpty()) return emptyList()
        val grouped = linkedMapOf<String, MutableList<MusicScanner.MusicFile>>()
        for (file in files) {
            val title = file.album.trim().ifBlank { "未知专辑" }
            val artistSeed = file.albumArtist.trim()
            val key = albumKey(title, artistSeed)
            grouped.getOrPut(key) { ArrayList() }.add(file)
        }

        val entries = grouped.values.map { songs ->
            val orderedSongs = sortedAlbumSongs(songs)
            val first = orderedSongs.first()
            val title = first.album.trim().ifBlank { "未知专辑" }
            val artist = resolveAlbumArtist(orderedSongs)
            val year = orderedSongs.asSequence()
                .map { it.year }
                .filter { it in 1000..9999 }
                .minOrNull() ?: 0
            AlbumEntry(
                key = albumKey(title, artist),
                title = title,
                artist = artist,
                year = year,
                songs = orderedSongs,
                coverPath = first.path,
                totalDurationMs = orderedSongs.sumOf { it.duration }
            )
        }

        val sorted = entries.sortedWith(albumEntryComparator())
        return if (SortSettings.getAlbumOrder(this) == SortSettings.Order.ASC) sorted else sorted.reversed()
    }

    private fun albumSidebarLetters(): List<String> {
        val letters = listOf("0") + ('A'..'Z').map { it.toString() } + listOf("#")
        return if (SortSettings.getAlbumOrder(this) == SortSettings.Order.ASC) letters else letters.asReversed()
    }

    private fun albumBucketRank(title: String): Int {
        val bucket = bucketOf(title)
        return when {
            bucket == "0" -> 0
            bucket.length == 1 && bucket[0] in 'A'..'Z' -> bucket[0] - 'A' + 1
            else -> 27
        }
    }

    private fun albumEntryComparator(): Comparator<AlbumEntry> {
        return compareBy<AlbumEntry> { albumBucketRank(it.title) }
            .thenBy { SortKeyHelper.keyOf(it.title) }
            .thenBy { SortKeyHelper.keyOf(it.artist) }
            .thenBy { it.year }
            .thenBy { it.key }
    }

    private fun resolveAlbumArtist(songs: List<MusicScanner.MusicFile>): String {
        if (songs.isEmpty()) return "未知艺术家"
        val candidates = songs.asSequence()
            .map { it.albumArtist.trim().ifBlank { it.artistGroup().trim() } }
            .filter { it.isNotBlank() }
            .toList()
        if (candidates.isEmpty()) return "未知艺术家"
        val picked = candidates.groupingBy { it }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length })
            ?.key
            .orEmpty()
        return ArtistUtils.displayArtists(picked.ifBlank { candidates.first() })
    }

    private fun sortedAlbumSongs(songs: List<MusicScanner.MusicFile>): List<MusicScanner.MusicFile> {
        return songs.sortedWith(
            compareBy<MusicScanner.MusicFile> { it.discNumber.coerceAtLeast(1) }
                .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { SortKeyHelper.keyOf(it.title) }
                .thenBy { it.path }
        )
    }

    private fun albumKey(title: String, artist: String): String {
        return title.trim() + "\u0001" + artist.trim()
    }

    private fun currentAlbumKey(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val cached = albumEntries.firstOrNull { entry -> entry.songs.any { it.path == path } }
        if (cached != null) return cached.key
        val rawFiles = visibleLibraryFiles(ScanResultHolder.result?.files ?: emptyList())
        return buildAlbumEntries(rawFiles).firstOrNull { entry -> entry.songs.any { it.path == path } }?.key
    }

    private fun showAlbumSortSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_sort, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)
        dialog.setOnShowListener {
            dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        view.findViewById<View>(R.id.rowMethodImportDate).visibility = View.GONE
        view.findViewById<View>(R.id.rowMethodArtistAlbum).visibility = View.GONE
        view.findViewById<View>(R.id.rowMethodPlayCount).visibility = View.GONE
        (view.findViewById<View>(R.id.rowMethodTitle) as? ViewGroup)
            ?.let { row ->
                (row.getChildAt(0) as? TextView)?.text = "专辑名"
            }

        val checkMethodTitle = view.findViewById<View>(R.id.checkMethodTitle)
        val checkAsc = view.findViewById<View>(R.id.checkOrderAsc)
        val checkDesc = view.findViewById<View>(R.id.checkOrderDesc)

        fun refresh() {
            checkMethodTitle.visibility = View.VISIBLE
            val order = SortSettings.getAlbumOrder(this)
            checkAsc.visibility = if (order == SortSettings.Order.ASC) View.VISIBLE else View.INVISIBLE
            checkDesc.visibility = if (order == SortSettings.Order.DESC) View.VISIBLE else View.INVISIBLE
        }
        refresh()

        view.findViewById<View>(R.id.rowOrderAsc).setOnClickListener {
            runThrottledSortAction {
                if (SortSettings.getAlbumOrder(this) != SortSettings.Order.ASC) {
                    SortSettings.setAlbumOrder(this, SortSettings.Order.ASC)
                    refreshAlbumsPage()
                    setupAlbumAlphabetSidebar(albumEntries)
                    refresh()
                }
            }
        }
        view.findViewById<View>(R.id.rowOrderDesc).setOnClickListener {
            runThrottledSortAction {
                if (SortSettings.getAlbumOrder(this) != SortSettings.Order.DESC) {
                    SortSettings.setAlbumOrder(this, SortSettings.Order.DESC)
                    refreshAlbumsPage()
                    setupAlbumAlphabetSidebar(albumEntries)
                    refresh()
                }
            }
        }

        dialog.show()
    }

    private fun showAlbumSheet(album: AlbumEntry) {
        val dialog = BottomSheetDialog(this)
        albumSheetDialog?.dismiss()
        albumSheetDialog = dialog
        val view = layoutInflater.inflate(R.layout.bottom_sheet_album, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)
        dialog.setOnDismissListener {
            if (albumSheetDialog === dialog) {
                albumSheetDialog = null
            }
        }
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog).findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT)
                val statusTop = ViewCompat.getRootWindowInsets(window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
                val coord = sheet.parent as? View
                val parentHeight = coord?.height ?: resources.displayMetrics.heightPixels
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

        ListenStats.load(this)
        val listenMap = ListenStats.snapshot()
        val listenedMs = album.songs.sumOf { listenMap[it.path] ?: 0L }
        listenedView.text = if (listenedMs > 0L) formatListenDuration(listenedMs) else "0 秒"

        tracksContainer.removeAllViews()
        val orderedSongs = sortedAlbumSongs(album.songs)
        val discs = orderedSongs.groupBy { it.discNumber.coerceAtLeast(1) }
        val showDiscHeader = discs.keys.size > 1 || discs.keys.any { it > 1 }

        discs.toSortedMap().forEach { (discNo, songs) ->
            if (showDiscHeader) {
                tracksContainer.addView(buildDiscHeader(discNo, songs.sumOf { it.duration }))
            }
            songs.forEachIndexed { indexInDisc, song ->
                tracksContainer.addView(
                    buildAlbumSongRow(
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

    private fun buildDiscHeader(discNumber: Int, durationMs: Long): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
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
        orderedSongs: List<MusicScanner.MusicFile>,
        song: MusicScanner.MusicFile,
        absoluteIndex: Int,
        indexInDisc: Int
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            // 不要 android.R.drawable.list_selector_background——那个在某些设备上是深黄/橙色。
            // 用主题里的 Material 水波纹反馈，按压时才淡入，松开即消失，不留背景色
            val ta = context.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackground)
            )
            background = ta.getDrawable(0)
            ta.recycle()
        }

        row.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            text = when {
                song.trackNumber > 0 -> song.trackNumber.toString()
                else -> (indexInDisc + 1).toString()
            }
            textSize = 13f
            setTextColor(0xFF000000.toInt())
        })

        val isCurrent = song.path == currentDisplayPath()
        val titleColor = if (isCurrent) 0xFF1565C0.toInt() else 0xFF111111.toInt()
        val subColor = if (isCurrent) 0xFF1565C0.toInt() else 0xFF777777.toInt()
        val timeColor = if (isCurrent) 0xFF1565C0.toInt() else 0xFF666666.toInt()

        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = song.title
                textSize = 15f
                setTextColor(titleColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                val subArtist = ArtistUtils.displayArtists(song.artist)
                text = subArtist
                textSize = 12f
                setTextColor(subColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        })

        row.addView(TextView(this).apply {
            text = SongAdapter.formatDuration(song.duration)
            textSize = 12f
            setTextColor(timeColor)
        })

        row.setOnClickListener {
            val mode = PlaybackSettings.getPreferredMode(this)
            val (queue, startIdx) = buildQueueForMode(orderedSongs, absoluteIndex, mode)
            PlaybackManager.playQueue(
                context = this,
                files = queue,
                startIndex = startIdx,
                mode = mode,
                sourceList = orderedSongs,
                sourceName = "专辑：${orderedSongs.firstOrNull()?.album?.trim().orEmpty().ifBlank { "未知专辑" }}"
            )
        }
        row.setOnLongClickListener {
            SongActionSheet.show(this, song)
            true
        }
        AppFont.applyTo(row)
        return row
    }

    private fun formatCompactDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${s}秒"
            else -> "${s}秒"
        }
    }

    /**
     * 聚合专辑里出现的所有独立艺术家（按歌曲顺序去重），
     * 全部能显示就显示全部；超过 3 位则展示前 2 位并追加"等 N 位"。
     * 单行显示由 XML 的 singleLine/ellipsize 兜底。
     */
    private fun buildAlbumArtistsLabel(album: AlbumEntry): String {
        val unique = LinkedHashSet<String>()
        for (song in album.songs) {
            for (name in ArtistUtils.splitArtists(song.artist)) {
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) unique.add(trimmed)
            }
        }
        if (unique.isEmpty()) {
            return album.artist.ifBlank { "未知艺术家" }
        }
        val list = unique.toList()
        return if (list.size <= 3) {
            list.joinToString(" / ")
        } else {
            val shown = list.take(2).joinToString(" / ")
            val rest = list.size - 2
            "$shown 等${rest}位"
        }
    }

    private fun leaderboardSubtitle(
        method: SortSettings.LeaderboardMethod,
        dateFilter: SortSettings.DateFilter
    ): String {
        val dateLabel = dateFilter.label
        return when (method) {
            SortSettings.LeaderboardMethod.SONG_TIME -> "${dateLabel}播放次数"
            SortSettings.LeaderboardMethod.LISTEN_DURATION -> "${dateLabel}播放时长"
            SortSettings.LeaderboardMethod.ARTIST_COUNT -> "${dateLabel}歌手播放次数"
            SortSettings.LeaderboardMethod.RECENT_PLAY -> "${dateLabel}最近播放"
        }
    }


    // ============================================================
    // 文件夹页 / 文件夹详情
    // ============================================================

    private fun refreshFoldersPage() {
        val rawFiles = ScanResultHolder.result?.files ?: emptyList()
        val entries = buildFolderEntries(rawFiles)
        folderEntries = entries
        findViewById<TextView>(R.id.tabFolderCount).text = entries.size.toString()

        rv.visibility = View.GONE
        rvAlbums.visibility = View.GONE
        rvArtists.visibility = View.GONE
        tabPlaceholder.visibility = View.GONE
        albumEmptyView.visibility = View.GONE
        artistEmptyView.visibility = View.GONE
        rvFolders.visibility = if (entries.isNotEmpty()) View.VISIBLE else View.GONE
        folderEmptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        if (folderAdapter == null) {
            folderAdapter = FolderAdapter(
                initialItems = entries,
                onItemClick = { _, folder -> showFolderDetail(folder.key) },
                onItemLongClick = { _, folder -> showFolderActionSheet(folder) }
            ).also {
                rvFolders.layoutManager = LinearLayoutManager(this)
                rvFolders.adapter = it
                rvFolders.setHasFixedSize(true)
                rvFolders.setItemViewCacheSize(20)
                (rvFolders.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
                rvFolders.itemAnimator?.moveDuration = 220
                rvFolders.itemAnimator?.changeDuration = 120
            }
        }
        folderAdapter?.updateItems(entries)
    }

    private fun scrollFoldersToKey(key: String, flash: Boolean) {
        val idx = folderEntries.indexOfFirst { it.key == key }
        if (idx < 0) return
        rvFolders.stopScroll()
        (rvFolders.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(idx, 0)
            ?: rvFolders.scrollToPosition(idx)
        if (flash) rvFolders.post { folderAdapter?.flashKey(key) }
    }

    private fun buildFolderEntries(files: List<MusicScanner.MusicFile>): List<FolderEntry> {
        if (files.isEmpty()) return emptyList()
        val grouped = linkedMapOf<String, MutableList<MusicScanner.MusicFile>>()
        for (file in files) {
            val key = folderKeyFor(file)
            if (key.isBlank()) continue
            grouped.getOrPut(key) { ArrayList() }.add(file)
        }

        val hiddenKeys = FolderVisibilityStore.hiddenKeys(this)
        val entries = grouped.map { (key, songsRaw) ->
            val songs = songsRaw.distinctBy { it.path }
            val latest = latestSongInFolder(songs)
            val name = folderNameFor(songs.firstOrNull(), key)
            FolderEntry(
                key = key,
                name = name,
                displayPath = folderDisplayPath(songs.firstOrNull(), key),
                songs = songs,
                coverPath = latest?.path.orEmpty(),
                totalDurationMs = songs.sumOf { it.duration },
                hidden = key in hiddenKeys
            )
        }.sortedWith(
            compareBy<FolderEntry> { SortKeyHelper.keyOf(it.name) }
                .thenBy { it.displayPath }
        )

        return if (SortSettings.getFolderOrder(this) == SortSettings.Order.ASC) entries else entries.reversed()
    }

    private fun visibleLibraryFiles(files: List<MusicScanner.MusicFile>): List<MusicScanner.MusicFile> {
        if (files.isEmpty()) return emptyList()
        val hiddenKeys = FolderVisibilityStore.hiddenKeys(this)
        if (hiddenKeys.isEmpty()) return files
        return files.filterNot { folderKeyFor(it) in hiddenKeys }
    }

    private fun setupFolderDetailAlphabetSidebar(files: List<MusicScanner.MusicFile>) {
        val sidebar = findViewById<LinearLayout>(R.id.alphabetSidebarFolderDetail)
        val bubble = findViewById<TextView>(R.id.alphabetBubbleFolderDetail)
        val rvDetail = findViewById<RecyclerView>(R.id.rvFolderDetail)
        val method = SortSettings.getMethod(this)
        setupAlphabetSidebarFor(sidebar, bubble, rvDetail, files, method)
    }

    private fun folderKeyFor(file: MusicScanner.MusicFile): String {
        if (file.folderPath.isNotBlank()) return file.folderPath
        val path = file.path
        if (path.startsWith("content://", ignoreCase = true)) return path.substringBeforeLast('/', path)
        return java.io.File(path).parent.orEmpty()
    }

    private fun folderNameFor(file: MusicScanner.MusicFile?, key: String): String {
        file?.folderName?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        if (key.startsWith("content://", ignoreCase = true)) return "文件夹"
        return java.io.File(key).name.ifBlank { "未知文件夹" }
    }

    private fun folderDisplayPath(file: MusicScanner.MusicFile?, key: String): String {
        file?.folderPath?.takeIf { it.isNotBlank() }?.let {
            return if (it.startsWith("content://", ignoreCase = true)) (file.folderName.ifBlank { "文件夹" }) else it
        }
        return key
    }

    private fun latestSongInFolder(songs: List<MusicScanner.MusicFile>): MusicScanner.MusicFile? {
        return songs.maxWithOrNull(
            compareBy<MusicScanner.MusicFile> { it.dateAddedSec }
                .thenBy { it.path }
        )
    }

    private fun showFolderActionSheet(folder: FolderEntry) {
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sheet_rounded_bg)
            setPadding(dp(20), dp(8), dp(20), dp(18))
        }
        root.addView(View(this).apply {
            setBackgroundResource(R.drawable.sheet_handle_bg)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = dp(2)
                bottomMargin = dp(12)
            }
        })
        root.addView(TextView(this).apply {
            text = folder.name
            textSize = 16f
            setTextColor(0xFF000000.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        })
        if (folder.hidden) {
            root.addView(makeMultiActionRow(R.drawable.ic_show_source_24, "显示", 0xFF000000.toInt()) {
                dialog.dismiss()
                setFolderHidden(folder.key, hidden = false)
            })
        } else {
            root.addView(makeMultiActionRow(R.drawable.ic_hide_source_24, "隐藏", 0xFF000000.toInt()) {
                dialog.dismiss()
                setFolderHidden(folder.key, hidden = true)
            })
        }
        AppFont.applyTo(root)
        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.show()
    }

    private fun setFolderHidden(folderKey: String, hidden: Boolean) {
        FolderVisibilityStore.setHidden(this, folderKey, hidden)
        reloadLibraryAfterFolderVisibilityChanged()
        val label = if (hidden) "已隐藏文件夹" else "已显示文件夹"
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        rvFolders.post { folderAdapter?.flashKey(folderKey) }
    }

    private fun reloadLibraryAfterFolderVisibilityChanged() {
        val result = ScanResultHolder.result ?: return
        val rawFiles = result.files
        val visibleFiles = visibleLibraryFiles(rawFiles)
        sortedFiles = applySortOrder(visibleFiles)
        adapter?.updateItems(sortedFiles)
        songsAdapterRef = adapter
        syncSongAdaptersTrailingMode()
        adapter?.currentPath = currentDisplayPath()
        setupAlphabetSidebar(sortedFiles)
        updateLibrarySummaryCounts(visibleFiles)
        refreshFoldersPage()
        when (currentTabKind) {
            TabKind.ALBUM -> {
                refreshAlbumsPage()
                setupAlbumAlphabetSidebar(albumEntries)
            }
            TabKind.ARTIST -> {
                refreshArtistsPage()
                setupArtistAlphabetSidebar(artistEntries)
            }
            TabKind.GENRE -> {
                tabPlaceholder.visibility = View.VISIBLE
                tvPlaceholder.text = "页面开发中"
            }
            TabKind.FOLDER -> refreshFoldersPage()
            TabKind.ALL -> {}
        }
        if (folderDetailVisible) populateFolderDetail()
        populateFavorites()
        if (leaderboardVisible) populateLeaderboard()
        if (playlistDetailVisible) populatePlaylistDetail() else populateUserPlaylists()
        updateLocateButtons(currentDisplayPath())
    }

    private fun showFolderDetail(folderKey: String) {
        if (multiSelectMode) exitMultiSelectMode()
        currentFolderKey = folderKey
        folderDetailVisible = true
        selectNav(NavKind.SONGS)
        songsContainer.visibility = View.GONE
        navPlaceholder.visibility = View.GONE
        profileContainer.visibility = View.GONE
        findViewById<View>(R.id.folderDetailView).visibility = View.VISIBLE
        populateFolderDetail()
        updateLocateButtons(currentDisplayPath())
    }

    private fun hideFolderDetail(showList: Boolean = true) {
        if (multiSelectMode) exitMultiSelectMode()
        findViewById<View>(R.id.folderDetailView).visibility = View.GONE
        folderDetailVisible = false
        currentFolderKey = null
        currentFolderSongs = emptyList()
        setupFolderDetailAlphabetSidebar(emptyList())
        fabLocateFolder.visibility = View.GONE
        if (showList && currentNavKind == NavKind.SONGS) {
            songsContainer.visibility = View.VISIBLE
            selectTab(TabKind.FOLDER, animate = false)
        }
        updateLocateButtons(currentDisplayPath())
    }

    private fun populateFolderDetail() {
        val key = currentFolderKey ?: return
        val entry = buildFolderEntries(ScanResultHolder.result?.files ?: emptyList())
            .firstOrNull { it.key == key }
        val rv = findViewById<RecyclerView>(R.id.rvFolderDetail)
        val empty = findViewById<View>(R.id.folderDetailEmpty)
        val topCover = findViewById<ShapeableImageView>(R.id.ivFolderDetailTopCover)
        val titleTv = findViewById<TextView>(R.id.tvFolderDetailTitle)
        val metaTv = findViewById<TextView>(R.id.tvFolderDetailMeta)

        if (entry == null) {
            folderDetailAdapter?.updateItems(emptyList())
            rv.visibility = View.GONE
            empty.visibility = View.VISIBLE
            topCover.setImageResource(R.drawable.ic_folder_24)
            titleTv.text = "文件夹"
            metaTv.visibility = View.GONE
            setupFolderDetailAlphabetSidebar(emptyList())
            return
        }

        titleTv.text = entry.name
        val files = applySortOrder(entry.songs)
        currentFolderSongs = files

        if (files.isEmpty()) {
            folderDetailAdapter?.updateItems(emptyList())
            rv.visibility = View.GONE
            empty.visibility = View.VISIBLE
            topCover.setImageResource(R.drawable.ic_folder_24)
            metaTv.visibility = View.GONE
            setupFolderDetailAlphabetSidebar(emptyList())
            return
        }

        rv.visibility = View.VISIBLE
        empty.visibility = View.GONE

        metaTv.text = formatPlaylistHeaderMeta(files)
        metaTv.visibility = View.VISIBLE

        val latest = latestSongInFolder(files)
        if (latest != null) {
            CoverLoader.load(topCover, latest.path, R.drawable.ic_folder_24)
        } else {
            topCover.setImageResource(R.drawable.ic_folder_24)
        }

        val adapter = folderDetailAdapter ?: SongAdapter(
            initialItems = files,
            onItemClick = { position, _ ->
                val mode = PlaybackSettings.getPreferredMode(this)
                val (queue, startIdx) = buildQueueForMode(currentFolderSongs, position, mode)
                val sourceName = currentFolderKey
                    ?.let { keyNow -> buildFolderEntries(ScanResultHolder.result?.files ?: emptyList()).firstOrNull { it.key == keyNow }?.name }
                    ?: "文件夹"
                PlaybackManager.playQueue(this, queue, startIdx, mode, currentFolderSongs, sourceName)
            },
            onItemLongClick = { _, file ->
                SongActionSheet.show(this, file)
            }
        ).also {
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = it
            rv.setHasFixedSize(true)
            rv.setItemViewCacheSize(20)
            folderDetailAdapter = it
        }
        adapter.updateItems(files)
        syncSongAdaptersTrailingMode()
        adapter.currentPath = currentDisplayPath()
        setupFolderDetailAlphabetSidebar(files)
        updateLocateButtons(currentDisplayPath())
    }

    private fun openFolderPicker() {
        if (folderImportInProgress) {
            Toast.makeText(this, "正在扫描当前文件夹，请稍后", Toast.LENGTH_SHORT).show()
            return
        }
        folderPickerLauncher.launch(null)
    }

    private fun importFolderFromTree(uri: Uri) {
        if (folderImportInProgress) {
            Toast.makeText(this, "正在扫描当前文件夹，请稍后", Toast.LENGTH_SHORT).show()
            return
        }
        folderImportInProgress = true
        showFolderImportProgressDialog()
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        lifecycleScope.launch {
            try {
                val scanned = withContext(Dispatchers.IO) {
                    MusicScanner.scanDocumentTree(this@SongListActivity, uri) { progress ->
                        withContext(Dispatchers.Main) {
                            updateFolderImportProgress(progress)
                        }
                    }
                }
                if (scanned.files.isEmpty()) {
                    Toast.makeText(this@SongListActivity, "该文件夹内没有支持的音频文件", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val existing = ScanResultHolder.result?.files ?: emptyList()
                val merged = linkedMapOf<String, MusicScanner.MusicFile>()
                existing.forEach { merged[it.path] = it }
                scanned.files.forEach { merged[it.path] = it }
                val files = merged.values.toList()
                val result = MusicScanner.ScanResult(
                    files = files,
                    formatCounts = files.groupingBy { it.format }.eachCount()
                )
                ScanResultHolder.result = result
                ScanCache.save(this@SongListActivity, result)
                applyLibraryResultToUi(result)
                selectTab(TabKind.FOLDER, animate = false)
                val newKey = scanned.files.firstOrNull()?.folderPath ?: uri.toString()
                rvFolders.post {
                    val idx = folderEntries.indexOfFirst { it.key == newKey }
                    if (idx >= 0) {
                        (rvFolders.layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(idx, 0)
                            ?: rvFolders.scrollToPosition(idx)
                        folderAdapter?.flashKey(newKey)
                    }
                }
                Toast.makeText(this@SongListActivity, "已添加 ${scanned.files.size} 首歌曲", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this@SongListActivity, "文件夹扫描失败，请重新选择文件夹", Toast.LENGTH_SHORT).show()
            } finally {
                folderImportInProgress = false
                hideFolderImportProgressDialog()
            }
        }
    }

    /**
     * 我的页 - 媒体库 - 扫描媒体库：
     *   - 使用 showLibraryRescanDialog() 展示一个跟首次扫描页 (ScanActivity) 完全同款的
     *     居中弹窗：半透明遮罩 + 中央白色圆角卡 + 标题「扫描媒体库」+ 转圈 + 文件名滚动 +
     *     底部「确定」按钮（扫描中灰、扫完蓝可点）。区别是这里不切活动页，弹窗就盖在
     *     SongListActivity 上面，关闭后留在原页面。
     *   - 扫描系统 Music 目录 + 所有已 take 持久权限的 SAF 文件夹
     *   - 合并结果（按 path 去重）写回 ScanResultHolder 与 ScanCache，刷新整个 UI
     *   - 与单文件夹导入复用的 BottomSheetDialog 互不影响；两套状态分开维护
     *
     * 重要：手动添加文件夹的持久 URI 权限会被系统记到 contentResolver.persistedUriPermissions
     * 里。对每个 isReadPermission 的 URI 重新跑一遍 scanDocumentTree，就能把所有"用户曾经
     * 添加过"的文件夹刷新一次。
     */
    private fun rescanMediaLibrary() {
        if (folderImportInProgress) {
            Toast.makeText(this, "正在扫描中，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        folderImportInProgress = true
        showLibraryRescanDialog()

        lifecycleScope.launch {
            try {
                val scanned = withContext(Dispatchers.IO) {
                    // LinkedHashMap 保留插入顺序，按 path 去重——SAF 文件夹先扫，再扫系统目录；
                    // 系统目录用 putIfAbsent，避免覆盖手动添加文件夹的 folderName/folderPath
                    val merged = linkedMapOf<String, MusicScanner.MusicFile>()

                    val persistedTreeUris = runCatching {
                        contentResolver.persistedUriPermissions
                            .filter { it.isReadPermission }
                            .map { it.uri }
                    }.getOrDefault(emptyList())

                    for (uri in persistedTreeUris) {
                        val tree = runCatching {
                            MusicScanner.scanDocumentTree(this@SongListActivity, uri) { progress ->
                                withContext(Dispatchers.Main) {
                                    onLibraryRescanProgress(progress)
                                }
                            }
                        }.getOrNull() ?: continue
                        tree.files.forEach { merged[it.path] = it }
                    }

                    val systemResult = MusicScanner.scanMusicFolder(this@SongListActivity) { progress ->
                        withContext(Dispatchers.Main) {
                            onLibraryRescanProgress(progress)
                        }
                    }
                    systemResult.files.forEach { merged.putIfAbsent(it.path, it) }

                    val files = merged.values.toList()
                    MusicScanner.ScanResult(
                        files = files,
                        formatCounts = files.groupingBy { it.format }.eachCount()
                    )
                }

                ScanResultHolder.result = scanned
                ScanCache.save(this@SongListActivity, scanned)
                applyLibraryResultToUi(scanned)
                // 注意：扫描完成后不自动关闭弹窗，跟首次扫描页保持一致——
                // 用户看到结果列表 + 蓝色「确定」按钮，自己点了才关闭
                finishLibraryRescanDialog(totalFound = scanned.files.size)
            } catch (_: Exception) {
                Toast.makeText(this@SongListActivity, "扫描失败，请稍后重试", Toast.LENGTH_SHORT).show()
                // 失败兜底：把弹窗也关掉，免得卡在扫描中状态
                dismissLibraryRescanDialog()
            } finally {
                folderImportInProgress = false
            }
        }
    }

    /** 构建并显示「扫描媒体库」专用的居中弹窗（直接 inflate activity_scan.xml）。 */
    private fun showLibraryRescanDialog() {
        // 老弹窗如果还在（不应该发生，因为 folderImportInProgress 拦了），先收掉
        libraryRescanDialog?.dismiss()
        libraryRescanFinished = false

        val dialog = android.app.Dialog(this, R.style.LibraryRescanDialog)
        // 复用首次扫描页的整张布局：FrameLayout(#80000000 遮罩) > LinearLayout 卡片 >
        // (header[标题+转圈], ScrollView[文件名列表], 底部确定按钮)
        val view = layoutInflater.inflate(R.layout.activity_scan, null, false)

        // 标题改成「扫描媒体库」——其它部件直接复用 activity_scan.xml 里的 id
        view.findViewById<TextView>(R.id.tvScanTitle)?.text = "扫描媒体库"

        libraryRescanList = view.findViewById(R.id.scanList)
        libraryRescanScroll = view.findViewById(R.id.scanScroll)
        libraryRescanSpinner = view.findViewById(R.id.scanSpinner)
        libraryRescanConfirm = view.findViewById(R.id.btnScanConfirm)

        // 卡片内文件名列表区域：钉一个固定上限（屏幕高的 52%），跟 ScanActivity 一致；
        // 这样列表自己滚，外层卡片不会随内容增长不断 re-layout。
        val maxScrollH = (resources.displayMetrics.heightPixels * 0.52f).toInt()
        libraryRescanScroll?.post {
            val scroll = libraryRescanScroll ?: return@post
            scroll.layoutParams?.height = maxScrollH
            scroll.requestLayout()
        }

        libraryRescanConfirm?.setOnClickListener {
            // 扫描中点确定无效；扫完后再点才会关闭
            if (!libraryRescanFinished) return@setOnClickListener
            dismissLibraryRescanDialog()
        }

        // 不允许返回键 / 点空白处取消（扫描进行中半路退出会造成 IO 协程 cancel）。
        // 扫描完成后不主动 dismiss，由用户点确定关闭。
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(view)
        AppFont.applyTo(view)

        // 让 Dialog 的 Window 铺满屏幕，背景透明：layout 自带 #80000000 遮罩
        dialog.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            w.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
        }

        dialog.show()
        libraryRescanDialog = dialog
    }

    /** 把一次扫描进度更新映射到「扫描媒体库」弹窗 UI 上（只关心 SCANNING 阶段的文件名）。 */
    private fun onLibraryRescanProgress(progress: MusicScanner.ScanProgress) {
        if (progress.stage != MusicScanner.ProgressStage.SCANNING) return
        val name = progress.fileName?.takeIf { it.isNotBlank() } ?: return
        appendLibraryRescanLine(name)
    }

    private fun appendLibraryRescanLine(name: String) {
        val list = libraryRescanList ?: return
        val tv = TextView(this).apply {
            text = name
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            val vgap = (resources.displayMetrics.density * 3f).toInt()
            setPadding(0, vgap, 0, vgap)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        list.addView(tv)
        // 大量文件时弹窗里会堆成几千行 TextView，列表自身的滚动还是能用，但内存吃紧。
        // 跟单文件夹导入弹窗一样，限制最多 80 行，老的从顶部弹出去。
        while (list.childCount > 80) {
            list.removeViewAt(0)
        }
        if (!libraryRescanFinished) {
            libraryRescanScroll?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    /** 扫描结束：转圈停掉、确定按钮从灰变蓝、空结果给一行提示。 */
    private fun finishLibraryRescanDialog(totalFound: Int) {
        libraryRescanFinished = true
        libraryRescanSpinner?.visibility = View.INVISIBLE

        if (totalFound == 0 && (libraryRescanList?.childCount ?: 0) == 0) {
            val empty = TextView(this).apply {
                text = "未找到歌曲。可以先在权限页允许音频权限，或手动添加文件夹。"
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                gravity = android.view.Gravity.CENTER
                val vpad = (resources.displayMetrics.density * 24f).toInt()
                setPadding(0, vpad, 0, vpad)
            }
            libraryRescanList?.addView(empty)
        }

        libraryRescanConfirm?.apply {
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.scan_btn_enabled_bg)
            setTextColor(0xFFFFFFFF.toInt())
        }

        // 扫描进行中我们用 setCancelable(false) 把返回键吞掉，避免 IO 协程被 cancel；
        // 扫完之后让返回键也能像点确定一样关闭弹窗，跟 ScanActivity.onBackPressed 一致
        libraryRescanDialog?.setCancelable(true)
    }

    private fun dismissLibraryRescanDialog() {
        libraryRescanDialog?.let { runCatching { it.dismiss() } }
        libraryRescanDialog = null
        libraryRescanList = null
        libraryRescanScroll = null
        libraryRescanSpinner = null
        libraryRescanConfirm = null
        libraryRescanFinished = false
    }

    private fun showFolderImportProgressDialog() {
        folderImportDialog?.dismiss()
        val dialog = BottomSheetDialog(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
            background = androidx.core.content.ContextCompat.getDrawable(this@SongListActivity, R.drawable.scan_card_bg)
        }

        val header = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        header.addView(TextView(this).apply {
            text = "扫描文件夹"
            textSize = 22f
            setTextColor(0xFF000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            )
        })
        header.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF1E5AC0.toInt())
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
        })
        card.addView(header)

        val status = TextView(this).apply {
            text = "正在读取文件夹…"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(12), 0, dp(10))
        }
        folderImportStatus = status
        card.addView(status)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // ScrollView 自己没有定义 LayoutParams 子类（继承自 FrameLayout），
            // Kotlin 不像 Java 那样允许通过子类名访问父类的嵌套类，所以这里直接用
            // FrameLayout.LayoutParams 才能编译通过。
            addView(list, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        folderImportList = list
        folderImportScroll = scroll
        card.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(230)
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })

        dialog.setContentView(card)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        dialog.show()
        folderImportDialog = dialog
    }

    private fun updateFolderImportProgress(progress: MusicScanner.ScanProgress) {
        val text = when (progress.stage) {
            MusicScanner.ProgressStage.DISCOVERING -> {
                val name = progress.fileName?.takeIf { it.isNotBlank() }
                if (name != null) "正在查找音频：${progress.stageCurrent} · $name" else "正在查找音频…"
            }
            MusicScanner.ProgressStage.SCANNING -> {
                val total = progress.stageTotal.coerceAtLeast(1)
                val name = progress.fileName?.takeIf { it.isNotBlank() }
                if (name != null) "正在解析：${progress.stageCurrent}/$total · $name" else "正在解析：${progress.stageCurrent}/$total"
            }
            MusicScanner.ProgressStage.ENRICHING -> "正在整理歌曲信息…"
        }
        folderImportStatus?.text = text
        if (progress.stage == MusicScanner.ProgressStage.SCANNING) {
            val name = progress.fileName?.takeIf { it.isNotBlank() } ?: return
            appendFolderImportLine("已解析 ${progress.stageCurrent}/${progress.stageTotal.coerceAtLeast(1)}  $name")
        }
    }

    private fun appendFolderImportLine(text: String) {
        val list = folderImportList ?: return
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF222222.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(4))
        }
        list.addView(tv)
        while (list.childCount > 80) {
            list.removeViewAt(0)
        }
        folderImportScroll?.post {
            folderImportScroll?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun hideFolderImportProgressDialog() {
        folderImportDialog?.dismiss()
        folderImportDialog = null
        folderImportStatus = null
        folderImportList = null
        folderImportScroll = null
    }

    private fun showFolderSortSheet() {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sheet_rounded_bg)
            setPadding(dp(24), dp(8), dp(24), dp(28))
        }
        container.addView(View(this).apply {
            setBackgroundResource(R.drawable.sheet_handle_bg)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(20)
            }
        })
        container.addView(TextView(this).apply {
            text = "排序方式"
            textSize = 15f
            setTextColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
        })

        fun row(label: String, checked: Boolean, onClick: () -> Unit): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
                    val d = it.getDrawable(0)
                    it.recycle()
                    d
                }
                setPadding(0, dp(10), 0, dp(10))
                addView(TextView(context).apply {
                    text = label
                    textSize = 14f
                    setTextColor(0xFF333333.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_check_24)
                    setColorFilter(0xFF000000.toInt())
                    visibility = if (checked) View.VISIBLE else View.INVISIBLE
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                })
                setOnClickListener { onClick() }
            }
        }

        fun rebuild() {
            if (container.childCount > 2) {
                container.removeViews(2, container.childCount - 2)
            }
            container.addView(row("文件名升序", SortSettings.getFolderOrder(this) == SortSettings.Order.ASC) {
                SortSettings.setFolderOrder(this, SortSettings.Order.ASC)
                refreshFoldersPage()
                dialog.dismiss()
            })
            container.addView(row("文件名降序", SortSettings.getFolderOrder(this) == SortSettings.Order.DESC) {
                SortSettings.setFolderOrder(this, SortSettings.Order.DESC)
                refreshFoldersPage()
                dialog.dismiss()
            })
        }
        rebuild()
        AppFont.applyTo(container)
        dialog.setContentView(container)
        dialog.setOnShowListener {
            dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        dialog.show()
    }

    private fun showFolderDetailSortSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_sort, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)

        dialog.setOnShowListener {
            dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val checkMethodTitle = view.findViewById<View>(R.id.checkMethodTitle)
        val checkMethodImportDate = view.findViewById<View>(R.id.checkMethodImportDate)
        val checkMethodArtistAlbum = view.findViewById<View>(R.id.checkMethodArtistAlbum)
        val checkMethodPlayCount = view.findViewById<View>(R.id.checkMethodPlayCount)
        val checkAsc = view.findViewById<View>(R.id.checkOrderAsc)
        val checkDesc = view.findViewById<View>(R.id.checkOrderDesc)

        fun refresh() {
            val method = SortSettings.getMethod(this)
            checkMethodTitle.visibility = if (method == SortSettings.Method.TITLE) View.VISIBLE else View.INVISIBLE
            checkMethodImportDate.visibility = if (method == SortSettings.Method.IMPORT_DATE) View.VISIBLE else View.INVISIBLE
            checkMethodArtistAlbum.visibility = if (method == SortSettings.Method.ARTIST_ALBUM) View.VISIBLE else View.INVISIBLE
            checkMethodPlayCount.visibility = if (method == SortSettings.Method.PLAY_COUNT) View.VISIBLE else View.INVISIBLE
            val order = SortSettings.getOrder(this)
            checkAsc.visibility = if (order == SortSettings.Order.ASC) View.VISIBLE else View.INVISIBLE
            checkDesc.visibility = if (order == SortSettings.Order.DESC) View.VISIBLE else View.INVISIBLE
        }
        refresh()

        fun pickMethod(method: SortSettings.Method) {
            if (SortSettings.getMethod(this) != method) {
                SortSettings.setMethod(this, method)
                applyAndReloadSort()
                populateFolderDetail()
                refresh()
            }
        }

        view.findViewById<View>(R.id.rowMethodTitle).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.Method.TITLE) }
        }
        view.findViewById<View>(R.id.rowMethodImportDate).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.Method.IMPORT_DATE) }
        }
        view.findViewById<View>(R.id.rowMethodArtistAlbum).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.Method.ARTIST_ALBUM) }
        }
        view.findViewById<View>(R.id.rowMethodPlayCount).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.Method.PLAY_COUNT) }
        }
        view.findViewById<View>(R.id.rowOrderAsc).setOnClickListener {
            runThrottledSortAction {
                if (SortSettings.getOrder(this) != SortSettings.Order.ASC) {
                    SortSettings.setOrder(this, SortSettings.Order.ASC)
                    applyAndReloadSort()
                    populateFolderDetail()
                    refresh()
                }
            }
        }
        view.findViewById<View>(R.id.rowOrderDesc).setOnClickListener {
            runThrottledSortAction {
                if (SortSettings.getOrder(this) != SortSettings.Order.DESC) {
                    SortSettings.setOrder(this, SortSettings.Order.DESC)
                    applyAndReloadSort()
                    populateFolderDetail()
                    refresh()
                }
            }
        }

        dialog.show()
    }

    private fun applyLibraryResultToUi(result: MusicScanner.ScanResult) {
        val rawFiles = result.files
        val files = visibleLibraryFiles(rawFiles)
        sortedFiles = applySortOrder(files)
        adapter?.updateItems(sortedFiles)
        songsAdapterRef = adapter
        syncSongAdaptersTrailingMode()
        setupAlphabetSidebar(sortedFiles)
        val emptyView = findViewById<View>(R.id.emptyView)
        if (rawFiles.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            songsContainer.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            if (!folderDetailVisible && currentNavKind == NavKind.SONGS) songsContainer.visibility = View.VISIBLE
        }
        updateLibrarySummaryCounts(files)
        refreshReplayGainSettingsUi()
        refreshQualityBadgeSettingsUi()
        when (currentTabKind) {
            TabKind.ALBUM -> {
                refreshAlbumsPage()
                setupAlbumAlphabetSidebar(albumEntries)
            }
            TabKind.ARTIST -> {
                refreshArtistsPage()
                setupArtistAlphabetSidebar(artistEntries)
            }
            TabKind.GENRE -> {
                tabPlaceholder.visibility = View.VISIBLE
                tvPlaceholder.text = "页面开发中"
            }
            TabKind.FOLDER -> refreshFoldersPage()
            TabKind.ALL -> {}
        }
        populateFavorites()
        if (leaderboardVisible) populateLeaderboard()
        if (playlistDetailVisible) populatePlaylistDetail() else populateUserPlaylists()
        if (folderDetailVisible) populateFolderDetail()
        updateLocateButtons(currentDisplayPath())
    }

    private fun updateLibrarySummaryCounts(files: List<MusicScanner.MusicFile>) {
        val albumCount = files.asSequence()
            .map {
                val album = it.album.trim()
                when {
                    album.isBlank() -> ""
                    it.albumArtist.isNotBlank() -> "${it.albumArtist}:$album"
                    else -> album
                }
            }
            .filter { it.isNotBlank() }
            .toSet()
            .size
        val artistCount = buildArtistEntries(files).size
        val rawFolderFiles = ScanResultHolder.result?.files ?: files
        val folderCount = buildFolderEntries(rawFolderFiles).size
        findViewById<TextView>(R.id.tabAllCount).text = files.size.toString()
        findViewById<TextView>(R.id.tabAlbumCount).text = albumCount.toString()
        findViewById<TextView>(R.id.tabArtistCount).text = artistCount.toString()
        findViewById<TextView>(R.id.tabFolderCount).text = folderCount.toString()
        findViewById<TextView>(R.id.tvSongCount).text = "${files.size} 首"
        findViewById<TextView>(R.id.tvLibraryAlbumCount).text = "${albumCount} 个"
        findViewById<TextView>(R.id.tvLibraryArtistCount).text = "${artistCount} 位"
        findViewById<TextView>(R.id.tvTotalDuration).text = formatTotalDuration(files)
        findViewById<TextView>(R.id.tvStorageUsed).text = formatStorageSize(files)
    }

    // ============================================================
    // 歌单子页 / 收藏夹 / 听歌排行
    // ============================================================

    // 供 SongActionSheet 在"添加到歌单"完成后调用：刷新当前可见的歌单 UI
    fun notifyPlaylistsChanged() {
        if (currentNavKind != NavKind.PLAYLIST) return
        if (playlistDetailVisible) {
            populatePlaylistDetail()
        } else {
            populateUserPlaylists()
        }
    }

    private fun showPlaylistGrid() {
        if (multiSelectMode) exitMultiSelectMode()
        findViewById<View>(R.id.playlistGridView).visibility = View.VISIBLE
        findViewById<View>(R.id.leaderboardView).visibility = View.GONE
        findViewById<View>(R.id.favoritesView).visibility = View.GONE
        findViewById<View>(R.id.playlistDetailView).visibility = View.GONE
        leaderboardVisible = false
        favoritesVisible = false
        playlistDetailVisible = false
        currentPlaylistId = null
        populateUserPlaylists()
        updateLocateButtons(currentDisplayPath())
    }

    private fun showPlaylistDetail(playlistId: String) {
        if (multiSelectMode) exitMultiSelectMode()
        currentPlaylistId = playlistId
        findViewById<View>(R.id.playlistGridView).visibility = View.GONE
        findViewById<View>(R.id.leaderboardView).visibility = View.GONE
        findViewById<View>(R.id.favoritesView).visibility = View.GONE
        findViewById<View>(R.id.playlistDetailView).visibility = View.VISIBLE
        leaderboardVisible = false
        favoritesVisible = false
        playlistDetailVisible = true
        populatePlaylistDetail()
        updateLocateButtons(currentDisplayPath())
    }

    private fun hidePlaylistDetail() {
        showPlaylistGrid()
    }

    // 新建歌单弹框：居中圆角卡片，输入歌单名。有 imeOptions=actionDone，回车也能提交。
    private fun showNewPlaylistDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_new_playlist, null)
        AppFont.applyTo(view)
        val et = view.findViewById<EditText>(R.id.etPlaylistName)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        fun commit() {
            val name = et.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(this, "请输入歌单名称", Toast.LENGTH_SHORT).show()
                return
            }
            PlaylistStore.create(this, name)
            dialog.dismiss()
            populateUserPlaylists()
        }

        view.findViewById<View>(R.id.btnPlaylistDialogCancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btnPlaylistDialogConfirm).setOnClickListener {
            commit()
        }
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                commit(); true
            } else false
        }

        dialog.setOnShowListener {
            et.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    // 按 PlaylistStore 当前状态重绘用户歌单条目。每个条目是一行小卡片：封面（最新加入的歌）+ 歌单名 + "X首"
    private fun populateUserPlaylists() {
        val container = findViewById<LinearLayout>(R.id.playlistItemsContainer) ?: return
        container.removeAllViews()
        val allFiles = ScanResultHolder.result?.files ?: emptyList()
        val byPath = allFiles.associateBy { it.path }

        val lists = PlaylistStore.all(this)
        for (playlist in lists) {
            val row = layoutInflater.inflate(R.layout.user_playlist_item, container, false)
            AppFont.applyTo(row)

            val cover = row.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivUserPlaylistCover)
            val nameTv = row.findViewById<TextView>(R.id.tvUserPlaylistName)
            val metaTv = row.findViewById<TextView>(R.id.tvUserPlaylistMeta)
            val moreBtn = row.findViewById<ImageButton>(R.id.btnUserPlaylistMore)

            nameTv.text = playlist.name
            metaTv.text = "${playlist.songs.size}首"

            // 封面：取最近加入该歌单的那首歌的封面；歌单为空就用占位图
            val latestPath = PlaylistStore.latestPath(this, playlist.id)
            val latestFile = latestPath?.let { byPath[it] }
            if (latestFile != null) {
                CoverLoader.load(cover, latestFile.path, R.drawable.music_note_24)
            } else {
                cover.setImageResource(R.drawable.music_note_24)
                cover.alpha = 0.5f
            }

            row.setOnClickListener { showPlaylistDetail(playlist.id) }
            moreBtn.setOnClickListener { showUserPlaylistMenu(playlist) }

            container.addView(row)
        }
    }

    // 用户歌单"更多"按钮：重命名 / 删除
    private fun showUserPlaylistMenu(playlist: PlaylistStore.Playlist) {
        val items = arrayOf("重命名", "删除歌单")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenamePlaylistDialog(playlist)
                    1 -> confirmDeletePlaylist(playlist)
                }
            }
            .show()
    }

    private fun showRenamePlaylistDialog(playlist: PlaylistStore.Playlist) {
        val view = layoutInflater.inflate(R.layout.dialog_new_playlist, null)
        AppFont.applyTo(view)
        view.findViewById<TextView>(R.id.btnPlaylistDialogCancel)
        val et = view.findViewById<EditText>(R.id.etPlaylistName)
        et.setText(playlist.name)
        et.setSelection(playlist.name.length)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        view.findViewById<View>(R.id.btnPlaylistDialogCancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btnPlaylistDialogConfirm).setOnClickListener {
            val newName = et.text?.toString()?.trim().orEmpty()
            if (newName.isBlank()) {
                Toast.makeText(this, "请输入歌单名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (PlaylistStore.rename(this, playlist.id, newName)) {
                dialog.dismiss()
                populateUserPlaylists()
                // 如果正好在详情页里重命名了当前歌单，也同步更新标题
                if (playlistDetailVisible && currentPlaylistId == playlist.id) {
                    findViewById<TextView>(R.id.tvPlaylistDetailTitle).text = newName
                }
            }
        }

        dialog.show()
    }

    private fun confirmDeletePlaylist(playlist: PlaylistStore.Playlist) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除歌单")
            .setMessage("确定要删除歌单「${playlist.name}」吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                PlaylistStore.delete(this, playlist.id)
                if (playlistDetailVisible && currentPlaylistId == playlist.id) {
                    hidePlaylistDetail()
                } else {
                    populateUserPlaylists()
                }
            }
            .show()
    }

    // 填充歌单详情页（RecyclerView + 头部封面/标题/副标题）
    private fun populatePlaylistDetail() {
        val playlistId = currentPlaylistId ?: return
        val playlist = PlaylistStore.get(this, playlistId)
        if (playlist == null) {
            hidePlaylistDetail()
            return
        }

        val rv = findViewById<RecyclerView>(R.id.rvPlaylistDetail)
        val empty = findViewById<View>(R.id.playlistDetailEmpty)
        val topCover = findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivPlaylistDetailTopCover)
        val titleTv = findViewById<TextView>(R.id.tvPlaylistDetailTitle)
        val metaTv = findViewById<TextView>(R.id.tvPlaylistDetailMeta)

        titleTv.text = playlist.name

        val allFiles = ScanResultHolder.result?.files ?: emptyList()
        val byPath = allFiles.associateBy { it.path }
        // 歌曲顺序走"收藏夹同步排序"：按用户在排序面板里选的方法/顺序排。
        // 默认 TITLE + ASC 时就是字母序；用户改成 PLAY_COUNT / ARTIST_ALBUM 时对应变化。
        val rawFiles = playlist.songs.mapNotNull { byPath[it.path] }
        val files = applyFavoritesSortOrder(rawFiles)
        currentPlaylistSongs = files

        if (files.isEmpty()) {
            playlistDetailAdapter?.updateItems(emptyList())
            rv.visibility = View.GONE
            empty.visibility = View.VISIBLE
            topCover.setImageResource(R.drawable.music_note_24)
            metaTv.text = formatPlaylistHeaderMeta(emptyList())
            metaTv.visibility = View.VISIBLE
            updateLocateButtons(currentDisplayPath())
            return
        }

        rv.visibility = View.VISIBLE
        empty.visibility = View.GONE

        metaTv.text = formatPlaylistHeaderMeta(files)
        metaTv.visibility = View.VISIBLE

        // 顶部小封面：用最新加入的歌
        val latest = PlaylistStore.latestPath(this, playlistId)?.let { byPath[it] }
            ?: files.firstOrNull()
        if (latest != null) {
            CoverLoader.load(topCover, latest.path, R.drawable.music_note_24)
        } else {
            topCover.setImageResource(R.drawable.music_note_24)
        }

        val adapter = playlistDetailAdapter ?: SongAdapter(
            initialItems = files,
            onItemClick = { position, _ ->
                val mode = PlaybackSettings.getPreferredMode(this)
                val (queue, startIdx) = buildQueueForMode(currentPlaylistSongs, position, mode)
                // 名字从 currentPlaylistId 拿实时的，避免用户切到别的歌单后 lambda 里还是老名字
                val sourceName = currentPlaylistId
                    ?.let { PlaylistStore.get(this, it)?.name }
                    ?: "歌单"
                PlaybackManager.playQueue(this, queue, startIdx, mode, currentPlaylistSongs, sourceName)
            },
            onItemLongClick = { _, file ->
                SongActionSheet.show(this, file, inPlaylistId = currentPlaylistId)
            }
        ).also {
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = it
            rv.setHasFixedSize(true)
            rv.setItemViewCacheSize(20)
            playlistDetailAdapter = it
        }
        adapter.updateItems(files)
        syncSongAdaptersTrailingMode()
        adapter.currentPath = currentDisplayPath()
        updateLocateButtons(currentDisplayPath())
    }

    private fun showLeaderboard() {
        if (multiSelectMode) exitMultiSelectMode()
        findViewById<View>(R.id.playlistGridView).visibility = View.GONE
        findViewById<View>(R.id.favoritesView).visibility = View.GONE
        findViewById<View>(R.id.playlistDetailView).visibility = View.GONE
        findViewById<View>(R.id.leaderboardView).visibility = View.VISIBLE
        favoritesVisible = false
        playlistDetailVisible = false
        currentPlaylistId = null
        leaderboardVisible = true
        populateLeaderboard()
        updateLocateButtons(currentDisplayPath())
    }

    private fun hideLeaderboard() {
        showPlaylistGrid()
    }

    private fun showFavorites() {
        if (multiSelectMode) exitMultiSelectMode()
        findViewById<View>(R.id.playlistGridView).visibility = View.GONE
        findViewById<View>(R.id.leaderboardView).visibility = View.GONE
        findViewById<View>(R.id.playlistDetailView).visibility = View.GONE
        findViewById<View>(R.id.favoritesView).visibility = View.VISIBLE
        leaderboardVisible = false
        playlistDetailVisible = false
        currentPlaylistId = null
        favoritesVisible = true
        populateFavorites()
        updateLocateButtons(currentDisplayPath())
    }

    private fun hideFavorites() {
        showPlaylistGrid()
    }

    private fun favoriteFilesFromLibrary(): List<MusicScanner.MusicFile> {
        val all = ScanResultHolder.result?.files ?: emptyList()
        if (all.isEmpty()) return emptyList()
        val byPath = all.associateBy { it.path }
        return FavoritesStore.entries(this)
            .mapNotNull { entry -> byPath[entry.path] }
    }

    private fun populateFavorites() {
        val rawFavorites = favoriteFilesFromLibrary()
        sortedFavorites = applyFavoritesSortOrder(rawFavorites)

        val empty = findViewById<View>(R.id.favEmpty)
        val topCover = findViewById<ShapeableImageView>(R.id.ivFavoritesTopCover)
        val favMeta = findViewById<TextView>(R.id.tvFavoritesMeta)

        if (sortedFavorites.isEmpty()) {
            favoritesAdapter?.updateItems(emptyList())
            rvFavorites.visibility = View.GONE
            empty.visibility = View.VISIBLE
            topCover.setImageResource(R.drawable.music_note_24)
            favMeta.text = formatPlaylistHeaderMeta(emptyList())
            favMeta.visibility = View.VISIBLE
            setupFavoritesSidebar(emptyList())
            updateLocateButtons(currentDisplayPath())
            return
        }

        rvFavorites.visibility = View.VISIBLE
        empty.visibility = View.GONE

        favMeta.text = formatPlaylistHeaderMeta(sortedFavorites)
        favMeta.visibility = View.VISIBLE

        val latestFavorite = FavoritesStore.latestPath(this)?.let { latestPath ->
            rawFavorites.firstOrNull { it.path == latestPath }
        } ?: sortedFavorites.firstOrNull()
        if (latestFavorite != null) {
            CoverLoader.load(topCover, latestFavorite.path, R.drawable.music_note_24)
        } else {
            topCover.setImageResource(R.drawable.music_note_24)
        }

        val currentAdapter = favoritesAdapter ?: SongAdapter(
            initialItems = sortedFavorites,
            onItemClick = { position, _ ->
                val mode = PlaybackSettings.getPreferredMode(this)
                val (queue, startIdx) = buildQueueForMode(sortedFavorites, position, mode)
                PlaybackManager.playQueue(this, queue, startIdx, mode, sortedFavorites, "收藏夹")
            },
            onItemLongClick = { _, file ->
                SongActionSheet.show(this, file)
            }
        ).also {
            rvFavorites.layoutManager = LinearLayoutManager(this)
            rvFavorites.adapter = it
            rvFavorites.setHasFixedSize(true)
            rvFavorites.setItemViewCacheSize(20)
            favoritesAdapter = it
        }

        currentAdapter.updateItems(sortedFavorites)
        syncSongAdaptersTrailingMode()
        currentAdapter.currentPath = currentDisplayPath()
        setupFavoritesSidebar(sortedFavorites)
        updateLocateButtons(currentDisplayPath())
    }

    private fun showFavoritesSortSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_sort, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)

        dialog.setOnShowListener {
            dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val rowImportDate = view.findViewById<View>(R.id.rowMethodImportDate)
        val checkMethodTitle = view.findViewById<View>(R.id.checkMethodTitle)
        val checkMethodArtistAlbum = view.findViewById<View>(R.id.checkMethodArtistAlbum)
        val checkMethodPlayCount = view.findViewById<View>(R.id.checkMethodPlayCount)
        val checkAsc = view.findViewById<View>(R.id.checkOrderAsc)
        val checkDesc = view.findViewById<View>(R.id.checkOrderDesc)

        rowImportDate.visibility = View.GONE

        fun refresh() {
            val method = SortSettings.getFavoritesMethod(this)
            checkMethodTitle.visibility =
                if (method == SortSettings.Method.TITLE) View.VISIBLE else View.INVISIBLE
            checkMethodArtistAlbum.visibility =
                if (method == SortSettings.Method.ARTIST_ALBUM) View.VISIBLE else View.INVISIBLE
            checkMethodPlayCount.visibility =
                if (method == SortSettings.Method.PLAY_COUNT) View.VISIBLE else View.INVISIBLE
            val order = SortSettings.getFavoritesOrder(this)
            checkAsc.visibility = if (order == SortSettings.Order.ASC) View.VISIBLE else View.INVISIBLE
            checkDesc.visibility = if (order == SortSettings.Order.DESC) View.VISIBLE else View.INVISIBLE
        }
        refresh()

        fun pickMethod(method: SortSettings.Method) {
            if (SortSettings.getFavoritesMethod(this) != method) {
                SortSettings.setFavoritesMethod(this, method)
                applyAndReloadFavorites()
                refresh()
            }
        }

        view.findViewById<View>(R.id.rowMethodTitle).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.Method.TITLE) }
        }
        view.findViewById<View>(R.id.rowMethodArtistAlbum).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.Method.ARTIST_ALBUM) }
        }
        view.findViewById<View>(R.id.rowMethodPlayCount).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.Method.PLAY_COUNT) }
        }
        view.findViewById<View>(R.id.rowOrderAsc).setOnClickListener {
            runThrottledSortAction {
                if (SortSettings.getFavoritesOrder(this) != SortSettings.Order.ASC) {
                    SortSettings.setFavoritesOrder(this, SortSettings.Order.ASC)
                    applyAndReloadFavorites()
                    refresh()
                }
            }
        }
        view.findViewById<View>(R.id.rowOrderDesc).setOnClickListener {
            runThrottledSortAction {
                if (SortSettings.getFavoritesOrder(this) != SortSettings.Order.DESC) {
                    SortSettings.setFavoritesOrder(this, SortSettings.Order.DESC)
                    applyAndReloadFavorites()
                    refresh()
                }
            }
        }

        dialog.show()
    }

    private fun applyAndReloadFavorites() {
        populateFavorites()
        // 如果排序面板是从用户歌单详情页打开的，也要同步刷新那个页面
        if (playlistDetailVisible) populatePlaylistDetail()
    }

    private fun currentSongTrailingMode(): SongAdapter.TrailingMode {
        return if (SortSettings.getMethod(this) == SortSettings.Method.PLAY_COUNT) {
            SongAdapter.TrailingMode.PLAY_COUNT
        } else {
            SongAdapter.TrailingMode.DURATION
        }
    }

    private fun currentFavoritesTrailingMode(): SongAdapter.TrailingMode {
        return if (SortSettings.getFavoritesMethod(this) == SortSettings.Method.PLAY_COUNT) {
            SongAdapter.TrailingMode.PLAY_COUNT
        } else {
            SongAdapter.TrailingMode.DURATION
        }
    }

    private fun syncSongAdaptersTrailingMode() {
        ListenStats.load(this)
        val countMap = ListenStats.countSnapshot()
        adapter?.setPlayCountMap(countMap)
        adapter?.setTrailingMode(currentSongTrailingMode())
        favoritesAdapter?.setPlayCountMap(countMap)
        favoritesAdapter?.setTrailingMode(currentFavoritesTrailingMode())
        // 歌单详情页的 adapter 和收藏夹同构，沿用一样的 trailing mode
        playlistDetailAdapter?.setPlayCountMap(countMap)
        playlistDetailAdapter?.setTrailingMode(currentFavoritesTrailingMode())
        folderDetailAdapter?.setPlayCountMap(countMap)
        folderDetailAdapter?.setTrailingMode(currentSongTrailingMode())
    }

    private fun populateLeaderboard() {
        ListenStats.load(this)
        val rvLb = findViewById<RecyclerView>(R.id.rvLeaderboard)
        val empty = findViewById<View>(R.id.lbEmpty)
        val topCover = findViewById<ShapeableImageView>(R.id.ivLeaderboardTopCover)
        val titleTv = findViewById<TextView>(R.id.tvLeaderboardTitle)
        val headerMetaTv = findViewById<TextView>(R.id.tvLeaderboardSubtitle)
        titleTv.text = "听歌排行"

        val all = ScanResultHolder.result?.files ?: emptyList()
        val method = SortSettings.getLeaderboardMethod(this)
        val order = SortSettings.getLeaderboardOrder(this)
        val dateFilter = SortSettings.getLeaderboardDateFilter(this)
        val sinceMs = SortSettings.dateFilterStart(dateFilter)

        val timeMap = ListenStats.timeSince(sinceMs)
        val countMap = ListenStats.countSince(sinceMs)
        val recentMap = ListenStats.recentSince(sinceMs)

        val rows: List<LeaderboardAdapter.Row>
        val topFile: MusicScanner.MusicFile?
        val headerFiles: List<MusicScanner.MusicFile>
        val onClick: (Int, LeaderboardAdapter.Row) -> Unit

        when (method) {
            SortSettings.LeaderboardMethod.SONG_TIME -> {
                val candidates = all.filter { (countMap[it.path] ?: 0) > 0 }
                val withCount = candidates.map { f -> f to (countMap[f.path] ?: 0) }
                val sortedAll = if (order == SortSettings.Order.DESC) {
                    withCount.sortedWith(
                        compareByDescending<Pair<MusicScanner.MusicFile, Int>> { it.second }
                            .thenBy { SortKeyHelper.keyOf(it.first.title) }
                    )
                } else {
                    withCount.sortedWith(
                        compareBy<Pair<MusicScanner.MusicFile, Int>> { it.second }
                            .thenBy { SortKeyHelper.keyOf(it.first.title) }
                    )
                }
                val sorted = sortedAll.take(100)

                rows = sorted.map { (f, n) ->
                    LeaderboardAdapter.Row(
                        file = f,
                        primary = f.title,
                        subtitle = ArtistUtils.displayArtists(f.artist),
                        trailing = "$n 次"
                    )
                }
                headerFiles = sorted.map { it.first }
                topFile = headerFiles.firstOrNull()
                val queue = headerFiles
                onClick = { pos, _ ->
                    val mode = PlaybackSettings.getPreferredMode(this)
                    val (q, idx) = buildQueueForMode(queue, pos, mode)
                    PlaybackManager.playQueue(this, q, idx, mode, queue, "听歌排行")
                }
            }

            SortSettings.LeaderboardMethod.LISTEN_DURATION -> {
                val candidates = all.filter { (timeMap[it.path] ?: 0L) > 0L }
                val withTime = candidates.map { f -> f to (timeMap[f.path] ?: 0L) }
                val sortedAll = if (order == SortSettings.Order.DESC) {
                    withTime.sortedWith(
                        compareByDescending<Pair<MusicScanner.MusicFile, Long>> { it.second }
                            .thenBy { SortKeyHelper.keyOf(it.first.title) }
                    )
                } else {
                    withTime.sortedWith(
                        compareBy<Pair<MusicScanner.MusicFile, Long>> { it.second }
                            .thenBy { SortKeyHelper.keyOf(it.first.title) }
                    )
                }
                val sorted = sortedAll.take(100)

                rows = sorted.map { (f, t) ->
                    LeaderboardAdapter.Row(
                        file = f,
                        primary = f.title,
                        subtitle = ArtistUtils.displayArtists(f.artist),
                        trailing = formatListenDuration(t)
                    )
                }
                headerFiles = sorted.map { it.first }
                topFile = headerFiles.firstOrNull()
                val queue = headerFiles
                onClick = { pos, _ ->
                    val mode = PlaybackSettings.getPreferredMode(this)
                    val (q, idx) = buildQueueForMode(queue, pos, mode)
                    PlaybackManager.playQueue(this, q, idx, mode, queue, "听歌排行")
                }
            }

            SortSettings.LeaderboardMethod.ARTIST_COUNT -> {
                val candidates = all.filter { (countMap[it.path] ?: 0) > 0 }
                val artistMap = linkedMapOf<String, MutableList<MusicScanner.MusicFile>>()
                for (song in candidates) {
                    for (artist in ArtistUtils.splitArtists(song.artist)) {
                        artistMap.getOrPut(artist) { ArrayList() }.add(song)
                    }
                }
                val artistRows = artistMap.mapNotNull { (artist, songs) ->
                    val totalCount = songs.sumOf { countMap[it.path] ?: 0 }
                    if (totalCount <= 0) {
                        null
                    } else {
                        val rep = songs.maxWithOrNull(
                            compareBy<MusicScanner.MusicFile>({ countMap[it.path] ?: 0 })
                                .thenBy { timeMap[it.path] ?: 0L }
                        ) ?: songs.first()
                        ArtistAggregate(artist, songs, rep, totalCount)
                    }
                }
                val sortedAll = if (order == SortSettings.Order.DESC) {
                    artistRows.sortedWith(
                        compareByDescending<ArtistAggregate> { it.totalCount }
                            .thenBy { SortKeyHelper.keyOf(it.artist) }
                    )
                } else {
                    artistRows.sortedWith(
                        compareBy<ArtistAggregate> { it.totalCount }
                            .thenBy { SortKeyHelper.keyOf(it.artist) }
                    )
                }
                val sorted = sortedAll.take(100)

                rows = sorted.map {
                    LeaderboardAdapter.Row(
                        file = it.rep,
                        primary = it.artist,
                        subtitle = "",
                        trailing = "${it.totalCount} 次",
                        clickable = false
                    )
                }
                headerFiles = candidates
                topFile = sorted.firstOrNull()?.rep
                onClick = { _, _ -> }
            }

            SortSettings.LeaderboardMethod.RECENT_PLAY -> {
                val candidates = all.filter { (recentMap[it.path] ?: 0L) > 0L }
                val withRecent = candidates.map { f -> f to (recentMap[f.path] ?: 0L) }
                val sortedAll = if (order == SortSettings.Order.DESC) {
                    withRecent.sortedWith(
                        compareByDescending<Pair<MusicScanner.MusicFile, Long>> { it.second }
                            .thenBy { SortKeyHelper.keyOf(it.first.title) }
                    )
                } else {
                    withRecent.sortedWith(
                        compareBy<Pair<MusicScanner.MusicFile, Long>> { it.second }
                            .thenBy { SortKeyHelper.keyOf(it.first.title) }
                    )
                }
                val sorted = sortedAll.take(100)

                rows = sorted.map { (f, _) ->
                    LeaderboardAdapter.Row(
                        file = f,
                        primary = f.title,
                        subtitle = ArtistUtils.displayArtists(f.artist),
                        trailing = SongAdapter.formatDuration(f.duration),
                        showRank = false
                    )
                }
                headerFiles = sorted.map { it.first }
                topFile = headerFiles.firstOrNull()
                val queue = headerFiles
                onClick = { pos, _ ->
                    val mode = PlaybackSettings.getPreferredMode(this)
                    val (q, idx) = buildQueueForMode(queue, pos, mode)
                    PlaybackManager.playQueue(this, q, idx, mode, queue, "听歌排行")
                }
            }
        }

        headerMetaTv.text = formatPlaylistHeaderMeta(headerFiles)
        headerMetaTv.visibility = View.VISIBLE

        if (rows.isEmpty()) {
            rvLb.visibility = View.GONE
            empty.visibility = View.VISIBLE
            topCover.setImageResource(R.drawable.music_note_24)
            leaderboardEntries = emptyList()
            leaderboardAdapter?.updateItems(emptyList())
            updateLocateButtons(currentDisplayPath())
            return
        }

        rvLb.visibility = View.VISIBLE
        empty.visibility = View.GONE

        topFile?.let { CoverLoader.load(topCover, it.path, R.drawable.music_note_24) }
            ?: topCover.setImageResource(R.drawable.music_note_24)

        val newAdapter = LeaderboardAdapter(
            initialItems = rows,
            onItemClick = { pos, row -> onClick(pos, row) },
            onItemLongClick = { _, row -> SongActionSheet.show(this, row.file) }
        )
        newAdapter.currentPath = currentDisplayPath()
        rvLb.layoutManager = rvLb.layoutManager ?: LinearLayoutManager(this)
        rvLb.adapter = newAdapter
        rvLb.setHasFixedSize(true)
        rvLb.setItemViewCacheSize(20)
        leaderboardAdapter = newAdapter
        leaderboardEntries = rows
        updateLocateButtons(currentDisplayPath())
    }

    private data class ArtistAggregate(
        val artist: String,
        val songs: List<MusicScanner.MusicFile>,
        val rep: MusicScanner.MusicFile,
        val totalCount: Int
    )

    private fun showLeaderboardSortSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_leaderboard_sort, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)
        dialog.setOnShowListener {
            dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val checkSong = view.findViewById<View>(R.id.checkMethodSongTime)
        val checkListen = view.findViewById<View>(R.id.checkMethodListenDuration)
        val checkArtist = view.findViewById<View>(R.id.checkMethodArtist)
        val checkRecent = view.findViewById<View>(R.id.checkMethodRecentPlay)
        val checkAsc = view.findViewById<View>(R.id.checkOrderAsc)
        val checkDesc = view.findViewById<View>(R.id.checkOrderDesc)

        // "选择日期"已迁移到"我的"页的听歌统计里，此面板只保留排序方式 + 顺序。
        // 强制数据源固定到"所有时间"，保证从老版本升级上来的用户也不会被过滤卡住。
        if (SortSettings.getLeaderboardDateFilter(this) != SortSettings.DateFilter.ALL) {
            SortSettings.setLeaderboardDateFilter(this, SortSettings.DateFilter.ALL)
        }

        fun refresh() {
            val m = SortSettings.getLeaderboardMethod(this)
            checkSong.visibility =
                if (m == SortSettings.LeaderboardMethod.SONG_TIME) View.VISIBLE else View.INVISIBLE
            checkListen.visibility =
                if (m == SortSettings.LeaderboardMethod.LISTEN_DURATION) View.VISIBLE else View.INVISIBLE
            checkArtist.visibility =
                if (m == SortSettings.LeaderboardMethod.ARTIST_COUNT) View.VISIBLE else View.INVISIBLE
            checkRecent.visibility =
                if (m == SortSettings.LeaderboardMethod.RECENT_PLAY) View.VISIBLE else View.INVISIBLE
            val o = SortSettings.getLeaderboardOrder(this)
            checkAsc.visibility = if (o == SortSettings.Order.ASC) View.VISIBLE else View.INVISIBLE
            checkDesc.visibility = if (o == SortSettings.Order.DESC) View.VISIBLE else View.INVISIBLE
        }
        refresh()

        fun pickMethod(m: SortSettings.LeaderboardMethod) {
            if (SortSettings.getLeaderboardMethod(this) != m) {
                SortSettings.setLeaderboardMethod(this, m)
                populateLeaderboard()
                refresh()
            }
        }
        fun pickOrder(o: SortSettings.Order) {
            if (SortSettings.getLeaderboardOrder(this) != o) {
                SortSettings.setLeaderboardOrder(this, o)
                populateLeaderboard()
                refresh()
            }
        }

        view.findViewById<View>(R.id.rowMethodSongTime).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.LeaderboardMethod.SONG_TIME) }
        }
        view.findViewById<View>(R.id.rowMethodListenDuration).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.LeaderboardMethod.LISTEN_DURATION) }
        }
        view.findViewById<View>(R.id.rowMethodArtist).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.LeaderboardMethod.ARTIST_COUNT) }
        }
        view.findViewById<View>(R.id.rowMethodRecentPlay).setOnClickListener {
            runThrottledSortAction { pickMethod(SortSettings.LeaderboardMethod.RECENT_PLAY) }
        }
        view.findViewById<View>(R.id.rowOrderAsc).setOnClickListener {
            runThrottledSortAction { pickOrder(SortSettings.Order.ASC) }
        }
        view.findViewById<View>(R.id.rowOrderDesc).setOnClickListener {
            runThrottledSortAction { pickOrder(SortSettings.Order.DESC) }
        }

        dialog.show()
    }

    private fun updateLocateButtons(currentPath: String?) {
        val inLibrary = currentPath != null && sortedFiles.any { it.path == currentPath }
        val inLeaderboard = currentPath != null && leaderboardEntries.any { it.file.path == currentPath }
        val inFavorites = currentPath != null && sortedFavorites.any { it.path == currentPath }
        val inFolderDetail = currentPath != null && currentFolderSongs.any { it.path == currentPath }

        fabLocate.visibility =
            if (currentNavKind == NavKind.SONGS && !folderDetailVisible && currentTabKind == TabKind.ALL && inLibrary) View.VISIBLE
            else View.GONE
        fabLocateLb.visibility =
            if (currentNavKind == NavKind.PLAYLIST && leaderboardVisible && inLeaderboard) View.VISIBLE
            else View.GONE
        fabLocateFav.visibility =
            if (currentNavKind == NavKind.PLAYLIST && favoritesVisible && inFavorites) View.VISIBLE
            else View.GONE
        fabLocateFolder.visibility =
            if (currentNavKind == NavKind.SONGS && folderDetailVisible && inFolderDetail) View.VISIBLE
            else View.GONE
    }

    // ============================================================
    // 备份导入：显示勾选对话框
    // ============================================================

    private fun showImportSheet(uri: android.net.Uri) {
        val preview = BackupManager.preview(this, uri)
        if (preview == null || preview.sections.isEmpty()) {
            Toast.makeText(this, "无法读取备份文件", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_import, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)
        dialog.setOnShowListener {
            dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val summary = view.findViewById<TextView>(R.id.tvImportSummary)
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(preview.exportedAt))
        summary.text = "备份时间：$dateStr"

        val container = view.findViewById<LinearLayout>(R.id.importSectionsContainer)
        val selected = mutableSetOf<String>()

        // 按 BackupManager.PREFS_LABELS 的顺序放行，这样 UI 顺序稳定
        val ordered = BackupManager.PREFS_LABELS.keys.filter { it in preview.sections }
        for (name in ordered) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(
                    this@SongListActivity,
                    android.R.drawable.list_selector_background
                )
            }
            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = "${BackupManager.PREFS_LABELS[name]}（${preview.sections[name] ?: 0} 项）"
                textSize = 14f
                setTextColor(0xFF333333.toInt())
            }
            val check = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                setImageResource(R.drawable.ic_check_24)
                setColorFilter(0xFF1565C0.toInt())
                visibility = View.VISIBLE   // 默认全选
            }
            selected.add(name)
            row.addView(label)
            row.addView(check)
            row.setOnClickListener {
                if (name in selected) {
                    selected.remove(name)
                    check.visibility = View.INVISIBLE
                } else {
                    selected.add(name)
                    check.visibility = View.VISIBLE
                }
            }
            container.addView(row)
        }

        view.findViewById<View>(R.id.btnImportCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnImportConfirm).setOnClickListener {
            if (selected.isEmpty()) {
                Toast.makeText(this, "请至少选择一项", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 关键顺序：先停播放（走 ListenStats.save 把当前 session 写回），
            // 再 reset 清内存态，再 import 覆写 prefs，最后重启任务栈
            PlaybackManager.stop(this)
            PlaybackStateStore.clear(this, true)
            ListenStats.reset()
            val ok = BackupManager.import(this, uri, selected)
            // 再 reset 一次确保没有 Service 残留的内存 map 覆盖刚导入的数据
            ListenStats.reset()
            dialog.dismiss()
            if (ok) {
                Toast.makeText(this, "导入成功，应用即将重启", Toast.LENGTH_SHORT).show()
                rv.postDelayed({
                    restartAppProcess()
                }, 220)
            } else {
                Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun restartAppProcess() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val restartIntent = when {
            launchIntent?.component != null -> Intent.makeRestartActivityTask(launchIntent.component)
            launchIntent != null -> launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            else -> Intent(this, SongListActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            restartIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(AlarmManager::class.java)
        alarmManager?.setExact(AlarmManager.RTC, System.currentTimeMillis() + 150L, pendingIntent)
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }

    // ============================================================
    // 阈值选择 bottom sheet
    // ============================================================

    private fun showThresholdSheet(tvLabel: TextView) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_threshold, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)
        dialog.setOnShowListener {
            dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val check70 = view.findViewById<View>(R.id.check70)
        val check90 = view.findViewById<View>(R.id.check90)
        val check100 = view.findViewById<View>(R.id.check100)

        fun refresh() {
            val p = PlaybackSettings.getThresholdPercent(this)
            check70.visibility = if (p == 70) View.VISIBLE else View.INVISIBLE
            check90.visibility = if (p == 90) View.VISIBLE else View.INVISIBLE
            check100.visibility = if (p == 100) View.VISIBLE else View.INVISIBLE
        }
        refresh()

        fun pick(v: Int) {
            PlaybackSettings.setThresholdPercent(this, v)
            PlaybackManager.refreshPlaybackThreshold()
            tvLabel.text = "%"
            refresh()
        }
        view.findViewById<View>(R.id.rowThreshold70).setOnClickListener { pick(70) }
        view.findViewById<View>(R.id.rowThreshold90).setOnClickListener { pick(90) }
        view.findViewById<View>(R.id.rowThreshold100).setOnClickListener { pick(100) }

        dialog.show()
    }


    private fun installAboutPanelDragToDismiss() {
        var downY = 0f
        var startTranslationY = 0f
        var dragging = false
        profileAboutPanel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startTranslationY = profileAboutPanel.translationY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    if (dy > dp(8) || dragging) {
                        dragging = true
                        profileAboutPanel.translationY = (startTranslationY + dy).coerceAtLeast(0f)
                        profileAboutOverlay.alpha = 1f
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        if (profileAboutPanel.translationY > dp(96)) {
                            hideProfileAboutPanel()
                        } else {
                            profileAboutPanel.animate().translationY(0f).setDuration(180L).start()
                            profileAboutOverlay.animate().alpha(1f).setDuration(180L).start()
                        }
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun showProfileAboutPanel() {
        if (profileAboutVisible) return
        if (profileSettingsVisible) hideProfileSettingsPanel()
        profileAboutVisible = true

        // 之前的实现是先把 overlay 设成 VISIBLE，再用 post {} 等下一次布局拿到 panel 高度，
        // 然后才把 panel 推到屏幕外开始上拉。结果 overlay 变可见的那一帧，panel 已经
        // 在贴底位置渲染过一次了——肉眼能看到一个"先冒一下、再下去、再上来"的闪。
        //
        // 这里改成：先用 root 高度估算 panel 目标高度（root 还没量到时回退到屏幕高），
        // 把 layoutParams 和 translationY 一起在 overlay 还隐藏的时候配置好。等 overlay
        // 可见时，panel 已经在屏幕外了，动画从屏外到 0 一气呵成，不再有第一帧的闪。
        val rootView = findViewById<View>(android.R.id.content)
        val rootHeight = rootView.height
            .takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        val targetHeight = (rootHeight * 0.86f).toInt().coerceAtLeast(dp(500))
        val offScreen = (targetHeight + dp(32)).toFloat()

        profileAboutPanel.layoutParams = profileAboutPanel.layoutParams.apply {
            height = targetHeight
        }
        profileAboutPanel.translationY = offScreen

        profileAboutOverlay.visibility = View.VISIBLE
        profileAboutOverlay.alpha = 1f

        profileAboutPanel.animate()
            .translationY(0f)
            .setDuration(280L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .start()
    }

    private fun hideProfileAboutPanel() {
        if (!profileAboutVisible) return
        profileAboutVisible = false
        profileAboutOverlay.alpha = 1f
        profileAboutPanel.animate()
            .translationY(((profileAboutPanel.layoutParams?.height ?: profileAboutPanel.height) + dp(32)).toFloat())
            .setDuration(220L)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
            .withEndAction {
                profileAboutOverlay.visibility = View.GONE
                profileAboutPanel.translationY = 0f
                profileAboutOverlay.alpha = 1f
            }
            .start()
    }

    private fun installSettingsPanelDragToDismiss() {
        var downY = 0f
        var startTranslationY = 0f
        var dragging = false
        profileSettingsPanel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startTranslationY = profileSettingsPanel.translationY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    if (dy > dp(8) || dragging) {
                        dragging = true
                        profileSettingsPanel.translationY = (startTranslationY + dy).coerceAtLeast(0f)
                        profileSettingsOverlay.alpha = 1f
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        if (profileSettingsPanel.translationY > dp(96)) {
                            hideProfileSettingsPanel()
                        } else {
                            profileSettingsPanel.animate().translationY(0f).setDuration(180L).start()
                            profileSettingsOverlay.animate().alpha(1f).setDuration(180L).start()
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun showProfileSettingsPanel() {
        if (profileSettingsVisible) return
        if (profileAboutVisible) hideProfileAboutPanel()
        profileSettingsVisible = true
        profileSettingsOverlay.visibility = View.VISIBLE
        profileSettingsOverlay.alpha = 0f
        profileSettingsPanel.post {
            val rootHeight = findViewById<View>(android.R.id.content).height.coerceAtLeast(profileSettingsPanel.height)
            val targetHeight = (rootHeight * 0.86f).toInt().coerceAtLeast(dp(500))
            profileSettingsPanel.layoutParams = profileSettingsPanel.layoutParams.apply { height = targetHeight }
            profileSettingsPanel.translationY = (targetHeight + dp(32)).toFloat()
            profileSettingsOverlay.animate()
                .alpha(1f)
                .setDuration(180L)
                .start()
            profileSettingsPanel.animate()
                .translationY(0f)
                .setDuration(280L)
                .start()
        }
    }

    private fun hideProfileSettingsPanel() {
        if (!profileSettingsVisible) return
        profileSettingsVisible = false
        profileSettingsOverlay.animate()
            .alpha(0f)
            .setDuration(160L)
            .start()
        profileSettingsPanel.animate()
            .translationY(((profileSettingsPanel.layoutParams?.height ?: profileSettingsPanel.height) + dp(32)).toFloat())
            .setDuration(220L)
            .withEndAction {
                profileSettingsOverlay.visibility = View.GONE
                profileSettingsPanel.translationY = 0f
                profileSettingsOverlay.alpha = 1f
            }
            .start()
    }

    // ====================== 多选模式 ======================

    /**
     * 把"当前用户在哪个列表里触发的多选"作为入参。每个作用域对应的 adapter 不同：
     *   LIBRARY     -> songsAdapterRef          (sortedFiles)
     *   FAVORITES   -> favoritesAdapter         (sortedFavorites)
     *   PLAYLIST    -> playlistDetailAdapter    (currentPlaylistSongs)
     *   LEADERBOARD -> leaderboardAdapter       (leaderboardEntries[].file)
     *
     * 所有 adapter 在多选模式下由自身处理点击 = 切换选中，并通过 onSelectionChanged
     * 把最新的选中集广播给 Activity，Activity 只负责刷新 multiSelectedPaths 这一份
     * 共享快照，供"分享"等操作使用。
     */
    /** 进入多选后把当前可见的 todolist 图标换成"关闭"样式，退出时恢复。 */
    private fun setTodoButtonsToExitIcon(isMultiSelecting: Boolean) {
        val iconRes =
            if (isMultiSelecting) R.drawable.ic_close_24 else R.drawable.ic_multiselect_24
        val desc = if (isMultiSelecting) "退出多选" else "多选"
        findViewById<ImageButton>(R.id.btnMultiSelect).apply {
            setImageResource(iconRes); contentDescription = desc
        }
        findViewById<ImageButton>(R.id.btnFavTodo).apply {
            setImageResource(iconRes); contentDescription = desc
        }
        findViewById<ImageButton>(R.id.btnLbMulti).apply {
            setImageResource(iconRes); contentDescription = desc
        }
        findViewById<ImageButton>(R.id.btnPlaylistDetailTodo).apply {
            setImageResource(iconRes); contentDescription = desc
        }
        findViewById<ImageButton>(R.id.btnFolderDetailTodo).apply {
            setImageResource(iconRes); contentDescription = desc
        }
    }

    private fun enterMultiSelectMode(scope: MultiSelectScope) {
        if (multiSelectMode) return
        multiSelectMode = true
        multiSelectScope = scope
        multiSelectedPaths.clear()
        when (scope) {
            MultiSelectScope.LIBRARY -> {
                songsAdapterRef?.let { a ->
                    a.setMultiSelectMode(true)
                    a.setSelectedPaths(emptySet())
                    a.onSelectionChanged = { paths -> onMultiSelectChanged(paths) }
                }
            }
            MultiSelectScope.FAVORITES -> {
                favoritesAdapter?.let { a ->
                    a.setMultiSelectMode(true)
                    a.setSelectedPaths(emptySet())
                    a.onSelectionChanged = { paths -> onMultiSelectChanged(paths) }
                }
            }
            MultiSelectScope.PLAYLIST -> {
                playlistDetailAdapter?.let { a ->
                    a.setMultiSelectMode(true)
                    a.setSelectedPaths(emptySet())
                    a.onSelectionChanged = { paths -> onMultiSelectChanged(paths) }
                }
            }
            MultiSelectScope.FOLDER -> {
                folderDetailAdapter?.let { a ->
                    a.setMultiSelectMode(true)
                    a.setSelectedPaths(emptySet())
                    a.onSelectionChanged = { paths -> onMultiSelectChanged(paths) }
                }
            }
            MultiSelectScope.LEADERBOARD -> {
                leaderboardAdapter?.let { a ->
                    a.setMultiSelectMode(true)
                    a.setSelectedPaths(emptySet())
                    a.onSelectionChanged = { paths -> onMultiSelectChanged(paths) }
                }
            }
        }
        setTodoButtonsToExitIcon(true)
        miniPlayerWrapper.visibility = View.GONE
        multiSelectActionBar.visibility = View.VISIBLE
    }

    private fun exitMultiSelectMode() {
        if (!multiSelectMode) return
        multiSelectMode = false
        multiSelectScope = null
        multiSelectedPaths.clear()
        // 四个 adapter 的多选都关掉（保险起见，避免"切页后还遗留个选中圈"）
        songsAdapterRef?.setMultiSelectMode(false)
        favoritesAdapter?.setMultiSelectMode(false)
        playlistDetailAdapter?.setMultiSelectMode(false)
        folderDetailAdapter?.setMultiSelectMode(false)
        leaderboardAdapter?.setMultiSelectMode(false)
        setTodoButtonsToExitIcon(false)
        multiSelectActionBar.visibility = View.GONE
        miniPlayerWrapper.visibility = View.VISIBLE
    }

    /** adapter 里的 selection 变动统一汇总到这里。 */
    private fun onMultiSelectChanged(paths: Set<String>) {
        multiSelectedPaths.clear()
        multiSelectedPaths.addAll(paths)
    }

    /** "全选" 按钮：一键把当前作用域列表里所有歌曲勾上。 */
    private fun selectAllCurrent() {
        if (!multiSelectMode) return
        val scope = multiSelectScope ?: return
        val allPaths: List<String> = when (scope) {
            MultiSelectScope.LIBRARY -> songsAdapterRef?.allPaths().orEmpty()
            MultiSelectScope.FAVORITES -> favoritesAdapter?.allPaths().orEmpty()
            MultiSelectScope.PLAYLIST -> playlistDetailAdapter?.allPaths().orEmpty()
            MultiSelectScope.FOLDER -> folderDetailAdapter?.allPaths().orEmpty()
            MultiSelectScope.LEADERBOARD -> leaderboardAdapter?.allPaths().orEmpty()
        }
        if (allPaths.isEmpty()) {
            Toast.makeText(this, "当前列表为空", Toast.LENGTH_SHORT).show()
            return
        }
        val currentSelected: Set<String> = when (scope) {
            MultiSelectScope.LIBRARY -> songsAdapterRef?.selectedPathsSnapshot().orEmpty()
            MultiSelectScope.FAVORITES -> favoritesAdapter?.selectedPathsSnapshot().orEmpty()
            MultiSelectScope.PLAYLIST -> playlistDetailAdapter?.selectedPathsSnapshot().orEmpty()
            MultiSelectScope.FOLDER -> folderDetailAdapter?.selectedPathsSnapshot().orEmpty()
            MultiSelectScope.LEADERBOARD -> leaderboardAdapter?.selectedPathsSnapshot().orEmpty()
        }
        // 已经全部选中的时候再点一次"全选"视作"取消全选"
        val nextSet: Set<String> = if (currentSelected.containsAll(allPaths)) {
            emptySet()
        } else {
            allPaths.toSet()
        }
        when (scope) {
            MultiSelectScope.LIBRARY -> songsAdapterRef?.setSelectedPaths(nextSet)
            MultiSelectScope.FAVORITES -> favoritesAdapter?.setSelectedPaths(nextSet)
            MultiSelectScope.PLAYLIST -> playlistDetailAdapter?.setSelectedPaths(nextSet)
            MultiSelectScope.FOLDER -> folderDetailAdapter?.setSelectedPaths(nextSet)
            MultiSelectScope.LEADERBOARD -> leaderboardAdapter?.setSelectedPaths(nextSet)
        }
        onMultiSelectChanged(nextSet)
    }

    private fun toggleMultiSelected(path: String) {
        // 保留这个方法给外部老代码路径调用；内部切换现在由 adapter 自己 handle。
        if (!multiSelectMode) return
        if (multiSelectedPaths.contains(path)) {
            multiSelectedPaths.remove(path)
        } else {
            multiSelectedPaths.add(path)
        }
        when (multiSelectScope) {
            MultiSelectScope.LIBRARY -> songsAdapterRef?.setSelectedPaths(multiSelectedPaths)
            MultiSelectScope.FAVORITES -> favoritesAdapter?.setSelectedPaths(multiSelectedPaths)
            MultiSelectScope.PLAYLIST -> playlistDetailAdapter?.setSelectedPaths(multiSelectedPaths)
            MultiSelectScope.FOLDER -> folderDetailAdapter?.setSelectedPaths(multiSelectedPaths)
            MultiSelectScope.LEADERBOARD -> leaderboardAdapter?.setSelectedPaths(multiSelectedPaths)
            null -> songsAdapterRef?.setSelectedPaths(multiSelectedPaths)
        }
    }

    private fun shareMultiSelectedSongs() {
        val paths = multiSelectedPaths.toList()
        if (paths.isEmpty()) {
            Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        val files = paths.map { java.io.File(it) }.filter { it.exists() && it.isFile }
        if (files.isEmpty()) {
            Toast.makeText(this, "选中的文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        if (files.size == 1) {
            shareSingleAudioFile(files.first())
        } else {
            shareFilesAsZip(files)
        }
    }

    private fun shareSingleAudioFile(audio: java.io.File) {
        val authority = "${packageName}.fileprovider"
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(this, authority, audio)
        }.getOrElse {
            Toast.makeText(this, "无法分享该文件", Toast.LENGTH_SHORT).show()
            return
        }
        val mime = when (audio.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "alac" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "audio/*"
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_TITLE, audio.nameWithoutExtension)
            clipData = android.content.ClipData.newUri(contentResolver, audio.name, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(android.content.Intent.createChooser(intent, "分享歌曲"))
            exitMultiSelectMode()
        }.onFailure {
            Toast.makeText(this, "未找到可分享的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFilesAsZip(files: List<java.io.File>) {
        // 在 Activity 挂起期间打包，避免主线程阻塞
        Toast.makeText(this, "正在打包 ${files.size} 首歌…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val zipFile: java.io.File? = withContext(Dispatchers.IO) {
                runCatching { buildZipInCache(files) }.getOrNull()
            }
            if (zipFile == null || !zipFile.exists()) {
                Toast.makeText(this@SongListActivity, "打包失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val authority = "${packageName}.fileprovider"
            val uri = runCatching {
                androidx.core.content.FileProvider.getUriForFile(this@SongListActivity, authority, zipFile)
            }.getOrElse {
                Toast.makeText(this@SongListActivity, "无法分享压缩包", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_TITLE, zipFile.name)
                clipData = android.content.ClipData.newUri(contentResolver, zipFile.name, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                startActivity(android.content.Intent.createChooser(intent, "分享歌曲合集"))
                exitMultiSelectMode()
            }.onFailure {
                Toast.makeText(this@SongListActivity, "未找到可分享的应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildZipInCache(files: List<java.io.File>): java.io.File {
        val shareDir = java.io.File(cacheDir, "shared").apply { mkdirs() }
        // 清理上次分享的 zip，只保留本次
        shareDir.listFiles { f -> f.name.startsWith("songs_") && f.name.endsWith(".zip") }
            ?.forEach { runCatching { it.delete() } }
        val zipFile = java.io.File(shareDir, "songs_${System.currentTimeMillis()}.zip")
        val usedNames = HashSet<String>()
        java.util.zip.ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            for (f in files) {
                val base = f.name
                var entryName = base
                var n = 1
                while (!usedNames.add(entryName)) {
                    entryName = "${f.nameWithoutExtension} ($n).${f.extension}"
                    n++
                }
                zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                f.inputStream().buffered().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile
    }



    private fun showMultiSelectMoreSheet() {
        val files = selectedMultiFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sheet_rounded_bg)
            setPadding(dp(20), dp(8), dp(20), dp(18))
        }
        root.addView(View(this).apply {
            setBackgroundResource(R.drawable.sheet_handle_bg)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = dp(2)
                bottomMargin = dp(12)
            }
        })
        root.addView(makeMultiActionRow(R.drawable.add_circle_24, "添加到歌单", 0xFF000000.toInt()) {
            dialog.dismiss()
            showAddMultiToPlaylistPicker(files)
        })
        root.addView(makeMultiActionRow(R.drawable.ic_delete_24, "删除", 0xFFD32F2F.toInt()) {
            dialog.dismiss()
            confirmDeleteSongs(files)
        })
        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.show()
    }

    private fun makeMultiActionRow(
        iconRes: Int,
        label: String,
        textColor: Int,
        onClick: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.song_item_touch_bg)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            )
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            })
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(16)
                }
            })
            setOnClickListener { onClick() }
        }
    }

    private fun selectedMultiFiles(): List<MusicScanner.MusicFile> {
        val selected = multiSelectedPaths.toSet()
        if (selected.isEmpty()) return emptyList()
        val all = ScanResultHolder.result?.files ?: sortedFiles
        val byPath = all.associateBy { it.path }
        return selected.mapNotNull { byPath[it] }
    }

    private fun showAddMultiToPlaylistPicker(files: List<MusicScanner.MusicFile>) {
        if (files.isEmpty()) {
            Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        val lists = PlaylistStore.all(this)
        if (lists.isEmpty()) {
            showCreatePlaylistThenAddMany(files)
            return
        }
        val names = lists.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("添加到歌单")
            .setItems(names) { _, which ->
                val target = lists[which]
                var added = 0
                for (file in files) {
                    val existed = PlaylistStore.containsSong(this, target.id, file.path)
                    PlaylistStore.addSong(this, target.id, file.path)
                    if (!existed) added++
                }
                notifyPlaylistsChanged()
                Toast.makeText(this, "已添加 ${added} 首歌曲到「${target.name}」", Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCreatePlaylistThenAddMany(files: List<MusicScanner.MusicFile>) {
        val view = layoutInflater.inflate(R.layout.dialog_new_playlist, null)
        AppFont.applyTo(view)
        view.findViewById<TextView>(R.id.tvPlaylistDialogTitle).text = "新建歌单并添加"
        val input = view.findViewById<EditText>(R.id.etPlaylistName)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .create()

        fun commit() {
            val name = input.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(this, "请输入歌单名称", Toast.LENGTH_SHORT).show()
                return
            }
            val playlist = PlaylistStore.create(this, name)
            files.forEach { PlaylistStore.addSong(this, playlist.id, it.path) }
            notifyPlaylistsChanged()
            Toast.makeText(this, "已创建「${playlist.name}」并添加 ${files.size} 首歌曲", Toast.LENGTH_SHORT).show()
            if (multiSelectMode) exitMultiSelectMode()
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btnPlaylistDialogCancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btnPlaylistDialogConfirm).setOnClickListener {
            commit()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                commit()
                true
            } else {
                false
            }
        }
        dialog.setOnShowListener {
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }

    fun confirmDeleteSongs(files: List<MusicScanner.MusicFile>) {
        val existing = files.distinctBy { it.path }.filter { java.io.File(it.path).exists() }
        if (existing.isEmpty()) {
            Toast.makeText(this, "歌曲文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val count = existing.size
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除歌曲")
            .setMessage("确定删除${count}首歌曲？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val deletedPaths = withContext(Dispatchers.IO) {
                        existing.filter { deleteAudioFileBestEffort(it.path) }.map { it.path }.toSet()
                    }
                    if (deletedPaths.isNotEmpty()) {
                        removeDeletedSongsFromStores(deletedPaths)
                        refreshLibraryAfterDelete(deletedPaths)
                        PlaybackManager.currentPath()?.let { if (it in deletedPaths) PlaybackManager.stop(this@SongListActivity) }
                    }
                    Toast.makeText(
                        this@SongListActivity,
                        if (deletedPaths.size == count) "已删除${deletedPaths.size}首歌曲" else "已删除${deletedPaths.size}/${count}首歌曲",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (multiSelectMode) exitMultiSelectMode()
                }
            }
            .show()
    }

    private fun deleteAudioFileBestEffort(path: String): Boolean {
        val file = java.io.File(path)
        if (!file.exists()) return true
        if (runCatching { file.delete() }.getOrDefault(false)) return true
        return runCatching {
            val uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            contentResolver.delete(uri, android.provider.MediaStore.Audio.Media.DATA + "=?", arrayOf(path)) > 0
        }.getOrDefault(false)
    }

    private fun removeDeletedSongsFromStores(paths: Set<String>) {
        for (path in paths) FavoritesStore.setFavorite(this, path, false)
        for (playlist in PlaylistStore.all(this)) {
            for (path in paths) PlaylistStore.removeSong(this, playlist.id, path)
        }
    }

    private fun refreshLibraryAfterDelete(deletedPaths: Set<String>) {
        val current = ScanResultHolder.result?.files ?: sortedFiles
        val remaining = current.filterNot { it.path in deletedPaths }
        val newResult = MusicScanner.ScanResult(
            files = remaining,
            formatCounts = remaining.groupingBy { it.format }.eachCount()
        )
        ScanResultHolder.result = newResult
        ScanCache.save(this, newResult)
        val visibleRemaining = visibleLibraryFiles(remaining)
        sortedFiles = applySortOrder(visibleRemaining)
        adapter?.updateItems(sortedFiles)
        songsAdapterRef = adapter
        setupAlphabetSidebar(sortedFiles)
        val emptyView = findViewById<View>(R.id.emptyView)
        if (remaining.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            songsContainer.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            songsContainer.visibility = View.VISIBLE
        }
        updateLibrarySummaryCounts(visibleRemaining)
        refreshReplayGainSettingsUi()
        refreshQualityBadgeSettingsUi()
        when (currentTabKind) {
            TabKind.ALBUM -> {
                refreshAlbumsPage()
                setupAlbumAlphabetSidebar(albumEntries)
            }
            TabKind.ARTIST -> {
                refreshArtistsPage()
                setupArtistAlphabetSidebar(artistEntries)
            }
            TabKind.GENRE -> {
                tabPlaceholder.visibility = View.VISIBLE
                tvPlaceholder.text = "页面开发中"
            }
            TabKind.FOLDER -> refreshFoldersPage()
            else -> {}
        }
        if (folderDetailVisible) populateFolderDetail()
        populateFavorites()
        if (leaderboardVisible) populateLeaderboard()
        if (playlistDetailVisible) populatePlaylistDetail() else populateUserPlaylists()
        updateLocateButtons(currentDisplayPath())
    }


private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

private fun switchListenReportRange(
    range: ListenReportCalculator.Range,
    force: Boolean = false
) {
    if (!force && currentReportRange == range) return
    currentReportRange = range
    btnReportDay.isSelected = range == ListenReportCalculator.Range.DAY
    btnReportWeek.isSelected = range == ListenReportCalculator.Range.WEEK
    btnReportMonth.isSelected = range == ListenReportCalculator.Range.MONTH
    val targetHeight = when (range) {
        ListenReportCalculator.Range.DAY -> dp(184)
        ListenReportCalculator.Range.WEEK -> dp(198)
        ListenReportCalculator.Range.MONTH -> {
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.DAY_OF_MONTH, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val offset = (cal.get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.MONDAY + 7) % 7
            val days = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH).coerceIn(28, 31)
            val rows = ((offset + days + 6) / 7).coerceAtLeast(4)
            dp(if (rows >= 6) 260 else 236)
        }
    }
    reportChartView.layoutParams = reportChartView.layoutParams.apply {
        height = targetHeight
    }
    reportChartView.requestLayout()
    refreshListenReportCard()
}

private fun refreshListenReportCard() {
    if (!::reportChartView.isInitialized) return
    val files = ScanResultHolder.result?.files ?: emptyList()
    val state = ListenReportCalculator.build(this, files, currentReportRange)
    tvReportRangeLabel.text = state.summaryLabel
    // 日视图下 "/1天" 是冗余信息，只保留分钟/小时本身；周、月视图仍然显示"活跃天数"。
    tvReportSummaryValue.text = if (state.range == ListenReportCalculator.Range.DAY) {
        formatListenDurationCompact(state.totalDurationMs)
    } else {
        "${formatListenDurationCompact(state.totalDurationMs)} / ${state.activeDays}天"
    }
    tvReportBestArtistValue.text = if (state.bestArtistName.isBlank() || state.bestArtistDurationMs <= 0L) {
        "${state.bestArtistLabel}：暂无"
    } else {
        "${state.bestArtistLabel}：${state.bestArtistName} · ${formatListenDurationCompact(state.bestArtistDurationMs)}"
    }
    updateReportRefreshCountdown()
    reportChartView.setState(state)
    bindListenReportRankSlots(state)
}

// 根据当前排行模式（歌曲 / 歌手）把 3 个卡槽刷成对应内容。
// 卡槽在布局里是按 rank 2-1-3 的顺序排列的，所以同一个数据源既能喂 song 也能喂 artist。
private fun bindListenReportRankSlots(state: ListenReportCalculator.ReportState) {
    when (currentReportRankMode) {
        ReportRankMode.SONGS -> {
            bindListenReportSongSlot(
                reportSongSlots[0],
                state.topSongs.firstOrNull { it.rank == 2 }
            )
            bindListenReportSongSlot(
                reportSongSlots[1],
                state.topSongs.firstOrNull { it.rank == 1 }
            )
            bindListenReportSongSlot(
                reportSongSlots[2],
                state.topSongs.firstOrNull { it.rank == 3 }
            )
        }
        ReportRankMode.ARTISTS -> {
            bindListenReportArtistSlot(
                reportSongSlots[0],
                state.topArtists.firstOrNull { it.rank == 2 }
            )
            bindListenReportArtistSlot(
                reportSongSlots[1],
                state.topArtists.firstOrNull { it.rank == 1 }
            )
            bindListenReportArtistSlot(
                reportSongSlots[2],
                state.topArtists.firstOrNull { it.rank == 3 }
            )
        }
    }
}

private fun switchListenReportRankMode(mode: ReportRankMode) {
    if (currentReportRankMode == mode) return
    currentReportRankMode = mode
    btnReportRankSongs.isSelected = mode == ReportRankMode.SONGS
    btnReportRankArtists.isSelected = mode == ReportRankMode.ARTISTS
    refreshListenReportCard()
}

private fun updateReportRefreshCountdown() {
    if (!::tvReportRefreshCountdown.isInitialized) return
    if (currentReportRange != ListenReportCalculator.Range.DAY) {
        tvReportRefreshCountdown.visibility = View.GONE
        return
    }
    val now = java.util.Calendar.getInstance()
    val midnight = (now.clone() as java.util.Calendar).apply {
        add(java.util.Calendar.DAY_OF_YEAR, 1)
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val diffMs = (midnight.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
    tvReportRefreshCountdown.visibility = View.VISIBLE
    tvReportRefreshCountdown.text = formatReportRefreshCountdown(diffMs)
}

private fun formatReportRefreshCountdown(diffMs: Long): String {
    val totalMinutes = ((diffMs + 59_999L) / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "还有 ${hours}小时${minutes}分刷新"
        hours > 0L -> "还有 ${hours}小时刷新"
        minutes > 0L -> "还有 ${minutes}分刷新"
        else -> "即将刷新"
    }
}

private fun bindListenReportSongSlot(
    slot: ReportSongSlot,
    song: ListenReportCalculator.RankedSong?
) {
    if (song == null) {
        slot.cover.setImageResource(R.drawable.music_note_24)
        slot.cover.alpha = 0.42f
        slot.title.text = "暂无数据"
        slot.title.setTextColor(0xFF999999.toInt())
        return
    }
    slot.cover.alpha = 1f
    // 480px 中等分辨率 > 列表里的 160px 小图，82dp 封面放大显示不再糊
    CoverLoader.loadAlbumCover(slot.cover, song.file.path, R.drawable.music_note_24)
    val artistName = song.file.artistGroup().ifBlank {
        ArtistUtils.displayArtists(song.file.artist).ifBlank { "未知歌手" }
    }
    // "歌名 - 歌手" 单行展示，和参考截图一致
    slot.title.text = "${song.file.title} - $artistName"
    slot.title.setTextColor(0xFF000000.toInt())
}

// 歌手排行的卡槽绑定：封面用"代表作"（该歌手听最久那首歌的封面），
// 文本展示为"歌手名 · X次"，和歌曲排行保持相同的单行结构。
private fun bindListenReportArtistSlot(
    slot: ReportSongSlot,
    artist: ListenReportCalculator.RankedArtist?
) {
    if (artist == null) {
        slot.cover.setImageResource(R.drawable.music_note_24)
        slot.cover.alpha = 0.42f
        slot.title.text = "暂无数据"
        slot.title.setTextColor(0xFF999999.toInt())
        return
    }
    slot.cover.alpha = 1f
    val repFile = artist.representativeFile
    if (repFile != null) {
        CoverLoader.loadAlbumCover(slot.cover, repFile.path, R.drawable.music_note_24)
    } else {
        slot.cover.setImageResource(R.drawable.music_note_24)
        CoverFrameStyler.applyDefault(slot.cover)
    }
    slot.title.text = "${artist.name} · ${artist.playCount}次"
    slot.title.setTextColor(0xFF000000.toInt())
}

private fun formatListenDurationCompact(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return when {
        h > 0L -> if (m > 0L) "${h}小时${m}分" else "${h}小时"
        m > 0L -> if (s > 0L) "${m}分${s}秒" else "${m}分"
        else -> "${s}秒"
    }
}

private fun setSettingsToggleState(toggle: ImageView?, enabled: Boolean) {
    toggle ?: return
    toggle.background = null
    toggle.clearColorFilter()
    val tintColor = if (enabled) SETTINGS_TOGGLE_ON_COLOR else SETTINGS_TOGGLE_OFF_COLOR
    val filter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_ATOP)
    if (toggle is LottieAnimationView) {
        toggle.cancelAnimation()
        toggle.setAnimation(R.raw.icons8_on_off)
        toggle.repeatCount = 0
        toggle.speed = 1f
        toggle.frame = if (enabled) SETTINGS_TOGGLE_ON_FRAME else SETTINGS_TOGGLE_OFF_FRAME
        toggle.progress = if (enabled) 0f else (SETTINGS_TOGGLE_OFF_FRAME / 28f)
        toggle.addValueCallback(
            KeyPath("**"),
            LottieProperty.COLOR_FILTER,
            LottieValueCallback(filter)
        )
    } else {
        toggle.setImageResource(if (enabled) R.drawable.toggle_on else R.drawable.toggle_off)
        toggle.colorFilter = filter
    }
    toggle.alpha = 1f
    toggle.invalidate()
}

private fun refreshReplayGainSettingsUi() {
    val status = runCatching { findViewById<TextView>(R.id.tvSettingsReplayGainScanStatus) }.getOrNull()
    val toggle = runCatching { findViewById<ImageView>(R.id.ivSettingsReplayGainToggle) }.getOrNull()
    val files = ScanResultHolder.result?.files ?: sortedFiles
    val stats = ReplayGainStore.stats(this, files)
    status?.text = if (stats.songCount <= 0) {
        "未扫描"
    } else {
        "${stats.withReplayGain}/${stats.songCount}"
    }
    setSettingsToggleState(toggle, PlaybackSettings.isReplayGainEnabled(this))
}


private fun refreshQualityBadgeSettingsUi() {
    val toggle = runCatching { findViewById<ImageView>(R.id.ivSettingsQualityBadgeToggle) }.getOrNull()
    val enabled = PlaybackSettings.isQualityBadgeEnabled(this)
    setSettingsToggleState(toggle, enabled)
    if (enabled) {
        val files = ScanResultHolder.result?.files ?: sortedFiles
        if (files.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                AudioQualityClassifier.preload(files)
                withContext(Dispatchers.Main) {
                    adapter?.notifyDataSetChanged()
                    favoritesAdapter?.notifyDataSetChanged()
                    playlistDetailAdapter?.notifyDataSetChanged()
                }
            }
        }
    }
}

private fun refreshCarLyricsSettingsUi() {
    val toggle = runCatching { findViewById<ImageView>(R.id.ivSettingsCarLyricsToggle) }.getOrNull()
    setSettingsToggleState(toggle, PlaybackSettings.isCarLyricsEnabled(this))
}

private fun refreshSongQualityBadges() {
    val files = ScanResultHolder.result?.files ?: sortedFiles
    if (PlaybackSettings.isQualityBadgeEnabled(this) && files.isNotEmpty()) {
        lifecycleScope.launch(Dispatchers.IO) {
            AudioQualityClassifier.preload(files)
            withContext(Dispatchers.Main) {
                adapter?.notifyDataSetChanged()
                favoritesAdapter?.notifyDataSetChanged()
                playlistDetailAdapter?.notifyDataSetChanged()
                folderDetailAdapter?.notifyDataSetChanged()
            }
        }
    } else {
        adapter?.notifyDataSetChanged()
        favoritesAdapter?.notifyDataSetChanged()
        playlistDetailAdapter?.notifyDataSetChanged()
        folderDetailAdapter?.notifyDataSetChanged()
    }
}

private fun refreshAiCritiqueSettingsStatus(label: TextView) {

        val configured = AiCritiqueSettings.getApiKey(this).isNotBlank()
        val enabled = AiCritiqueSettings.isEnabled(this)
        label.text = when {
            !configured -> "未配置"
            enabled -> "已开启"
            else -> "已配置"
        }
    }

    private fun showAiCritiqueSettingsDialog(statusLabel: TextView) {
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val hintLabel = TextView(ctx).apply {
            text = "输入 DeepSeek API Key（sk- 开头）。打开开关后，歌曲详情页会根据歌词生成一句" +
                "≤150 字的中文锐评。Key 只保存在本机。"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
        }
        container.addView(hintLabel)

        val input = android.widget.EditText(ctx).apply {
            hint = "sk-..."
            setText(AiCritiqueSettings.getApiKey(ctx))
            setSingleLine(true)
            textSize = 14f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(12)
            layoutParams = lp
        }
        container.addView(input)

        val toggleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(14)
            layoutParams = lp
        }
        val toggleLabel = TextView(ctx).apply {
            text = "启用 AI 锐评"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        // 使用 icons8_on_off Lottie 开关：点击只切换图标状态，不显示额外灰色背景
        var toggleState = AiCritiqueSettings.isEnabled(ctx)
        val toggle = LottieAnimationView(ctx).apply {
            val lp = LinearLayout.LayoutParams(dp(58), dp(32))
            layoutParams = lp
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            setSettingsToggleState(this, toggleState)
            setOnClickListener {
                toggleState = !toggleState
                setSettingsToggleState(this, toggleState)
            }
        }
        toggleRow.addView(toggleLabel)
        toggleRow.addView(toggle)
        container.addView(toggleRow)

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("AI 歌曲总结")
            .setView(container)
            .setPositiveButton("保存") { dialog, _ ->
                val newKey = input.text.toString().trim()
                AiCritiqueSettings.setApiKey(ctx, newKey)
                // 未配置 key 时不允许开启
                val wantEnabled = toggleState && newKey.isNotBlank()
                AiCritiqueSettings.setEnabled(ctx, wantEnabled)
                // key 变化时清掉旧缓存，下次生成才用新 key
                AiCritiqueSettings.clearCritiques()
                refreshAiCritiqueSettingsStatus(statusLabel)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把毫秒格式化成 "X 小时 Y 分" / "X 分 Y 秒" / "X 秒" */
    private fun formatListenDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "$h 小时 $m 分"
            m > 0 -> "$m 分 $s 秒"
            else -> "$s 秒"
        }
    }

    private fun currentDisplayFile(): MusicScanner.MusicFile? = PlaybackUiResolver.displayFile(this)

    private fun currentDisplayPath(): String? = PlaybackUiResolver.displayPath(this)

    override fun onBackPressed() {
        when {
            multiSelectMode -> exitMultiSelectMode()
            folderDetailVisible -> hideFolderDetail()
            profileAboutVisible -> hideProfileAboutPanel()
            profileSettingsVisible -> hideProfileSettingsPanel()
            playlistDetailVisible -> hidePlaylistDetail()
            favoritesVisible -> hideFavorites()
            leaderboardVisible -> hideLeaderboard()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    // ============================================================
    // 外部跳转：PlayerActivity / SongActionSheet 通过 Intent 跳到歌手 / 专辑页
    // ============================================================

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleOpenExtras(it) }
    }

    private fun handleOpenExtras(intent: Intent) {
        val artistName = intent.getStringExtra(EXTRA_OPEN_ARTIST)
        val albumTitle = intent.getStringExtra(EXTRA_OPEN_ALBUM)
        val albumArtist = intent.getStringExtra(EXTRA_OPEN_ALBUM_ARTIST).orEmpty()
        // 清掉 extras 防止旋转屏幕重走
        intent.removeExtra(EXTRA_OPEN_ARTIST)
        intent.removeExtra(EXTRA_OPEN_ALBUM)
        intent.removeExtra(EXTRA_OPEN_ALBUM_ARTIST)

        when {
            !artistName.isNullOrBlank() -> openArtistByName(artistName)
            !albumTitle.isNullOrBlank() -> openAlbumByName(albumTitle, albumArtist)
        }
    }

    /** 按歌手名找到 ArtistEntry 并打开其 sheet */
    fun openArtistByName(name: String) {
        if (ArtistDetailSheet.show(this, name)) return
        selectNav(NavKind.SONGS)
        selectTab(TabKind.ARTIST, animate = false)
        rvArtists.post {
            refreshArtistsPage()
            val key = SortKeyHelper.keyOf(name).ifBlank { name.trim().lowercase() }
            val target = artistEntries.firstOrNull { it.key == key }
            if (target != null) {
                scrollArtistsToKey(target.key, flash = true)
                rvArtists.post { showArtistSheet(target) }
            } else {
                Toast.makeText(this, "未找到歌手「$name」", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun showAlbumSheetForSong(file: MusicScanner.MusicFile): Boolean {
        val cached = albumEntries.firstOrNull { entry -> entry.songs.any { it.path == file.path } }
        if (cached != null) {
            showAlbumSheet(cached)
            return true
        }
        val rawFiles = visibleLibraryFiles(ScanResultHolder.result?.files.orEmpty())
        val matched = buildAlbumEntries(rawFiles).firstOrNull { entry -> entry.songs.any { it.path == file.path } }
        if (matched != null) {
            showAlbumSheet(matched)
            return true
        }
        return false
    }

    /** 按专辑标题找到 AlbumEntry 并打开其 sheet */
    private fun openAlbumByName(title: String, artistHint: String) {
        if (ArtistDetailSheet.showAlbum(this, title, artistHint)) return
        selectNav(NavKind.SONGS)
        selectTab(TabKind.ALBUM, animate = false)
        rvAlbums.post {
            refreshAlbumsPage()
            val target = albumEntries.firstOrNull {
                it.title.equals(title, true) && (artistHint.isBlank() || it.artist.equals(artistHint, true))
            } ?: albumEntries.firstOrNull { it.title.equals(title, true) }
            if (target != null) {
                scrollAlbumsToKey(target.key, flash = true)
                rvAlbums.post { showAlbumSheet(target) }
            } else {
                Toast.makeText(this, "未找到专辑「$title」", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================================
    // 下拉刷新：重新扫描媒体库
    // ============================================================

    private fun refreshLibrary() {
        lifecycleScope.launch {
            try {
                // 在 IO 线程重新扫描
                val rescanned = withContext(Dispatchers.IO) {
                    MusicScanner.scanMusicFolder(this@SongListActivity) { /* 静默，不推送进度 */ }
                }
                val existingSafFiles = (ScanResultHolder.result?.files ?: emptyList())
                    .filter { it.path.startsWith("content://", ignoreCase = true) }
                val merged = linkedMapOf<String, MusicScanner.MusicFile>()
                rescanned.files.forEach { merged[it.path] = it }
                existingSafFiles.forEach { merged[it.path] = it }
                val newResult = MusicScanner.ScanResult(
                    files = merged.values.toList(),
                    formatCounts = merged.values.groupingBy { it.format }.eachCount()
                )

                // 更新全局缓存
                ScanResultHolder.result = newResult
                ScanCache.save(this@SongListActivity, newResult)

                // 刷新 UI
                val rawFilesAfterRefresh = newResult.files
                val files = visibleLibraryFiles(rawFilesAfterRefresh)
                sortedFiles = applySortOrder(files)

                // 更新 Tab 计数和"我的"音乐库卡片
                updateLibrarySummaryCounts(files)
                refreshReplayGainSettingsUi()

                // 更新歌曲列表
                val emptyView = findViewById<View>(R.id.emptyView)
                if (rawFilesAfterRefresh.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    songsContainer.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    songsContainer.visibility = View.VISIBLE
                    if (adapter != null) {
                        adapter?.updateItems(sortedFiles)
                    } else {
                        adapter = SongAdapter(
                            initialItems = sortedFiles,
                            onItemClick = { position, _ ->
                                val mode = PlaybackSettings.getPreferredMode(this@SongListActivity)
                                val (queue, startIdx) = buildQueueForMode(sortedFiles, position, mode)
                                PlaybackManager.playQueue(this@SongListActivity, queue, startIdx, mode, sortedFiles, "曲库")
                            },
                            onItemLongClick = { _, file ->
                                SongActionSheet.show(this@SongListActivity, file)
                            }
                        ).also {
                            rv.layoutManager = LinearLayoutManager(this@SongListActivity)
                            rv.adapter = it
                        }
                    }
                    syncSongAdaptersTrailingMode()
                    setupAlphabetSidebar(sortedFiles)
                }

                // 刷新当前可见的子页（专辑/歌手）
                when (currentTabKind) {
                    TabKind.ALBUM -> {
                        refreshAlbumsPage()
                        setupAlbumAlphabetSidebar(albumEntries)
                    }
                    TabKind.ARTIST -> {
                        refreshArtistsPage()
                        setupArtistAlphabetSidebar(artistEntries)
                    }
                    TabKind.GENRE -> {
                        tabPlaceholder.visibility = View.VISIBLE
                        tvPlaceholder.text = "页面开发中"
                    }
                    TabKind.FOLDER -> refreshFoldersPage()
                    else -> {}
                }
                if (folderDetailVisible) populateFolderDetail()

                // 刷新收藏夹
                populateFavorites()
                if (leaderboardVisible) populateLeaderboard()
                // 刷新用户自建歌单 UI（封面可能依赖刚完成的扫描结果）
                if (playlistDetailVisible) populatePlaylistDetail() else populateUserPlaylists()

                // 更新定位按钮
                updateLocateButtons(currentDisplayPath())

                Toast.makeText(this@SongListActivity, "媒体库已刷新（${files.size} 首）", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SongListActivity, "刷新失败", Toast.LENGTH_SHORT).show()
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    companion object {
        private val SETTINGS_TOGGLE_ON_COLOR = 0xFF1565C0.toInt()
        private val SETTINGS_TOGGLE_OFF_COLOR = 0xFFD0D4DA.toInt()
        private const val SETTINGS_TOGGLE_ON_FRAME = 0
        private const val SETTINGS_TOGGLE_OFF_FRAME = 11

        const val EXTRA_OPEN_ARTIST = "com.example.localmusicapp.OPEN_ARTIST"
        const val EXTRA_OPEN_ALBUM = "com.example.localmusicapp.OPEN_ALBUM"
        const val EXTRA_OPEN_ALBUM_ARTIST = "com.example.localmusicapp.OPEN_ALBUM_ARTIST"
    }
}

