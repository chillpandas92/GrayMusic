package com.example.localmusicapp

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.AbsoluteCornerSize
import com.google.android.material.shape.CornerSize
import com.google.android.material.shape.RelativeCornerSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏播放页 —— 点击 Mini Player 从底部滑入。
 */
class PlayerActivity : AppCompatActivity(), PlaybackManager.Listener {

    private lateinit var backdropView: ImageView
    private lateinit var backdropOverlay: View
    private lateinit var ivCover: ShapeableImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var playerLyricPreviewGroup: View
    private lateinit var tvPlayerLyricPreviewLine1: KaraokeLyricTextView
    private lateinit var tvPlayerLyricPreviewLine2: KaraokeLyricTextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvNextTrackHint: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: LottieAnimationView
    private lateinit var btnPlayPauseWrap: ViewGroup
    private lateinit var btnPlayPauseStatic: ImageView
    private lateinit var btnPrev: ImageView
    private lateinit var btnNext: ImageView
    private lateinit var btnMode: ImageButton
    private lateinit var btnQueue: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var btnFavorite: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var settingsHost: View
    private lateinit var playerBottomGlow: View
    private lateinit var pagesScroll: PlayerPagesScrollView
    private lateinit var mainPageRoot: View
    private lateinit var detailPageRoot: View
    private lateinit var pageIndicatorLayout: View
    private lateinit var indicatorLeft: View
    private lateinit var indicatorCenter: View
    private lateinit var indicatorRight: View
    private lateinit var tvDetailTitle: TextView
    private lateinit var tvDetailYear: TextView
    private lateinit var detailArtistRow: View
    private lateinit var detailAlbumRow: View
    private lateinit var ivDetailArtistCover: ShapeableImageView
    private lateinit var ivDetailAlbumCover: ShapeableImageView
    private lateinit var tvDetailArtist: TextView
    private lateinit var tvDetailAlbum: TextView
    private lateinit var productionGroup: View
    private lateinit var tvDetailProductionLabel: TextView
    private lateinit var tvDetailProductionValue: TextView
    private lateinit var tvDetailFileInfoLabel: TextView
    private lateinit var tvDetailFileInfoValue: TextView
    // 歌曲回忆：只展示已结束 session，避免当前播放中的临时时长造成误差。
    private lateinit var tvDetailMemoryTitle: TextView
    private lateinit var tvDetailMemoryDate: TextView
    private lateinit var tvDetailMemoryDuration: TextView
    private lateinit var aiCritiqueGroup: View
    private lateinit var swAiCritique: ImageView
    private lateinit var tvAiCritique: TextView
    private var aiCritiqueJob: Job? = null
    private lateinit var lyricsPageRoot: View
    private lateinit var tvLyricsTitle: TextView
    private lateinit var tvLyricsArtist: TextView
    private lateinit var btnLyricsFavorite: ImageButton
    private lateinit var btnLyricsSource: TextView
    private lateinit var btnLyricsApparel: ImageButton
    private lateinit var lyricsRecycler: LyricsFadeRecyclerView
    private lateinit var lyricsTopSoftFade: View
    private lateinit var playerBottomAccent: View
    private lateinit var lyricsAdapter: LyricLineAdapter

    // 追踪上次已应用到 Lottie 按钮的状态：null 表示尚未初始化
    private var lastLottiePlayingState: Boolean? = null
    private var playerCoverRotationAnimator: ObjectAnimator? = null
    private var nextTrackHintVisible = false

