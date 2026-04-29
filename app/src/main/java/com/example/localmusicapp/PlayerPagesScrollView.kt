package com.example.localmusicapp

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerPagesScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    var pageWidthPx: Int = 0
    var pageCount: Int = 3
    private var settledPage: Int = 1
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastX: Float = 0f
    private var horizontalGesture = false
    private var verticalGesture = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                horizontalGesture = false
                verticalGesture = false
                super.onInterceptTouchEvent(ev)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val absDx = abs(dx)
                val absDy = abs(dy)
                if (!horizontalGesture && !verticalGesture) {
                    if (absDy > touchSlop && absDy >= absDx * 0.75f) {
                        verticalGesture = true
                        return false
                    }
                    if (absDx > touchSlop && absDx > absDy * 1.55f) {
                        horizontalGesture = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                if (verticalGesture) return false
                if (horizontalGesture) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                horizontalGesture = false
                verticalGesture = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                horizontalGesture = false
                verticalGesture = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = ev.x
            }
        }
        val handled = super.onTouchEvent(ev)
        if ((ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) && pageWidthPx > 0) {
            val dragDistance = lastX - downX
            post { snapAfterDrag(dragDistance, animated = true) }
            horizontalGesture = false
            verticalGesture = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }

    fun setCurrentPage(page: Int, animated: Boolean) {
        if (pageWidthPx <= 0) return
        val targetPage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        settledPage = targetPage
        val targetScroll = targetPage * pageWidthPx
        if (animated) {
            smoothScrollTo(targetScroll, 0)
        } else {
            scrollTo(targetScroll, 0)
        }
    }

    fun isOnMainPage(): Boolean {
        if (pageWidthPx <= 0) return true
        return currentPage() == 1
    }

    fun currentPage(): Int {
        if (pageWidthPx <= 0) return settledPage
        return (scrollX.toFloat() / pageWidthPx.toFloat())
            .roundToInt()
            .coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }

    fun snapToNearestPage(animated: Boolean) {
        if (pageWidthPx <= 0) return
        setCurrentPage(currentPage(), animated)
    }

    private fun snapAfterDrag(dragDistance: Float, animated: Boolean) {
        if (pageWidthPx <= 0) return
        val progress = (scrollX.toFloat() / pageWidthPx.toFloat())
            .coerceIn(0f, (pageCount - 1).coerceAtLeast(0).toFloat())
        val threshold = 0.18f
        val basePage = settledPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val target = when {
            progress <= basePage - threshold -> basePage - 1
            progress >= basePage + threshold -> basePage + 1
            abs(dragDistance) > pageWidthPx * 0.08f -> if (dragDistance < 0) basePage + 1 else basePage - 1
            else -> progress.roundToInt()
        }.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        setCurrentPage(target, animated)
    }
}
