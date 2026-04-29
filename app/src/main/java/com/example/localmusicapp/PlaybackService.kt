package com.example.localmusicapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import kotlin.math.roundToInt
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media.session.MediaButtonReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 前台播放服务。
 *
 * 关键职责：
 *   1. 持有 MediaPlayer / MediaSession / 通知
 *   2. 管理当前播放队列
 *   3. 周期性持久化播放状态，保证重进应用可恢复歌曲与进度
 */
class PlaybackService : LifecycleService() {

    private lateinit var mediaSession: MediaSessionCompat
    private var mediaPlayer: MediaPlayer? = null

    private var queue: List<MusicScanner.MusicFile> = emptyList()
    private var currentIndex: Int = -1
    private var queueMode: PlaybackSettings.Mode = PlaybackSettings.Mode.SEQUENTIAL
    private var sourceList: List<MusicScanner.MusicFile> = emptyList()
    private var queueSourceName: String = ""
    private val currentFile: MusicScanner.MusicFile?
        get() = queue.getOrNull(currentIndex)

    private var playing: Boolean = false
    private var coverJob: Job? = null
    private var carLyricsJob: Job? = null
    private var metadataCoverBitmap: Bitmap? = null
    private var carLyricsText: String = ""
    private var persistJob: Job? = null
    private var playRequestToken: Long = 0L
    private var trackSwitching: Boolean = false
    private var fallbackDirection: Int = 1
    private var forcedNextPath: String? = null
    private val failedPathsInCurrentChain = linkedSetOf<String>()
    private var replayGainEnhancer: LoudnessEnhancer? = null
    private var replayGainVolume: Float = 1f
    private var scrubMutedForUi: Boolean = false

