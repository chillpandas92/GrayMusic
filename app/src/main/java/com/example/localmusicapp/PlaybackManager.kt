package com.example.localmusicapp

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet

/**
 * PlaybackService 的单例门面
 */
object PlaybackManager {

    interface Listener {
        fun onPlaybackChanged(currentPath: String?, isPlaying: Boolean)
    }

    @Volatile private var service: PlaybackService? = null
    @Volatile private var cachedCurrentFile: MusicScanner.MusicFile? = null
    @Volatile private var cachedIsPlaying: Boolean = false
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun hasService(): Boolean = service != null
    fun currentPath(): String? = service?.currentFile()?.path ?: cachedCurrentFile?.path
    fun currentFile(): MusicScanner.MusicFile? = service?.currentFile() ?: cachedCurrentFile
    fun isPlaying(): Boolean = service?.isPlaying() ?: cachedIsPlaying
    fun currentPositionMs(): Long = service?.currentPositionMs() ?: 0L

    // --- 平滑播放位置 ---
    // MediaPlayer.currentPosition 在 Android 上约每 100ms 才内部刷新一次，直接按帧取会有"冻结再跳"的
    // 感觉。下面用 SystemClock 做插值：两次 MediaPlayer 位置变化之间，按真实时间线性外推，
    // 这样逐字歌词的扫光能做到帧级平滑。seek / 暂停 / 大幅偏差会自动重新对齐。
    @Volatile private var smoothAnchorAudioMs: Long = -1L
    @Volatile private var smoothAnchorClockMs: Long = 0L

    /**
     * 给歌词扫光等需要帧级平滑的场景使用。其他场景（进度条更新、保存状态等）继续用
     * [currentPositionMs] 拿 MediaPlayer 的原始值即可。
     */
    fun smoothPositionMs(): Long {
        val svc = service ?: return 0L
        val raw = svc.currentPositionMs()
        val now = SystemClock.elapsedRealtime()
        val playing = svc.isPlaying()

        if (!playing) {
            // 暂停时直接用原始值，并把锚点对齐到当前，这样恢复播放时从真实位置继续。
            smoothAnchorAudioMs = raw
            smoothAnchorClockMs = now
            return raw
        }

        val anchorAudio = smoothAnchorAudioMs
        val anchorClock = smoothAnchorClockMs
        if (anchorAudio < 0L) {
            smoothAnchorAudioMs = raw
            smoothAnchorClockMs = now
            return raw
        }

        val interpolated = anchorAudio + (now - anchorClock)
        val drift = raw - interpolated

        // 偏差超过 ~450ms：判定为 seek / 切歌 / 缓冲异常，直接对齐到原始值。
        if (drift > 450L || drift < -450L) {
            smoothAnchorAudioMs = raw
            smoothAnchorClockMs = now
            return raw
        }

        // MediaPlayer 的内部刻度走了一格（raw 超过了预测），说明需要温和地把锚点前推、
        // 以免长时间累积漂移。用 1/10 的比例，比之前的 1/3 更柔和，每次只有几毫秒的前推，
        // 用户几乎看不到跳帧，但长时间仍然会稳定对齐。
        if (drift > 8L) {
            smoothAnchorAudioMs = anchorAudio + drift / 10
            smoothAnchorClockMs = now
            return smoothAnchorAudioMs
        }

        return interpolated
    }
    fun totalDurationMs(): Long = service?.totalDurationMs()?.takeIf { it > 0L } ?: currentFile()?.duration ?: 0L
    fun seekTo(posMs: Long) { service?.seekToMs(posMs) }

    fun refreshReplayGain() { service?.refreshReplayGain() }
    fun refreshCarLyrics() { service?.refreshCarLyrics() }
    fun refreshPlaybackThreshold() { service?.refreshPlaybackThreshold() }

    /** 拖进度条专用静音：PlayerActivity 在 onStartTrackingTouch 调 true，松手后调 false */
    fun setScrubMuted(muted: Boolean) { service?.setScrubMuted(muted) }

    // 播放列表抽屉需要的 API
    fun queue(): List<MusicScanner.MusicFile> = service?.queue() ?: emptyList()
    fun currentQueueIndex(): Int = service?.currentQueueIndex() ?: -1
    fun queueSourceName(): String = service?.queueSourceName().orEmpty()
    fun queueMode(): PlaybackSettings.Mode =
        service?.queueMode() ?: PlaybackSettings.Mode.SEQUENTIAL
    fun isTrackSwitching(): Boolean = service?.isTrackSwitching() == true
    fun playAt(context: Context, index: Int) {
        ensureForegroundServiceStarted(context)
        service?.playAt(index)
    }

