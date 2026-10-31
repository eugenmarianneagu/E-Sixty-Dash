package com.bmw.carlauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

/**
 * BmwWingGlowButton: FrameLayout wrapper for the primary dashboard buttons.
 * Halo on focus is disabled on the main dashboard screen.
 */
class BmwWingGlowButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var isRightSide: Boolean = false
    var bandPosition: Int = 0 // 0 = TOP, 1 = MID, 2 = BOT

    private var maskResId: Int = 0
    private var maskBitmap: Bitmap? = null

    private var glowProgress: Float = 0.0f
    private var animator: ValueAnimator? = null

    init {
        setWillNotDraw(true)
        isFocusable = true
        isClickable = true

        if (attrs != null) {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.AgamaBandButton)
            isRightSide = typedArray.getBoolean(R.styleable.AgamaBandButton_isRightSide, false)
            bandPosition = typedArray.getInt(R.styleable.AgamaBandButton_bandPosition, 0)
            maskResId = typedArray.getResourceId(R.styleable.AgamaBandButton_buttonMask, 0)
            typedArray.recycle()
        }
    }

    fun setMaskResource(resId: Int) {
        maskResId = resId
    }
}
