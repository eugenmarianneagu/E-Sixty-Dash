package com.bmw.carlauncher

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

class BmwCircleHaloView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    fun setGlowActive(active: Boolean, animated: Boolean = true) {
        // Halo around center circle disabled on the main page
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
    }
}
