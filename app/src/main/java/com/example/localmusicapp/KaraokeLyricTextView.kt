package com.example.localmusicapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.text.Layout
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.roundToInt

class KaraokeLyricTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private data class FillClip(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    private data class ProgressPoint(
        val lineIndex: Int,
        val x: Float
    )

    private var lyricLine: LyricRepository.LyricLine? = null
    private var karaokeActive: Boolean = false
    private var playbackPositionMs: Long = 0L
    private var fillColor: Int = Color.BLACK
    private var unfilledColor: Int = Color.BLACK
    // 刚进入某行高亮时，记录激活瞬间的 wall-clock 和这行的起始时间。前 200ms 用 smoothstep
    // 把显示位置从 lineStart 缓动到真实播放位置 —— 避免第一帧就跳到当前进度（第一字没有"从0渐入"的感觉）。
    private var karaokeActivatedAtElapsedMs: Long = 0L
    private var activeLineStartMs: Long = 0L
    private var activeLineTimeKey: Long = Long.MIN_VALUE
    private val visibleRect = Rect()

    fun bindKaraokeLine(
        line: LyricRepository.LyricLine,
        active: Boolean,
        currentPositionMs: Long,
        fillColor: Int,
        unfilledColor: Int
    ) {
        this.lyricLine = line
        val wasSameActiveLine = karaokeActive && activeLineTimeKey == line.timeMs
        this.karaokeActive = active
        this.playbackPositionMs = currentPositionMs
        this.fillColor = fillColor
        this.unfilledColor = unfilledColor
        if (active && !wasSameActiveLine) {
            // 新一行刚变高亮 —— 打时间戳，接下来的 200ms 把扫光从 lineStart 平滑缓动到真实音频位置。
            karaokeActivatedAtElapsedMs = SystemClock.elapsedRealtime()
            activeLineStartMs = line.words.firstOrNull()?.startTimeMs ?: line.timeMs
            activeLineTimeKey = line.timeMs
        } else if (!active) {
            karaokeActivatedAtElapsedMs = 0L
            activeLineTimeKey = Long.MIN_VALUE
        }
        if (active && line.words.isNotEmpty()) {
            postInvalidateOnAnimation()
        } else {
            invalidate()
        }
    }

    fun setPlaybackPosition(positionMs: Long) {
        if (playbackPositionMs == positionMs) return
        playbackPositionMs = positionMs
        if (karaokeActive && lyricLine?.words?.isNotEmpty() == true) {
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val line = lyricLine
        val textLayout = layout
        if (!karaokeActive || line == null || line.words.isEmpty() || textLayout == null || text.isNullOrEmpty()) {
            super.onDraw(canvas)
            return
        }

        drawTextLayout(canvas, textLayout, unfilledColor, null)
        val clips = buildFillClips(line, textLayout, resolvedPlaybackPositionMs())
        for (clip in clips) {
            drawTextLayout(canvas, textLayout, fillColor, clip)
        }

        if (isAttachedToWindow && PlaybackManager.isPlaying() && isActuallyVisibleInWindow()) {
            postInvalidateOnAnimation()
        }
    }

    private fun resolvedPlaybackPositionMs(): Long {
        val live = PlaybackManager.smoothPositionMs()
        return if (live > 0L || PlaybackManager.isPlaying()) live else playbackPositionMs
    }

    private fun isActuallyVisibleInWindow(): Boolean {
        if (!isShown || windowVisibility != android.view.View.VISIBLE || alpha <= 0f) return false
        return getGlobalVisibleRect(visibleRect) && visibleRect.width() > 0 && visibleRect.height() > 0
    }

    private fun drawTextLayout(canvas: Canvas, textLayout: Layout, color: Int, clip: FillClip?) {
        val oldColor = paint.color
        paint.color = color
        val saveCount = canvas.save()
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
        if (clip != null) {
            canvas.clipRect(clip.left, clip.top, clip.right, clip.bottom)
        }
        textLayout.draw(canvas)
        canvas.restoreToCount(saveCount)
        paint.color = oldColor
    }

    private fun buildFillClips(
        line: LyricRepository.LyricLine,
        textLayout: Layout,
        positionMs: Long
    ): List<FillClip> {
        val point = progressPointFor(line, textLayout, positionMs) ?: return emptyList()
        val clips = ArrayList<FillClip>(point.lineIndex + 1)
        val lastLine = point.lineIndex.coerceIn(0, textLayout.lineCount - 1)
        for (visualLine in 0..lastLine) {
            val rawLeft = textLayout.getLineLeft(visualLine)
            val rawRight = textLayout.getLineRight(visualLine)
            val lineLeft = minOf(rawLeft, rawRight)
            val lineRight = maxOf(rawLeft, rawRight)
            val right = if (visualLine < point.lineIndex) {
                lineRight
            } else {
                point.x.coerceIn(lineLeft, lineRight)
            }
            if (right > lineLeft + 0.5f) {
                clips.add(
                    FillClip(
                        left = lineLeft,
                        top = textLayout.getLineTop(visualLine).toFloat(),
                        right = right,
                        bottom = textLayout.getLineBottom(visualLine).toFloat()
                    )
                )
            }
        }
        return clips
    }

    private fun progressPointFor(
        line: LyricRepository.LyricLine,
        textLayout: Layout,
        positionMs: Long
    ): ProgressPoint? {
        val words = line.words
        if (words.isEmpty() || positionMs < words.first().startTimeMs) return null

        for (index in words.indices) {
            val word = words[index]
            if (positionMs < word.startTimeMs) {
                return words.getOrNull(index - 1)?.let { pointAtOffset(textLayout, it.endChar) }
            }
            if (positionMs < word.endTimeMs) {
                val duration = (word.endTimeMs - word.startTimeMs).coerceAtLeast(1L)
                val fraction = ((positionMs - word.startTimeMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                return interpolatedPoint(textLayout, word.startChar, word.endChar, fraction)
            }
        }
        return pointAtOffset(textLayout, words.last().endChar)
    }

    private fun interpolatedPoint(
        textLayout: Layout,
        startOffset: Int,
        endOffset: Int,
        fraction: Float
    ): ProgressPoint {
        val safeStart = startOffset.coerceIn(0, text.length)
        val safeEnd = endOffset.coerceIn(safeStart, text.length)
        if (safeStart == safeEnd) return pointAtOffset(textLayout, safeEnd)

        val startLine = textLayout.getLineForOffset(safeStart)
        val endLine = textLayout.getLineForOffset(safeEnd)
        if (startLine == endLine) {
            val startX = textLayout.getPrimaryHorizontal(safeStart)
            val endX = textLayout.getPrimaryHorizontal(safeEnd)
            return ProgressPoint(startLine, startX + (endX - startX) * fraction.coerceIn(0f, 1f))
        }

        data class Segment(val line: Int, val left: Float, val right: Float)
        val segments = ArrayList<Segment>()
        for (lineIndex in startLine..endLine) {
            val segmentStart = if (lineIndex == startLine) safeStart else textLayout.getLineStart(lineIndex)
            val segmentEnd = if (lineIndex == endLine) safeEnd else textLayout.getLineEnd(lineIndex).coerceAtMost(text.length)
            val left = textLayout.getPrimaryHorizontal(segmentStart)
            val right = textLayout.getPrimaryHorizontal(segmentEnd)
            val visualLeft = minOf(left, right)
            val visualRight = maxOf(left, right)
            if (visualRight > visualLeft) segments.add(Segment(lineIndex, visualLeft, visualRight))
        }
        if (segments.isEmpty()) return pointAtOffset(textLayout, safeEnd)
        val total = segments.sumOf { (it.right - it.left).toDouble() }.toFloat().coerceAtLeast(1f)
        var remain = total * fraction.coerceIn(0f, 1f)
        for (segment in segments) {
            val width = segment.right - segment.left
            if (remain <= width) return ProgressPoint(segment.line, segment.left + remain)
            remain -= width
        }
        val last = segments.last()
        return ProgressPoint(last.line, last.right)
    }

    private fun pointAtOffset(textLayout: Layout, rawOffset: Int): ProgressPoint {
        val safeOffset = rawOffset.coerceIn(0, text.length)
        val lineIndex = textLayout.getLineForOffset(safeOffset).coerceIn(0, textLayout.lineCount - 1)
        return ProgressPoint(lineIndex, textLayout.getPrimaryHorizontal(safeOffset))
    }
}
