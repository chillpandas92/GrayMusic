package com.example.localmusicapp

import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

object UiEffects {
    fun flashTwice(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.34f, 1f, 0.34f, 1f).apply {
            duration = 560L
            interpolator = AccelerateDecelerateInterpolator()
        }.start()
    }

    fun pulseSelection(view: View, durationMs: Long = 150L) {
        view.isActivated = true
        view.postDelayed({ view.isActivated = false }, durationMs)
    }
}