    fun playNext(context: Context, file: MusicScanner.MusicFile) {
        val active = service
        if (active != null) {
            active.playNext(file)
        } else {
            pendingOnAttach = { it.playNext(file) }
            ensureForegroundServiceStarted(context)
        }
    }

    fun appendNextAndPlay(context: Context, file: MusicScanner.MusicFile) {
        val active = service
        if (active != null) {
            active.appendNextAndPlay(file)
        } else {
            pendingOnAttach = { it.appendNextAndPlay(file) }
            ensureForegroundServiceStarted(context)
        }
    }

    fun replaceQueueOrder(files: List<MusicScanner.MusicFile>) {
        service?.replaceQueueOrder(files)
    }

    fun removeFromQueue(path: String) {
        service?.removeFromQueue(path)
    }

    /** 运行中切换播放模式——不打断当前播放 */
    fun switchQueueMode(mode: PlaybackSettings.Mode) {
        service?.switchQueueMode(mode)
    }

    /**
     * 以整个队列开始播放，startIndex 指定起始。
     * 之后就能调 next()/prev() 在队列里切换。
     *
     * @param mode 构建队列时使用的策略（随机 / 顺序 / 单曲循环）。只作标记给 UI，不会再重排 files
     * @param sourceList 用户点击歌曲时所在页面的完整有序歌曲列表。用户抽屉里切模式时用它重建队列。
     *                   不传则默认等于 files 本身（适合已经有序的场景）
     */
    fun playQueue(
        context: Context,
        files: List<MusicScanner.MusicFile>,
        startIndex: Int,
        mode: PlaybackSettings.Mode = PlaybackSettings.Mode.SEQUENTIAL,
        sourceList: List<MusicScanner.MusicFile> = files,
        sourceName: String = ""
    ) {
        if (files.isEmpty() || startIndex !in files.indices) return
        val s = service
        if (s != null) {
            s.playQueue(files, startIndex, mode, sourceList, sourceName)
        } else {
            pendingOnAttach = { it.playQueue(files, startIndex, mode, sourceList, sourceName) }
            ensureForegroundServiceStarted(context)
        }
    }

    /**
     * 根据已持久化的播放状态恢复当前队列与进度。
     *
     * 恢复后的状态默认是“已加载但暂停”，避免应用重进后直接外放。即使 Service 已经存在，
     * 只要它还没有队列，也要重新注入快照；这能修复长时间后台后 Service 被系统重建为空壳，
     * 播放页却显示“请播放歌曲”的问题。
     */
    fun restoreSavedStateIfNeeded(
        context: Context,
        libraryFiles: List<MusicScanner.MusicFile>
    ) {
        val snapshot = PlaybackStateStore.load(context) ?: return
        val restoreFiles = libraryFiles.ifEmpty { PlaybackUiResolver.restoreLibraryFiles(context) }
        if (restoreFiles.isEmpty()) return

        val action: (PlaybackService) -> Unit = { s ->
            if (s.currentFile() == null) {
                s.restoreState(snapshot, restoreFiles, autoStart = false)
            }
        }
        val s = service
        if (s != null) {
            action(s)
        } else {
            pendingOnAttach = action
            ensureBackgroundServiceStarted(context)
        }
    }

    fun toggle(context: Context) {
        val active = service
        if (active != null) {
            if (active.currentFile() != null) {
                active.toggle()
                return
            }

            val snapshot = PlaybackStateStore.load(context)
            val libraryFiles = PlaybackUiResolver.restoreLibraryFiles(context)
            if (snapshot != null && libraryFiles.isNotEmpty()) {
                active.restoreState(snapshot, libraryFiles, autoStart = true)
            }
            return
        }

        val snapshot = PlaybackStateStore.load(context) ?: return
        val libraryFiles = PlaybackUiResolver.restoreLibraryFiles(context)
        if (libraryFiles.isEmpty()) return
        pendingOnAttach = { s ->
            s.restoreState(snapshot, libraryFiles, autoStart = true)
        }
        ensureForegroundServiceStarted(context)
    }

    fun next(context: Context) {
        val active = service
        if (active != null) {
            if (active.currentFile() != null) {
                active.skipToNext()
                return
            }

            val snapshot = PlaybackStateStore.load(context)
            val libraryFiles = PlaybackUiResolver.restoreLibraryFiles(context)
            val shifted = snapshot?.shiftedFor(libraryFiles, direction = 1)
            if (shifted != null) {
                active.restoreState(shifted, libraryFiles, autoStart = true)
            }
            return
        }

        val snapshot = PlaybackStateStore.load(context) ?: return
        val libraryFiles = PlaybackUiResolver.restoreLibraryFiles(context)
        val shifted = snapshot.shiftedFor(libraryFiles, direction = 1) ?: return
        pendingOnAttach = { s ->
            s.restoreState(shifted, libraryFiles, autoStart = true)
        }
        ensureForegroundServiceStarted(context)
    }

