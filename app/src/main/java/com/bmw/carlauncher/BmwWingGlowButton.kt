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
 * BmwWingGlowButton: FrameLayout wrapper for the 6 primary dashboard buttons.
 *
 * Implements the text & icon illumination effect:
 * - Background is completely transparent (only native ripple effect on touch, no orange background masks or outlines).
 * - Modul Normal (Unfocused): Text & Icon are displayed in subtle light gray / off-white (#E0E0E0) with 85% opacity,
 *   matching the native dashboard aesthetic cleanly without glow or shadows.
 * - Modul Focusat / Pressed:
 *   * Colors smoothly transition to BMW Amber (#FF7200).
 *   * A soft, warm golden glow aura (#A0FF9F33 / #90FFFFFF) radiates gently from behind the text and icons.
 *   * Transition is animated via ValueAnimator (~180ms) for a silky smooth fade in/out.
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

    // Animation progress: 0.0f (Normal) -> 1.0f (Focused / Pressed)
    private var glowProgress: Float = 0.0f
    private var animator: ValueAnimator? = null

    // Paints
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Colors requested:
    // Normal: off-white/light-gray #E0E0E0 at 85% opacity
    // Focused/Pressed: Amber #FF7200 with warm white/amber aura
    private val colorNormal = Color.parseColor("#E0E0E0")
    private val colorAmber = Color.parseColor("#FF7200")
    private val colorAura = Color.parseColor("#90FFFFFF")

    // Blur mask filter for the glowing aura behind text & icon
    private var blurFilter: BlurMaskFilter? = null

    private val srcRect = Rect()
    private val dstRect = Rect()

    init {
        setWillNotDraw(false)
        isFocusable = true
        isClickable = true

        // Ensure software layer for BlurMaskFilter support
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        if (attrs != null) {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.AgamaBandButton)
            isRightSide = typedArray.getBoolean(R.styleable.AgamaBandButton_isRightSide, false)
            bandPosition = typedArray.getInt(R.styleable.AgamaBandButton_bandPosition, 0)
            maskResId = typedArray.getResourceId(R.styleable.AgamaBandButton_buttonMask, 0)
            typedArray.recycle()
        }

        if (maskResId != 0) {
            loadMaskBitmap(maskResId)
        }

        // 8f provides a soft, refined aura around the letters and icon lines
        blurFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.OUTER)
    }

    fun setMaskResource(resId: Int) {
        maskResId = resId
        loadMaskBitmap(resId)
        invalidate()
    }

    private fun loadMaskBitmap(resId: Int) {
        val drawable = ContextCompat.getDrawable(context, resId)
        if (drawable is BitmapDrawable) {
            maskBitmap = drawable.bitmap
        } else if (drawable != null) {
            val bmp = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val c = Canvas(bmp)
            drawable.setBounds(0, 0, c.width, c.height)
            drawable.draw(c)
            maskBitmap = bmp
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animateGlow(isFocused || isPressed)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animateGlow(isFocused || isPressed)
    }

    private fun animateGlow(targetState: Boolean) {
        val target = if (targetState) 1.0f else 0.0f
        if (glowProgress == target && animator == null) return

        animator?.cancel()
        animator = ValueAnimator.ofFloat(glowProgress, target).apply {
            duration = 180L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                glowProgress = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dstRect.set(0, 0, w, h)
        maskBitmap?.let {
            srcRect.set(0, 0, it.width, it.height)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bmp = maskBitmap ?: return
        if (srcRect.isEmpty) {
            srcRect.set(0, 0, bmp.width, bmp.height)
        }
        if (dstRect.isEmpty) {
            dstRect.set(0, 0, width, height)
        }

        // Modul Normal (glowProgress < 1.0f):
        // Textul este alb-murdar / gri-deschis (#E0E0E0) cu opacitate 85% (~217 alpha).
        // Fără glow / umbră.
        if (glowProgress < 1.0f) {
            val normalAlpha = ((1.0f - glowProgress) * 217).toInt()
            normalPaint.colorFilter = PorterDuffColorFilter(colorNormal, PorterDuff.Mode.SRC_IN)
            normalPaint.alpha = normalAlpha
            canvas.drawBitmap(bmp, srcRect, dstRect, normalPaint)
        }

        // Modul Focusat / Pressed (glowProgress > 0.0f):
        // 1. Aură Glow luminoasă alb-aurie în spatele literelor și iconiței
        // 2. Text și iconiță aprinse în Amber BMW vibrant (#FF7200)
        if (glowProgress > 0.0f) {
            val focusAlpha = (glowProgress * 255).toInt()

            // 1. Aură Glow în spate
            auraPaint.colorFilter = PorterDuffColorFilter(colorAura, PorterDuff.Mode.SRC_IN)
            auraPaint.maskFilter = blurFilter
            auraPaint.alpha = (glowProgress * 150).toInt()
            canvas.drawBitmap(bmp, srcRect, dstRect, auraPaint)

            // 2. Text & Iconiță Amber vibrant
            glowPaint.colorFilter = PorterDuffColorFilter(colorAmber, PorterDuff.Mode.SRC_IN)
            glowPaint.maskFilter = null
            glowPaint.alpha = focusAlpha
            canvas.drawBitmap(bmp, srcRect, dstRect, glowPaint)
        }
    }
}
