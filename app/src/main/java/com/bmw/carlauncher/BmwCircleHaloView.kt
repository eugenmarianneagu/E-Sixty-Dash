package com.bmw.carlauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class BmwCircleHaloView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var glowProgress = 0f
    private var animator: ValueAnimator? = null

    // 1. Strat larg de atmosferă difuză (aură caldă radiantă)
    private val atmosphericHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    // 2. Strat median neon de intensitate ridicată (culoarea conturului de la butoanele principale)
    private val neonBodyHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    // 3. Contur luminos fin pe buza cercului
    private val coreRimHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        updatePaints()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePaints()
    }

    private fun updatePaints() {
        atmosphericHaloPaint.strokeWidth = dpToPx(34f)
        atmosphericHaloPaint.maskFilter = BlurMaskFilter(dpToPx(24f), BlurMaskFilter.Blur.NORMAL)

        neonBodyHaloPaint.strokeWidth = dpToPx(16f)
        neonBodyHaloPaint.maskFilter = BlurMaskFilter(dpToPx(10f), BlurMaskFilter.Blur.NORMAL)

        coreRimHaloPaint.strokeWidth = dpToPx(5f)
        coreRimHaloPaint.maskFilter = BlurMaskFilter(dpToPx(3f), BlurMaskFilter.Blur.NORMAL)
    }

    fun setGlowActive(active: Boolean, animated: Boolean = true) {
        val target = if (active) 1.0f else 0.0f
        if (!animated) {
            animator?.cancel()
            glowProgress = target
            invalidate()
            return
        }

        if (animator?.isRunning == true) {
            animator?.cancel()
        }

        animator = ValueAnimator.ofFloat(glowProgress, target).apply {
            duration = if (active) 240L else 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                glowProgress = va.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (glowProgress <= 0.002f) return

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(w, h) / 2f) - dpToPx(50f) - dpToPx(1.5f)

        if (radius <= 0f) return

        // Nuanța exactă de la conturul butoanelor principale din fundal (RGB ~ 238, 82, 28 / 248, 102, 34)
        // 1. Aura atmosferică extinsă
        val alpha1 = (glowProgress * 230).toInt().coerceIn(0, 255)
        atmosphericHaloPaint.color = Color.argb(alpha1, 238, 82, 28)
        canvas.drawCircle(cx, cy, radius, atmosphericHaloPaint)

        // 2. Inel radiant neon intens
        val alpha2 = (glowProgress * 255).toInt().coerceIn(0, 255)
        neonBodyHaloPaint.color = Color.argb(alpha2, 248, 102, 34)
        canvas.drawCircle(cx, cy, radius, neonBodyHaloPaint)

        // 3. Contur luminos central
        val alpha3 = (glowProgress * 255).toInt().coerceIn(0, 255)
        coreRimHaloPaint.color = Color.argb(alpha3, 255, 165, 60)
        canvas.drawCircle(cx, cy, radius, coreRimHaloPaint)
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
}
