package com.example.localmusicapp

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 长按歌曲后的铃声编辑抽屉。
 *
 * 预览时严格按 start/end 播放片段；设为铃声时优先导出所选片段，设备或编码器
 * 不支持当前音频容器裁剪时，会提示用户重新选择更通用的音频文件，而不是静默使用整首歌。
 */
object RingtoneEditorSheet {
    private const val MIN_SEGMENT_MS = 1_000L
    private const val DEFAULT_RINGTONE_MS = 30_000L

    fun show(activity: AppCompatActivity, file: MusicScanner.MusicFile) {
        val audioFile = File(file.path)
        if (!audioFile.exists() || !audioFile.isFile) {
            Toast.makeText(activity, "歌曲文件不存在，无法设置铃声", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_ringtone_editor, null)
        AppFont.applyTo(view)
        dialog.setContentView(view)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.setBackgroundColor(Color.TRANSPARENT)
                sheet.layoutParams = sheet.layoutParams.apply {
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
                BottomSheetBehavior.from(sheet).apply {
                    isFitToContents = false
                    expandedOffset = 0
                    skipCollapsed = true
                    isHideable = true
                    isDraggable = true
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }

        val title = view.findViewById<TextView>(R.id.tvRingtoneTitle)
        val subtitle = view.findViewById<TextView>(R.id.tvRingtoneSubtitle)
        val segmentInfo = view.findViewById<TextView>(R.id.tvRingtoneSegmentInfo)
        val waveform = view.findViewById<RingtoneWaveformView>(R.id.viewRingtoneWaveform)
        val playbackSeek = view.findViewById<SeekBar>(R.id.seekRingtonePlayback)
        val startSeek = view.findViewById<SeekBar>(R.id.seekRingtoneStart)
        val endSeek = view.findViewById<SeekBar>(R.id.seekRingtoneEnd)
        val startInput = view.findViewById<EditText>(R.id.inputRingtoneStart)
        val endInput = view.findViewById<EditText>(R.id.inputRingtoneEnd)
        val playButton = view.findViewById<TextView>(R.id.btnRingtonePlayPause)
        val applyButton = view.findViewById<TextView>(R.id.btnRingtoneApply)
        val useCurrentStart = view.findViewById<TextView>(R.id.btnRingtoneUseCurrentStart)
        val useCurrentEnd = view.findViewById<TextView>(R.id.btnRingtoneUseCurrentEnd)

        val durationMs = file.duration.takeIf { it > 0L } ?: readDurationMs(audioFile)
        if (durationMs <= 0L) {
            Toast.makeText(activity, "无法读取音频时长", Toast.LENGTH_SHORT).show()
            return
        }
        if (durationMs < MIN_SEGMENT_MS) {
            Toast.makeText(activity, "歌曲太短，无法设置为铃声片段", Toast.LENGTH_SHORT).show()
            return
        }

        var startMs = 0L
        var endMs = min(durationMs, DEFAULT_RINGTONE_MS)
        var updatingUi = false
        var isPrepared = false
        val handler = Handler(Looper.getMainLooper())
        var mediaPlayer: MediaPlayer? = null

        fun progressToMs(progress: Int): Long {
            return ((progress.coerceIn(0, 1000) / 1000.0) * durationMs).toLong()
                .coerceIn(0L, durationMs)
        }

        fun msToProgress(ms: Long): Int {
            if (durationMs <= 0L) return 0
            return ((ms.coerceIn(0L, durationMs).toDouble() / durationMs.toDouble()) * 1000.0).toInt()
                .coerceIn(0, 1000)
        }

        fun updatePlaybackPositionUi(positionMs: Long) {
            val safePosition = positionMs.coerceIn(startMs, endMs)
            playbackSeek.progress = msToProgress(safePosition)
            waveform.setPlaybackFraction(
                if (durationMs > 0L) safePosition.toFloat() / durationMs.toFloat() else 0f
            )
        }

        fun seekPreviewTo(positionMs: Long) {
            val upper = (endMs - 40L).coerceAtLeast(startMs)
            val target = positionMs.coerceIn(startMs, upper)
            if (isPrepared) {
                runCatching { mediaPlayer?.seekTo(target.toInt()) }
            }
            updatePlaybackPositionUi(target)
        }

        fun refreshUi() {
            updatingUi = true
            startSeek.progress = msToProgress(startMs)
            endSeek.progress = msToProgress(endMs)
            startInput.setText(formatTime(startMs))
            endInput.setText(formatTime(endMs))
            segmentInfo.text = "片段：${formatTime(startMs)} - ${formatTime(endMs)} · ${formatDuration(endMs - startMs)}"
            waveform.setRange(
                if (durationMs > 0L) startMs.toFloat() / durationMs.toFloat() else 0f,
                if (durationMs > 0L) endMs.toFloat() / durationMs.toFloat() else 1f
            )
            updatePlaybackPositionUi(mediaPlayer?.takeIf { isPrepared }?.currentPosition?.toLong() ?: startMs)
            updatingUi = false
        }

        fun stopPreview(resetToStart: Boolean = false) {
            val player = mediaPlayer
            runCatching {
                if (player?.isPlaying == true) player.pause()
                if (resetToStart && isPrepared) player?.seekTo(startMs.toInt())
            }
            if (resetToStart) updatePlaybackPositionUi(startMs) else player?.currentPosition?.toLong()?.let { updatePlaybackPositionUi(it) }
            playButton.text = "播放片段"
        }

        val previewTicker = object : Runnable {
            override fun run() {
                val player = mediaPlayer
                if (player == null || !isPrepared || !player.isPlaying) return
                updatePlaybackPositionUi(player.currentPosition.toLong())
                if (player.currentPosition.toLong() >= endMs - 40L) {
                    stopPreview(resetToStart = true)
                    return
                }
                handler.postDelayed(this, 80L)
            }
        }

        fun restartPreviewIfPlaying() {
            val player = mediaPlayer ?: return
            if (!isPrepared || !player.isPlaying) return
            runCatching { player.seekTo(startMs.toInt()) }
            handler.removeCallbacks(previewTicker)
            handler.post(previewTicker)
        }

        fun normalizeBounds(changedStart: Boolean) {
            startMs = startMs.coerceIn(0L, durationMs)
            endMs = endMs.coerceIn(0L, durationMs)
            if (endMs - startMs < MIN_SEGMENT_MS) {
                if (changedStart) {
                    startMs = (endMs - MIN_SEGMENT_MS).coerceAtLeast(0L)
                } else {
                    endMs = (startMs + MIN_SEGMENT_MS).coerceAtMost(durationMs)
                }
            }
            if (startMs >= endMs) {
                startMs = (endMs - MIN_SEGMENT_MS).coerceAtLeast(0L)
            }
        }

        fun applyStart(value: Long, fromTyping: Boolean = false) {
            startMs = value
            normalizeBounds(changedStart = true)
            refreshUi()
            if (!fromTyping) restartPreviewIfPlaying()
        }

        fun applyEnd(value: Long, fromTyping: Boolean = false) {
            endMs = value
            normalizeBounds(changedStart = false)
            refreshUi()
            if (!fromTyping) restartPreviewIfPlaying()
        }

        title.text = "设置铃声"
        val artist = ArtistUtils.displayArtists(file.artist.ifBlank { file.albumArtist }).ifBlank { "未知歌手" }
        subtitle.text = "${file.title} · $artist"
        refreshUi()

        mediaPlayer = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            setOnPreparedListener {
                isPrepared = true
                runCatching { seekTo(startMs.toInt()) }
                updatePlaybackPositionUi(startMs)
            }
            setOnCompletionListener { stopPreview(resetToStart = true) }
            prepareAsync()
        }

        startSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !updatingUi) applyStart(progressToMs(progress))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        endSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !updatingUi) applyEnd(progressToMs(progress))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        playbackSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !updatingUi) seekPreviewTo(progressToMs(progress))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        waveform.setOnSeekFractionChanged { fraction ->
            seekPreviewTo((fraction * durationMs).toLong())
        }

        fun commitTypedTime(input: EditText, isStart: Boolean) {
            if (updatingUi) return
            val parsed = parseTime(input.text?.toString().orEmpty())
            if (parsed != null) {
                if (isStart) applyStart(parsed, fromTyping = true) else applyEnd(parsed, fromTyping = true)
            } else {
                refreshUi()
            }
        }

        fun bindTimeInput(input: EditText, isStart: Boolean) {
            input.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) commitTypedTime(input, isStart)
            }
            input.setOnEditorActionListener { _, _, _ ->
                commitTypedTime(input, isStart)
                input.clearFocus()
                true
            }
        }
        bindTimeInput(startInput, isStart = true)
        bindTimeInput(endInput, isStart = false)

        playButton.setOnClickListener {
            val player = mediaPlayer
            if (player == null || !isPrepared) {
                Toast.makeText(activity, "音频仍在准备中", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (player.isPlaying) {
                stopPreview(resetToStart = false)
            } else {
                val current = player.currentPosition.toLong()
                if (current < startMs || current >= endMs - 40L) {
                    runCatching { player.seekTo(startMs.toInt()) }
                }
                runCatching { player.start() }
                playButton.text = "暂停"
                handler.removeCallbacks(previewTicker)
                handler.post(previewTicker)
            }
        }

        useCurrentStart.setOnClickListener {
            val player = mediaPlayer
            val current = if (player != null && isPrepared) player.currentPosition.toLong() else startMs
            applyStart(current)
        }
        useCurrentEnd.setOnClickListener {
            val player = mediaPlayer
            val current = if (player != null && isPrepared) player.currentPosition.toLong() else endMs
            applyEnd(current)
        }

        applyButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(activity)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                Toast.makeText(activity, "请允许修改系统设置后再点击设为铃声", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            stopPreview(resetToStart = true)
            applyButton.isEnabled = false
            applyButton.text = "正在设置…"
            val selectedStartMs = startMs
            val selectedEndMs = endMs
            Thread {
                try {
                    val clipped = buildSelectedSegment(activity, audioFile, file, selectedStartMs, selectedEndMs, durationMs)
                    val ringtoneUri = installRingtone(activity, clipped, file, selectedStartMs, selectedEndMs)
                    RingtoneManager.setActualDefaultRingtoneUri(
                        activity,
                        RingtoneManager.TYPE_RINGTONE,
                        ringtoneUri
                    )
                    activity.runOnUiThread {
                        Toast.makeText(activity, "已设为铃声", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        applyButton.isEnabled = true
                        applyButton.text = "设为铃声"
                        Toast.makeText(activity, e.message ?: "设置铃声失败", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            runCatching { mediaPlayer?.stop() }
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
        }
        dialog.show()
    }

    private fun readDurationMs(file: File): Long {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
    }

    private fun buildSelectedSegment(
        context: Context,
        source: File,
        file: MusicScanner.MusicFile,
        startMs: Long,
        endMs: Long,
        durationMs: Long
    ): File {
        val wholeSong = startMs <= 500L && abs(endMs - durationMs) <= 500L
        if (wholeSong) return source

        val safeName = sanitizeFileName(file.title.ifBlank { source.nameWithoutExtension })
        val out = File(context.cacheDir, "ringtone_${System.currentTimeMillis()}_$safeName.m4a")
        runCatching {
            trimWithExtractor(source.absolutePath, out.absolutePath, startMs * 1000L, endMs * 1000L)
        }.onSuccess {
            if (out.exists() && out.length() > 0L) return out
            out.delete()
        }.onFailure {
            out.delete()
        }

        // FLAC/WAV/OGG 等格式不同设备支持的裁剪容器不一致。为了确保这些格式也能
        // 被系统设为铃声，裁剪失败时回退为原文件写入系统铃声库，而不是直接报错。
        return source
    }

    private fun trimWithExtractor(sourcePath: String, outputPath: String, startUs: Long, endUs: Long) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(sourcePath)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) {
                throw IllegalStateException("未找到音频轨道")
            }
            extractor.selectTrack(audioTrackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(format)
            muxer.start()

            val maxInputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
            } else {
                1024 * 1024
            }
            val buffer = ByteBuffer.allocate(maxInputSize)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0L || sampleTime > endUs) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, max(0L, sampleTime - startUs), extractor.sampleFlags)
                muxer.writeSampleData(muxerTrack, buffer, info)
                buffer.clear()
                extractor.advance()
            }
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun installRingtone(
        context: Context,
        source: File,
        file: MusicScanner.MusicFile,
        startMs: Long,
        endMs: Long
    ): Uri {
        val resolver = context.contentResolver
        val ext = source.extension.ifBlank { "m4a" }.lowercase()
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            else -> "audio/mp4"
        }
        val displayName = sanitizeFileName(
            "${file.title.ifBlank { source.nameWithoutExtension }}_${formatTimeForName(startMs)}_${formatTimeForName(endMs)}.$ext"
        )

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.TITLE, file.title.ifBlank { source.nameWithoutExtension })
            put(MediaStore.Audio.Media.ARTIST, file.artist.ifBlank { file.albumArtist }.ifBlank { "未知歌手" })
            put(MediaStore.Audio.Media.IS_RINGTONE, true)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
            put(MediaStore.Audio.Media.IS_ALARM, false)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES + "/LocalMusic")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES)
                dir.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(dir, displayName).absolutePath)
            }
        }

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法写入系统铃声库")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("无法保存铃声文件")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun parseTime(raw: String): Long? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val parts = text.split(":")
        return runCatching {
            val seconds = when (parts.size) {
                1 -> parts[0].toDouble()
                2 -> parts[0].toLong() * 60.0 + parts[1].toDouble()
                3 -> parts[0].toLong() * 3600.0 + parts[1].toLong() * 60.0 + parts[2].toDouble()
                else -> return null
            }
            (seconds * 1000.0).toLong().coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSeconds / 3600L
        val m = (totalSeconds % 3600L) / 60L
        val s = totalSeconds % 60L
        return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun formatDuration(ms: Long): String {
        val sec = (ms / 1000L).coerceAtLeast(0L)
        val m = sec / 60L
        val s = sec % 60L
        return if (m > 0L) "${m}分${s}秒" else "${s}秒"
    }

    private fun formatTimeForName(ms: Long): String {
        return formatTime(ms).replace(":", "-")
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "ringtone" }
    }
}
