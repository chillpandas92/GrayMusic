package com.example.localmusicapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/** Lightweight visual audio strip for ringtone selection. */
class RingtoneWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9D9D9.toInt() }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt() }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val playbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val rect = RectF()
    private var startFraction = 0f
    private var endFraction = 1f
    private var playbackFraction = 0f
    private var onSeekFractionChanged: ((Float) -> Unit)? = null

    fun setRange(start: Float, end: Float) {
        startFraction = start.coerceIn(0f, 1f)
        endFraction = end.coerceIn(startFraction, 1f)
        playbackFraction = playbackFraction.coerceIn(startFraction, endFraction)
        invalidate()
    }

    fun setPlaybackFraction(fraction: Float) {
        playbackFraction = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    fun setOnSeekFractionChanged(listener: ((Float) -> Unit)?) {
        onSeekFractionChanged = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val listener = onSeekFractionChanged ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val fraction = (event.x / width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                listener(fraction)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    isPressed = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (54f * resources.displayMetrics.density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val barCount = 42
        val gap = 3f * resources.displayMetrics.density
        val barWidth = ((w - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)
        val centerY = h / 2f
        val selectedLeft = w * startFraction
        val selectedRight = w * endFraction
        for (i in 0 until barCount) {
            val left = i * (barWidth + gap)
            val xCenter = left + barWidth / 2f
            val t = i / (barCount - 1).toFloat()
            val wave = 0.34f + abs(sin(t * 12.7f) * 0.46f + sin(t * 31f) * 0.16f)
            val barHeight = (h * wave).coerceIn(h * 0.18f, h * 0.86f)
            rect.set(left, centerY - barHeight / 2f, left + barWidth, centerY + barHeight / 2f)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, if (xCenter in selectedLeft..selectedRight) selectedPaint else basePaint)
        }
        val handleWidth = 2f * resources.displayMetrics.density
        rect.set(selectedLeft - handleWidth / 2f, 4f, selectedLeft + handleWidth / 2f, h - 4f)
        canvas.drawRoundRect(rect, handleWidth, handleWidth, handlePaint)
        rect.set(selectedRight - handleWidth / 2f, 4f, selectedRight + handleWidth / 2f, h - 4f)
        canvas.drawRoundRect(rect, handleWidth, handleWidth, handlePaint)

        val playX = w * playbackFraction
        val playWidth = 2.6f * resources.displayMetrics.density
        rect.set(playX - playWidth / 2f, 0f, playX + playWidth / 2f, h)
        canvas.drawRoundRect(rect, playWidth, playWidth, playbackPaint)
    }
}
