package com.example.localmusicapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class ListenReportChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var reportState: ListenReportCalculator.ReportState? = null
    private var accentColor: Int = 0xFF1565C0.toInt()
    private val tempRect = RectF()
    private val secondaryTextColor = 0xFF7A7A7A.toInt()

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryTextColor
        textSize = sp(8.7f)
        textAlign = Paint.Align.CENTER
        typeface = AppFont.typeface(context)
    }
    private val axisValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(secondaryTextColor, 214)
        textSize = sp(8.3f)
        textAlign = Paint.Align.RIGHT
        typeface = AppFont.typeface(context)
    }
    private val helperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(secondaryTextColor, 206)
        textSize = sp(8.2f)
        textAlign = Paint.Align.CENTER
        typeface = AppFont.typeface(context)
    }
    private val dayNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(secondaryTextColor, 178)
        textSize = sp(7.2f)
        textAlign = Paint.Align.CENTER
        typeface = AppFont.typeface(context)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.9f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.95f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pointInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.05f)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryTextColor
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
        typeface = AppFont.typeface(context)
    }

    fun setAccentColor(color: Int) {
        if (accentColor == color) return
        accentColor = color
        invalidate()
    }

    fun setState(state: ListenReportCalculator.ReportState) {
        reportState = state
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val state = reportState
        if (state == null) {
            drawEmptyState(canvas)
            return
        }
        when (state.range) {
            ListenReportCalculator.Range.DAY -> drawDayHistogram(canvas, state.dayHoursMs)
            ListenReportCalculator.Range.WEEK -> drawWeekTrend(canvas, state.weekDaysMs, state.weekDateLabels)
            ListenReportCalculator.Range.MONTH -> drawMonthHeatmap(canvas, state)
        }
    }

    private fun drawDayHistogram(canvas: Canvas, values: LongArray) {
        val maxValue = values.maxOrNull() ?: 0L
        val axisMax = niceAxisMax(maxValue)
        val chartRect = RectF(
            paddingLeft.toFloat() + dp(28f),
            paddingTop.toFloat() + dp(8f),
            width - paddingRight.toFloat() - dp(6f),
            height - paddingBottom.toFloat() - dp(22f)
        )
        val chartHeight = chartRect.height().coerceAtLeast(dp(56f))
        val chartWidth = chartRect.width().coerceAtLeast(dp(120f))
        val baselineY = chartRect.bottom

        drawValueGrid(canvas, chartRect, axisMax)

        val slotGap = dp(2.5f)
        val barWidth = ((chartWidth - slotGap * 23f) / 24f).coerceAtLeast(dp(3.1f))
        val radius = min(barWidth / 2f, dp(6f))

        if (axisMax > 0L) {
            for (index in 0 until 24) {
                val value = values.getOrElse(index) { 0L }
                if (value <= 0L) continue
                val ratio = (value.toFloat() / axisMax.toFloat()).coerceIn(0f, 1f)
                val barLeft = chartRect.left + index * (barWidth + slotGap)
                val barRight = barLeft + barWidth
                val barHeight = max(dp(4f), chartHeight * ratio)
                val barTop = baselineY - barHeight
                fillPaint.color = ColorUtils.blendARGB(
                    ColorUtils.blendARGB(Color.WHITE, accentColor, 0.30f),
                    accentColor,
                    0.54f + 0.26f * ratio
                )
                tempRect.set(barLeft, barTop, barRight, baselineY)
                canvas.drawRoundRect(tempRect, radius, radius, fillPaint)
            }
        }

        val labels = arrayOf("0点", "6点", "12点", "18点", "24点")
        val fractions = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val labelY = height - paddingBottom.toFloat() - dp(7f) + textCenterOffset(axisPaint)
        labels.forEachIndexed { index, label ->
            val x = chartRect.left + chartWidth * fractions[index]
            axisPaint.textAlign = when (index) {
                0 -> Paint.Align.LEFT
                labels.lastIndex -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(label, x, labelY, axisPaint)
        }
        axisPaint.textAlign = Paint.Align.CENTER

        if (maxValue <= 0L) {
            drawOverlayEmpty(canvas, centerY = chartRect.top + chartHeight / 2f)
        }
    }

    private fun drawWeekTrend(
        canvas: Canvas,
        values: LongArray,
        dateLabels: List<String>
    ) {
        val weekdays = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val maxValue = values.maxOrNull() ?: 0L
        val axisMax = niceAxisMax(maxValue)
        val chartRect = RectF(
            paddingLeft.toFloat() + dp(28f),
            paddingTop.toFloat() + dp(8f),
            width - paddingRight.toFloat() - dp(6f),
            height - paddingBottom.toFloat() - dp(34f)
        )
        val chartHeight = chartRect.height().coerceAtLeast(dp(58f))
        drawValueGrid(canvas, chartRect, axisMax)

        val stepX = if (values.size > 1) chartRect.width() / (values.size - 1).toFloat() else 0f
        val points = values.indices.map { index ->
            val x = chartRect.left + index * stepX
            val ratio = if (axisMax > 0L) {
                (values[index].toFloat() / axisMax.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val y = chartRect.bottom - chartHeight * ratio
            x to y
        }
        val todayIndex = ((Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7)
        val visiblePoints = points.filterIndexed { index, _ -> index <= todayIndex }

        if (maxValue > 0L && visiblePoints.isNotEmpty()) {
            fillPaint.color = ColorUtils.setAlphaComponent(accentColor, 40)
            canvas.drawPath(buildSmoothPath(visiblePoints, baselineY = chartRect.bottom), fillPaint)

            linePaint.color = accentColor
            canvas.drawPath(buildSmoothPath(visiblePoints, baselineY = null), linePaint)
        }

        pointPaint.color = accentColor
        points.forEachIndexed { index, (x, y) ->
            val isFuture = index > todayIndex
            if (isFuture) {
                pointPaint.alpha = 118
                canvas.drawCircle(x, chartRect.bottom, dp(2.6f), pointPaint)
            } else {
                pointPaint.alpha = 255
                canvas.drawCircle(x, y, dp(3.8f), pointPaint)
                canvas.drawCircle(x, y, dp(1.7f), pointInnerPaint)
            }
        }
        pointPaint.alpha = 255

        val weekdayY = chartRect.bottom + dp(13f) + textCenterOffset(axisPaint)
        val dateY = chartRect.bottom + dp(24f) + textCenterOffset(helperPaint)
        weekdays.forEachIndexed { index, label ->
            val x = if (weekdays.size > 1) {
                chartRect.left + chartRect.width() * index / (weekdays.size - 1).toFloat()
            } else {
                chartRect.centerX()
            }
            axisPaint.color = if (isFutureWeekIndex(index)) {
                ColorUtils.setAlphaComponent(secondaryTextColor, 132)
            } else {
                secondaryTextColor
            }
            helperPaint.color = if (isFutureWeekIndex(index)) {
                ColorUtils.setAlphaComponent(secondaryTextColor, 116)
            } else {
                ColorUtils.setAlphaComponent(secondaryTextColor, 198)
            }
            canvas.drawText(label, x, weekdayY, axisPaint)
            canvas.drawText(dateLabels.getOrNull(index).orEmpty(), x, dateY, helperPaint)
        }
        axisPaint.color = secondaryTextColor
        helperPaint.color = ColorUtils.setAlphaComponent(secondaryTextColor, 206)

        if (maxValue <= 0L) {
            drawOverlayEmpty(canvas, centerY = chartRect.top + chartHeight / 2f)
        }
    }

    private fun drawMonthHeatmap(canvas: Canvas, state: ListenReportCalculator.ReportState) {
        val values = state.monthDaysMs
        if (values.isEmpty()) {
            drawEmptyState(canvas)
            return
        }

        // 纯方格热力图：不再显示日期数字，也不再画图例；颜色由绝对收听时长决定。
        val labels = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val rows = max(1, (state.monthFirstDayOffset + values.size + 6) / 7)
        val left = paddingLeft.toFloat() + dp(2f)
        val right = width - paddingRight.toFloat() - dp(2f)
        val top = paddingTop.toFloat() + dp(4f)
        val weekdayLabelHeight = dp(14f)
        val gridTop = top + weekdayLabelHeight + dp(8f)
        val gridBottom = height - paddingBottom.toFloat() - dp(4f)
        val availableWidth = (right - left).coerceAtLeast(dp(120f))
        val availableHeight = (gridBottom - gridTop).coerceAtLeast(dp(90f))
        // 方格要"更紧凑、稍微大一点、但仍然均匀分布"：
        // 优先用"把容器宽度按 7 格 + 固定小缝隙切开"算方格边长；缝隙固定为 3dp，方格越大越紧。
        // 然后再按 availableHeight 反约束一次，避免高度不够时方格被拉成细长条。
        val targetGap = dp(3f)
        val cellSize = min(
            (availableWidth - targetGap * 6f) / 7f,
            (availableHeight - targetGap * (rows - 1).coerceAtLeast(0)) / rows.toFloat()
        ).coerceIn(dp(12f), dp(24f))
        // 方格边长定好后，再按剩余宽度均分 gap，让 7 个方格刚好贴满一行（分布绝对均匀）。
        val gapX = ((availableWidth - cellSize * 7f) / 6f).coerceAtLeast(dp(2f))
        val gapY = if (rows > 1) {
            ((availableHeight - cellSize * rows.toFloat()) / (rows - 1).toFloat()).coerceAtLeast(dp(2f))
        } else {
            dp(2f)
        }
        val startX = left
        val palette = heatPalette()
        val now = Calendar.getInstance()
        val todayIndex = if (state.monthYear == now.get(Calendar.YEAR) && state.monthZeroBased == now.get(Calendar.MONTH)) {
            now.get(Calendar.DAY_OF_MONTH) - 1
        } else {
            -1
        }

        val weekdayY = top + weekdayLabelHeight / 2f + textCenterOffset(axisPaint)
        labels.forEachIndexed { index, label ->
            val x = startX + index * (cellSize + gapX) + cellSize / 2f
            canvas.drawText(label, x, weekdayY, axisPaint)
        }

        strokePaint.color = ColorUtils.setAlphaComponent(accentColor, 170)
        for (day in values.indices) {
            val cellIndex = state.monthFirstDayOffset + day
            val row = cellIndex / 7
            val column = cellIndex % 7
            val cellLeft = startX + column * (cellSize + gapX)
            val cellTop = gridTop + row * (cellSize + gapY)
            tempRect.set(cellLeft, cellTop, cellLeft + cellSize, cellTop + cellSize)

            val level = heatLevel(values[day])
            fillPaint.color = palette[level]
            canvas.drawRoundRect(tempRect, dp(3.5f), dp(3.5f), fillPaint)

            dayNumberPaint.color = if (level >= 4) {
                ColorUtils.setAlphaComponent(Color.WHITE, 224)
            } else {
                ColorUtils.setAlphaComponent(secondaryTextColor, 184)
            }
            canvas.drawText(
                (day + 1).toString(),
                tempRect.centerX(),
                tempRect.centerY() + textCenterOffset(dayNumberPaint),
                dayNumberPaint
            )

            if (day == todayIndex) {
                canvas.drawRoundRect(tempRect, dp(3.5f), dp(3.5f), strokePaint)
            }
        }

        val maxValue = values.maxOrNull() ?: 0L
        if (maxValue <= 0L) {
            drawOverlayEmpty(canvas, centerY = gridTop + (gridBottom - gridTop) / 2f)
        }
    }

    private fun drawValueGrid(canvas: Canvas, chartRect: RectF, axisMax: Long) {
        val labelX = chartRect.left - dp(6f)
        val steps = listOf(1f, 0.5f, 0f)
        steps.forEach { fraction ->
            val y = chartRect.bottom - chartRect.height() * fraction
            gridPaint.color = if (fraction == 0f) {
                ColorUtils.setAlphaComponent(accentColor, 42)
            } else {
                ColorUtils.setAlphaComponent(accentColor, 24)
            }
            canvas.drawLine(chartRect.left, y, chartRect.right, y, gridPaint)
            val value = (axisMax.toFloat() * fraction).roundToLong().coerceAtLeast(0L)
            canvas.drawText(
                formatDurationTick(value),
                labelX,
                y + textCenterOffset(axisValuePaint),
                axisValuePaint
            )
        }
    }

    private fun heatPalette(): IntArray {
        // Level 0: 无收听，使用近白的浅灰作为"未激活"底色
        // Level 1~5: 按收听时长由浅到深蓝递进
        return intArrayOf(
            0xFFEDEEF0.toInt(),
            ColorUtils.blendARGB(Color.WHITE, accentColor, 0.18f),
            ColorUtils.blendARGB(Color.WHITE, accentColor, 0.36f),
            ColorUtils.blendARGB(Color.WHITE, accentColor, 0.56f),
            ColorUtils.blendARGB(Color.WHITE, accentColor, 0.78f),
            accentColor
        )
    }

    // 按绝对小时数分桶：0h / 0~1h / 1~3h / 3~6h / 6~10h / 10h+
    // 这样即便某一天听得多，别的天数依然能按 "真实听歌量" 显示颜色，而不是被相对归一化稀释。
    private fun heatLevel(value: Long): Int {
        if (value <= 0L) return 0
        val hours = value.toFloat() / 3_600_000f
        return when {
            hours < 1f -> 1
            hours < 3f -> 2
            hours < 6f -> 3
            hours < 10f -> 4
            else -> 5
        }
    }

    private fun buildSmoothPath(points: List<Pair<Float, Float>>, baselineY: Float?): Path {
        val path = Path()
        if (points.isEmpty()) return path
        val (firstX, firstY) = points.first()
        if (baselineY != null) {
            path.moveTo(firstX, baselineY)
            path.lineTo(firstX, firstY)
        } else {
            path.moveTo(firstX, firstY)
        }

        var previousX = firstX
        var previousY = firstY
        for (index in 1 until points.size) {
            val (x, y) = points[index]
            val midX = (previousX + x) / 2f
            path.cubicTo(midX, previousY, midX, y, x, y)
            previousX = x
            previousY = y
        }

        if (baselineY != null) {
            path.lineTo(previousX, baselineY)
            path.close()
        }
        return path
    }

    private fun drawEmptyState(canvas: Canvas) {
        drawOverlayEmpty(canvas, centerY = height / 2f)
    }

    private fun drawOverlayEmpty(canvas: Canvas, centerY: Float) {
        canvas.drawText(
            "暂无数据",
            width / 2f,
            centerY + textCenterOffset(emptyPaint),
            emptyPaint
        )
    }

    private fun isFutureWeekIndex(index: Int): Boolean {
        val todayIndex = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        return index > todayIndex
    }

    private fun niceAxisMax(maxValue: Long): Long {
        if (maxValue <= 0L) return 60_000L
        val candidates = longArrayOf(
            5L * 60_000L,
            10L * 60_000L,
            15L * 60_000L,
            20L * 60_000L,
            30L * 60_000L,
            45L * 60_000L,
            60L * 60_000L,
            90L * 60_000L,
            2L * 60L * 60_000L,
            3L * 60L * 60_000L,
            4L * 60L * 60_000L,
            6L * 60L * 60_000L,
            8L * 60L * 60_000L,
            12L * 60L * 60_000L,
            24L * 60L * 60_000L
        )
        return candidates.firstOrNull { it >= maxValue } ?: (((maxValue + 3_599_999L) / 3_600_000L) * 3_600_000L)
    }

    private fun formatDurationTick(ms: Long): String {
        if (ms <= 0L) return "0"
        if (ms < 60L * 60_000L) {
            val minutes = (ms.toFloat() / 60_000f)
            return if (minutes >= 10f) {
                "${minutes.roundToLong()}m"
            } else {
                String.format(Locale.US, "%.1fm", minutes)
            }
        }
        val hours = ms.toFloat() / 3_600_000f
        return if (hours >= 10f || hours % 1f == 0f) {
            "${hours.roundToLong()}h"
        } else {
            String.format(Locale.US, "%.1fh", hours)
        }
    }

    private fun textCenterOffset(paint: Paint): Float {
        val metrics = paint.fontMetrics
        return -(metrics.ascent + metrics.descent) / 2f
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
