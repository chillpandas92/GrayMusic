package com.example.localmusicapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.sin

/**
 * 播放列表右侧的小波浪。
 *
 * - 当前歌曲可见
 * - 播放时做轻量动画
 * - 暂停时停在静态高度
 */
class WaveBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt()
        style = Paint.Style.FILL
    }
    private val rect = RectF()
    private var phase: Float = 0f
    private var animator: ValueAnimator? = null

    var animating: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startAnimator() else stopAnimator()
            invalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animating) startAnimator()
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = dp(18)
        val desiredH = dp(16)
        val w = resolveSize(desiredW, widthMeasureSpec)
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        val barCount = 3
        val gap = w * 0.10f
        val barWidth = (w - gap * (barCount - 1)) / barCount
        val minHeight = h * 0.26f

        for (i in 0 until barCount) {
            val ratio = if (animating) {
                val raw = (sin(phase + i * 0.9f) + 1f) / 2f
                0.30f + raw * 0.62f
            } else {
                when (i) {
                    0 -> 0.42f
                    1 -> 0.80f
                    else -> 0.58f
                }
            }
            val barHeight = max(minHeight, h * ratio)
            val left = i * (barWidth + gap)
            val top = (h - barHeight) / 2f
            rect.set(left, top, left + barWidth, top + barHeight)
            val radius = barWidth / 2f
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private fun startAnimator() {
        if (!isAttachedToWindow) return
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 700L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
        phase = 0f
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
