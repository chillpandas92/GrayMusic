package com.example.localmusicapp

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewParent
import android.widget.ImageView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView

object CoverFrameStyler {

    fun applyDefault(view: ImageView) {
        clearFrame(view)
        clearAncestorCardFrame(view)
    }

    fun applyFromBitmap(view: ImageView, bitmap: Bitmap?) {
        clearFrame(view)
        clearAncestorCardFrame(view)
    }

    private fun clearFrame(view: ImageView) {
        if (view is ShapeableImageView) {
            view.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
            view.strokeWidth = 0f
        }
    }

    private fun clearAncestorCardFrame(view: View) {
        var parent: ViewParent? = view.parent
        var hops = 0
        while (parent != null && hops < 4) {
            if (parent is MaterialCardView) {
                parent.strokeColor = Color.TRANSPARENT
                parent.strokeWidth = 0
                return
            }
            parent = parent.parent
            hops += 1
        }
    }
}
