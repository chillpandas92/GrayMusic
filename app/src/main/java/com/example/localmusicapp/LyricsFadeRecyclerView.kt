package com.example.localmusicapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

class LyricsFadeRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private val edgeFadeLengthPx: Float = 72f * resources.displayMetrics.density
    private val fadeMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val topTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var topTintStartColor: Int = Color.TRANSPARENT
    private var topTintMidColor: Int = Color.TRANSPARENT

    init {
        // 不再绘制白色矩形雾化层；只对歌词内容本身做透明渐隐，避免出现白块或硬截断。
        isVerticalFadingEdgeEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        setFadingEdgeLength(edgeFadeLengthPx.toInt())
        setWillNotDraw(false)
    }

    override fun getTopFadingEdgeStrength(): Float = 0f

    override fun getBottomFadingEdgeStrength(): Float = 0f

    fun setTopSoftTint(startColor: Int, midColor: Int) {
        if (topTintStartColor == startColor && topTintMidColor == midColor) return
        topTintStartColor = startColor
        topTintMidColor = midColor
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) {
            super.dispatchDraw(canvas)
            return
        }

        val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        drawContentFadeMask(canvas)
        canvas.restoreToCount(saveCount)
        drawTopSoftTint(canvas)
    }

    private fun drawTopSoftTint(canvas: Canvas) {
        if (topTintStartColor == Color.TRANSPARENT && topTintMidColor == Color.TRANSPARENT) return
        val tintHeight = (edgeFadeLengthPx * 1.10f).coerceAtMost(height * 0.30f)
        if (tintHeight <= 0f) return
        topTintPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            tintHeight,
            intArrayOf(topTintStartColor, topTintMidColor, Color.TRANSPARENT),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), tintHeight, topTintPaint)
        topTintPaint.shader = null
    }

    private fun drawContentFadeMask(canvas: Canvas) {
        val fadeLength = edgeFadeLengthPx.coerceAtMost(height * 0.34f)
        if (fadeLength <= 0f) return

        val topStop = (fadeLength / height.toFloat()).coerceIn(0.01f, 0.48f)
        val bottomStop = (1f - topStop).coerceIn(0.52f, 0.99f)
        fadeMaskPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(84, 0, 0, 0),
                Color.BLACK,
                Color.BLACK,
                Color.argb(84, 0, 0, 0),
                Color.TRANSPARENT
            ),
            floatArrayOf(
                0f,
                topStop * 0.46f,
                topStop,
                bottomStop,
                1f - (topStop * 0.46f),
                1f
            ),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadeMaskPaint)
        fadeMaskPaint.shader = null
    }
}
