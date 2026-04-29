package com.example.localmusicapp

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * 应用字体入口。
 *
 * 如果项目中存在 app/src/main/assets/fonts/SourceHanSansOLD-Bold-2.otf，运行时会使用该字体；
 * 如果没有该文件，则退回 Android 系统 sans-serif，保证源码可以正常编译运行。
 */
object AppFont {
    private const val FONT_ASSET_PATH = "fonts/SourceHanSansOLD-Bold-2.otf"

    @Volatile private var cachedTypeface: Typeface? = null
    @Volatile private var triedLoading: Boolean = false

    fun typeface(context: Context): Typeface {
        cachedTypeface?.let { return it }
        synchronized(this) {
            cachedTypeface?.let { return it }
            if (!triedLoading) {
                triedLoading = true
                cachedTypeface = try {
                    Typeface.createFromAsset(context.applicationContext.assets, FONT_ASSET_PATH)
                } catch (_: Exception) {
                    Typeface.create("sans-serif", Typeface.NORMAL)
                }
            }
            return cachedTypeface ?: Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }

    fun applyTo(root: View?) {
        if (root == null) return
        val tf = typeface(root.context)
        applyTypeface(root, tf)
    }

    private fun applyTypeface(view: View, typeface: Typeface) {
        if (view is TextView) {
            view.typeface = Typeface.create(typeface, Typeface.NORMAL)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyTypeface(view.getChildAt(i), typeface)
            }
        }
    }
}