    private var sessionLastObservedPosMs: Long = 0L
    private var sessionDurationMs: Long = 0L
    private var sessionCounted: Boolean = false
    private var sessionPath: String? = null
    private var sessionThresholdPercent: Int = 70
    private var sessionContinuousPlayedMs: Long = 0L
    private var sessionContinuousAnchorElapsedMs: Long = 0L
    private var sessionUserSeeking: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        CoverDiskCache.init(this)
        initMediaSession()
        PlaybackManager.onServiceCreated(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        sampleCurrentPosition()
        persistPlaybackState(sync = true)
        markCountIfPassedThreshold()
        ListenStats.save(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        sampleCurrentPosition()
        persistPlaybackState(sync = true)
        markCountIfPassedThreshold()
        ListenStats.save(this)
        coverJob?.cancel()
        carLyricsJob?.cancel()
        releasePlayer()
        mediaSession.release()
        PlaybackManager.onServiceDestroyed()
        super.onDestroy()
    }


    // ============================================================
    // 对外 API（PlaybackManager 调用）
    // ============================================================

    fun currentFile(): MusicScanner.MusicFile? = currentFile
    fun isPlaying(): Boolean = playing
    fun currentPositionMs(): Long = safeCurrentPositionMs(mediaPlayer)
    fun totalDurationMs(): Long = safeDurationMs(mediaPlayer)

    fun refreshReplayGain() {
        applyReplayGainForCurrent()
    }

    fun refreshCarLyrics() {
        val file = currentFile ?: return
        val duration = totalDurationMs().takeIf { it > 0L } ?: file.duration
        carLyricsText = ""
        if (PlaybackSettings.isCarLyricsEnabled(this)) {
            startCarLyricsUpdater(file, duration)
        } else {
            carLyricsJob?.cancel()
        }
        pushMetadata(file, duration, metadataCoverBitmap)
    }

    fun refreshPlaybackThreshold() {
        sampleContinuousPlaybackProgress()
        sessionThresholdPercent = PlaybackSettings.getThresholdPercent(this).coerceIn(1, 100)
        markCountIfPassedThreshold()
    }

    /**
     * 进度条拖动专用静音。
     *
     * 这里不只负责静音，还顺便把“连续真实播放”计时切开：
     *   - 按下开始拖动 → 立即冻结连续计时，并暂停 ListenStats 的 live session；
     *   - 松手结束拖动 → seek 完成后再恢复 live session，新的连续计时从 0 开始。
     *
     * 这样拖拽过程中流逝的墙钟时间不会算进“连续播放阀值”，
     * 也不会污染播放时长统计。
     */
    fun setScrubMuted(muted: Boolean) {
        val mp = mediaPlayer ?: return
        try {
            scrubMutedForUi = muted
            if (muted) mp.setVolume(0f, 0f) else restorePlaybackVolume(mp)
        } catch (_: Exception) {
            // setVolume 在某些 ROM 上状态不对时会抛 IllegalStateException，吞掉就好
        }

        if (muted) {
            sampleContinuousPlaybackProgress()
            sessionUserSeeking = true
            ListenStats.pauseSession()
        } else {
            val wasSeeking = sessionUserSeeking
            sessionUserSeeking = false
            if (wasSeeking && playing) {
                currentFile?.let { beginListenSession(it) }
            }
        }
    }

    fun seekToMs(posMs: Long) {
        val mp = mediaPlayer ?: return
        try {
            val targetPosMs = posMs.coerceAtLeast(0L)
            sampleCurrentPosition()
            markCountIfPassedThreshold()
            mp.seekTo(targetPosMs.toInt())
            onManualSeek(targetPosMs)
            persistPlaybackState(sync = false)
        } catch (_: Exception) {
        }
    }
    fun hasNext(): Boolean = currentIndex in 0 until (queue.size - 1)
    fun hasPrev(): Boolean = currentIndex > 0

    fun queue(): List<MusicScanner.MusicFile> = queue
    fun currentQueueIndex(): Int = currentIndex
    fun queueMode(): PlaybackSettings.Mode = queueMode
    fun queueSourceName(): String = queueSourceName
    fun isTrackSwitching(): Boolean = trackSwitching

    fun playAt(index: Int) {
        if (index !in queue.indices || trackSwitching) return
        fallbackDirection = if (currentIndex >= 0 && index < currentIndex) -1 else 1
        if (index == currentIndex) {
            when {
                mediaPlayer == null -> {
                    failedPathsInCurrentChain.clear()
                    playCurrent()
                }
                !playing -> toggle()
            }
            return
        }
        failedPathsInCurrentChain.clear()
        currentIndex = index
        if (currentFile?.path == forcedNextPath) forcedNextPath = null
        persistPlaybackState(sync = false, positionOverrideMs = 0L)
        playCurrent()
    }

    /**
     * 把任意歌曲插到当前曲目的下一位。
     *
     * 这里直接改“实际播放队列”，不依赖顺序 / 随机模式的 UI 状态。
     * 如果歌曲原本已在队列里，会先移除旧位置再插到当前曲目后面，保证下一次点“下一首”
     * 一定会播放它，也避免同一首歌在队列里重复出现。
     */
    fun playNext(file: MusicScanner.MusicFile) {
        if (file.path.isBlank()) return

        if (queue.isEmpty() || currentIndex !in queue.indices) {
            queue = listOf(file)
            currentIndex = 0
            queueMode = PlaybackSettings.getPreferredMode(this)
            sourceList = listOf(file)
            queueSourceName = "手动添加"
            forcedNextPath = null
            fallbackDirection = 1
            failedPathsInCurrentChain.clear()
            persistPlaybackState(sync = false, positionOverrideMs = 0L)
            playCurrent()
            return
        }

        val currentPath = currentFile?.path ?: return
        if (file.path == currentPath) return

        val reordered = queue.toMutableList()
        val existingIndex = reordered.indexOfFirst { it.path == file.path }
        if (existingIndex >= 0) reordered.removeAt(existingIndex)

        val currentAfterRemoval = reordered.indexOfFirst { it.path == currentPath }
        if (currentAfterRemoval < 0) return

        val insertIndex = (currentAfterRemoval + 1).coerceIn(0, reordered.size)
        reordered.add(insertIndex, file)

        queue = reordered.distinctBy { it.path }
        currentIndex = queue.indexOfFirst { it.path == currentPath }.coerceAtLeast(0)
        if (sourceList.none { it.path == file.path }) {
            sourceList = (sourceList.ifEmpty { queue } + file).distinctBy { it.path }
        }
        forcedNextPath = file.path
        fallbackDirection = 1
        failedPathsInCurrentChain.clear()
        persistPlaybackState(sync = false)
        pushState()
        pushNotification()
        PlaybackManager.notifyStateChanged()
    }

    fun appendNextAndPlay(file: MusicScanner.MusicFile) {
        if (file.path.isBlank()) return

        if (queue.isEmpty() || currentIndex !in queue.indices) {
            queue = listOf(file)
            currentIndex = 0
            queueMode = PlaybackSettings.getPreferredMode(this)
            sourceList = listOf(file)
            queueSourceName = "手动插播"
            forcedNextPath = null
            fallbackDirection = 1
            failedPathsInCurrentChain.clear()
            persistPlaybackState(sync = false, positionOverrideMs = 0L)
            playCurrent()
            return
        }

        val currentPath = currentFile?.path
        if (currentPath != null && file.path == currentPath) {
            if (!playing) toggle()
            return
        }

        val reordered = queue.toMutableList()
        val existingIndex = reordered.indexOfFirst { it.path == file.path }
        if (existingIndex >= 0) reordered.removeAt(existingIndex)

        val currentAfterRemoval = currentPath?.let { path -> reordered.indexOfFirst { it.path == path } } ?: -1
        val insertIndex = if (currentAfterRemoval >= 0) currentAfterRemoval + 1 else (currentIndex + 1).coerceIn(0, reordered.size)
        reordered.add(insertIndex.coerceIn(0, reordered.size), file)

        queue = reordered.distinctBy { it.path }
        currentIndex = queue.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        if (sourceList.none { it.path == file.path }) {
            sourceList = (sourceList.ifEmpty { queue } + file).distinctBy { it.path }
        }
        forcedNextPath = null
        fallbackDirection = 1
        failedPathsInCurrentChain.clear()
        persistPlaybackState(sync = false, positionOverrideMs = 0L)
        playCurrent()
    }

    /**
     * 播放列表抽屉拖拽排序后替换队列顺序。
     * 当前正在播放的文件本身不被重启，只重新定位 currentIndex，让上一首 / 下一首按新顺序工作。
     */
    fun replaceQueueOrder(newQueue: List<MusicScanner.MusicFile>) {
        if (queue.isEmpty() || currentIndex !in queue.indices) return
        val currentPath = currentFile?.path ?: return
        val oldPaths = queue.map { it.path }.toSet()
        val normalized = newQueue
            .filter { it.path in oldPaths }
            .distinctBy { it.path }
        if (normalized.size != oldPaths.size) return
        if (normalized.none { it.path == currentPath }) return

        queue = normalized
        currentIndex = queue.indexOfFirst { it.path == currentPath }.coerceAtLeast(0)
        val nextPath = queue.getOrNull(normalizeIndex(currentIndex + 1))?.path
        if (forcedNextPath != null && forcedNextPath != nextPath) forcedNextPath = null
        failedPathsInCurrentChain.clear()
        persistPlaybackState(sync = false)
        pushState()
        PlaybackManager.notifyStateChanged()
    }

    fun removeFromQueue(path: String) {
        if (path.isBlank() || queue.isEmpty()) return

        val removeIndex = queue.indexOfFirst { it.path == path }
        if (removeIndex < 0) return

        val wasPlaying = playing
        val currentPath = currentFile?.path
        val removingCurrent = path == currentPath

        if (queue.size <= 1) {
            if (removingCurrent) {
                explicitStopAndRelease(clearSnapshot = true)
            }
            return
        }

        val updatedQueue = queue.toMutableList().apply {
            removeAt(removeIndex)
        }
        queue = updatedQueue.distinctBy { it.path }
        sourceList = sourceList.filterNot { it.path == path }.ifEmpty { queue }

        if (forcedNextPath == path) forcedNextPath = null
        failedPathsInCurrentChain.clear()

        if (removingCurrent) {
            currentIndex = removeIndex.coerceAtMost(queue.lastIndex).coerceAtLeast(0)
            fallbackDirection = 1
            persistPlaybackState(sync = false, positionOverrideMs = 0L)
            playCurrent(initialPositionMs = 0L, autoStart = wasPlaying)
            return
        }

        if (removeIndex < currentIndex) {
            currentIndex = (currentIndex - 1).coerceAtLeast(0)
        } else {
            currentIndex = currentIndex.coerceIn(0, queue.lastIndex)
        }

        persistPlaybackState(sync = false)
        pushState()
        pushNotification()
        PlaybackManager.notifyStateChanged()
    }

    fun playQueue(
        files: List<MusicScanner.MusicFile>,
        startIndex: Int,
        mode: PlaybackSettings.Mode = PlaybackSettings.Mode.SEQUENTIAL,
        sourceList: List<MusicScanner.MusicFile> = files,
        sourceName: String = ""
    ) {
        if (files.isEmpty() || startIndex !in files.indices) return

        val normalizedQueue = files.distinctBy { it.path }
        val normalizedSourceList = (if (sourceList.isNotEmpty()) sourceList else normalizedQueue)
            .distinctBy { it.path }
        val startPath = files[startIndex].path
        val mappedIndex = normalizedQueue.indexOfFirst { it.path == startPath }.coerceAtLeast(0)

        queue = normalizedQueue
        currentIndex = mappedIndex
        queueMode = mode
        this.sourceList = normalizedSourceList
        queueSourceName = sourceName
        PlaybackSettings.setPreferredMode(this, mode)
        forcedNextPath = null
        fallbackDirection = 1
        failedPathsInCurrentChain.clear()
        persistPlaybackState(sync = false, positionOverrideMs = 0L)
        playCurrent()
    }

    fun switchQueueMode(newMode: PlaybackSettings.Mode) {
        if (newMode == queueMode) return
        val current = currentFile ?: return
        if (queue.isEmpty() || currentIndex !in queue.indices) return

        if (newMode == PlaybackSettings.Mode.REPEAT_ONE) {
            queueMode = newMode
            PlaybackSettings.setPreferredMode(this, newMode)
            persistPlaybackState(sync = false)
            pushState()
            pushNotification()
            PlaybackManager.notifyStateChanged()
            return
        }

        val baseList = (if (sourceList.isNotEmpty()) sourceList else queue)
            .distinctBy { it.path }
            .ifEmpty { listOf(current) }

        val rebuiltQueue = when (newMode) {
            PlaybackSettings.Mode.SEQUENTIAL -> {
                val idx = baseList.indexOfFirst { it.path == current.path }
                if (idx >= 0) baseList.toList() else listOf(current) + baseList.filter { it.path != current.path }
            }
            PlaybackSettings.Mode.RANDOM -> {
                val others = baseList.filter { it.path != current.path }.shuffled()
                listOf(current) + others
            }
            PlaybackSettings.Mode.REPEAT_ONE -> queue
        }

        queue = applyForcedNext(rebuiltQueue, current)
        currentIndex = queue.indexOfFirst { it.path == current.path }.coerceAtLeast(0)
        sourceList = baseList.toList()
        queueMode = newMode
        PlaybackSettings.setPreferredMode(this, newMode)
        fallbackDirection = 1
        failedPathsInCurrentChain.clear()
        persistPlaybackState(sync = false)
        pushState()
        pushNotification()
        PlaybackManager.notifyStateChanged()
    }

    private fun applyForcedNext(
        candidateQueue: List<MusicScanner.MusicFile>,
        current: MusicScanner.MusicFile
    ): List<MusicScanner.MusicFile> {
        val nextPath = forcedNextPath ?: return candidateQueue
        if (nextPath == current.path) return candidateQueue
        val forced = candidateQueue.firstOrNull { it.path == nextPath } ?: return candidateQueue
        val withoutForced = candidateQueue.filter { it.path != nextPath }
        val currentIndexInCandidate = withoutForced.indexOfFirst { it.path == current.path }
        if (currentIndexInCandidate < 0) return candidateQueue
        return withoutForced.toMutableList().apply {
            add((currentIndexInCandidate + 1).coerceIn(0, size), forced)
        }
    }

    fun restoreState(
        snapshot: PlaybackStateStore.Snapshot,
        libraryFiles: List<MusicScanner.MusicFile>,
        autoStart: Boolean = false
    ) {
        if (libraryFiles.isEmpty()) return
        val byPath = libraryFiles.associateBy { it.path }
        val restoredQueue = snapshot.queuePaths.mapNotNull(byPath::get).distinctBy { it.path }
        val restoredSource = snapshot.sourcePaths.mapNotNull(byPath::get).distinctBy { it.path }
        val fallbackQueue = (if (restoredQueue.isNotEmpty()) restoredQueue else restoredSource)
            .ifEmpty {
                snapshot.currentPath.takeIf { it.isNotBlank() }
                    ?.let(byPath::get)
                    ?.let(::listOf)
                    ?: emptyList()
            }
        if (fallbackQueue.isEmpty()) {
            PlaybackStateStore.clear(this, sync = true)
            return
        }

        val resolvedPath = snapshot.currentPath.takeIf { it.isNotBlank() }
            ?: fallbackQueue.getOrNull(snapshot.currentIndex)?.path
        val indexByPath = fallbackQueue.indexOfFirst { it.path == resolvedPath }
        val resolvedIndex = when {
            indexByPath >= 0 -> indexByPath
            snapshot.currentIndex in fallbackQueue.indices -> snapshot.currentIndex
            else -> 0
        }

        queue = fallbackQueue
        currentIndex = resolvedIndex
        queueMode = snapshot.mode
        sourceList = if (restoredSource.isNotEmpty()) restoredSource else fallbackQueue
        queueSourceName = snapshot.sourceName
        PlaybackSettings.setPreferredMode(this, snapshot.mode)
        forcedNextPath = null
        fallbackDirection = 1
        failedPathsInCurrentChain.clear()
        playCurrent(initialPositionMs = snapshot.positionMs, autoStart = autoStart)
    }

    fun toggle() {
        val mp = mediaPlayer ?: return
        try {
            if (playing) {
                sampleCurrentPosition()
                sampleContinuousPlaybackProgress()
                mp.pause()
                playing = false
                stopPersistLoop()
                markCountIfPassedThreshold()
                persistPlaybackState(sync = true)
                ListenStats.save(this)
            } else {
                mp.start()
                applyReplayGainForCurrent()
                playing = true
                currentFile?.let { beginListenSession(it) }
                startPersistLoop()
                persistPlaybackState(sync = false)
            }
            pushState()
            pushNotification()
            PlaybackManager.notifyStateChanged()
        } catch (e: Exception) {
            Log.e(TAG, "toggle failed", e)
        }
    }

    fun onMediaSessionStopExternal() {
        explicitStopAndRelease(clearSnapshot = true)
    }

    fun skipToNext() {
        if (queue.isEmpty() || currentIndex < 0 || trackSwitching) return
        currentIndex = normalizeIndex(currentIndex + 1)
        if (currentFile?.path == forcedNextPath) forcedNextPath = null
        fallbackDirection = 1
        failedPathsInCurrentChain.clear()
        persistPlaybackState(sync = false, positionOverrideMs = 0L)
        playCurrent()
    }

    fun skipToPrevious() {
        if (queue.isEmpty() || currentIndex < 0 || trackSwitching) return
        currentIndex = normalizeIndex(currentIndex - 1)
        fallbackDirection = -1
        failedPathsInCurrentChain.clear()
        persistPlaybackState(sync = false, positionOverrideMs = 0L)
        playCurrent()
    }

    private fun playCurrent(initialPositionMs: Long = 0L, autoStart: Boolean = true) {
        val file = currentFile ?: run {
            trackSwitching = false
            PlaybackStateStore.clear(this, sync = true)
            return
        }

        sampleCurrentPosition()
        markCountIfPassedThreshold()
        coverJob?.cancel()
        carLyricsJob?.cancel()
        metadataCoverBitmap = null
        carLyricsText = ""
        trackSwitching = true

        val requestToken = ++playRequestToken
        val oldPlayer = mediaPlayer
        mediaPlayer = null
        playing = false
        stopPersistLoop()
        releaseReplayGainEnhancer()
        safeReleasePlayer(oldPlayer)

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
            }
            mediaPlayer = player

            player.setOnPreparedListener { mp ->
                if (requestToken != playRequestToken || mediaPlayer !== mp) {
                    safeReleasePlayer(mp)
                    return@setOnPreparedListener
                }

                val durationMs = safeDurationMs(mp).takeIf { it > 0L } ?: file.duration.coerceAtLeast(0L)
                val seekMs = initialPositionMs.coerceAtLeast(0L)
                    .coerceAtMost(durationMs.takeIf { it > 0L } ?: initialPositionMs.coerceAtLeast(0L))

                if (seekMs > 0L) {
                    try {
                        mp.seekTo(seekMs.toInt())
                    } catch (_: Exception) {
                    }
                }

                initializeCountTracking(file, durationMs, seekMs)
                applyReplayGainForCurrent()

                if (autoStart) {
                    try {
                        mp.start()
                    } catch (e: Exception) {
                        Log.e(TAG, "start after prepare failed path=${file.path}", e)
                        if (requestToken == playRequestToken && mediaPlayer === mp) {
                            handlePlayerLoadFailure(file, e)
                        } else {
                            safeReleasePlayer(mp)
                        }
                        return@setOnPreparedListener
                    }
                    playing = true
                    failedPathsInCurrentChain.clear()
                    beginListenSession(file)
                    ListenStats.markRecentPlay(this@PlaybackService, file.path)
                    startPersistLoop()
                } else {
                    playing = false
                    failedPathsInCurrentChain.clear()
                    stopPersistLoop()
                }

                trackSwitching = false
                pushState()
                pushMetadata(file, durationMs, null)
                startCarLyricsUpdater(file, durationMs)
                pushNotification()
                persistPlaybackState(sync = !autoStart, positionOverrideMs = seekMs)
                PlaybackManager.notifyStateChanged()

                coverJob = lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        CoverLoader.loadBitmapHighRes(file.path)
                    }
                    if (requestToken != playRequestToken || currentFile?.path != file.path) return@launch
                    metadataCoverBitmap = bmp
                    pushMetadata(file, durationMs, bmp)
                    pushNotification(bmp)
                }
            }

            player.setOnCompletionListener { mp ->
                if (requestToken != playRequestToken || mediaPlayer !== mp) return@setOnCompletionListener
                sampleCurrentPosition()
                markCountIfPassedThreshold(durationOverride = sessionDurationMs.takeIf { it > 0L } ?: safeDurationMs(mp))
                persistPlaybackState(sync = true, positionOverrideMs = sessionDurationMs)
                ListenStats.save(this@PlaybackService)
                if (queueMode == PlaybackSettings.Mode.REPEAT_ONE) {
                    playCurrent()
                } else {
                    skipToNext()
                }
            }

            player.setOnErrorListener { mp, what, extra ->
                if (requestToken != playRequestToken || mediaPlayer !== mp) return@setOnErrorListener true
                val ex = IllegalStateException("MediaPlayer error $what/$extra path=${file.path}")
                Log.e(TAG, "MediaPlayer error $what/$extra path=${file.path}")
                handlePlayerLoadFailure(file, ex)
                true
            }

            if (file.path.startsWith("content://", ignoreCase = true)) {
                player.setDataSource(this@PlaybackService, Uri.parse(file.path))
            } else {
                player.setDataSource(file.path)
            }
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "playCurrent failed", e)
            if (requestToken == playRequestToken) {
                handlePlayerLoadFailure(file, e)
            }
        }
    }

    // ============================================================
    // MediaSession
    // ============================================================

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "GrayMusic").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (mediaPlayer != null && !playing) toggle()
                }

                override fun onPause() {
                    if (playing) toggle()
                }

                override fun onSkipToNext() {
                    skipToNext()
                }

                override fun onSkipToPrevious() {
                    skipToPrevious()
                }

                override fun onStop() {
                    // 某些设备在长时间暂停后也可能回调 onStop；保留快照，避免 UI 突然清空为“请播放歌曲”。
                    explicitStopAndRelease(clearSnapshot = false)
                }
            })
            isActive = true
        }
    }

    private fun pushState() {
        val state = if (playing) {
            PlaybackStateCompat.STATE_PLAYING
        } else if (currentFile != null) {
            PlaybackStateCompat.STATE_PAUSED
        } else {
            PlaybackStateCompat.STATE_STOPPED
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, safeCurrentPositionMs(mediaPlayer), 1.0f)
                .build()
        )
    }

    private fun pushMetadata(
        file: MusicScanner.MusicFile,
        duration: Long,
        cover: Bitmap?
    ) {
        val effectiveCover = cover ?: metadataCoverBitmap
        val displayText = if (PlaybackSettings.isCarLyricsEnabled(this) && carLyricsText.isNotBlank()) carLyricsText else ArtistUtils.displayArtists(file.artist)
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, file.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, ArtistUtils.displayArtists(file.artist))
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, file.album)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, file.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, ArtistUtils.displayArtists(file.artist))
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, displayText)
            .putLong(
                MediaMetadataCompat.METADATA_KEY_DURATION,
                if (duration > 0) duration else file.duration
            )
        if (effectiveCover != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, effectiveCover)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, effectiveCover)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, effectiveCover)
        }
        mediaSession.setMetadata(builder.build())
    }

    // ============================================================
    private fun startCarLyricsUpdater(file: MusicScanner.MusicFile, durationMs: Long) {
        carLyricsJob?.cancel()
        if (!PlaybackSettings.isCarLyricsEnabled(this)) return
        carLyricsJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { LyricRepository.load(this@PlaybackService, file) }
            while (currentFile?.path == file.path) {
                val pos = safeCurrentPositionMs(mediaPlayer)
                val line = if (result.isTimed) {
                    val idx = LyricRepository.activeIndexFor(result.lines, pos)
                    result.lines.getOrNull(idx)?.text.orEmpty()
                } else {
                    result.lines.firstOrNull()?.text.orEmpty()
                }
                val nextText = line.trim().ifBlank { ArtistUtils.displayArtists(file.artist) }
                if (nextText != carLyricsText) {
                    carLyricsText = nextText
                    pushMetadata(file, durationMs, metadataCoverBitmap)
                }
                delay(900L)
            }
        }
    }

    // Notification
    // ============================================================

    private fun pushNotification(largeIcon: Bitmap? = null) {
        val file = currentFile ?: return
        val playPauseIcon = if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_arrow_24
        val playPauseLabel = if (playing) "暂停" else "播放"

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SongListActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.music_note_24)
            .setContentTitle(file.title)
            .setContentText(ArtistUtils.displayArtists(file.artist))
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_skip_previous_24,
                "上一首",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
            )
            .addAction(
                playPauseIcon,
                playPauseLabel,
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
            )
            .addAction(
                R.drawable.ic_skip_next_24,
                "下一首",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                            this,
                            PlaybackStateCompat.ACTION_STOP
                        )
                    )
            )

        if (largeIcon != null) builder.setLargeIcon(largeIcon)

        val notification: Notification = builder.build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示当前播放的歌曲"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun explicitStopAndRelease(clearSnapshot: Boolean) {
        sampleCurrentPosition()
        markCountIfPassedThreshold()
        ListenStats.save(this)

        if (clearSnapshot) {
            PlaybackStateStore.clear(this, sync = true)
        } else {
            persistPlaybackState(sync = true)
        }

        coverJob?.cancel()
        carLyricsJob?.cancel()
        releasePlayer()
        queue = emptyList()
        sourceList = emptyList()
        queueSourceName = ""
        currentIndex = -1
        playing = false
        trackSwitching = false
        forcedNextPath = null
        failedPathsInCurrentChain.clear()

        pushState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopSelf()
        PlaybackManager.notifyStateChanged()
    }

    private fun releasePlayer() {
        playRequestToken++
        stopPersistLoop()
        val player = mediaPlayer
        mediaPlayer = null
        trackSwitching = false
        releaseReplayGainEnhancer()
        safeReleasePlayer(player)
        playing = false
        replayGainVolume = 1f
        scrubMutedForUi = false
    }

    private fun applyReplayGainForCurrent() {
        val mp = mediaPlayer ?: return
        releaseReplayGainEnhancer()
        val file = currentFile
        val gainDb = if (file != null && PlaybackSettings.isReplayGainEnabled(this)) {
            ReplayGainStore.getGainDb(this, file.path)
        } else {
            null
        }

        val volume = if (gainDb != null) {
            val linear = ReplayGainStore.dbToLinearVolume(gainDb)
            if (linear < 1f) {
                linear.coerceIn(0.05f, 1f)
            } else {
                try {
                    replayGainEnhancer = LoudnessEnhancer(mp.audioSessionId).apply {
                        setTargetGain((gainDb * 100f).roundToInt().coerceIn(0, 1200))
                        enabled = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ReplayGain boost unavailable", e)
                }
                1f
            }
        } else {
            1f
        }

        replayGainVolume = volume
        if (!scrubMutedForUi) {
            restorePlaybackVolume(mp)
        }
    }

    private fun restorePlaybackVolume(player: MediaPlayer? = mediaPlayer) {
        val mp = player ?: return
        try {
            mp.setVolume(replayGainVolume, replayGainVolume)
        } catch (_: Exception) {
        }
    }

    private fun releaseReplayGainEnhancer() {
        val enhancer = replayGainEnhancer ?: return
        replayGainEnhancer = null
        try { enhancer.enabled = false } catch (_: Exception) {}
        try { enhancer.release() } catch (_: Exception) {}
    }

    private fun safeReleasePlayer(player: MediaPlayer?) {
        if (player == null) return
        try { player.setOnPreparedListener(null) } catch (_: Exception) {}
        try { player.setOnCompletionListener(null) } catch (_: Exception) {}
        try { player.setOnErrorListener(null) } catch (_: Exception) {}
        try { player.release() } catch (_: Exception) {}
    }

    private fun safeCurrentPositionMs(player: MediaPlayer?): Long {
        return try {
            player?.currentPosition?.toLong()?.coerceAtLeast(0L) ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun safeDurationMs(player: MediaPlayer?): Long {
        return try {
            player?.duration?.toLong()?.coerceAtLeast(0L) ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun startPersistLoop() {
        if (persistJob?.isActive == true) return
        persistJob = lifecycleScope.launch {
            var statsTick = 0
            while (true) {
                delay(2000)
                sampleCurrentPosition()
                markCountIfPassedThreshold()
                persistPlaybackState(sync = false)
                statsTick++
                if (statsTick >= 3) {
                    statsTick = 0
                    ListenStats.persistSnapshot(this@PlaybackService, sync = false)
                }
            }
        }
    }

    private fun stopPersistLoop() {
        persistJob?.cancel()
        persistJob = null
    }

    private fun persistPlaybackState(sync: Boolean, positionOverrideMs: Long? = null) {
        val file = currentFile
        if (file == null || queue.isEmpty() || currentIndex !in queue.indices) {
            // 不在这里清空快照。长时间后台后，系统可能会把 Service 重新拉起成一个
            // 暂无队列的空实例；如果此时覆盖掉上一次有效快照，播放页就会退回
            // “请播放歌曲”。真正需要清空的场景由 explicitStopAndRelease(clearSnapshot = true)
            // 和播放失败兜底路径显式处理。
            return
        }
        val positionMs = (positionOverrideMs ?: safeCurrentPositionMs(mediaPlayer)).coerceAtLeast(0L)
        PlaybackStateStore.save(
            context = this,
            snapshot = PlaybackStateStore.Snapshot(
                currentPath = file.path,
                currentIndex = currentIndex,
                mode = queueMode,
                positionMs = positionMs,
                queuePaths = queue.map { it.path },
                sourcePaths = (if (sourceList.isNotEmpty()) sourceList else queue).map { it.path },
                sourceName = queueSourceName,
                savedAt = System.currentTimeMillis()
            ),
            sync = sync
        )
    }

    private fun initializeCountTracking(
        file: MusicScanner.MusicFile,
        durationMs: Long,
        initialPositionMs: Long
    ) {
        sessionDurationMs = durationMs.coerceAtLeast(0L)
        sessionPath = file.path
        sessionThresholdPercent = PlaybackSettings.getThresholdPercent(this).coerceIn(1, 100)
        sessionCounted = false
        sessionLastObservedPosMs = initialPositionMs.coerceAtLeast(0L)
        sessionContinuousPlayedMs = 0L
        sessionContinuousAnchorElapsedMs = 0L
        sessionUserSeeking = false
    }

    private fun beginListenSession(file: MusicScanner.MusicFile) {
        sessionPath = file.path
        sessionLastObservedPosMs = safeCurrentPositionMs(mediaPlayer).coerceAtLeast(0L)
        ListenStats.startSession(this, file.path)
        startContinuousPlaybackWindowIfNeeded()
    }

    private fun thresholdListenMs(durationMs: Long = sessionDurationMs): Long {
        if (durationMs <= 0L) return Long.MAX_VALUE
        return (durationMs * sessionThresholdPercent / 100.0).toLong().coerceAtLeast(1L)
    }

    private fun onManualSeek(targetPosMs: Long) {
        sessionLastObservedPosMs = targetPosMs.coerceAtLeast(0L)
        sessionContinuousAnchorElapsedMs = 0L
        if (!sessionCounted) {
            sessionContinuousPlayedMs = 0L
        }
        ListenStats.pauseSession()
        if (playing && !sessionUserSeeking) {
            currentFile?.let { beginListenSession(it) }
        }
    }

    private fun recordThresholdCount(path: String) {
        ListenStats.incrementPlayCount(path)
        ListenStats.recordCountEvent(path, System.currentTimeMillis())
        sessionCounted = true
        sessionContinuousAnchorElapsedMs = 0L
        ListenStats.persistSnapshot(this, sync = false)
        PlaybackManager.notifyStateChanged()
    }

    private fun startContinuousPlaybackWindowIfNeeded() {
        if (sessionCounted) return
        if (!playing || sessionUserSeeking) return
        if (sessionDurationMs <= 0L) return
        if (sessionPath.isNullOrBlank()) return
        if (sessionContinuousAnchorElapsedMs > 0L) return
        sessionContinuousAnchorElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun normalizeIndex(index: Int): Int {
        if (queue.isEmpty()) return -1
        val size = queue.size
        return ((index % size) + size) % size
    }

    private fun findFallbackIndex(
        failedIndex: Int,
        direction: Int
    ): Int {
        if (queue.isEmpty() || failedIndex !in queue.indices) return -1
        if (failedPathsInCurrentChain.size >= queue.size) return -1

        val step = if (direction < 0) -1 else 1
        for (offset in 1..queue.size) {
            val candidateIndex = normalizeIndex(failedIndex + offset * step)
            val candidate = queue.getOrNull(candidateIndex) ?: continue
            if (candidate.path !in failedPathsInCurrentChain) return candidateIndex
        }
        return -1
    }

    private fun handlePlayerLoadFailure(file: MusicScanner.MusicFile, error: Exception) {
        Log.e(TAG, "handlePlayerLoadFailure path=${file.path}", error)
        sampleCurrentPosition()
        markCountIfPassedThreshold()
        ListenStats.save(this)

        val failedIndex = currentIndex
        failedPathsInCurrentChain.add(file.path)
        if (file.path == forcedNextPath) forcedNextPath = null
        val fallbackIndex = findFallbackIndex(failedIndex, fallbackDirection)

        releasePlayer()
        playing = false
        trackSwitching = false
        pushState()
        PlaybackManager.notifyStateChanged()

        if (fallbackIndex >= 0) {
            currentIndex = fallbackIndex
            persistPlaybackState(sync = false, positionOverrideMs = 0L)
            playCurrent()
        } else {
            PlaybackStateStore.clear(this, sync = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    // ============================================================
    // 播放阀值辅助
    // ============================================================

    private fun sampleCurrentPosition() {
        val mp = mediaPlayer ?: return
        try {
            sessionLastObservedPosMs = mp.currentPosition.toLong().coerceAtLeast(0L)
        } catch (_: Exception) {
        }
    }

    private fun sampleContinuousPlaybackProgress() {
        if (sessionCounted) return
        val anchor = sessionContinuousAnchorElapsedMs
        if (anchor <= 0L) return
        val now = SystemClock.elapsedRealtime()
        val delta = (now - anchor).coerceAtLeast(0L)
        if (delta > 0L) {
            sessionContinuousPlayedMs += delta
        }
        sessionContinuousAnchorElapsedMs = if (playing && !sessionUserSeeking) now else 0L
    }

    private fun markCountIfPassedThreshold(durationOverride: Long = -1L) {
        if (sessionCounted) return
        val path = sessionPath ?: return
        val duration = if (durationOverride > 0L) durationOverride else sessionDurationMs
        if (duration <= 0L) return

        sampleContinuousPlaybackProgress()
        val thresholdMs = thresholdListenMs(duration)
        if (sessionContinuousPlayedMs >= thresholdMs) {
            recordThresholdCount(path)
        } else {
            startContinuousPlaybackWindowIfNeeded()
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
    }
}