    private var boundPath: String? = null
    private var userSeeking = false
    private val handler = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 300)
        }
    }
    // 事件驱动的歌词推进：不再用固定 33ms / 220ms 的轮询去"踩"时间轴，而是每次推完一行
    // 之后，直接算出"下一次激活集合会变化"的精确毫秒（下一行的 timeMs，或当前活跃行的
    // endTimeMs，取较小者），用 postDelayed 一次性睡到那一刻。这样高亮锁定永远跟着
    // LRC 时间戳走，而不是跟着 30Hz 的刷新栅格走，行与行之间不会再有 0~33ms 的抖动。
    //
    // 副作用：在两次切换之间设备完全不会被唤醒（暂停/无歌词时彻底空闲），耗电更低；同时
    // 由于这个调度器不依赖 lyrics 页是否可见，切到封面页/详情页期间高亮仍会精确推进；
    // 真正的 RecyclerView 滚动只在歌词页稳定显示后校准，避免横向切页中间帧造成错位。
    //
    // 单字卡拉OK 填色仍由 KaraokeLyricTextView 内部用 postInvalidateOnAnimation 自驱动，
    // 并且只在当前 view 实际可见 + 正在播放时才请求下一帧 —— 不在这里管。
    private val lyricsEventTick = object : Runnable {
        override fun run() {
            if (!::lyricsAdapter.isInitialized) return
            val hasTimedLyrics = currentLyricsResult.isTimed && currentLyricsResult.lines.isNotEmpty()
            if (!hasTimedLyrics) return  // 没有时间戳：等下次 result 装载时通过 scheduleLyricsTick 重启

            // 切歌瞬间 PlaybackManager.smoothPositionMs() 可能短暂回 0，不要把高亮拉回开头。
            // 给个温和的 200ms 重试，等 service 把新轨道刻度准备好。
            if (PlaybackManager.isTrackSwitching()) {
                handler.postDelayed(this, 200L)
                return
            }

            val pos = PlaybackManager.smoothPositionMs()
            updateLyricsForPosition(pos)

            // 暂停时：只把当前位置的高亮刷一次，然后停掉调度。下次 onPlaybackChanged
            // (恢复播放) 或 seek 会调用 scheduleLyricsTick 重新唤起。
            if (!PlaybackManager.isPlaying()) return

            // 算到下一次激活集合变化还有多久，精确睡到那一刻。下限 20ms 防止极短时间戳
            // 造成的紧密循环；上限 5s 是兜底（理论上不会触发，因为下一行总会存在或最后
            // 一行 endTimeMs == -1 ⇒ 永久活跃，此时只剩"下一行不存在"分支）。
            val deltaMs = msUntilNextLyricEvent(pos).coerceIn(20L, 5_000L)
            handler.postDelayed(this, deltaMs)
        }
    }

    /**
     * 从当前位置出发，距离下一次"激活集合会变化"还有多少毫秒。变化点有两类：
     *   1. 下一行的 timeMs（这行将变成活跃）
     *   2. 当前活跃行中最早的 endTimeMs（这行将退出活跃）
     * 取两者最小值。lines 已按 timeMs 升序，第一个 timeMs > pos 的就是 1 的最近候选。
     */
    private fun msUntilNextLyricEvent(positionMs: Long): Long {
        val lines = currentLyricsResult.lines
        if (lines.isEmpty()) return 5_000L
        var nextDelta = Long.MAX_VALUE
        // 1) 最近的"下一行激活"
        for (line in lines) {
            if (line.timeMs > positionMs) {
                nextDelta = line.timeMs - positionMs
                break
            }
        }
        // 2) 当前活跃行中最近的"退出活跃"。这里要遍历所有 timeMs <= pos 的行，因为
        // 重叠时间段允许多行同时活跃，它们各自的 endTimeMs 不一定都在最后一行之前。
        for (line in lines) {
            if (line.timeMs > positionMs) break
            if (line.timeMs < 0L) continue
            val end = line.endTimeMs
            if (end > positionMs) {
                val d = end - positionMs
                if (d < nextDelta) nextDelta = d
            }
        }
        return if (nextDelta == Long.MAX_VALUE) 5_000L else nextDelta
    }

    /** 立即重算 + 重新排程下一次精确唤醒。供生命周期 / 状态变化 / seek / 装载完成调用。 */
    private fun scheduleLyricsTick() {
        handler.removeCallbacks(lyricsEventTick)
        handler.post(lyricsEventTick)
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lrcFileLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handlePickedLrcFile(uri)
    }
    private var detailMetaJob: Job? = null
    private var lyricsJob: Job? = null
    private val detailMetaCache = HashMap<String, TrackDetailMeta>()
    private val lyricsCache = HashMap<String, LyricRepository.LyricResult>()
    private var currentLyricsResult: LyricRepository.LyricResult = LyricRepository.LyricResult.EMPTY
    private var detailBaseTopPadding = 0
    private var detailBaseBottomPadding = 0
    private var lyricsBaseTopPadding = 0
    private var lyricsBaseBottomPadding = 0
    private var lastPageWidth = 0
    private var indicatorBaseTopMargin = 0
    private var indicatorActiveColor = 0xDE000000.toInt()
    private var indicatorInactiveColor = 0x4A000000
    private var coverBaseTopMargin = 0
    private var boundLyricsPath: String? = null
    private var activeLyricIndex = -1
    private var lyricsAnchorCorrectionPosted = false
    private var lyricsUserTouching = false
    private var lyricsLastTouchElapsed = 0L
    private var lyricsTouchDownX = 0f
    private var lyricsTouchDownY = 0f
    private var lyricsHorizontalPagingGesture = false
    private var lyricsSnapNextAutoCenter = false
    private var lyricsManualScrollHoldUntil = 0L
    private var playerPrimaryTextColor = Color.BLACK
    private var playerLyricPreviewPrimaryColor = 0xD8000000.toInt()
    private var playerLyricPreviewSecondaryColor = 0x98888888.toInt()
    private var playerLyricAccentColor = Color.BLACK
    // 行切换的滚动 easing：用一个手写的 cubic ease-out (1 - (1-t)^3)，比单纯的减速
    // 插值器多一点"入场快、收尾轻"的物理感，看上去就像歌词自己稳稳落到锁定位、
    // 而不是匀速被拖过去。事件驱动 + 这条曲线一起，让相邻两行的切换不再像机械步进。
    private val lyricsAutoScrollInterpolator = android.view.animation.Interpolator { input ->
        val t = (1f - input).coerceIn(0f, 1f)
        1f - t * t * t
    }

    private data class TrackDetailMeta(
        val year: Int = 0,
        val lyricist: String = "",
        val composer: String = "",
        val fileInfoLine: String = ""
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareEdgeToEdgeWindow()
        setContentView(R.layout.activity_player)
        AppFont.applyTo(findViewById(android.R.id.content))
        CoverDiskCache.init(this)

        backdropView = findViewById(R.id.ivPlayerBackdrop)
        backdropOverlay = findViewById(R.id.playerBackdropOverlay)
        playerBottomGlow = findViewById(R.id.playerBottomGlow)
        pagesScroll = findViewById(R.id.playerPagesScroll)
        mainPageRoot = findViewById(R.id.playerMainPageRoot)
        detailPageRoot = findViewById(R.id.playerDetailPageRoot)
        pageIndicatorLayout = findViewById(R.id.playerPageIndicatorLayout)
        indicatorLeft = findViewById(R.id.playerPageIndicatorLeft)
        indicatorCenter = findViewById(R.id.playerPageIndicatorCenter)
        indicatorRight = findViewById(R.id.playerPageIndicatorRight)
        tvDetailTitle = findViewById(R.id.tvPlayerDetailTitle)
        tvDetailYear = findViewById(R.id.tvPlayerDetailYear)
        detailArtistRow = findViewById(R.id.playerDetailArtistRow)
        detailAlbumRow = findViewById(R.id.playerDetailAlbumRow)
        ivDetailArtistCover = findViewById(R.id.ivPlayerDetailArtistCover)
        ivDetailAlbumCover = findViewById(R.id.ivPlayerDetailAlbumCover)
        tvDetailArtist = findViewById(R.id.tvPlayerDetailArtist)
        tvDetailAlbum = findViewById(R.id.tvPlayerDetailAlbum)
        productionGroup = findViewById(R.id.playerDetailProductionGroup)
        tvDetailProductionLabel = findViewById(R.id.tvPlayerDetailProductionLabel)
        tvDetailProductionValue = findViewById(R.id.tvPlayerDetailProductionValue)
        tvDetailFileInfoLabel = findViewById(R.id.tvPlayerDetailFileInfoLabel)
        tvDetailFileInfoValue = findViewById(R.id.tvPlayerDetailFileInfoValue)
        tvDetailMemoryTitle = findViewById(R.id.tvPlayerDetailMemoryTitle)
        tvDetailMemoryDate = findViewById(R.id.tvPlayerDetailMemoryDate)
        tvDetailMemoryDuration = findViewById(R.id.tvPlayerDetailMemoryDuration)
        aiCritiqueGroup = findViewById(R.id.playerDetailAiCritiqueGroup)
        swAiCritique = findViewById(R.id.swPlayerDetailAiCritique)
        tvAiCritique = findViewById(R.id.tvPlayerDetailAiCritique)
        swAiCritique.setOnClickListener {
            val ctx = this
            val currentlyEnabled = AiCritiqueSettings.isEnabled(ctx)
            val nextEnabled = !currentlyEnabled
            if (nextEnabled && AiCritiqueSettings.getApiKey(ctx).isBlank()) {
                Toast.makeText(ctx, "请先在设置里填入 DeepSeek API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AiCritiqueSettings.setEnabled(ctx, nextEnabled)
            applyAiToggleIcon(nextEnabled)
            refreshAiCritiqueForCurrent()
        }
        lyricsPageRoot = findViewById(R.id.playerLyricsPageRoot)
        tvLyricsTitle = findViewById(R.id.tvPlayerLyricsTitle)
        tvLyricsArtist = findViewById(R.id.tvPlayerLyricsArtist)
        btnLyricsFavorite = findViewById(R.id.btnPlayerLyricsFavorite)
        btnLyricsSource = findViewById(R.id.btnPlayerLyricsSource)
        btnLyricsApparel = findViewById(R.id.btnPlayerLyricsApparel)
        lyricsRecycler = findViewById(R.id.rvPlayerLyrics)
        lyricsTopSoftFade = findViewById(R.id.playerLyricsTopSoftFade)
        lyricsTopSoftFade.visibility = View.GONE
        playerBottomAccent = findViewById(R.id.playerBottomAccent)
        detailBaseTopPadding = detailPageRoot.paddingTop
        detailBaseBottomPadding = detailPageRoot.paddingBottom
        lyricsBaseTopPadding = lyricsPageRoot.paddingTop
        lyricsBaseBottomPadding = lyricsPageRoot.paddingBottom
        ivCover = findViewById(R.id.ivPlayerCover)
        coverBaseTopMargin = (ivCover.layoutParams as? ConstraintLayout.LayoutParams)?.topMargin ?: 0
        tvTitle = findViewById(R.id.tvPlayerTitle)
        tvArtist = findViewById(R.id.tvPlayerArtist)
        playerLyricPreviewGroup = findViewById(R.id.playerLyricPreviewGroup)
        tvPlayerLyricPreviewLine1 = findViewById(R.id.tvPlayerLyricPreviewLine1)
        tvPlayerLyricPreviewLine2 = findViewById(R.id.tvPlayerLyricPreviewLine2)
        tvCurrent = findViewById(R.id.tvPlayerCurrentTime)
        tvTotal = findViewById(R.id.tvPlayerTotalTime)
        tvNextTrackHint = findViewById(R.id.tvPlayerNextTrackHint)
        seekBar = findViewById(R.id.playerSeekBar)
        btnPlayPause = findViewById(R.id.btnPlayerPlayPause)
        btnPlayPauseWrap = findViewById(R.id.btnPlayerPlayPauseWrap)
        btnPlayPauseStatic = findViewById(R.id.btnPlayerPlayPauseStatic)
        btnPrev = findViewById(R.id.btnPlayerPrev)
        btnNext = findViewById(R.id.btnPlayerNext)
        btnMode = findViewById(R.id.btnPlayerMode)
        btnQueue = findViewById(R.id.btnPlayerQueue)
        btnClose = findViewById(R.id.btnPlayerClose)
        btnFavorite = findViewById(R.id.btnPlayerFavorite)
        btnSettings = findViewById(R.id.btnPlayerSettings)
        settingsHost = findViewById(R.id.playerSettingsHost)
        settingsHost.visibility = View.GONE
        applyPlayerCoverShape()
        indicatorBaseTopMargin = (pageIndicatorLayout.layoutParams as? android.widget.FrameLayout.LayoutParams)?.topMargin ?: 0
        pagesScroll.pageCount = 3
        setupLyricsList()

        // Lottie 图标本体是黑色路径，这里把所有层染白以匹配圆形黑底按钮
        val tint = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
        btnPlayPause.addValueCallback(
            KeyPath("**"),
            LottieProperty.COLOR_FILTER,
            LottieValueCallback(tint)
        )
        // 同样把静态覆盖图标染白
        btnPlayPauseStatic.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
        // 收藏图标固定黑/红，避免随封面调色漂移
        btnFavorite.clearColorFilter()
        tvTitle.isSelected = true
        tvTitle.isSingleLine = true

        val cachedLibraryFiles = ScanResultHolder.files(this)
        PlaybackManager.restoreSavedStateIfNeeded(this, cachedLibraryFiles)

        // Lottie 动画结束后，回到静态图标以保证清晰可见
        btnPlayPause.addAnimatorListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {
                btnPlayPauseStatic.visibility = View.INVISIBLE
                btnPlayPause.visibility = View.VISIBLE
            }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                syncPlayPauseStaticIcon()
                btnPlayPause.visibility = View.INVISIBLE
                btnPlayPauseStatic.visibility = View.VISIBLE
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {
                syncPlayPauseStaticIcon()
                btnPlayPause.visibility = View.INVISIBLE
                btnPlayPauseStatic.visibility = View.VISIBLE
            }
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })

        val root = findViewById<View>(R.id.playerRoot)
        val closeBaseTopMargin = (btnClose.layoutParams as? android.widget.FrameLayout.LayoutParams)?.topMargin ?: 0
        val settingsBaseTopMargin = (settingsHost.layoutParams as? android.widget.FrameLayout.LayoutParams)?.topMargin ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            btnClose.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                topMargin = closeBaseTopMargin + bars.top
            }
            pageIndicatorLayout.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                topMargin = indicatorBaseTopMargin + bars.top
            }
            settingsHost.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                topMargin = settingsBaseTopMargin + bars.top
            }
            detailPageRoot.setPadding(
                detailPageRoot.paddingLeft,
                detailBaseTopPadding + bars.top,
                detailPageRoot.paddingRight,
                detailBaseBottomPadding + bars.bottom
            )
            lyricsPageRoot.setPadding(
                lyricsPageRoot.paddingLeft,
                lyricsBaseTopPadding + bars.top,
                lyricsPageRoot.paddingRight,
                lyricsBaseBottomPadding + bars.bottom
            )
            ivCover.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = coverBaseTopMargin + bars.top
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
        pagesScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            updatePageIndicator(scrollX)
        }
        root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            configurePagerPages(
                width = (right - left).coerceAtLeast(0),
                keepCurrentPage = lastPageWidth > 0
            )
        }
        root.post {
            configurePagerPages(root.width.coerceAtLeast(0), keepCurrentPage = false)
            updatePageIndicator()
        }

        btnClose.setOnClickListener { closeWithAnim() }

        btnPlayPauseWrap.setOnClickListener {
            if (currentDisplayFile() == null) return@setOnClickListener
            PlaybackManager.toggle(this)
        }
        btnPrev.setOnClickListener {
            if (PlaybackManager.isTrackSwitching()) return@setOnClickListener
            PlaybackManager.prev(this)
        }
        btnNext.setOnClickListener {
            if (PlaybackManager.isTrackSwitching()) return@setOnClickListener
            PlaybackManager.next(this)
        }

        btnFavorite.setOnClickListener { toggleCurrentFavorite() }
        btnLyricsFavorite.setOnClickListener { toggleCurrentFavorite() }
        btnLyricsSource.setOnClickListener { showLyricsSourceChooser() }
        btnLyricsApparel.setOnClickListener { showApparelSettingsSheet() }

        val openArtistClick = View.OnClickListener { openCurrentArtistSheet() }
        tvArtist.setOnClickListener(openArtistClick)
        detailArtistRow.setOnClickListener(openArtistClick)
        tvDetailArtist.setOnClickListener(openArtistClick)
        tvLyricsArtist.setOnClickListener(openArtistClick)

        val openAlbumClick = View.OnClickListener { openCurrentAlbumSheet() }
        detailAlbumRow.setOnClickListener(openAlbumClick)
        tvDetailAlbum.setOnClickListener(openAlbumClick)

        updateModeIcon()
        btnMode.setOnClickListener { cyclePlayMode() }

        btnQueue.setOnClickListener { showQueueSheet() }
        seekBar.isEnabled = true
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val total = PlaybackManager.totalDurationMs().takeIf { it > 0L }
                    ?: currentDisplayFile()?.duration
                    ?: 0L
                val pos = (total * progress / 1000L).coerceAtLeast(0L)
                tvCurrent.text = formatSeconds(pos / 1000)
                val remainSec = ((total - pos).coerceAtLeast(0L)) / 1000L
                tvTotal.text = "-" + formatSeconds(remainSec)
                // 拖动过程中不再"边拖边 seek"——那样用户手指划一下会听到一小段跳过去的音频。
                // 只更新时间数字和进度条视觉位置，真正的 seek 留到 onStopTrackingTouch 做
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
                // 按下进度条的那一刻把音量掐到 0：拖动过程中完全安静，
                // 不会有原来那段"一边划一边漏出一小段音频"的感觉
                PlaybackManager.setScrubMuted(true)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val total = PlaybackManager.totalDurationMs().takeIf { it > 0L }
                    ?: currentDisplayFile()?.duration
                    ?: 0L
                val progress = seekBar?.progress ?: 0
                val pos = (total * progress / 1000L).coerceAtLeast(0L)
                // 顺序很重要：先 seek 到新位置，再解除静音——
                // 这样解除静音时用户听到的第一帧已经是目标位置的音频，不会漏出旧位置的残音
                PlaybackManager.seekTo(pos)
                PlaybackManager.setScrubMuted(false)
                userSeeking = false
                lyricsManualScrollHoldUntil = 0L
                updateProgress()
                // seek 后旧的 postDelayed 还挂在原本计算出的下一行时间上，但现在 pos 已经
                // 跳到别处，那个唤醒不再正确。立刻按新 pos 重排。
                scheduleLyricsTick()
            }
        })
        seekBar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    pagesScroll.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pagesScroll.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        bindCurrent()
    }

    override fun onStart() {
        super.onStart()
        PlaybackManager.addListener(this)
        PlaybackManager.restoreSavedStateIfNeeded(this, ScanResultHolder.files(this))
        handler.post(progressTick)
        // 启动事件驱动的歌词调度器。它内部会先推一次当前位置（兼顾 forceCenter 行为），
        // 再按下一行的精确 timeMs 排程唤醒；不依赖 lyrics 页是否可见。
        refreshLyricsPositionNow(forceCenter = true)
        scheduleLyricsTick()
        updatePlayerCoverRotation()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(progressTick)
        handler.removeCallbacks(lyricsEventTick)
        playerCoverRotationAnimator?.let { animator ->
            if (animator.isStarted && !animator.isPaused) animator.pause()
        }
        PlaybackManager.removeListener(this)
    }

    override fun onDestroy() {
        detailMetaJob?.cancel()
        lyricsJob?.cancel()
        aiCritiqueJob?.cancel()
        playerCoverRotationAnimator?.cancel()
        playerCoverRotationAnimator = null
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (::pagesScroll.isInitialized && !pagesScroll.isOnMainPage()) {
            pagesScroll.setCurrentPage(1, true)
            return
        }
        closeWithAnim()
    }

    private fun prepareEdgeToEdgeWindow() {
        // 关键：关掉系统栏 fit，内容层（backdrop / overlay / scrim）就会延伸到状态栏 / 导航栏之下
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 状态栏 / 导航栏一直保持透明——颜色由 overlay + scrim 负责，不让系统再补一层色。
        // 这样即使 Palette 还没算完，状态栏区也是 backdrop 的颜色，不会出现白色条带。
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = Color.TRANSPARENT
        }
        // Android 10+ 会在透明栏后面自动叠一层半透明对比色，必须关掉，否则 scrim 颜色会被冲淡
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        // 用新 API 表达"内容延伸到系统栏下"，不再使用被废弃的 systemUiVisibility flags
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    private fun closeWithAnim() {
        finish()
        overridePendingTransition(R.anim.stay, R.anim.slide_down)
    }

    override fun onPlaybackChanged(currentPath: String?, isPlaying: Boolean) {
        bindCurrent()
        updateModeIcon()
        updateFavoriteButton()
        updatePlayerCoverRotation()
        // 播放状态变化（开始/暂停/切歌）时立刻重算下一次唤醒。暂停 → 调度器在跑一次后
        // 自动停掉；恢复播放 → 这里把它叫醒并重新挂到下一行的 timeMs 上。
        if (::lyricsAdapter.isInitialized) scheduleLyricsTick()
    }

    private fun bindCurrent() {
        val displayFile = currentDisplayFile()
        val displayPath = displayFile?.path
        val pathChanged = displayPath != boundPath

        if (pathChanged) {
            boundPath = displayPath
            lyricsManualScrollHoldUntil = 0L
            if (displayFile == null) {
                tvTitle.text = "请播放歌曲"
                tvArtist.text = ""
                setPlayerLyricPreviewLines(null, null, emptySet(), 0L)
                ivCover.setImageResource(R.drawable.music_note_24)
                seekBar.progress = 0
                tvCurrent.text = "00:00"
                tvTotal.text = "-00:00"
                resetThemeColor()
            } else {
                tvTitle.text = displayFile.title
                tvTitle.isSelected = true
                tvArtist.text = ArtistUtils.displayArtists(displayFile.artist)
                val loadingPath = displayFile.path
                CoverLoader.loadHighRes(ivCover, loadingPath, R.drawable.music_note_24) { bmp ->
                    val current = currentDisplayFile()?.path
                    if (current != loadingPath) return@loadHighRes
                    applyThemeFromBitmap(bmp)
                }
            }
        }

        if (displayFile == null) {
            detailMetaJob?.cancel()
            lyricsJob?.cancel()
            boundLyricsPath = null
            updateTrackDetailViews(null, null)
            updateLyricsViews(null, null, isLoading = false)
            setLottiePlayPauseState(isPlaying = false, animate = false)
            btnPlayPauseWrap.alpha = 0.35f
        } else {
            updateTrackDetailViews(displayFile, detailMetaCache[displayFile.path])
            updateLyricsHeader(displayFile)
            if (pathChanged) {
                loadTrackDetailMeta(displayFile)
                bindLyricsForTrack(displayFile)
            } else if (boundLyricsPath != displayFile.path) {
                bindLyricsForTrack(displayFile)
            }
            setLottiePlayPauseState(isPlaying = PlaybackManager.isPlaying(), animate = true)
            btnPlayPauseWrap.alpha = 1.0f
        }
        updateFavoriteButton()
        updateProgress()
    }

    /**
     * 驱动 Lottie 播放/暂停图标：
     * - 帧 0 = 暂停图形（音乐正在播放时显示）
     * - 帧 14 = 播放三角（音乐未播放时显示）
     * - 0→14 是 pause→play 的平滑变形，14→28 是 play→pause 的平滑变形
     * 首次进入或未变化时只做静态设置；状态切换时做一次正向动画。
     */
    private fun setLottiePlayPauseState(isPlaying: Boolean, animate: Boolean) {
        val prev = lastLottiePlayingState
        if (prev == isPlaying) return
        lastLottiePlayingState = isPlaying

        btnPlayPause.cancelAnimation()
        btnPlayPause.frame = if (isPlaying) 0 else 14
        // 固定使用静态矢量图显示播放/暂停状态。Lottie 变形动画的图形边界会随帧变化，
        // 造成播放三角和暂停双竖线看起来左右跳动；这里保留 Lottie 资源但不再显示过渡帧。
        syncPlayPauseStaticIcon()
        btnPlayPause.visibility = View.INVISIBLE
        btnPlayPauseStatic.visibility = View.VISIBLE
        return
    }

    /** 根据当前播放状态把覆盖层的静态图标刷新成对应的 play/pause 矢量图 */
    private fun syncPlayPauseStaticIcon() {
        val playing = lastLottiePlayingState == true
        // 正在播放 → 显示"暂停"图标（点击即暂停）
        // 未播放 → 显示"播放"图标（点击即播放）
        btnPlayPauseStatic.setImageResource(
            if (playing) R.drawable.ic_pause_custom
            else R.drawable.ic_play_custom
        )
    }

    private fun updateFavoriteButton() {
        val file = currentDisplayFile()
        if (file == null) {
            listOf(btnFavorite, btnLyricsFavorite).forEach { button ->
                button.setImageResource(R.drawable.ic_favorite_black_custom)
                button.clearColorFilter()
                button.alpha = 0.35f
                button.isEnabled = false
            }
            return
        }
        val favored = FavoritesStore.isFavorite(this, file.path)
        val icon = if (favored) R.drawable.ic_favorite_filled_custom else R.drawable.ic_favorite_black_custom
        listOf(btnFavorite, btnLyricsFavorite).forEach { button ->
            button.setImageResource(icon)
            button.clearColorFilter()
            button.alpha = 1f
            button.isEnabled = true
        }
    }

    private fun toggleCurrentFavorite() {
        val path = currentDisplayFile()?.path ?: return
        FavoritesStore.toggle(this, path)
        updateFavoriteButton()
    }

    private fun showApparelSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_apparel_settings, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT)
                BottomSheetBehavior.from(sheet).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }

        val btnSquare = view.findViewById<TextView>(R.id.btnPlayerCoverShapeSquare)
        val btnCircle = view.findViewById<TextView>(R.id.btnPlayerCoverShapeCircle)

        fun refreshButtons() {
            val shape = PlaybackSettings.getPlayerCoverShape(this)
            fun apply(button: TextView, selected: Boolean) {
                button.setBackgroundResource(if (selected) R.drawable.tab_bg_on else R.drawable.tab_bg_off)
                button.setTextColor(if (selected) 0xFF1565C0.toInt() else 0xFF333333.toInt())
            }
            apply(btnSquare, shape == PlaybackSettings.PlayerCoverShape.SQUARE)
            apply(btnCircle, shape == PlaybackSettings.PlayerCoverShape.CIRCLE)
        }

        btnSquare.setOnClickListener {
            PlaybackSettings.setPlayerCoverShape(this, PlaybackSettings.PlayerCoverShape.SQUARE)
            applyPlayerCoverShape()
            refreshButtons()
        }
        btnCircle.setOnClickListener {
            PlaybackSettings.setPlayerCoverShape(this, PlaybackSettings.PlayerCoverShape.CIRCLE)
            applyPlayerCoverShape()
            refreshButtons()
        }

        refreshButtons()
        dialog.show()
    }

    private fun applyPlayerCoverShape() {
        if (!::ivCover.isInitialized) return
        val shape = PlaybackSettings.getPlayerCoverShape(this)
        val cornerSize: CornerSize = if (shape == PlaybackSettings.PlayerCoverShape.CIRCLE) {
            RelativeCornerSize(0.5f)
        } else {
            AbsoluteCornerSize(dp(4).toFloat())
        }
        ivCover.shapeAppearanceModel = ivCover.shapeAppearanceModel
            .toBuilder()
            .setAllCornerSizes(cornerSize)
            .build()
        ivCover.invalidate()
        updatePlayerCoverRotation()
    }

    private fun updatePlayerCoverRotation() {
        if (!::ivCover.isInitialized) return
        val circular = PlaybackSettings.getPlayerCoverShape(this) == PlaybackSettings.PlayerCoverShape.CIRCLE
        val hasTrack = currentDisplayFile() != null
        val shouldRotate = circular && hasTrack && PlaybackManager.isPlaying()
        val rotationPeriodMs = 22_000f

        fun positionBasedRotation(): Float {
            val position = PlaybackManager.smoothPositionMs().takeIf { it > 0L }
                ?: PlaybackManager.currentPositionMs().takeIf { it > 0L }
                ?: savedPositionMs()
            return ((position % rotationPeriodMs.toLong()).toFloat() / rotationPeriodMs) * 360f
        }

        if (shouldRotate) {
            if (ivCover.width > 0 && ivCover.height > 0) {
                ivCover.pivotX = ivCover.width / 2f
                ivCover.pivotY = ivCover.height / 2f
            }
            if (playerCoverRotationAnimator?.isPaused == true) {
                playerCoverRotationAnimator?.cancel()
                playerCoverRotationAnimator = null
            }
            if (playerCoverRotationAnimator == null) {
                ivCover.rotation = positionBasedRotation()
            }
            val animator = playerCoverRotationAnimator ?: ObjectAnimator
                .ofFloat(ivCover, View.ROTATION, ivCover.rotation, ivCover.rotation + 360f)
                .apply {
                    duration = rotationPeriodMs.toLong()
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    playerCoverRotationAnimator = this
                }
            if (!animator.isStarted) {
                animator.start()
            } else if (animator.isPaused) {
                animator.resume()
            }
        } else {
            playerCoverRotationAnimator?.let { animator ->
                if (!circular || !hasTrack) {
                    animator.cancel()
                    playerCoverRotationAnimator = null
                } else if (animator.isStarted && !animator.isPaused) {
                    animator.pause()
                }
            }
            if (!circular || !hasTrack) {
                ivCover.animate().rotation(0f).setDuration(180L).start()
            } else {
                ivCover.rotation = positionBasedRotation()
            }
        }
    }

    private fun showLyricsSourceChooser() {
        val displayFile = currentDisplayFile()
        if (displayFile == null) {
            Toast.makeText(this, "请先播放歌曲", Toast.LENGTH_SHORT).show()
            return
        }

        // 用统一的 BottomSheetDialog 上拉抽屉，跟"播放阈值"、"排序"等其它弹层风格一致；
        // 抽屉里只有「内嵌」和「LRC文件」两项，逻辑沿用之前的 popup 版本。
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_lyrics_source, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT)
                BottomSheetBehavior.from(sheet).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }

        val selectedLrcUri = LyricOverrideStore.get(this, displayFile.path).isNotBlank()
        val currentSource = currentLyricsResult.source
        val lrcChecked = selectedLrcUri || currentSource == "LRC文件" || currentSource == "外挂歌词"
        val embeddedChecked = !lrcChecked && currentSource.startsWith("内嵌")

        view.findViewById<View>(R.id.checkLyricsSourceEmbedded).visibility =
            if (embeddedChecked) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.checkLyricsSourceLrc).visibility =
            if (lrcChecked) View.VISIBLE else View.GONE

        val btnAlignLeft = view.findViewById<TextView>(R.id.btnLyricsAlignLeft)
        val btnAlignCenter = view.findViewById<TextView>(R.id.btnLyricsAlignCenter)
        fun refreshLyricsAlignButtons() {
            val alignment = LyricsSettings.getAlignment(this)
            fun apply(button: TextView, selected: Boolean) {
                button.setBackgroundResource(if (selected) R.drawable.tab_bg_on else R.drawable.tab_bg_off)
                button.setTextColor(if (selected) 0xFF1565C0.toInt() else 0xFF333333.toInt())
            }
            apply(btnAlignLeft, alignment == LyricsSettings.Alignment.LEFT)
            apply(btnAlignCenter, alignment == LyricsSettings.Alignment.CENTER)
        }
        fun setLyricsAlignment(alignment: LyricsSettings.Alignment) {
            LyricsSettings.setAlignment(this, alignment)
            lyricsAdapter.setAlignment(alignment)
            lyricsRecycler.stopScroll()
            refreshLyricsPositionNow(forceCenter = true)
            refreshLyricsAlignButtons()
        }
        refreshLyricsAlignButtons()
        btnAlignLeft.setOnClickListener { setLyricsAlignment(LyricsSettings.Alignment.LEFT) }
        btnAlignCenter.setOnClickListener { setLyricsAlignment(LyricsSettings.Alignment.CENTER) }

        view.findViewById<View>(R.id.rowLyricsSourceEmbedded).setOnClickListener {
            dialog.dismiss()
            LyricOverrideStore.clear(this, displayFile.path)
            lyricsCache.remove(displayFile.path)
            bindLyricsForTrack(displayFile)
            Toast.makeText(this, "已切换到内嵌/同名歌词", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.rowLyricsSourceLrc).setOnClickListener {
            dialog.dismiss()
            lrcFileLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
        }

        dialog.show()
    }

    private fun handlePickedLrcFile(uri: Uri) {
        val displayFile = currentDisplayFile()
        if (displayFile == null) {
            Toast.makeText(this, "请先播放歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val targetPath = displayFile.path
        lyricsJob?.cancel()
        updateLyricsViews(displayFile, null, isLoading = true)
        lyricsJob = uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                LyricRepository.loadFromUri(this@PlayerActivity, uri, source = "LRC文件")
            }
            val currentFile = currentDisplayFile()
            if (isFinishing || isDestroyed || currentFile?.path != targetPath) return@launch
            if (result.lines.isEmpty()) {
                lyricsCache.remove(targetPath)
                updateLyricsViews(currentFile, LyricRepository.LyricResult.EMPTY, isLoading = false)
                Toast.makeText(this@PlayerActivity, "该 LRC 文件没有可用歌词", Toast.LENGTH_SHORT).show()
                return@launch
            }
            LyricOverrideStore.set(this@PlayerActivity, targetPath, uri.toString())
            lyricsCache[targetPath] = result
            updateLyricsViews(currentFile, result, isLoading = false)
            Toast.makeText(this@PlayerActivity, "已加载 LRC 歌词", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCurrentArtistSheet() {
        val displayFile = currentDisplayFile() ?: return
        val rawArtist = displayFile.artist.ifBlank { displayFile.albumArtist }
        if (rawArtist.isBlank()) return
        ArtistPicker.pick(this, rawArtist)
    }

    private fun openCurrentAlbumSheet() {
        val displayFile = currentDisplayFile() ?: return
        val albumTitle = displayFile.album.ifBlank { return }
        val albumArtist = displayFile.albumArtist.ifBlank { ArtistUtils.primaryArtist(displayFile.artist) }
        if (!ArtistDetailSheet.showAlbum(this, albumTitle, albumArtist)) {
            Toast.makeText(this, "未找到专辑「$albumTitle」", Toast.LENGTH_SHORT).show()
        }
    }

    private fun configurePagerPages(width: Int, keepCurrentPage: Boolean) {
        if (width <= 0) return
        val targetPage = if (keepCurrentPage && lastPageWidth > 0) pagesScroll.currentPage() else 1
        if (lastPageWidth == width && pagesScroll.pageWidthPx == width) {
            if (!keepCurrentPage) {
                pagesScroll.setCurrentPage(1, false)
                updatePageIndicator()
            }
            return
        }
        lastPageWidth = width
        detailPageRoot.updateLayoutParams<ViewGroup.LayoutParams> {
            this.width = width
        }
        mainPageRoot.updateLayoutParams<ViewGroup.LayoutParams> {
            this.width = width
        }
        lyricsPageRoot.updateLayoutParams<ViewGroup.LayoutParams> {
            this.width = width
        }
        pagesScroll.pageWidthPx = width
        pagesScroll.post {
            pagesScroll.setCurrentPage(targetPage, false)
            updatePageIndicator()
        }
    }

    private fun updatePageIndicator(scrollX: Int = pagesScroll.scrollX) {
        val pageWidth = pagesScroll.pageWidthPx
        if (pageWidth <= 0) return
        val progress = (scrollX.toFloat() / pageWidth.toFloat()).coerceIn(0f, 2f)
        val activeWidth = dp(16)
        val inactiveWidth = dp(6)
        applyPageIndicatorState(indicatorLeft, pageIndex = 0, progress = progress, activeWidth = activeWidth, inactiveWidth = inactiveWidth)
        applyPageIndicatorState(indicatorCenter, pageIndex = 1, progress = progress, activeWidth = activeWidth, inactiveWidth = inactiveWidth)
        applyPageIndicatorState(indicatorRight, pageIndex = 2, progress = progress, activeWidth = activeWidth, inactiveWidth = inactiveWidth)
    }

    private fun lockLyricsAnchorToCurrentPosition(immediate: Boolean) {
        if (!::lyricsAdapter.isInitialized || !::lyricsRecycler.isInitialized) return
        val result = currentLyricsResult
        if (!result.isTimed || result.lines.isEmpty()) return
        val position = PlaybackManager.smoothPositionMs().takeIf { it > 0L } ?: savedPositionMs()
        val activeIndices = LyricRepository.activeIndicesFor(result.lines, position)
        if (activeIndices.isEmpty()) return
        val latestActiveTime = activeIndices.mapNotNull { result.lines.getOrNull(it)?.timeMs }.maxOrNull() ?: return
        val centerIndex = result.lines.indexOfFirst { it.timeMs == latestActiveTime }
        if (centerIndex < 0) return
        activeLyricIndex = centerIndex
        centerLyricPosition(centerIndex, immediate = immediate)
    }

    private fun postLyricsAnchorCorrection(delayMs: Long) {
        if (lyricsAnchorCorrectionPosted || !::lyricsRecycler.isInitialized) return
        lyricsAnchorCorrectionPosted = true
        val action = Runnable {
            lyricsAnchorCorrectionPosted = false
            if (!lyricsUserTouching) {
                lockLyricsAnchorToCurrentPosition(immediate = true)
            }
        }
        if (delayMs <= 0L) {
            lyricsRecycler.post(action)
        } else {
            lyricsRecycler.postDelayed(action, delayMs)
        }
    }

    private fun applyPageIndicatorState(
        view: View,
        pageIndex: Int,
        progress: Float,
        activeWidth: Int,
        inactiveWidth: Int
    ) {
        val activeFraction = (1f - kotlin.math.abs(progress - pageIndex)).coerceIn(0f, 1f)
        applyPageIndicatorState(
            view,
            width = lerp(inactiveWidth, activeWidth, activeFraction),
            color = ColorUtils.blendARGB(indicatorInactiveColor, indicatorActiveColor, activeFraction)
        )
    }

    private fun applyPageIndicatorState(view: View, width: Int, color: Int) {
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            if (this.width != width) {
                this.width = width
            }
        }
        view.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun lerp(start: Int, end: Int, progress: Float): Int {
        return (start + (end - start) * progress.coerceIn(0f, 1f)).toInt()
    }

    private fun updateTrackDetailViews(
        displayFile: MusicScanner.MusicFile?,
        detailMeta: TrackDetailMeta?
    ) {
        if (displayFile == null) {
            tvDetailTitle.text = "请播放歌曲"
            tvDetailYear.text = "发行年份：未知"
            tvDetailArtist.text = "未知艺术家"
            tvDetailAlbum.text = "未知专辑"
            ivDetailArtistCover.setImageResource(R.drawable.music_note_24)
            ivDetailAlbumCover.setImageResource(R.drawable.music_note_24)
            detailArtistRow.alpha = 0.55f
            detailAlbumRow.alpha = 0.55f
            productionGroup.visibility = View.GONE
            tvDetailFileInfoValue.text = "格式 / 文件 / kHz / bits / ch / ReplayGain：未知"
            refreshSongMemoryView(null)
            return
        }

        tvDetailTitle.text = displayFile.title
        val resolvedYear = detailMeta?.year?.takeIf { it in 1000..9999 }
            ?: displayFile.year.takeIf { it in 1000..9999 }
        tvDetailYear.text = "发行年份：" + (resolvedYear?.toString() ?: "未知")

        val artistText = ArtistUtils.displayArtists(
            displayFile.artist.ifBlank { displayFile.albumArtist }
        ).ifBlank { "未知艺术家" }
        val albumText = displayFile.album.ifBlank { "未知专辑" }
        tvDetailArtist.text = artistText
        tvDetailAlbum.text = albumText
        detailArtistRow.alpha = 1f
        detailAlbumRow.alpha = 1f
        val artistCoverPath = ArtistDetailSheet.coverPathFor(this, ArtistUtils.primaryArtist(displayFile.artist.ifBlank { displayFile.albumArtist }).ifBlank { artistText })
        if (artistCoverPath.isNotBlank()) {
            CoverLoader.loadHighRes(ivDetailArtistCover, artistCoverPath, R.drawable.music_note_24)
        } else {
            CoverLoader.load(ivDetailArtistCover, displayFile.path, R.drawable.music_note_24)
        }
        CoverLoader.loadAlbumCover(ivDetailAlbumCover, displayFile.path, R.drawable.music_note_24)

        val pieces = buildList {
            detailMeta?.lyricist?.takeIf { it.isNotBlank() }?.let { add("作词：$it") }
            detailMeta?.composer?.takeIf { it.isNotBlank() }?.let { add("作曲：$it") }
        }
        if (pieces.isEmpty()) {
            productionGroup.visibility = View.GONE
        } else {
            productionGroup.visibility = View.VISIBLE
            tvDetailProductionValue.text = pieces.joinToString("  ")
        }

        tvDetailFileInfoValue.text = detailMeta?.fileInfoLine?.takeIf { it.isNotBlank() }
            ?: buildFallbackFileInfoLine(displayFile)

        refreshSongMemoryView(displayFile)

        refreshAiCritiqueForCurrent()
    }

    /**
     * 刷新"歌曲回忆"内容。
     * 这里只读取已经达到播放阈值的记录；未达到阈值的短试听不计入“听了 X 次”。
     */
    private fun refreshSongMemoryView(displayFile: MusicScanner.MusicFile?) {
        if (!::tvDetailMemoryTitle.isInitialized) return
        tvDetailMemoryTitle.text = "歌曲回忆"
        if (displayFile == null) {
            tvDetailMemoryDate.text = "暂无歌曲回忆"
            tvDetailMemoryDuration.text = ""
            return
        }
        ListenStats.load(this)
        val count = ListenStats.countOf(displayFile.path)
        val last = ListenStats.lastCountEventOf(displayFile.path)
        if (count <= 0 || last == null) {
            tvDetailMemoryDate.text = "暂无达标收听记录"
            tvDetailMemoryDuration.text = ""
            return
        }
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = last.atMs }
        val dateStr = "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
        val timeStr = "%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
        tvDetailMemoryDate.text = "$dateStr · $timeStr · 最近达标收听"
        tvDetailMemoryDuration.text = "听了 $count 次"
    }

    private fun formatMemoryDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return when {
            h > 0L && m > 0L -> "${h}小时${m}分"
            h > 0L -> "${h}小时"
            m > 0L && s > 0L -> "${m}分${s}秒"
            m > 0L -> "${m}分"
            else -> "${s}秒"
        }
    }

    private fun applyAiToggleIcon(on: Boolean) {
        swAiCritique.background = null
        swAiCritique.clearColorFilter()
        val tintColor = if (on) 0xFF1565C0.toInt() else 0xFFD0D4DA.toInt()
        val filter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_ATOP)
        if (swAiCritique is LottieAnimationView) {
            val lottie = swAiCritique as LottieAnimationView
            lottie.cancelAnimation()
            lottie.setAnimation(R.raw.icons8_on_off)
            lottie.repeatCount = 0
            lottie.speed = 1f
            lottie.frame = if (on) 0 else 11
            lottie.progress = if (on) 0f else (11f / 28f)
            lottie.addValueCallback(
                KeyPath("**"),
                LottieProperty.COLOR_FILTER,
                LottieValueCallback(filter)
            )
        } else {
            swAiCritique.setImageResource(if (on) R.drawable.toggle_on else R.drawable.toggle_off)
            swAiCritique.colorFilter = filter
        }
        swAiCritique.alpha = 1f
        swAiCritique.invalidate()
    }

    /**
     * 根据当前歌曲 + AI 开关状态 + 已有缓存，决定是否展示 AI 锐评、是否发起请求。
     * 没有配置 API key → 整块隐藏；配置了但没打开 → 只显示标题和关闭状态的开关；
     * 打开 → 先命中缓存，没有缓存就发起一次请求（取消前一首的未完成请求）。
     */
    private fun refreshAiCritiqueForCurrent() {
        val ctx = this
        val apiKey = AiCritiqueSettings.getApiKey(ctx)
        if (apiKey.isBlank()) {
            aiCritiqueGroup.visibility = View.GONE
            aiCritiqueJob?.cancel()
            aiCritiqueJob = null
            return
        }

        val file = currentDisplayFile()
        if (file == null) {
            aiCritiqueGroup.visibility = View.GONE
            aiCritiqueJob?.cancel()
            aiCritiqueJob = null
            return
        }

        aiCritiqueGroup.visibility = View.VISIBLE
        val enabled = AiCritiqueSettings.isEnabled(ctx)
        applyAiToggleIcon(enabled)

        if (!enabled) {
            tvAiCritique.visibility = View.GONE
            aiCritiqueJob?.cancel()
            aiCritiqueJob = null
            return
        }

        tvAiCritique.visibility = View.VISIBLE

        val path = file.path
        AiCritiqueSettings.cachedCritique(path)?.let { cached ->
            tvAiCritique.text = cached
            aiCritiqueJob?.cancel()
            aiCritiqueJob = null
            return
        }

        // 没有缓存 → 发起请求
        tvAiCritique.text = "正在生成锐评…"
        aiCritiqueJob?.cancel()
        val titleText = file.title
        val artistText = ArtistUtils.displayArtists(
            file.artist.ifBlank { file.albumArtist }
        )
        val albumText = file.album
        val lyricsSnippet = currentLyricsResult.lines
            .asSequence()
            .filter { it.timeMs >= 0L && it.text.isNotBlank() }
            .map { it.text }
            .distinct()
            .take(80)
            .joinToString("\n")
        aiCritiqueJob = uiScope.launch {
            val result = AiCritiqueClient.generateCritique(
                apiKey = apiKey,
                title = titleText,
                artist = artistText,
                album = albumText,
                lyricsSnippet = lyricsSnippet
            )
            // 请求期间可能切歌，回到主线程后再判断当前还是不是这首
            val stillCurrent = currentDisplayFile()?.path == path
            if (!stillCurrent) return@launch
            when (result) {
                is AiCritiqueClient.Result.Ok -> {
                    AiCritiqueSettings.putCritique(path, result.text)
                    tvAiCritique.text = result.text
                }
                is AiCritiqueClient.Result.Err -> {
                    tvAiCritique.text = "生成失败：${result.message}"
                }
            }
        }
    }

    private fun loadTrackDetailMeta(displayFile: MusicScanner.MusicFile) {
        detailMetaJob?.cancel()
        detailMetaCache[displayFile.path]?.let { cached ->
            updateTrackDetailViews(displayFile, cached)
            return
        }

        val targetPath = displayFile.path
        detailMetaJob = uiScope.launch {
            val detailMeta = withContext(Dispatchers.IO) {
                readTrackDetailMeta(displayFile)
            }
            detailMetaCache[targetPath] = detailMeta
            val currentFile = currentDisplayFile()
            if (!isFinishing && !isDestroyed && currentFile?.path == targetPath) {
                updateTrackDetailViews(currentFile, detailMeta)
            }
        }
    }

    private fun readTrackDetailMeta(file: MusicScanner.MusicFile): TrackDetailMeta {
        val path = file.path
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever().apply { setDataSource(path) }
            val rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.trim().orEmpty()
            val rawDate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                ?.trim().orEmpty()
            val lyricist = sanitizeDetailMetaValue(
                MusicScanner.fixEncoding(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_WRITER)
                        ?.trim().orEmpty()
                )
            ).ifBlank {
                sanitizeDetailMetaValue(
                    MusicScanner.fixEncoding(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                            ?.trim().orEmpty()
                    )
                )
            }
            val composer = sanitizeDetailMetaValue(
                MusicScanner.fixEncoding(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                        ?.trim().orEmpty()
                )
            )
            TrackDetailMeta(
                year = extractYear(rawYear) ?: extractYear(rawDate) ?: 0,
                lyricist = lyricist,
                composer = composer,
                fileInfoLine = readFileInfoLine(file)
            )
        } catch (_: Exception) {
            TrackDetailMeta(fileInfoLine = readFileInfoLine(file))
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {
            }
        }
    }


    private fun readFileInfoLine(file: MusicScanner.MusicFile): String {
        val path = file.path
        var extractor: MediaExtractor? = null
        val ext = path.substringAfterLast('.', "").uppercase().ifBlank { "未知格式" }
        val gainText = resolveReplayGainText(file)
        val sizeText = formatFileSizeText(path)
        return try {
            extractor = MediaExtractor().apply { setDataSource(path) }
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    format = f
                    break
                }
            }
            val audioFormat = format
            val sampleRate = audioFormat?.takeIf { it.containsKey(MediaFormat.KEY_SAMPLE_RATE) }
                ?.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = audioFormat?.takeIf { it.containsKey(MediaFormat.KEY_CHANNEL_COUNT) }
                ?.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val bitRate = audioFormat?.takeIf { it.containsKey(MediaFormat.KEY_BIT_RATE) }
                ?.getInteger(MediaFormat.KEY_BIT_RATE)
            val bits = audioFormat?.let { inferBitsPerSample(it, ext) }
            val khzText = sampleRate?.let { "%.1f kHz".format(it / 1000f) } ?: "未知 kHz"
            val bitsText = bits?.let { "$it bits" } ?: bitRate?.let { "${(it / 1000).coerceAtLeast(1)} kbps" } ?: "未知 bits"
            val chText = channels?.let { "$it ch" } ?: "未知 ch"
            "$ext / $sizeText / $khzText / $bitsText / $chText / $gainText"
        } catch (_: Exception) {
            "$ext / $sizeText / 未知 kHz / 未知 bits / 未知 ch / $gainText"
        } finally {
            runCatching { extractor?.release() }
        }
    }

    private fun buildFallbackFileInfoLine(file: MusicScanner.MusicFile): String {
        val ext = file.format.uppercase().ifBlank { file.path.substringAfterLast('.', "").uppercase().ifBlank { "未知格式" } }
        val gainText = ReplayGainStore.getGainDb(this, file.path)?.let { "%.1f dB ReplayGain".format(it) }
            ?: "读取中 ReplayGain"
        return "$ext / ${formatFileSizeText(file.path)} / 未知 kHz / 未知 bits / 未知 ch / $gainText"
    }

    private fun formatFileSizeText(path: String): String {
        val bytes = runCatching { java.io.File(path).length() }.getOrDefault(0L)
        if (bytes <= 0L) return "未知"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "%.2f GB".format(gb)
            mb >= 1.0 -> "%.1f MB".format(mb)
            kb >= 1.0 -> "%.0f KB".format(kb)
            else -> "${bytes} B"
        }
    }

    private fun resolveReplayGainText(file: MusicScanner.MusicFile): String {
        ReplayGainStore.getGainDb(this, file.path)?.let {
            return "%.1f dB ReplayGain".format(it)
        }
        val entry = runCatching { ReplayGainReader.read(file) }.getOrNull()
        val gain = entry?.effectiveGainDb
        if (entry != null && gain != null) {
            ReplayGainStore.saveEntries(this, listOf(entry))
            return "%.1f dB ReplayGain".format(gain)
        }
        return "未读取 ReplayGain"
    }


    private fun inferBitsPerSample(format: MediaFormat, ext: String): Int? {
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            return when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                android.media.AudioFormat.ENCODING_PCM_32BIT,
                android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                else -> null
            }
        }
        return when (ext.lowercase()) {
            "wav" -> 16
            else -> null
        }
    }


    private fun sanitizeDetailMetaValue(value: String): String {
        if (value.isBlank()) return ""
        if (value.equals("<unknown>", ignoreCase = true)) return ""
        return value
    }

    private fun extractYear(raw: String): Int? {
        if (raw.isBlank()) return null
        var index = 0
        while (index <= raw.length - 4) {
            val slice = raw.substring(index, index + 4)
            if (slice.all { it.isDigit() }) {
                val year = slice.toIntOrNull()
                if (year != null && year in 1000..9999) return year
            }
            index++
        }
        return null
    }

    private fun setupLyricsList() {
        lyricsAdapter = LyricLineAdapter { line ->
            if (line.timeMs >= 0L) {
                PlaybackManager.seekTo(line.timeMs)
                lyricsUserTouching = false
                lyricsLastTouchElapsed = 0L
                lyricsManualScrollHoldUntil = 0L
                updateLyricsForPosition(line.timeMs, forceCenter = true)
                updateProgress()
                // 点歌词跳转后，把调度挂到从这一行往后的下一次时间戳变化上。
                scheduleLyricsTick()
            }
        }
        lyricsRecycler.layoutManager = LinearLayoutManager(this)
        lyricsRecycler.adapter = lyricsAdapter
        lyricsRecycler.itemAnimator = null
        lyricsRecycler.setHasFixedSize(false)
        lyricsRecycler.setItemViewCacheSize(28)
        lyricsRecycler.isNestedScrollingEnabled = false
        lyricsRecycler.isVerticalFadingEdgeEnabled = false
        lyricsRecycler.setFadingEdgeLength(dp(72))
        // 动态底部 padding：LinearLayoutManager 不会滚到"内容末尾之后"，所以如果最后一行下面
        // 没有足够的空间，它就无法被滚到 0.24H 的高亮位（会停在底部）。为了让任何一行都能
        // 滚到高亮位，底部 padding 必须 ≥ 可视高度 - targetTop - 一行的高度。
        lyricsRecycler.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val h = bottom - top
            if (h <= 0) return@addOnLayoutChangeListener
            if ((bottom - top) == (oldBottom - oldTop)) return@addOnLayoutChangeListener
            val targetTop = (h * 0.24f).toInt()
            val desiredBottom = (h - targetTop - dp(56)).coerceAtLeast(dp(64))
            if (v.paddingBottom != desiredBottom) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, desiredBottom)
                postLyricsAnchorCorrection(delayMs = 32L)
            }
        }
        lyricsAdapter.setPrimaryColor(playerPrimaryTextColor)
        lyricsAdapter.setAlignment(LyricsSettings.getAlignment(this))
        lyricsRecycler.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lyricsTouchDownX = event.x
                    lyricsTouchDownY = event.y
                    lyricsHorizontalPagingGesture = false
                    // 只把真实纵向拖拽歌词视为用户接管滚动；单纯按住歌词框不暂停自动锁定。
                    lyricsUserTouching = false
                    lyricsLastTouchElapsed = SystemClock.elapsedRealtime()
                    requestLyricsParentDisallowIntercept(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.x - lyricsTouchDownX)
                    val dy = kotlin.math.abs(event.y - lyricsTouchDownY)
                    val slop = dp(8).toFloat()
                    when {
                        dy > slop && dy >= dx * 0.72f -> {
                            lyricsUserTouching = true
                            lyricsLastTouchElapsed = SystemClock.elapsedRealtime()
                            lyricsManualScrollHoldUntil = lyricsLastTouchElapsed + 4_500L
                            requestLyricsParentDisallowIntercept(true)
                        }
                        dx > slop && dx > dy * 1.55f -> {
                            lyricsUserTouching = false
                            lyricsHorizontalPagingGesture = true
                            requestLyricsParentDisallowIntercept(false)
                        }
                        else -> requestLyricsParentDisallowIntercept(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    lyricsLastTouchElapsed = SystemClock.elapsedRealtime()
                    val wasHorizontalPaging = lyricsHorizontalPagingGesture
                    lyricsHorizontalPagingGesture = false
                    val wasDraggingLyrics = lyricsUserTouching
                    if (wasHorizontalPaging) {
                        lyricsUserTouching = false
                    } else if (!wasDraggingLyrics) {
                        lyricsUserTouching = false
                        // 单纯点击或按住歌词框不强制回弹，避免用户刚松手就被锁回当前行。
                    } else {
                        lyricsUserTouching = false
                        lyricsManualScrollHoldUntil = lyricsLastTouchElapsed + 4_500L
                        lyricsSnapNextAutoCenter = false
                    }
                    requestLyricsParentDisallowIntercept(false)
                }
            }
            false
        }
        lyricsAdapter.submitLines(listOf(messageLyricLine("暂无歌词")))
    }

    private fun requestLyricsParentDisallowIntercept(disallow: Boolean) {
        var currentParent = lyricsRecycler.parent
        while (currentParent != null) {
            currentParent.requestDisallowInterceptTouchEvent(disallow)
            if (currentParent === pagesScroll) break
            currentParent = (currentParent as? View)?.parent
        }
    }

    private fun updateLyricsHeader(displayFile: MusicScanner.MusicFile?) {
        if (displayFile == null) {
            tvLyricsTitle.text = "请播放歌曲"
            tvLyricsArtist.text = ""
            return
        }
        tvLyricsTitle.text = displayFile.title
        tvLyricsArtist.text = ArtistUtils.displayArtists(
            displayFile.artist.ifBlank { displayFile.albumArtist }
        ).ifBlank { "未知艺术家" }
    }

    private fun bindLyricsForTrack(displayFile: MusicScanner.MusicFile) {
        val targetPath = displayFile.path
        boundLyricsPath = targetPath
        activeLyricIndex = -1
        // 严格按"最新的内嵌歌词"走：每次切到这首歌都重新读一遍源文件，不让 lyricsCache
        // 把上一次的解析结果当成最终答案。用户外部改了 ID3 / Vorbis / FLAC 标签里的歌词
        // 文本后，回到这首歌就能立刻看到新内嵌歌词，不会被旧的 LyricResult 卡住。
        // 缓存仍保留——只是先把上次的结果先平滑显示出来，避免每次都先闪一下"正在读取歌词…"，
        // 后台读到新内容后，loadTrackLyrics 会用新结果替换。
        val cached = lyricsCache[targetPath]
        if (cached != null) {
            updateLyricsViews(displayFile, cached, isLoading = false)
        } else {
            updateLyricsViews(displayFile, null, isLoading = true)
        }
        loadTrackLyrics(displayFile)
    }

    private fun loadTrackLyrics(displayFile: MusicScanner.MusicFile) {
        lyricsJob?.cancel()
        val targetPath = displayFile.path
        lyricsJob = uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                LyricRepository.load(this@PlayerActivity, displayFile)
            }
            val previous = lyricsCache[targetPath]
            lyricsCache[targetPath] = result
            val currentFile = currentDisplayFile()
            if (!isFinishing && !isDestroyed && currentFile?.path == targetPath) {
                // 仅在新读到的结果和已经摆出来的不一样、或者上一次还没缓存（界面在 isLoading 状态）
                // 时刷新 UI，避免每次回到这首歌都让歌词列表重排一次造成闪一下。
                if (previous == null || previous != result) {
                    updateLyricsViews(currentFile, result, isLoading = false)
                }
            }
        }
    }

    private fun updateLyricsViews(
        displayFile: MusicScanner.MusicFile?,
        result: LyricRepository.LyricResult?,
        isLoading: Boolean
    ) {
        updateLyricsHeader(displayFile)
        activeLyricIndex = -1
        currentLyricsResult = result ?: LyricRepository.LyricResult.EMPTY
        val lines = when {
            displayFile == null -> listOf(messageLyricLine("暂无歌词"))
            isLoading -> listOf(messageLyricLine("正在读取歌词…"))
            result == null || result.lines.isEmpty() -> listOf(messageLyricLine("暂无歌词"))
            else -> result.lines
        }
        lyricsAdapter.submitLines(lines)
        lyricsAdapter.setPrimaryColor(playerPrimaryTextColor)
        lyricsAdapter.setAlignment(LyricsSettings.getAlignment(this))
        val previewPos = PlaybackManager.smoothPositionMs().takeIf { it > 0L } ?: savedPositionMs()
        updatePlayerLyricPreview(previewPos)
        if (!isLoading && result != null && result.isTimed) {
            val pos = PlaybackManager.smoothPositionMs().takeIf { it > 0L } ?: savedPositionMs()
            updateLyricsForPosition(pos, forceCenter = true)
            // 新一首歌的时间戳集刚装好：重算下一次唤醒，把调度挂到这首歌的下一行 timeMs 上。
            scheduleLyricsTick()
        } else {
            centerLyricPosition(0, immediate = true)
        }
    }

    private fun updatePlayerLyricPreview(positionMs: Long) {
        val result = currentLyricsResult
        if (result.lines.isEmpty()) {
            setPlayerLyricPreviewLines(null, null, emptySet(), positionMs)
            return
        }
        val activeTimes = if (result.isTimed) {
            LyricRepository.activeIndicesFor(result.lines, positionMs)
                .mapNotNull { index -> result.lines.getOrNull(index)?.timeMs?.takeIf { it >= 0L } }
                .toSet()
        } else {
            emptySet()
        }
        val previewLines = if (result.isTimed) {
            previewTimedLyricLines(result.lines, positionMs)
        } else {
            result.lines.asSequence()
                .filter { it.text.trim().isNotBlank() }
                .take(2)
                .toList()
        }
        setPlayerLyricPreviewLines(
            previewLines.getOrNull(0),
            previewLines.getOrNull(1),
            activeTimes,
            positionMs
        )
    }

    private fun previewTimedLyricLines(
        lines: List<LyricRepository.LyricLine>,
        positionMs: Long
    ): List<LyricRepository.LyricLine> {
        if (lines.isEmpty()) return emptyList()
        val activeIndices = LyricRepository.activeIndicesFor(lines, positionMs)
        val anchorIndex = when {
            activeIndices.isNotEmpty() -> activeIndices.maxByOrNull { lines[it].timeMs } ?: 0
            else -> LyricRepository.activeIndexFor(lines, positionMs).takeIf { it >= 0 } ?: 0
        }
        return buildPlayerPreviewLines(lines, anchorIndex)
    }

    private fun buildPlayerPreviewLines(
        lines: List<LyricRepository.LyricLine>,
        anchorIndex: Int
    ): List<LyricRepository.LyricLine> {
        if (lines.isEmpty()) return emptyList()
        var startIndex = anchorIndex.coerceIn(0, lines.lastIndex)
        val anchorTime = lines[startIndex].timeMs
        while (startIndex > 0 && lines[startIndex - 1].timeMs == anchorTime) {
            startIndex--
        }
        val preview = ArrayList<LyricRepository.LyricLine>(2)
        var index = startIndex
        while (index < lines.size && preview.size < 2) {
            val line = lines[index]
            if (line.text.trim().isNotBlank()) {
                preview.add(line)
            }
            index++
        }
        return preview
    }

    private fun setPlayerLyricPreviewLines(
        line1: LyricRepository.LyricLine?,
        line2: LyricRepository.LyricLine?,
        activeTimes: Set<Long>,
        positionMs: Long
    ) {
        // 固定两行预览区域的占位高度。即使到最后一行或暂无下一行，也不让进度条上移。
        playerLyricPreviewGroup.visibility = View.VISIBLE
        bindPlayerLyricPreviewLine(
            view = tvPlayerLyricPreviewLine1,
            line = line1,
            activeTimes = activeTimes,
            positionMs = positionMs,
            primarySlot = true
        )
        bindPlayerLyricPreviewLine(
            view = tvPlayerLyricPreviewLine2,
            line = line2,
            activeTimes = activeTimes,
            positionMs = positionMs,
            primarySlot = false
        )
    }

    private fun bindPlayerLyricPreviewLine(
        view: KaraokeLyricTextView,
        line: LyricRepository.LyricLine?,
        activeTimes: Set<Long>,
        positionMs: Long,
        primarySlot: Boolean
    ) {
        val displayLine = line?.takeIf { it.text.trim().isNotBlank() }
        if (displayLine == null) {
            view.text = ""
            view.visibility = View.INVISIBLE
            view.bindKaraokeLine(
                line = messageLyricLine(""),
                active = false,
                currentPositionMs = positionMs,
                fillColor = playerLyricAccentColor,
                unfilledColor = if (primarySlot) playerLyricPreviewPrimaryColor else playerLyricPreviewSecondaryColor
            )
            return
        }

        view.visibility = View.VISIBLE
        val active = displayLine.timeMs >= 0L && activeTimes.contains(displayLine.timeMs)
        val hasWordTiming = active && displayLine.words.isNotEmpty()
        val baseColor = if (primarySlot) playerLyricPreviewPrimaryColor else playerLyricPreviewSecondaryColor
        val activeAlpha = if (primarySlot) 236 else 222
        val unfilledAlpha = if (primarySlot) 146 else 132
        val resolvedTextColor = if (active) {
            ColorUtils.setAlphaComponent(playerLyricAccentColor, activeAlpha)
        } else {
            baseColor
        }
        val unfilledColor = ColorUtils.setAlphaComponent(baseColor, unfilledAlpha)
        view.text = displayLine.text.trim()
        view.maxLines = 1
        view.ellipsize = android.text.TextUtils.TruncateAt.END
        view.setTextColor(if (hasWordTiming) unfilledColor else resolvedTextColor)
        view.bindKaraokeLine(
            line = displayLine,
            active = hasWordTiming,
            currentPositionMs = positionMs,
            fillColor = playerLyricAccentColor,
            unfilledColor = unfilledColor
        )
    }

    private fun messageLyricLine(text: String): LyricRepository.LyricLine {
        return LyricRepository.LyricLine(
            timeMs = -1L,
            text = text,
            sourceIndex = 0,
            groupIndex = 0,
            groupSize = 1,
            isContinuationInGroup = false
        )
    }

    private fun refreshLyricsPositionNow(forceCenter: Boolean) {
        if (!::lyricsAdapter.isInitialized) return
        val position = PlaybackManager.smoothPositionMs().takeIf { it > 0L } ?: savedPositionMs()
        updateLyricsForPosition(position, forceCenter = forceCenter)
    }

    private fun updateLyricsForPosition(
        positionMs: Long,
        forceCenter: Boolean = false,
        allowScroll: Boolean = true
    ) {
        if (!::lyricsAdapter.isInitialized) return
        lyricsAdapter.setPlaybackPosition(positionMs)
        updatePlayerLyricPreview(positionMs)
        val result = currentLyricsResult
        if (!result.isTimed || result.lines.isEmpty()) {
            lyricsAdapter.setActiveTimes(emptySet())
            return
        }
        // 支持时间重叠：拿所有当前处于活跃时间段的行，统一传给 adapter 做同步高亮。
        val activeIndices = LyricRepository.activeIndicesFor(result.lines, positionMs)
        if (activeIndices.isEmpty()) {
            if (activeLyricIndex != -1) {
                activeLyricIndex = -1
                lyricsAdapter.setActiveTimes(emptySet())
            }
            return
        }
        // 居中滚动跟随“最新开始时间”所在 DOM 的第一行。
        // 相同 DOM 多行歌词同时高亮时，固定第一行位置，避免滚动锚点在同一组内跳动。
        val latestActiveTime = activeIndices.mapNotNull { result.lines.getOrNull(it)?.timeMs }.maxOrNull() ?: -1L
        val centerIndex = if (latestActiveTime >= 0L) {
            result.lines.indexOfFirst { it.timeMs == latestActiveTime }
        } else {
            -1
        }
        val activeTimesSet = activeIndices.mapNotNull {
            result.lines.getOrNull(it)?.timeMs?.takeIf { t -> t >= 0L }
        }.toSet()
        lyricsAdapter.setActiveTimes(activeTimesSet)
        if (!allowScroll) {
            activeLyricIndex = centerIndex
            return
        }
        // 歌词框使用独立的垂直锚点逻辑：横向页面切换只移动页面，不再改变歌词列表的
        // 滚动策略。自动居中只由歌词时间推进、seek 或点选歌词触发。
        val shouldAutoCenter = forceCenter || canAutoCenterLyrics()
        if (!shouldAutoCenter) return
        val alreadyLocked = centerIndex == activeLyricIndex && isLyricPositionLocked(centerIndex)
        if (alreadyLocked && !forceCenter) return
        activeLyricIndex = centerIndex
        if (centerIndex >= 0) {
            val immediateCenter = forceCenter || lyricsSnapNextAutoCenter
            centerLyricPosition(centerIndex, immediate = immediateCenter)
            if (!forceCenter && lyricsSnapNextAutoCenter) {
                lyricsSnapNextAutoCenter = false
            }
        }
    }

    private fun isLyricPositionLocked(position: Int): Boolean {
        if (!::lyricsRecycler.isInitialized || position < 0) return false
        if (lyricsRecycler.height <= 0) return false
        val layoutManager = lyricsRecycler.layoutManager as? LinearLayoutManager ?: return false
        val targetView = layoutManager.findViewByPosition(position) ?: return false
        val targetTop = (lyricsRecycler.height * 0.24f).toInt().coerceAtLeast(0)
        return kotlin.math.abs(targetView.top - targetTop) <= dp(2)
    }

    private fun canAutoCenterLyrics(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lyricsUserTouching) return false
        // 手动拖动歌词后保留一段自由浏览时间，不在松手瞬间把列表锁回当前高亮行。
        if (now < lyricsManualScrollHoldUntil) return false
        return true
    }

    private fun centerLyricPosition(position: Int, immediate: Boolean) {
        if (!::lyricsRecycler.isInitialized || position < 0) return
        val layoutManager = lyricsRecycler.layoutManager as? LinearLayoutManager ?: return
        if (lyricsRecycler.height <= 0) {
            lyricsRecycler.post { centerLyricPosition(position, immediate = true) }
            return
        }
        val targetTopFraction = 0.24f
        val targetTop = (lyricsRecycler.height * targetTopFraction).toInt().coerceAtLeast(0)
        // immediate 只来自歌词自身逻辑（切歌、seek、点选歌词、首次装载）。横向页面切换
        // 不再把歌词列表改成瞬移模式，避免切页后高亮行位置被重新拉动。
        if (immediate) {
            val targetView = layoutManager.findViewByPosition(position)
            if (targetView != null && kotlin.math.abs(targetView.top - targetTop) <= dp(1)) return
            lyricsRecycler.stopScroll()
            layoutManager.scrollToPositionWithOffset(position, targetTop)
            lyricsRecycler.post {
                val viewAfterLayout = layoutManager.findViewByPosition(position)
                if (viewAfterLayout != null && kotlin.math.abs(viewAfterLayout.top - targetTop) > dp(1)) {
                    layoutManager.scrollToPositionWithOffset(position, targetTop)
                }
            }
            return
        }

        val targetView = layoutManager.findViewByPosition(position)
        if (targetView != null) {
            val dy = targetView.top - targetTop
            val absDy = kotlin.math.abs(dy)
            if (absDy <= dp(1)) return
            // 相邻歌词切换保持细腻缓动，同时缩短尾部时间，避免滑回歌词页或松手后追赶明显。
            val duration = (280 + absDy * 0.36f).toInt().coerceIn(280, 860)
            lyricsRecycler.smoothScrollBy(0, dy, lyricsAutoScrollInterpolator, duration)
            return
        }

        val smoothScroller = object : LinearSmoothScroller(this) {
            override fun getVerticalSnapPreference(): Int = LinearSmoothScroller.SNAP_TO_ANY

            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int {
                return boxStart + targetTop - viewStart
            }

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                return 210f / displayMetrics.densityDpi
            }
        }
        smoothScroller.targetPosition = position
        layoutManager.startSmoothScroll(smoothScroller)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun applyLyricsSourceButtonTint(color: Int) {
        if (!::btnLyricsSource.isInitialized) return
        btnLyricsSource.setTextColor(color)
        btnLyricsSource.background = lyricSquareIconBackground(color)
        applyLyricsApparelButtonTint(color)
    }

    private fun applyLyricsApparelButtonTint(color: Int) {
        if (!::btnLyricsApparel.isInitialized) return
        btnLyricsApparel.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        btnLyricsApparel.background = lyricSquareIconBackground(color)
    }

    private fun lyricSquareIconBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1).coerceAtLeast(1), color)
        }
    }

    // ============================================================
    // 沉浸式动态着色：Palette + 模糊封面 + 渐变蒙层 + 对比度适配
    // ============================================================

    private fun applyThemeFromBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) {
            resetThemeColor()
            return
        }

        val initialBackdropBitmap = createSoftBackdropBitmap(bitmap) ?: bitmap
        backdropView.setImageBitmap(initialBackdropBitmap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 保持封面足够模糊，但让底色更多来自封面本身，而不是白色蒙层。
            backdropView.setRenderEffect(RenderEffect.createBlurEffect(280f, 280f, Shader.TileMode.CLAMP))
        }
        backdropView.alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.42f else 0.34f

        Palette.from(bitmap)
            .maximumColorCount(32)
            .clearFilters()
            .generate { palette ->
                if (palette == null) {
                    resetThemeColor()
                    return@generate
                }
                val fallback = palette.getDominantColor(Color.parseColor("#DDE3DD"))
                val sortedSwatches = palette.swatches.sortedByDescending { it.population }
                val dominantSwatch = sortedSwatches.firstOrNull()
                val dominant = dominantSwatch?.rgb ?: fallback
                val secondarySwatch = sortedSwatches.firstOrNull { swatch ->
                    swatch.population >= ((dominantSwatch?.population ?: 0) * 0.10f).toInt() &&
                        colorDistance(dominant, swatch.rgb) > 36.0
                }
                val secondary = secondarySwatch?.rgb ?: palette.getVibrantColor(
                    palette.getMutedColor(lightenColor(dominant, 0.08f))
                )
                val topBandDominant = dominantColorFromRegion(bitmap, 0f, 0.34f, dominant)

                val dominantLuma = luminanceOf(dominant)
                val topBandLuma = luminanceOf(topBandDominant)
                // 不再叠加 createEnhancedBackdropBitmap 的径向色晕。那些 radial gradient 会把
                // 主色 / 副色提亮加饱和后盖在封面上，虽然好看但会把颜色"拉偏"，不再是封面原色。
                // 现在只保留 createSoftBackdropBitmap 的 downsample-upsample 软化 + RenderEffect 高斯，
                // 颜色还原封面真实。
                val darkSeed = palette.getDarkVibrantColor(
                    palette.getDarkMutedColor(darkenColor(dominant, if (dominantLuma > 0.62) 0.16f else 0.22f))
                )
                val lightSeed = palette.getLightVibrantColor(
                    palette.getLightMutedColor(lightenColor(dominant, if (dominantLuma > 0.62) 0.48f else 0.36f))
                )
                val mutedSeed = palette.getMutedColor(dominant)
                val brightBias = if (dominantLuma > 0.62) 0.86f else 0.76f
                val accentBase = saturateColor(ColorUtils.blendARGB(darkSeed, mutedSeed, 0.24f), 0.84f)
                val topColor = ColorUtils.blendARGB(
                    lightenColor(ColorUtils.blendARGB(lightSeed, dominant, 0.36f), if (dominantLuma > 0.62) 0.28f else 0.22f),
                    Color.WHITE,
                    if (dominantLuma > 0.62) 0.60f else 0.52f
                )
                val secondaryGlow = ColorUtils.blendARGB(
                    lightenColor(saturateColor(secondary, 0.92f), 0.28f),
                    Color.WHITE,
                    0.42f
                )
                val topBandBlurSeed = ColorUtils.blendARGB(
                    saturateColor(topBandDominant, 0.90f),
                    Color.WHITE,
                    if (topBandLuma > 0.58) 0.38f else 0.28f
                )
                val bottomBase = ColorUtils.blendARGB(darkSeed, mutedSeed, 0.20f)
                val bottomBrightSeed = ColorUtils.blendARGB(
                    lightenColor(topBandBlurSeed, 0.20f),
                    ColorUtils.blendARGB(lightSeed, Color.WHITE, 0.12f),
                    0.66f
                )
                val bottomColor = liftColorPreservingHue(
                    ColorUtils.blendARGB(bottomBase, bottomBrightSeed, 0.90f),
                    if (dominantLuma > 0.62) 0.08f else 0.11f
                )
                val accentColor = darkenColor(
                    ColorUtils.blendARGB(accentBase, bottomBrightSeed, 0.22f),
                    if (luminanceOf(accentBase) > 0.54) 0.12f else 0.06f
                )
                val softBottomGlowTint = ColorUtils.blendARGB(
                    lightenColor(lightSeed, if (dominantLuma > 0.62) 0.16f else 0.22f),
                    Color.WHITE,
                    if (dominantLuma > 0.62) 0.52f else 0.40f
                )
                applyImmersiveColors(
                    topColor = topColor,
                    secondaryColor = secondaryGlow,
                    bottomColor = bottomColor,
                    accentColor = accentColor,
                    darkUiSeed = darkSeed,
                    bottomBlurSeed = topBandBlurSeed,
                    bottomGlowTint = softBottomGlowTint
                )
            }
    }

    private fun applyLyricsSurfaceTreatments(topColor: Int, bottomGlowTint: Int) {
        // 歌词页顶部只保留内容本身的透明渐隐，不再叠加白色雾层或矩形 fade view，
        // 避免顶部出现一整块发白的“框”。
        lyricsRecycler.setTopSoftTint(Color.TRANSPARENT, Color.TRANSPARENT)
        lyricsTopSoftFade.background = null
        lyricsTopSoftFade.visibility = View.GONE

        playerBottomAccent.visibility = View.GONE
        playerBottomAccent.alpha = 0f
        playerBottomAccent.background = null

        val glowCore = ColorUtils.blendARGB(bottomGlowTint, Color.WHITE, 0.14f)
        val glowEdge = ColorUtils.blendARGB(bottomGlowTint, Color.WHITE, 0.34f)
        playerBottomGlow.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(glowCore, 10),
                ColorUtils.setAlphaComponent(glowCore, 30),
                ColorUtils.setAlphaComponent(glowCore, 68),
                ColorUtils.setAlphaComponent(glowEdge, 126),
                ColorUtils.setAlphaComponent(glowEdge, 162)
            )
        )
        playerBottomGlow.alpha = 1f
    }

    private fun applyImmersiveColors(
        topColor: Int,
        secondaryColor: Int,
        bottomColor: Int,
        accentColor: Int,
        darkUiSeed: Int,
        bottomBlurSeed: Int,
        bottomGlowTint: Int
    ) {
        // 目标是更稳定的"封面色雾化"效果：色相完全来自封面，但保证整体不会脏、不会黑。
        // 之前 whiteBlend 0.05–0.08 + saturationFactor 0.94–1.00 + lift 0.015 这套组合
        // 在浅色封面上还行，遇到黑色 / 暗灰色封面时雾化层会被压成偏黑的深色泥巴；
        // 这一版统一抬白雾化和 lift，并通过 mistColor 的 minLightness 给所有层加一个"地板"，
        // 让封面再暗也能在雾化层里保留一个明亮的色场，色相依旧来自封面，不会再灰扑扑。
        val topSurface = mistColor(
            ColorUtils.blendARGB(topColor, Color.WHITE, 0.10f),
            saturationFactor = 0.92f,
            whiteBlend = 0.16f,
            lift = 0.06f,
            minLightness = 0.78f
        )
        val coverGlowColor = mistColor(
            lightenColor(ColorUtils.blendARGB(topSurface, secondaryColor, 0.50f), 0.10f),
            saturationFactor = 0.94f,
            whiteBlend = 0.14f,
            lift = 0.05f,
            minLightness = 0.74f
        )
        val upperMidColor = mistColor(
            liftColorPreservingHue(coverGlowColor, 0.06f),
            saturationFactor = 0.92f,
            whiteBlend = 0.15f,
            lift = 0.05f,
            minLightness = 0.72f
        )
        val lowerMidBase = ColorUtils.blendARGB(secondaryColor, bottomColor, 0.46f)
        val lowerMidColor = mistColor(
            lightenColor(liftColorPreservingHue(lowerMidBase, 0.10f), 0.12f),
            saturationFactor = 0.90f,
            whiteBlend = 0.16f,
            lift = 0.05f,
            minLightness = 0.70f
        )
        val bottomBlurBase = ColorUtils.blendARGB(bottomColor, bottomBlurSeed, 0.62f)
        val bottomBlurColor = mistColor(
            lightenColor(liftColorPreservingHue(bottomBlurBase, 0.10f), 0.12f),
            saturationFactor = 0.88f,
            whiteBlend = 0.18f,
            lift = 0.05f,
            minLightness = 0.68f
        )
        val fadeBelowMetaColor = ColorUtils.blendARGB(lowerMidColor, Color.WHITE, 0.12f)
        val statusInsetColor = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(topSurface, upperMidColor, 0.30f),
            255
        )
        val navigationInsetColor = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(bottomBlurColor, Color.WHITE, 0.06f),
            255
        )
        applyLyricsSurfaceTreatments(topSurface, bottomGlowTint)
        // overlay alpha 上调：mist 颜色已经被提亮到带 minLightness 兜底，保持原来的低 alpha
        // 会让下面那张接近原色的 2×2 低采样 backdrop（在暗色封面下会发黑发脏）继续穿过来；
        // 把 alpha 提高到 226–236 之间，让明亮的 mist 雾化层主导，整体不会再脏不会再暗，
        // 又因为 mist 自身仍由封面色相驱动，主题色没丢。
        backdropOverlay.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.setAlphaComponent(statusInsetColor, 236),
                ColorUtils.setAlphaComponent(upperMidColor, 232),
                ColorUtils.setAlphaComponent(lowerMidColor, 230),
                ColorUtils.setAlphaComponent(fadeBelowMetaColor, 226),
                ColorUtils.setAlphaComponent(navigationInsetColor, 234)
            )
        )
        applySystemBarColors(statusInsetColor, navigationInsetColor)

        val uiReference = ColorUtils.blendARGB(statusInsetColor, navigationInsetColor, 0.60f)
        val useDarkSystemIcons = luminanceOf(uiReference) > 0.60
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = useDarkSystemIcons
            isAppearanceLightNavigationBars = useDarkSystemIcons
        }

        val uiBase = ColorUtils.blendARGB(darkUiSeed, accentColor, 0.24f)
        // darkReadableTextColor 现在会保留封面的 hue 并把 lightness 压到 0.10–0.20 区间。
        // 旧版会再 blendARGB(BLACK, 0.14) 把它推近黑色，主色味就丢了——这里直接用结果，
        // 保证三个播放页（主页 / 歌词 / 详情）的文字颜色更直观地体现封面主题色。
        val baseTextColor = darkReadableTextColor(uiBase)
        val primaryText = baseTextColor
        // 副文色：hue 与主色一致，alpha 184（约 72%）拉低视觉权重，比再混黑更干净。
        val secondaryText = ColorUtils.setAlphaComponent(baseTextColor, 184)
        val iconColor = darkReadableTextColor(
            ColorUtils.blendARGB(darkUiSeed, accentColor, 0.16f)
        )
        val trackColor = ColorUtils.setAlphaComponent(iconColor, 54)
        val playButtonSeed = darkenColor(
            ColorUtils.blendARGB(accentColor, bottomBlurColor, 0.46f),
            if (luminanceOf(accentColor) > 0.54) 0.18f else 0.10f
        )
        val playButtonFill = ColorUtils.blendARGB(
            playButtonSeed,
            Color.BLACK,
            0.04f
        )
        val playGlyph = iconColor

        ivCover.setBackgroundColor(Color.TRANSPARENT)
        tvTitle.setTextColor(primaryText)
        tvArtist.setTextColor(secondaryText)
        playerLyricPreviewPrimaryColor = ColorUtils.setAlphaComponent(primaryText, 216)
        playerLyricPreviewSecondaryColor = ColorUtils.setAlphaComponent(secondaryText, 208)
        tvPlayerLyricPreviewLine1.setTextColor(playerLyricPreviewPrimaryColor)
        tvPlayerLyricPreviewLine2.setTextColor(playerLyricPreviewSecondaryColor)
        tvCurrent.setTextColor(secondaryText)
        tvTotal.setTextColor(secondaryText)
        tvNextTrackHint.setTextColor(ColorUtils.setAlphaComponent(primaryText, 186))
        playerPrimaryTextColor = primaryText
        indicatorActiveColor = ColorUtils.setAlphaComponent(iconColor, 230)
        indicatorInactiveColor = ColorUtils.setAlphaComponent(iconColor, 88)
        updatePageIndicator()
        tvDetailTitle.setTextColor(primaryText)
        tvDetailYear.setTextColor(secondaryText)
        tvDetailArtist.setTextColor(secondaryText)
        tvDetailAlbum.setTextColor(secondaryText)
        tvDetailProductionLabel.setTextColor(primaryText)
        tvDetailProductionValue.setTextColor(secondaryText)
        tvDetailFileInfoLabel.setTextColor(primaryText)
        tvDetailFileInfoValue.setTextColor(secondaryText)
        // 歌曲回忆：标题用主文色，日期与时长跟随副文色。
        tvDetailMemoryTitle.setTextColor(primaryText)
        tvDetailMemoryDate.setTextColor(secondaryText)
        tvDetailMemoryDuration.setTextColor(secondaryText)
        tvLyricsTitle.setTextColor(primaryText)
        tvLyricsArtist.setTextColor(secondaryText)
        val bgLuma = luminanceOf(uiReference)
        val accentLuma = luminanceOf(accentColor)
        val lyricAccentSeed = when {
            // 背景偏亮：当前行进一步压暗 + 提饱和，形成"深色高饱和"对比
            bgLuma > 0.55 && accentLuma > 0.42 -> darkenColor(saturateColor(accentColor, 1.16f), 0.30f)
            bgLuma > 0.55 && accentLuma > 0.30 -> darkenColor(saturateColor(accentColor, 1.12f), 0.18f)
            // 背景偏暗：提亮 + 提饱和让活动行更醒目
            bgLuma < 0.30 && accentLuma < 0.42 -> saturateColor(lightenColor(accentColor, 0.22f), 1.14f)
            else -> saturateColor(accentColor, 1.12f)
        }
        // 之前 blend(seed, primaryText, 0.58) 把 58% 拉回去 primaryText，accent 几乎消失；
        // primaryText 现在已经带封面色相，accent 跟它太接近反而看不出"当前行"——
        // 这里把 primaryText 的混入比降到 0.32，accent 自身的饱和保留更多，活动行更醒目。
        playerLyricAccentColor = ColorUtils.blendARGB(lyricAccentSeed, primaryText, 0.32f)
        if (::lyricsAdapter.isInitialized) {
            lyricsAdapter.setPrimaryColor(primaryText)
            lyricsAdapter.setAccentColor(playerLyricAccentColor)
        }
        updatePlayerLyricPreview(PlaybackManager.smoothPositionMs().takeIf { it > 0L } ?: savedPositionMs())

        seekBar.progressTintList = ColorStateList.valueOf(iconColor)
        seekBar.progressBackgroundTintList = ColorStateList.valueOf(trackColor)

        btnClose.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
        btnSettings.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
        btnPrev.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
        btnNext.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
        btnMode.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
        btnQueue.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
        btnFavorite.clearColorFilter()
        btnLyricsFavorite.clearColorFilter()
        applyLyricsSourceButtonTint(iconColor)

        btnPlayPauseWrap.background = null
        applyPlayPauseIconTint(playGlyph)
    }

    private fun applySystemBarColors(statusColor: Int, navigationColor: Int) {
        // 保持系统栏完全透明：颜色由 playerBackdropOverlay 渐变绘制，
        // overlay 延伸到系统栏下面。不往 window.statusBarColor 写不透明色——
        // 那会盖掉 backdrop 图，反而让颜色"不连贯"。
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun applyPlayPauseIconTint(color: Int) {
        val tint = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        btnPlayPause.addValueCallback(
            KeyPath("**"),
            LottieProperty.COLOR_FILTER,
            LottieValueCallback(tint)
        )
        btnPlayPauseStatic.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    private fun createEnhancedBackdropBitmap(source: Bitmap, dominant: Int, secondary: Int): Bitmap? {
        val base = createSoftBackdropBitmap(source) ?: return null
        return try {
            val result = if (base.config == Bitmap.Config.ARGB_8888 && base.isMutable) {
                base
            } else {
                base.copy(Bitmap.Config.ARGB_8888, true)
            }
            val width = result.width.toFloat()
            val height = result.height.toFloat()
            val maxSide = maxOf(width, height)
            val canvas = Canvas(result)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            paint.color = brightenBackdropBase(ColorUtils.blendARGB(dominant, secondary, 0.42f))
            paint.alpha = 68
            canvas.drawRect(0f, 0f, width, height, paint)

            paint.shader = RadialGradient(
                width * 0.24f,
                height * 0.22f,
                maxSide * 0.76f,
                ColorUtils.setAlphaComponent(brightenBackdropAccent(saturateColor(dominant, 1.12f), 0.26f), 132),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width, height, paint)

            paint.shader = RadialGradient(
                width * 0.50f,
                height * 0.34f,
                maxSide * 0.58f,
                ColorUtils.setAlphaComponent(
                    brightenBackdropAccent(ColorUtils.blendARGB(saturateColor(dominant, 1.08f), secondary, 0.34f), 0.30f),
                    172
                ),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width, height, paint)

            paint.shader = RadialGradient(
                width * 0.78f,
                height * 0.48f,
                maxSide * 0.74f,
                ColorUtils.setAlphaComponent(brightenBackdropAccent(saturateColor(secondary, 1.14f), 0.22f), 118),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width, height, paint)

            paint.shader = RadialGradient(
                width * 0.52f,
                height * 0.86f,
                maxSide * 0.74f,
                ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(
                        brightenBackdropAccent(ColorUtils.blendARGB(dominant, secondary, 0.50f), 0.10f),
                        Color.WHITE,
                        0.34f
                    ),
                    72
                ),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width, height, paint)
            result
        } catch (_: Exception) {
            base
        }
    }

    private fun createSoftBackdropBitmap(source: Bitmap): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        return try {
            // 缩到一个非常小的尺寸再放大 —— 相当于马赛克式模糊。数字越小越彻底地把封面
            // 打散成"几块颜色"而不是"一张模糊的照片"，配合 API 31+ 的 RenderEffect 高斯，
            // 最终背景看起来更像柔和的色场（color field），而不是模糊的封面原图。
            //
            // 之前用 minSide = 8（短边 8 像素，大约 64–96 个色块）；用户反馈高斯模糊不够纯色，
            // 这里改成 minSide = 2（短边 2 像素），加上 280f 的高斯，
            // 整张背景更像是"封面主题色的一片纯色雾"，色相变化非常缓。
            val minSide = 2
            val ratio = source.width.toFloat() / source.height.toFloat()
            val smallWidth: Int
            val smallHeight: Int
            if (ratio >= 1f) {
                smallHeight = minSide
                smallWidth = (minSide * ratio).toInt().coerceAtLeast(minSide)
            } else {
                smallWidth = minSide
                smallHeight = (minSide / ratio).toInt().coerceAtLeast(minSide)
            }
            val tiny = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
            Bitmap.createScaledBitmap(tiny, source.width, source.height, true).also {
                if (tiny !== source && !tiny.isRecycled) tiny.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun dominantColorFromRegion(
        bitmap: Bitmap,
        startFraction: Float,
        endFraction: Float,
        fallback: Int
    ): Int {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return fallback

        val startY = (bitmap.height * startFraction.coerceIn(0f, 1f)).toInt()
            .coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
        val endY = (bitmap.height * endFraction.coerceIn(0f, 1f)).toInt()
            .coerceIn(startY + 1, bitmap.height)
        val regionHeight = (endY - startY).coerceAtLeast(1)

        var region: Bitmap? = null
        return try {
            region = Bitmap.createBitmap(bitmap, 0, startY, bitmap.width, regionHeight)
            Palette.from(region)
                .maximumColorCount(16)
                .clearFilters()
                .generate()
                .getDominantColor(fallback)
        } catch (_: Exception) {
            fallback
        } finally {
            if (region != null && region !== bitmap && !region.isRecycled) {
                region.recycle()
            }
        }
    }

    private fun liftColorPreservingHue(color: Int, amount: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = (hsl[1] * 0.96f).coerceIn(0f, 1f)
        hsl[2] = (hsl[2] + amount.coerceIn(0f, 0.16f)).coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun darkReadableTextColor(seed: Int): Int {
        // 之前是把 seed 跟 BLACK 混 42% 再循环加深到 luma < 0.24，结果颜色会大量褪色到几乎纯黑。
        // 用户希望"字体颜色更体现标题的主题颜色"，所以这里改用 HSL：保留封面的 hue，
        // 适度提饱和（让暗部仍能看得出色相），把 lightness 压到一个"够暗、够可读"的窄区间。
        // 落在浅雾化背景上时，对比足以读清；同时颜色不再是死黑，而是带封面色味的深色。
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(seed, hsl)
        // hsl[0] 是 hue（不动）。原 seed 几乎无饱和度时（灰度封面）依然给一点点底色，
        // 这样不会出现"和默认黑完全一样"的退化情况；高饱和的封面则被适度收一收避免刺眼。
        hsl[1] = (hsl[1] * 1.45f + 0.06f).coerceIn(0f, 0.78f)
        hsl[2] = hsl[2].coerceIn(0.10f, 0.20f)
        return ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), 255)
    }

    private fun brightenBackdropBase(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = (hsl[1] * 1.06f).coerceIn(0f, 1f)
        hsl[2] = (hsl[2] + 0.08f).coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun brightenBackdropAccent(color: Int, lightnessBoost: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = (hsl[1] * 1.10f).coerceIn(0f, 1f)
        hsl[2] = (hsl[2] + lightnessBoost).coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun mistColor(
        color: Int,
        saturationFactor: Float,
        whiteBlend: Float,
        lift: Float = 0f,
        minLightness: Float = 0f
    ): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = (hsl[1] * saturationFactor).coerceIn(0f, 1f)
        // 先加 lift，再用 minLightness 兜底——保证非常暗的封面也能拿到一个"亮一点的雾化底"，
        // 避免 hsl[2] 还在 0.10–0.20 区间时雾化出来仍然偏黑、看起来又脏又暗。
        hsl[2] = (hsl[2] + lift).coerceIn(0f, 1f)
        if (minLightness > 0f) {
            hsl[2] = hsl[2].coerceAtLeast(minLightness.coerceIn(0f, 1f))
        }
        return ColorUtils.blendARGB(
            ColorUtils.HSLToColor(hsl),
            Color.WHITE,
            whiteBlend.coerceIn(0f, 1f)
        )
    }

    private fun lightenColor(color: Int, amount: Float): Int {
        return ColorUtils.blendARGB(color, Color.WHITE, amount.coerceIn(0f, 1f))
    }

    private fun darkenColor(color: Int, amount: Float): Int {
        return ColorUtils.blendARGB(color, Color.BLACK, amount.coerceIn(0f, 1f))
    }

    private fun saturateColor(color: Int, factor: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = (hsl[1] * factor).coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun luminanceOf(color: Int): Double {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
    }

    private fun colorDistance(first: Int, second: Int): Double {
        val dr = (Color.red(first) - Color.red(second)).toDouble()
        val dg = (Color.green(first) - Color.green(second)).toDouble()
        val db = (Color.blue(first) - Color.blue(second)).toDouble()
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
    }

    private fun resetThemeColor() {
        backdropView.setImageDrawable(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdropView.setRenderEffect(null)
        }
        backdropView.alpha = 0f
        backdropOverlay.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.WHITE, 0xFFF2F4F3.toInt())
        )
        val defaultStatusColor = Color.WHITE
        val defaultNavigationColor = 0xFFF4F6F5.toInt()
        applySystemBarColors(defaultStatusColor, defaultNavigationColor)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ivCover.setBackgroundColor(Color.TRANSPARENT)
        indicatorActiveColor = 0xDE000000.toInt()
        indicatorInactiveColor = 0x4A000000
        updatePageIndicator()
        tvTitle.setTextColor(0xFF000000.toInt())
        tvArtist.setTextColor(0xFF888888.toInt())
        playerLyricPreviewPrimaryColor = 0xD8000000.toInt()
        playerLyricPreviewSecondaryColor = 0x98888888.toInt()
        tvPlayerLyricPreviewLine1.setTextColor(playerLyricPreviewPrimaryColor)
        tvPlayerLyricPreviewLine2.setTextColor(playerLyricPreviewSecondaryColor)
        tvCurrent.setTextColor(0xFF888888.toInt())
        tvTotal.setTextColor(0xFF888888.toInt())
        tvNextTrackHint.setTextColor(0xAA000000.toInt())
        tvDetailTitle.setTextColor(0xFF000000.toInt())
        tvDetailYear.setTextColor(0xFF7A7A7A.toInt())
        tvDetailArtist.setTextColor(0xFF7A7A7A.toInt())
        tvDetailAlbum.setTextColor(0xFF7A7A7A.toInt())
        tvDetailProductionLabel.setTextColor(0xFF000000.toInt())
        tvDetailProductionValue.setTextColor(0xFF7A7A7A.toInt())
        tvDetailFileInfoLabel.setTextColor(0xFF000000.toInt())
        tvDetailFileInfoValue.setTextColor(0xFF7A7A7A.toInt())
        tvDetailMemoryTitle.setTextColor(0xFF000000.toInt())
        tvDetailMemoryDate.setTextColor(0xFF7A7A7A.toInt())
        tvDetailMemoryDuration.setTextColor(0xFF7A7A7A.toInt())
        tvLyricsTitle.setTextColor(0xFF000000.toInt())
        tvLyricsArtist.setTextColor(0xFF888888.toInt())
        playerPrimaryTextColor = 0xFF000000.toInt()
        playerLyricAccentColor = playerPrimaryTextColor
        if (::lyricsAdapter.isInitialized) {
            lyricsAdapter.setPrimaryColor(playerPrimaryTextColor)
        lyricsAdapter.setAlignment(LyricsSettings.getAlignment(this))
            lyricsAdapter.setAccentColor(playerPrimaryTextColor)
        }
        updatePlayerLyricPreview(PlaybackManager.smoothPositionMs().takeIf { it > 0L } ?: savedPositionMs())
        applyLyricsSurfaceTreatments(
            topColor = ColorUtils.blendARGB(Color.WHITE, 0xFFF6F7F6.toInt(), 0.76f),
            bottomGlowTint = 0x80FFFFFF.toInt()
        )
        val baseIcon = 0xFF000000.toInt()
        btnClose.setColorFilter(baseIcon, PorterDuff.Mode.SRC_IN)
        btnSettings.setColorFilter(baseIcon, PorterDuff.Mode.SRC_IN)
        btnPrev.setColorFilter(baseIcon, PorterDuff.Mode.SRC_IN)
        btnNext.setColorFilter(baseIcon, PorterDuff.Mode.SRC_IN)
        btnMode.setColorFilter(baseIcon, PorterDuff.Mode.SRC_IN)
        btnQueue.setColorFilter(baseIcon, PorterDuff.Mode.SRC_IN)
        btnFavorite.clearColorFilter()
        btnLyricsFavorite.clearColorFilter()
        applyLyricsSourceButtonTint(baseIcon)
        seekBar.progressTintList = ColorStateList.valueOf(baseIcon)
        seekBar.progressBackgroundTintList = ColorStateList.valueOf(0x1A000000)
        btnPlayPauseWrap.background = null
        applyPlayPauseIconTint(Color.WHITE)
    }

    private fun updateProgress() {
        if (PlaybackManager.isTrackSwitching() || userSeeking) return

        val activeTotal = PlaybackManager.totalDurationMs()
        val activePos = PlaybackManager.currentPositionMs()
        val displayFile = currentDisplayFile()
        val snapshotPos = savedPositionMs()

        val total = when {
            activeTotal > 0L -> activeTotal
            displayFile != null && displayFile.duration > 0L -> displayFile.duration
            else -> 0L
        }
        val pos = when {
            activeTotal > 0L -> activePos
            snapshotPos > 0L -> snapshotPos.coerceAtMost(total)
            else -> 0L
        }

        if (total > 0) {
            val ratio = (pos.toDouble() / total * 1000).toInt().coerceIn(0, 1000)
            seekBar.progress = ratio
            val currSec = pos / 1000
            val totalSec = total / 1000
            val remainSec = (totalSec - currSec).coerceAtLeast(0L)
            tvCurrent.text = formatSeconds(currSec)
            tvTotal.text = "-" + formatSeconds(remainSec)
            updateNextTrackHint(pos, total)
            if (!PlaybackManager.isPlaying() || !currentLyricsResult.isTimed) {
                updateLyricsForPosition(pos)
            }
        } else {
            seekBar.progress = 0
            tvCurrent.text = "00:00"
            tvTotal.text = "-00:00"
            hideNextTrackHint(immediate = true)
            updateLyricsForPosition(pos)
        }
    }

    private fun updateNextTrackHint(positionMs: Long, totalMs: Long) {
        if (!::tvNextTrackHint.isInitialized) return
        val remainingMs = (totalMs - positionMs).coerceAtLeast(0L)
        val nextTitle = resolveUpcomingTrackTitle()
        if (PlaybackManager.isPlaying() && totalMs > 0L && remainingMs <= 10_000L && !nextTitle.isNullOrBlank()) {
            val text = "下一首即将播放 $nextTitle"
            if (tvNextTrackHint.text.toString() != text) {
                tvNextTrackHint.text = text
            }
            if (!nextTrackHintVisible || tvNextTrackHint.visibility != View.VISIBLE) {
                nextTrackHintVisible = true
                tvNextTrackHint.animate().cancel()
                tvNextTrackHint.alpha = 0f
                tvNextTrackHint.visibility = View.VISIBLE
                tvNextTrackHint.animate()
                    .alpha(1f)
                    .setDuration(320L)
                    .start()
            }
        } else {
            hideNextTrackHint()
        }
    }

    private fun hideNextTrackHint(immediate: Boolean = false) {
        if (!::tvNextTrackHint.isInitialized) return
        if (!nextTrackHintVisible && tvNextTrackHint.visibility != View.VISIBLE) return
        nextTrackHintVisible = false
        tvNextTrackHint.animate().cancel()
        if (immediate) {
            tvNextTrackHint.alpha = 0f
            tvNextTrackHint.visibility = View.INVISIBLE
            return
        }
        tvNextTrackHint.animate()
            .alpha(0f)
            .setDuration(240L)
            .withEndAction {
                if (!nextTrackHintVisible) {
                    tvNextTrackHint.visibility = View.INVISIBLE
                }
            }
            .start()
    }

    private fun resolveUpcomingTrackTitle(): String? {
        val queue = PlaybackManager.queue()
        val index = PlaybackManager.currentQueueIndex()
        if (queue.isEmpty() || index !in queue.indices) return null
        val nextFile = when (PlaybackManager.queueMode()) {
            PlaybackSettings.Mode.REPEAT_ONE -> queue.getOrNull(index)
            else -> {
                if (queue.size <= 1) null else queue.getOrNull((index + 1) % queue.size)
            }
        }
        return nextFile?.title?.takeIf { it.isNotBlank() }
    }

    private fun formatSeconds(totalSec: Long): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
    }


    private fun currentDisplayFile(): MusicScanner.MusicFile? = PlaybackUiResolver.displayFile(this)

    private fun savedPositionMs(): Long = PlaybackUiResolver.savedPositionMs(this)

    // ============================================================
    // 播放列表抽屉
    // ============================================================

    private var queueSheetAdapter: QueueAdapter? = null
    private var queueSheetDialog: BottomSheetDialog? = null
    private var queueSheetListener: PlaybackManager.Listener? = null

    private fun showQueueSheet() {
        val queue = PlaybackManager.queue()
        if (queue.isEmpty()) {
            Toast.makeText(this, "当前没有播放队列", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_queue, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)

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

        val btnMode = view.findViewById<ImageButton>(R.id.btnQueueMode)
        val tvModeLabel = view.findViewById<TextView>(R.id.tvQueueModeLabel)
        val tvSource = view.findViewById<TextView>(R.id.tvQueueSource)
        val tvCount = view.findViewById<TextView>(R.id.tvQueueCount)

        fun refreshHeader() {
            val m = PlaybackManager.queueMode()
            btnMode.setImageResource(modeIconFor(m))
            tvModeLabel.text = m.label
            val source = PlaybackManager.queueSourceName().ifBlank { "当前播放页" }
            tvSource.text = "来源：$source"
            tvCount.text = "${PlaybackManager.queue().size} 首"
        }
        refreshHeader()

        btnMode.setOnClickListener {
            if (PlaybackManager.currentFile() == null) return@setOnClickListener
            val newMode = nextMode(PlaybackManager.queueMode())
            PlaybackManager.switchQueueMode(newMode)
            refreshHeader()
            queueSheetAdapter?.updateItems(PlaybackManager.queue())
            queueSheetAdapter?.currentPath = PlaybackManager.currentPath()
            queueSheetAdapter?.isPlaying = PlaybackManager.isPlaying()
        }

        view.findViewById<ImageButton>(R.id.btnQueueClose).setOnClickListener {
            dialog.dismiss()
        }

        val rv = view.findViewById<RecyclerView>(R.id.rvQueue)
        var itemTouchHelper: ItemTouchHelper? = null
        val adapter = QueueAdapter(
            initialItems = queue,
            onItemClick = { position, _ ->
                PlaybackManager.playAt(this, position)
            },
            onItemLongClick = { _, file ->
                SongActionSheet.show(this, file)
            },
            onRemoveClick = { _, file ->
                PlaybackManager.removeFromQueue(file.path)
                val remaining = PlaybackManager.queue()
                if (remaining.isEmpty()) {
                    dialog.dismiss()
                } else {
                    queueSheetAdapter?.updateItems(remaining)
                    queueSheetAdapter?.currentPath = PlaybackManager.currentPath()
                    queueSheetAdapter?.isPlaying = PlaybackManager.isPlaying()
                    refreshHeader()
                }
            },
            onStartDrag = { holder ->
                itemTouchHelper?.startDrag(holder)
            }
        )
        adapter.currentPath = PlaybackManager.currentPath()
        adapter.isPlaying = PlaybackManager.isPlaying()
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.setHasFixedSize(true)
        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false
            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val pos = viewHolder.bindingAdapterPosition
                return if (adapter.canDrag(pos)) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                } else {
                    makeMovementFlags(0, 0)
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                return adapter.moveItem(from, to)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                PlaybackManager.replaceQueueOrder(adapter.itemsSnapshot())
            }
        })
        itemTouchHelper?.attachToRecyclerView(rv)
        val idx = PlaybackManager.currentQueueIndex().coerceAtLeast(0)
        (rv.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset(idx, dp(80))

        queueSheetAdapter = adapter
        queueSheetDialog = dialog

        val listener = object : PlaybackManager.Listener {
            override fun onPlaybackChanged(currentPath: String?, isPlaying: Boolean) {
                val a = queueSheetAdapter ?: return
                a.updateItems(PlaybackManager.queue())
                a.currentPath = currentPath
                a.isPlaying = isPlaying
                refreshHeader()
            }
        }
        PlaybackManager.addListener(listener)
        queueSheetListener = listener

        dialog.setOnDismissListener {
            queueSheetListener?.let { PlaybackManager.removeListener(it) }
            queueSheetListener = null
            queueSheetAdapter = null
            queueSheetDialog = null
        }

        dialog.show()
    }

    // ============================================================
    // 播放模式相关小工具
    // ============================================================

    private fun modeIconFor(mode: PlaybackSettings.Mode): Int = when (mode) {
        PlaybackSettings.Mode.SEQUENTIAL -> R.drawable.repeat_24
        PlaybackSettings.Mode.REPEAT_ONE -> R.drawable.repeat_one_24
        PlaybackSettings.Mode.RANDOM -> R.drawable.shuffle_24
    }

    private fun nextMode(current: PlaybackSettings.Mode): PlaybackSettings.Mode = when (current) {
        PlaybackSettings.Mode.SEQUENTIAL -> PlaybackSettings.Mode.REPEAT_ONE
        PlaybackSettings.Mode.REPEAT_ONE -> PlaybackSettings.Mode.RANDOM
        PlaybackSettings.Mode.RANDOM -> PlaybackSettings.Mode.SEQUENTIAL
    }

    private fun updateModeIcon() {
        val mode = if (PlaybackManager.currentFile() != null) {
            PlaybackManager.queueMode()
        } else {
            PlaybackSettings.getPreferredMode(this)
        }
        btnMode.setImageResource(modeIconFor(mode))
    }

    private fun cyclePlayMode() {
        if (PlaybackManager.currentFile() == null) return
        val newMode = nextMode(PlaybackManager.queueMode())
        PlaybackManager.switchQueueMode(newMode)
        updateModeIcon()
    }
}