    fun prev(context: Context) {
        val active = service
        if (active != null) {
            if (active.currentFile() != null) {
                active.skipToPrevious()
                return
            }

            val snapshot = PlaybackStateStore.load(context)
            val libraryFiles = PlaybackUiResolver.restoreLibraryFiles(context)
            val shifted = snapshot?.shiftedFor(libraryFiles, direction = -1)
            if (shifted != null) {
                active.restoreState(shifted, libraryFiles, autoStart = true)
            }
            return
        }

        val snapshot = PlaybackStateStore.load(context) ?: return
        val libraryFiles = PlaybackUiResolver.restoreLibraryFiles(context)
        val shifted = snapshot.shiftedFor(libraryFiles, direction = -1) ?: return
        pendingOnAttach = { s ->
            s.restoreState(shifted, libraryFiles, autoStart = true)
        }
        ensureForegroundServiceStarted(context)
    }

    private fun PlaybackStateStore.Snapshot.shiftedFor(
        libraryFiles: List<MusicScanner.MusicFile>,
        direction: Int
    ): PlaybackStateStore.Snapshot? {
        if (libraryFiles.isEmpty()) return null
        val availablePaths = libraryFiles.map { it.path }.toSet()
        val restoredQueuePaths = queuePaths
            .filter { it in availablePaths }
            .distinct()
        val restoredSourcePaths = sourcePaths
            .filter { it in availablePaths }
            .distinct()
        val effectiveQueue = when {
            restoredQueuePaths.isNotEmpty() -> restoredQueuePaths
            restoredSourcePaths.isNotEmpty() -> restoredSourcePaths
            currentPath in availablePaths -> listOf(currentPath)
            else -> emptyList()
        }
        if (effectiveQueue.isEmpty()) return null

        val resolvedCurrentPath = currentPath.takeIf { it.isNotBlank() }
            ?: effectiveQueue.getOrNull(currentIndex)
        val currentQueueIndex = effectiveQueue.indexOfFirst { it == resolvedCurrentPath }
            .takeIf { it >= 0 }
            ?: currentIndex.takeIf { it in effectiveQueue.indices }
            ?: 0
        val nextIndex = normalizeQueueIndex(currentQueueIndex + direction, effectiveQueue.size)
        val shiftedPath = effectiveQueue.getOrNull(nextIndex) ?: return null

        return copy(
            currentPath = shiftedPath,
            currentIndex = nextIndex,
            positionMs = 0L,
            queuePaths = effectiveQueue,
            sourcePaths = restoredSourcePaths.ifEmpty { effectiveQueue }
        )
    }

    private fun normalizeQueueIndex(index: Int, size: Int): Int {
        if (size <= 0) return -1
        return ((index % size) + size) % size
    }

    /** 彻底停止播放并释放 Service；导入备份前使用，避免 ListenStats 并发写入 */
    fun stop(context: Context) {
        val intent = Intent(context, PlaybackService::class.java)
        service?.let {
            // 通过 MediaSession onStop 走已有的 save + releasePlayer 路径
            it.onMediaSessionStopExternal()
        }
        // 兜底：如果 Service 还活着（MediaSession 已 release 或回调异常），直接 stopService
        try { context.stopService(intent) } catch (_: Exception) {}
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onPlaybackChanged(currentPath(), isPlaying())
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    // Service 生命周期回调
    @Volatile private var pendingOnAttach: ((PlaybackService) -> Unit)? = null

    internal fun onServiceCreated(s: PlaybackService) {
        service = s
        cachedCurrentFile = s.currentFile()
        cachedIsPlaying = s.isPlaying()
        pendingOnAttach?.let { it(s); pendingOnAttach = null }
        notifyStateChanged()
    }

    internal fun onServiceDestroyed() {
        val lastService = service
        cachedCurrentFile = lastService?.currentFile()
        cachedIsPlaying = false
        service = null
        notifyStateChanged()
    }

    internal fun notifyStateChanged() {
        service?.let {
            cachedCurrentFile = it.currentFile()
            cachedIsPlaying = it.isPlaying()
        }
        val p = currentPath()
        val playing = isPlaying()
        listeners.forEach { it.onPlaybackChanged(p, playing) }
    }

    private fun ensureForegroundServiceStarted(context: Context) {
        if (service != null) return
        val intent = Intent(context, PlaybackService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun ensureBackgroundServiceStarted(context: Context) {
        if (service != null) return
        val intent = Intent(context, PlaybackService::class.java)
        context.startService(intent)
    }
}
